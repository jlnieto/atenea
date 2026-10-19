package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {
    private static final String UFD_WORKFLOW = ".github/workflows/ufd-validation-v1.yml";
    private static final String UFD_DISPATCH = "atenea-ufd-owned-head-v1";

    private static final Pattern HTTPS_REMOTE = Pattern.compile("^https://github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");
    private static final Pattern SSH_REMOTE = Pattern.compile("^git@github\\.com:([^/]+)/([^/.]+?)(?:\\.git)?$");

    private final ObjectMapper objectMapper;
    private final GitHubProperties properties;
    private final HttpClient httpClient;
    private volatile String fileToken;

    public GitHubClient(ObjectMapper objectMapper, GitHubProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public GitHubPullRequest createPullRequest(
            GitHubRepositoryRef repository,
            String title,
            String body,
            String headBranch,
            String baseBranch
    ) {
        ensureConfigured();
        JsonNode response = sendJsonRequest(
                "POST",
                properties.getApiBaseUrl().resolve("/repos/" + repository.owner() + "/" + repository.repo() + "/pulls"),
                """
                        {
                          "title": %s,
                          "body": %s,
                          "head": %s,
                          "base": %s,
                          "draft": true
                        }
                        """.formatted(
                        jsonString(title),
                        jsonString(body),
                        jsonString(headBranch),
                        jsonString(baseBranch)
                ));
        return toPullRequest(response);
    }

    public GitHubPullRequest getPullRequest(GitHubRepositoryRef repository, long pullRequestNumber) {
        ensureConfigured();
        JsonNode response = sendJsonRequest(
                "GET",
                properties.getApiBaseUrl().resolve("/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + pullRequestNumber),
                null);
        return toPullRequest(response);
    }

    /** A push run, or the closed main-controller run bound to the exact owned head. */
    public void requireUfdValidation(GitHubRepositoryRef repository, String sha, String branch) {
        ensureConfigured();
        requireSha(sha);
        String repo = repository.owner() + "/" + repository.repo();
        JsonNode runs = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repo + "/actions/workflows/ufd-validation-v1.yml/runs?head_sha="
                        + sha + "&branch=" + encode(branch) + "&event=push&per_page=100"), null);
        if (!runs.path("workflow_runs").isArray() || runs.path("workflow_runs").size() >= 100) {
            throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        JsonNode latest = null;
        for (JsonNode run : runs.path("workflow_runs")) {
            if (sha.equals(run.path("head_sha").asText())
                    && branch.equals(run.path("head_branch").asText())
                    && repo.equals(run.path("head_repository").path("full_name").asText())
                    && UFD_WORKFLOW.equals(run.path("path").asText())
                    && "push".equals(run.path("event").asText())
                    && (latest == null || run.path("id").asLong() > latest.path("id").asLong())) latest = run;
        }
        if (latest == null && "jlnieto".equals(repository.owner()) && "atenea".equals(repository.repo())
                && branch != null && branch.matches("atenea/android-version-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            // Git references created through the REST API have a create event.
            // Do not assume that a push event will also be emitted.
            JsonNode created = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                    "/repos/" + repo + "/actions/workflows/ufd-validation-v1.yml/runs?head_sha="
                            + sha + "&branch=" + encode(branch) + "&event=create&per_page=100"), null);
            if (!created.path("workflow_runs").isArray() || created.path("workflow_runs").size() >= 100)
                throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
            for (JsonNode run : created.path("workflow_runs")) {
                if (sha.equals(run.path("head_sha").asText()) && branch.equals(run.path("head_branch").asText())
                        && repo.equals(run.path("head_repository").path("full_name").asText())
                        && UFD_WORKFLOW.equals(run.path("path").asText()) && "create".equals(run.path("event").asText())
                        && (latest==null || run.path("id").asLong()>latest.path("id").asLong())) latest=run;
            }
        }
        if (latest == null && ownedHead(repository, branch)) {
            latest = ownedHeadUfdRun(repository, sha, branch);
        }
        if (latest == null) {
            JsonNode workflow = content(repository, UFD_WORKFLOW, sha);
            if (workflow.isMissingNode()) {
                throw new GitHubIntegrationException("UFD_WORKFLOW_MISSING: la rama publicada no contiene el disparador UFD");
            }
            throw new GitHubIntegrationException("UFD_NOT_STARTED: GitHub aún no ha registrado la comprobación de este commit");
        }
        if ("completed".equals(latest.path("status").asText())
                && "success".equals(latest.path("conclusion").asText())) return;
        if ("completed".equals(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_FAILED: la validación del commit publicado ha fallado");
        }
        if (List.of("queued", "waiting", "requested", "pending").contains(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_QUEUED: GitHub ha registrado el run, pendiente de ejecución");
        }
        if (!"in_progress".equals(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        throw new GitHubIntegrationException("UFD_PENDING: validación GitHub pendiente para este commit; vuelve a consultar sin crear otra PR");
    }

    public record UfdDispatchRequest(UUID requestId, String headSha, String headBranch, String authoritySha) { }

    public UfdDispatchRequest prepareOwnedHeadUfd(String sha, String branch) {
        requireSha(sha);
        GitHubRepositoryRef repository = new GitHubRepositoryRef("jlnieto", "atenea");
        if (!ownedHead(repository, branch)) throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
        String authority = canonicalMain(repository);
        JsonNode file = content(repository, UFD_WORKFLOW, authority);
        if (file.isMissingNode()) throw new GitHubIntegrationException("UFD_CONTROLLER_UNAVAILABLE");
        JsonNode workflow = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/jlnieto/atenea/actions/workflows/ufd-validation-v1.yml"), null, true);
        if (!"active".equals(workflow.path("state").asText())
                || !UFD_WORKFLOW.equals(workflow.path("path").asText())
                || !"base64".equals(file.path("encoding").asText())
                || !new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8)
                        .contains(UFD_DISPATCH)) {
            throw new GitHubIntegrationException("UFD_CONTROLLER_UNAVAILABLE");
        }
        return new UfdDispatchRequest(ufdRequestId(sha, branch), sha, branch, authority);
    }

    /** Called only after the delivery outbox has durably claimed this one send. */
    public void dispatchOwnedHeadUfd(UfdDispatchRequest request) {
        requireSha(request.headSha()); requireSha(request.authoritySha());
        if (!ownedHead(new GitHubRepositoryRef("jlnieto", "atenea"), request.headBranch())
                || !ufdRequestId(request.headSha(), request.headBranch()).equals(request.requestId())) {
            throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
        }
        var payload = objectMapper.createObjectNode();
        payload.put("event_type", UFD_DISPATCH);
        payload.putObject("client_payload").put("requestId", request.requestId().toString())
                .put("headSha", request.headSha()).put("headBranch", request.headBranch())
                .put("authoritySha", request.authoritySha());
        sendJsonRequest("POST", properties.getApiBaseUrl().resolve("/repos/jlnieto/atenea/dispatches"), payload.toString());
    }

    public static UUID ufdRequestId(String sha, String branch) {
        return UUID.nameUUIDFromBytes((UFD_DISPATCH + "|jlnieto/atenea|" + branch + "|" + sha)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean ownedHead(GitHubRepositoryRef repository, String branch) {
        return "jlnieto".equals(repository.owner()) && "atenea".equals(repository.repo())
                && branch != null && branch.matches("atenea/change-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    JsonNode ownedHeadUfdRun(GitHubRepositoryRef repository, String sha, String branch) {
        String title = "Atenea UFD owned-head " + ufdRequestId(sha, branch) + " " + sha;
        JsonNode latest = null;
        // An immutable run is discoverable even if the dispatch HTTP reply was lost.
        // Bound pagination: incomplete evidence is never a PASS or an endless pending.
        for (int page = 1; page <= 10; page++) {
            JsonNode runs = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                    "/repos/jlnieto/atenea/actions/workflows/ufd-validation-v1.yml/runs?event=repository_dispatch&per_page=100&page=" + page), null);
            if (!runs.path("workflow_runs").isArray()) throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
            for (JsonNode run : runs.path("workflow_runs")) {
                if (title.equals(run.path("display_title").asText())
                        && "main".equals(run.path("head_branch").asText())
                        && "jlnieto/atenea".equals(run.path("head_repository").path("full_name").asText())
                        && UFD_WORKFLOW.equals(run.path("path").asText())
                        && "repository_dispatch".equals(run.path("event").asText())
                        && (latest == null || run.path("id").asLong() > latest.path("id").asLong())) latest = run;
            }
            if (runs.path("workflow_runs").size() < 100) break;
            if (page == 10) throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        if (latest != null) {
            String authority = latest.path("head_sha").asText(); requireSha(authority);
            String main = canonicalMain(repository);
            JsonNode comparison = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                    "/repos/jlnieto/atenea/compare/" + authority + "..." + main), null);
            if (!List.of("identical", "ahead").contains(comparison.path("status").asText())) {
                throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
            }
        }
        return latest;
    }

    private JsonNode content(GitHubRepositoryRef repository, String path, String ref) {
        return sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/contents/" + path + "?ref=" + ref), null, true);
    }

    public String canonicalMain(GitHubRepositoryRef repository) {
        ensureConfigured();
        String sha = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/git/ref/heads/main"), null)
                .path("object").path("sha").asText();
        requireSha(sha);
        return sha;
    }

    private static final GitHubRepositoryRef ANDROID_REPOSITORY = new GitHubRepositoryRef("jlnieto", "atenea");
    private static final String ANDROID_VERSION_FILE = "android/app/build.gradle.kts";
    public AndroidProductVersion androidProductVersion(String source) {
        requireSha(source);
        return AndroidProductVersion.read(androidVersionSource(source));
    }
    private String androidVersionSource(String source) {
        JsonNode file = content(ANDROID_REPOSITORY, ANDROID_VERSION_FILE, source);
        if (!"file".equals(file.path("type").asText()) || !ANDROID_VERSION_FILE.equals(file.path("path").asText())
                || !"base64".equals(file.path("encoding").asText()) || file.path("size").asLong(-1) > 100_000
                || file.path("size").asLong(-1) < 1) throw new GitHubIntegrationException("ANDROID_VERSION_POLICY_REJECTED");
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(file.path("content").asText());
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (bytes.length != file.path("size").asInt(-1) || !java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException rejected) { throw new GitHubIntegrationException("ANDROID_VERSION_POLICY_REJECTED"); }
    }

    public static String androidVersionBranch(UUID operation) { return "atenea/android-version-" + operation; }

    /** Fixed file/repository/branch and deterministic Git objects. No force update, paths or scripts from a caller. */
    public String prepareAndroidVersion(UUID operation, String base, AndroidProductVersion channel, Instant createdAt) {
        ensureConfigured(); requireSha(base);
        String root = "/repos/jlnieto/atenea";
        String branch = androidVersionBranch(operation);
        String before = androidVersionSource(base);
        AndroidProductVersion old = AndroidProductVersion.read(before);
        // Only one normal patch increment; an unexpected version lineage needs review, not a jump.
        AndroidProductVersion next = old.nextAfter(channel);
        if (!next.equals(old.nextAfter(old))) throw new GitHubIntegrationException("ANDROID_VERSION_POLICY_REJECTED");
        String after = old.replace(before, next);
        if (!base.equals(canonicalMain(ANDROID_REPOSITORY))) throw new GitHubIntegrationException("CANONICAL_MAIN_MOVED");
        JsonNode parent = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(root + "/git/commits/" + base), null);
        String tree = parent.path("tree").path("sha").asText(); requireSha(tree);
        var blob = objectMapper.createObjectNode().put("encoding", "utf-8").put("content", after);
        String blobSha = sendJsonRequest("POST", properties.getApiBaseUrl().resolve(root + "/git/blobs"), blob.toString()).path("sha").asText(); requireSha(blobSha);
        var treeBody = objectMapper.createObjectNode().put("base_tree", tree);
        treeBody.putArray("tree").addObject().put("path", ANDROID_VERSION_FILE).put("mode", "100644").put("type", "blob").put("sha", blobSha);
        String treeSha = sendJsonRequest("POST", properties.getApiBaseUrl().resolve(root + "/git/trees"), treeBody.toString()).path("sha").asText(); requireSha(treeSha);
        var commit = objectMapper.createObjectNode().put("message", "chore(android): prepare " + next.name() + " / " + next.code() + "\n\nAtenea version preparation " + operation).put("tree", treeSha);
        commit.putArray("parents").add(base);
        commit.putObject("author").put("name", "Atenea").put("email", "atenea@users.noreply.github.com")
                .put("date", createdAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        commit.set("committer", commit.path("author"));
        String head = sendJsonRequest("POST", properties.getApiBaseUrl().resolve(root + "/git/commits"), commit.toString()).path("sha").asText(); requireSha(head);
        // A lost reply may have created this exact ref. Observe it; never move an existing ref.
        JsonNode ref = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(root + "/git/ref/heads/" + branch), null, true);
        if (ref.isMissingNode()) {
            if (!base.equals(canonicalMain(ANDROID_REPOSITORY))) throw new GitHubIntegrationException("CANONICAL_MAIN_MOVED");
            ref = sendJsonRequest("POST", properties.getApiBaseUrl().resolve(root + "/git/refs"),
                    objectMapper.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", head).toString());
        }
        if (!head.equals(ref.path("object").path("sha").asText())) throw new GitHubIntegrationException("ANDROID_VERSION_IDENTITY_MISMATCH");
        return head;
    }

    /** Only called after validate-change's exact-head UFD PASS, before creating the technical PR. */
    public GitHubPullRequest androidVersionPullRequest(UUID operation, String head, AndroidProductVersion version) {
        String branch = androidVersionBranch(operation); requireUfdValidation(ANDROID_REPOSITORY, head, branch);
        JsonNode matches = sendJsonRequest("GET", properties.getApiBaseUrl().resolve("/repos/jlnieto/atenea/pulls?state=all&head="
                + encode("jlnieto:" + branch) + "&base=main&per_page=2"), null);
        if (!matches.isArray() || matches.size() > 1) throw new GitHubIntegrationException("ANDROID_VERSION_IDENTITY_MISMATCH");
        if (matches.size() == 1) {
            JsonNode pr = matches.get(0); requireExactPullRequest(ANDROID_REPOSITORY, pr.path("number").asLong(), branch, head, pr);
            if (!"open".equals(pr.path("state").asText()) && !pr.path("merged_at").isTextual()) throw new GitHubIntegrationException("ANDROID_VERSION_PR_CLOSED");
            return toPullRequest(pr);
        }
        GitHubPullRequest pr = createPullRequest(ANDROID_REPOSITORY, "Preparar Atenea Android " + version.name() + " / " + version.code(),
                "Incremento exclusivo de la versión Android, preparado por Atenea. No publica ni modifica el cambio original.\n\nOperación: " + operation,
                branch, "main");
        if (pr.number() < 1 || !("https://github.com/jlnieto/atenea/pull/" + pr.number()).equals(pr.htmlUrl())
                || !"jlnieto/atenea".equals(pr.baseRepository()) || !"jlnieto/atenea".equals(pr.headRepository())
                || !"main".equals(pr.baseRef()) || !branch.equals(pr.headRef()) || !head.equals(pr.headSha()))
            throw new GitHubIntegrationException("ANDROID_VERSION_IDENTITY_MISMATCH");
        // The normal exact-head integration still checks CI, mergeability and branch protections.
        return pr;
    }

    public void requireAndroidVersionHead(String base, String head, AndroidProductVersion channel) {
        requireSha(base); requireSha(head);
        String original = androidVersionSource(base);
        AndroidProductVersion old = AndroidProductVersion.read(original);
        String expected = old.replace(original, old.nextAfter(channel));
        JsonNode commit = sendJsonRequest("GET", properties.getApiBaseUrl().resolve("/repos/jlnieto/atenea/commits/" + head), null);
        if (!head.equals(commit.path("sha").asText()) || commit.path("parents").size() != 1
                || !base.equals(commit.path("parents").path(0).path("sha").asText())
                || !commit.path("files").isArray() || commit.path("files").size() != 1
                || !ANDROID_VERSION_FILE.equals(commit.path("files").path(0).path("filename").asText())
                || !"modified".equals(commit.path("files").path(0).path("status").asText())
                || !expected.equals(androidVersionSource(head))) throw new GitHubIntegrationException("ANDROID_VERSION_IDENTITY_MISMATCH");
    }

    public record ProductCopy(String locale,String title,String summary) { }
    public record MergedChange(long number,String title,String summary,String branch,String headCommit,String mergeCommit,Instant mergedAt,String url,ProductCopy productCopy) {
        public MergedChange(long number,String title,String summary,String branch,String headCommit,String mergeCommit,Instant mergedAt,String url) {
            this(number,title,summary,branch,headCommit,mergeCommit,mergedAt,url,null);
        }
    }
    public record MergedChangesPage(List<MergedChange> changes,boolean more) { }

    /** Fixed-size read-only history. Repository selection is server-owned, not an API input. */
    public MergedChangesPage recentMergedChanges(GitHubRepositoryRef repository,int page) {
        ensureConfigured();
        if (page<1 || page>50) throw new GitHubIntegrationException("HISTORY_PAGE_INVALID");
        String root="/repos/"+repository.owner()+"/"+repository.repo();
        JsonNode values=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(root+"/pulls?state=closed&base=main&sort=updated&direction=desc&per_page=5&page="+page),null);
        if (!values.isArray() || values.size()>5) throw new GitHubIntegrationException("HISTORY_EVIDENCE_INCOMPLETE");
        var changes=new ArrayList<MergedChange>();
        for (JsonNode pr:values) {
            if (!pr.path("merged_at").isTextual()) continue;
            changes.add(mergedChange(repository,pr));
        }
        return new MergedChangesPage(List.copyOf(changes),values.size()==5 && page<50);
    }
    public MergedChange mergedChange(GitHubRepositoryRef repository,long number) {
        ensureConfigured();
        if(number<1)throw new GitHubIntegrationException("HISTORY_EVIDENCE_INCOMPLETE");
        JsonNode pr=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(
            "/repos/"+repository.owner()+"/"+repository.repo()+"/pulls/"+number),null);
        if(pr.path("number").asLong(-1)!=number)throw new GitHubIntegrationException("HISTORY_EVIDENCE_INCOMPLETE");
        return mergedChange(repository,pr);
    }
    private MergedChange mergedChange(GitHubRepositoryRef repository,JsonNode pr) {
            long number=pr.path("number").asLong(-1);String branch=pr.path("head").path("ref").asText();String head=pr.path("head").path("sha").asText();
            requireSha(head);requireSha(pr.path("merge_commit_sha").asText());requireExactPullRequest(repository,number,branch,head,pr);
            Instant merged=Instant.parse(pr.path("merged_at").asText());
            if (!"closed".equals(pr.path("state").asText()) || merged.isAfter(Instant.now().plusSeconds(60))) throw new GitHubIntegrationException("HISTORY_EVIDENCE_INCOMPLETE");
            String title=productText(pr.path("title").asText(),200);
            if (title.isBlank()) title="Mejora de Atenea";
            String summary=productSummary(pr.path("body").asText(),title);
            return new MergedChange(number,title,summary,branch,head,pr.path("merge_commit_sha").asText(),merged,
                    "https://github.com/"+repository.owner()+"/"+repository.repo()+"/pull/"+number,declaredProductCopy(pr.path("body").asText()));
    }
    static ProductCopy declaredProductCopy(String body) {
        var match=Pattern.compile("(?s)## Novedades para el usuario\\s*\\n<!-- atenea-news:es -->\\s*\\nTítulo: ([^\\n]+)\\n(.*?)(?:\\n## |\\z)").matcher(body==null ? "" : body);
        if(!match.find())return null;
        String title=productText(match.group(1),200),summary=productSummary("## Novedades para el usuario\n"+match.group(2),"");
        return title.isBlank() || summary.isBlank() ? null : new ProductCopy("es",title,summary);
    }
    static String productText(String text,int maximum) {
        if (text==null || text.matches("(?is).*(https?://|/srv/|/home/|bearer |password|secret|\\b(?:sudo|curl|bash|docker|python|git)\\b|\\bssh\\s+(?:-|[^\\s]*@)).*")) return "";
        String clean=text.replaceAll("[\\p{Cntrl}`]"," ").replaceAll("\\s+"," ").trim();
        return clean.substring(0,Math.min(maximum,clean.length()));
    }
    static String productSummary(String body,String fallback) {
        var matcher=Pattern.compile("(?is)## Novedades para el usuario\\s*\\n(.*?)(?:\\n## |\\z)").matcher(body==null ? "" : body);
        if (!matcher.find()) return fallback;
        String result=matcher.group(1).lines().map(line -> productText(line.replaceFirst("^\\s*[-*]\\s*",""),220))
                .filter(line -> !line.isBlank()).limit(4).collect(java.util.stream.Collectors.joining("\n"));
        return result.isBlank() ? fallback : result;
    }

    /** Bounded exact-head file classification, including both sides of a rename. */
    public List<String> changedFiles(GitHubRepositoryRef repository, long number, String branch, String head) {
        ensureConfigured(); requireSha(head);
        String root = "/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + number;
        JsonNode before = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(root), null);
        requireExactPullRequest(repository, number, branch, head, before);
        int count = before.path("changed_files").asInt(-1);
        if (count < 1 || count > 1000) throw new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE");
        var paths = new java.util.TreeSet<String>();
        var filenames = new java.util.HashSet<String>();
        int observed = 0;
        for (int page=1; observed<count; page++) {
            JsonNode files=sendJsonRequest("GET", properties.getApiBaseUrl().resolve(root+"/files?per_page=100&page="+page),null);
            if (!files.isArray() || files.isEmpty() || files.size()>100 || observed+files.size()>count) {
                throw new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE");
            }
            for (JsonNode file:files) {
                if (!filenames.add(file.path("filename").asText())) throw new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE");
                for (String field : List.of("filename", "previous_filename")) {
                    if (!file.has(field) && "previous_filename".equals(field)) continue;
                    String path=file.path(field).asText();
                    if (path.isBlank() || path.startsWith("/") || path.contains("\\")
                            || java.util.Arrays.asList(path.split("/",-1)).stream().anyMatch(p -> p.isBlank() || p.equals(".") || p.equals(".."))) {
                        throw new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE");
                    }
                    paths.add(path);
                }
            }
            observed+=files.size();
        }
        JsonNode after=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(root),null);
        requireExactPullRequest(repository,number,branch,head,after);
        if (after.path("changed_files").asInt(-1)!=count
                || !before.path("merge_commit_sha").equals(after.path("merge_commit_sha"))) {
            throw new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE");
        }
        return List.copyOf(paths);
    }

    /** Read-only proof for a pinned publication recovery, not selection of a new source. */
    public void requireIntegratedChangeInSource(GitHubRepositoryRef repository, long number,
            String branch, String publishedHead, String integratedMerge, String selectedMain) {
        ensureConfigured(); requireSha(publishedHead); requireSha(integratedMerge); requireSha(selectedMain);
        String root = "/repos/" + repository.owner() + "/" + repository.repo();
        JsonNode pr = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(root + "/pulls/" + number), null);
        requireExactPullRequest(repository, number, branch, publishedHead, pr);
        if (!pr.path("merged").asBoolean() || !"closed".equals(pr.path("state").asText())
                || !integratedMerge.equals(pr.path("merge_commit_sha").asText())) {
            throw new GitHubIntegrationException("RELEASE_INTEGRATION_EVIDENCE_MISMATCH");
        }
        if (integratedMerge.equals(selectedMain)) return;
        JsonNode comparison = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                root + "/compare/" + integratedMerge + "..." + selectedMain + "?per_page=100"), null);
        JsonNode commits = comparison.path("commits");
        int count = comparison.path("ahead_by").asInt(-1);
        if (!"ahead".equals(comparison.path("status").asText())
                || !integratedMerge.equals(comparison.path("base_commit").path("sha").asText())
                || !integratedMerge.equals(comparison.path("merge_base_commit").path("sha").asText())
                || comparison.path("behind_by").asInt(-1) != 0) {
            throw new GitHubIntegrationException("RELEASE_CHANGE_NOT_INCLUDED");
        }
        // Do not accept truncated or contradictory ancestry evidence.
        if (count < 1 || count > 100 || comparison.path("total_commits").asInt(-1) != count
                || !commits.isArray() || commits.size() != count
                || !selectedMain.equals(commits.path(count - 1).path("sha").asText())) {
            throw new GitHubIntegrationException("RELEASE_ANCESTRY_EVIDENCE_INCOMPLETE");
        }
        for (JsonNode commit : commits) requireSha(commit.path("sha").asText());
    }

    /** Read-only observation of the exact server-owned PR; never marks a draft ready. */
    public GitHubMergeState observeMergeState(GitHubRepositoryRef repository, long number, String headBranch, String headSha) {
        ensureConfigured();
        requireSha(headSha);
        JsonNode pr = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + number), null);
        requireExactPullRequest(repository, number, headBranch, headSha, pr);
        return GitHubMergeState.observe(pr);
    }

    /** Read-only notification proof; mergeability alone does not mean that tests passed. */
    public void requireIntegrationReadiness(GitHubRepositoryRef repository, long number, String headBranch, String headSha) {
        ensureConfigured(); requireSha(headSha);
        String root="/repos/"+repository.owner()+"/"+repository.repo();
        JsonNode pr=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(root+"/pulls/"+number),null);
        requireExactPullRequest(repository,number,headBranch,headSha,pr);
        if(GitHubMergeState.observe(pr)!=GitHubMergeState.MERGEABLE) throw new GitHubIntegrationException("CI_PENDING: mergeability");
        requireUfdValidation(repository,headSha,headBranch);
        String base=requireBackendMergeValidation(repository,number,headBranch,headSha,pr);
        JsonNode checks=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(root+"/commits/"+headSha+"/check-runs?per_page=100&filter=latest"),null);
        if(!checks.path("check_runs").isArray() || checks.path("total_count").asInt(-1)<0 || checks.path("total_count").asInt()>100
                || checks.path("total_count").asInt()!=checks.path("check_runs").size()) throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        for(var check:checks.path("check_runs")) if(!"completed".equals(check.path("status").asText())
                || !java.util.Set.of("success","neutral","skipped").contains(check.path("conclusion").asText()))
            throw new GitHubIntegrationException("CI_PENDING: checks");
        JsonNode statuses=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(root+"/commits/"+headSha+"/status"),null);
        int count=statuses.path("total_count").asInt(-1);
        if(count<0 || java.util.Set.of("failure","error").contains(statuses.path("state").asText())
                || count>0 && !"success".equals(statuses.path("state").asText())) throw new GitHubIntegrationException("CI_PENDING: statuses");
        if(base!=null && !base.equals(canonicalMain(repository))) throw new GitHubIntegrationException("CI_PENDING: base moved");
    }

    /** No caller-selected PR/head/base and no protection bypass. Lost responses reconcile by GET. */
    public String integrateExact(GitHubRepositoryRef repository, long number, String headBranch, String headSha) {
        ensureConfigured();
        requireSha(headSha);
        String path = "/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + number;
        JsonNode pr = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(path), null);
        requireExactPullRequest(repository, number, headBranch, headSha, pr);
        if (pr.path("merged").asBoolean()) {
            String sha = pr.path("merge_commit_sha").asText();
            requireSha(sha);
            return sha;
        }
        if (!"open".equals(pr.path("state").asText())) {
            throw new GitHubIntegrationException("PR_CLOSED: la PR está cerrada sin integrar");
        }
        if (GitHubMergeState.observe(pr) == GitHubMergeState.CONFLICTS) {
            throw new GitHubIntegrationException("PR_MERGE_CONFLICTS: hay conflictos que resolver antes de integrar");
        }
        requireUfdValidation(repository, headSha, headBranch);
        String validatedBase = requireBackendMergeValidation(repository, number, headBranch, headSha, pr);
        if (pr.path("draft").asBoolean()) {
            String nodeId = pr.path("node_id").asText();
            if (nodeId.isBlank()) throw new GitHubIntegrationException("PR_IDENTITY_INVALID");
            JsonNode ready = sendJsonRequest("POST", properties.getApiBaseUrl().resolve("/graphql"),
                    "{\"query\":\"mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}\",\"variables\":{\"id\":"
                            + jsonString(nodeId) + "}}");
            JsonNode draft = ready.path("data").path("markPullRequestReadyForReview").path("pullRequest").path("isDraft");
            if (ready.has("errors") || !draft.isBoolean() || draft.asBoolean()) {
                throw new GitHubIntegrationException("PR_READY_REJECTED");
            }
            throw new GitHubIntegrationException("CI_PENDING: PR preparada para revisión; comprobando protecciones");
        }
        if (GitHubMergeState.observe(pr) == GitHubMergeState.UNKNOWN) {
            throw new GitHubIntegrationException("PR_MERGEABILITY_PENDING: GitHub aún calcula si esta PR puede integrarse");
        }
        if (GitHubMergeState.observe(pr) != GitHubMergeState.MERGEABLE
                || !"clean".equals(pr.path("mergeable_state").asText())) {
            throw new GitHubIntegrationException("PR_PROTECTED: las protecciones no permiten integrar este commit");
        }
        JsonNode checks = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/commits/" + headSha
                        + "/check-runs?per_page=100&filter=latest"), null);
        if (checks.path("total_count").asInt(-1) < 0 || checks.path("total_count").asInt() > 100) {
            throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        }
        for (JsonNode check : checks.path("check_runs")) {
            if ("completed".equals(check.path("status").asText())
                    && !java.util.Set.of("success", "neutral", "skipped").contains(check.path("conclusion").asText())) {
                throw new GitHubIntegrationException("CI_FAILED: una comprobación ha fallado");
            }
            if (!"completed".equals(check.path("status").asText())) {
                throw new GitHubIntegrationException("CI_PENDING: una comprobación no ha terminado correctamente");
            }
        }
        JsonNode statuses = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/commits/" + headSha + "/status"), null);
        if (java.util.Set.of("failure", "error").contains(statuses.path("state").asText())) {
            throw new GitHubIntegrationException("CI_FAILED: un estado de integración ha fallado");
        }
        if (statuses.path("total_count").asInt(-1) < 0
                || (statuses.path("total_count").asInt() > 0 && !"success".equals(statuses.path("state").asText()))) {
            throw new GitHubIntegrationException("CI_PENDING: estado de integración pendiente");
        }
        if (validatedBase != null && !validatedBase.equals(canonicalMain(repository))) {
            throw new GitHubIntegrationException("PR_BASE_VALIDATION_MOVED: main avanzó después de comprobar el backend");
        }
        JsonNode merged = sendJsonRequest("PUT", properties.getApiBaseUrl().resolve(path + "/merge"),
                "{\"sha\":" + jsonString(headSha) + ",\"merge_method\":\"merge\"}");
        if (!merged.path("merged").asBoolean()) throw new GitHubIntegrationException("MERGE_REJECTED");
        String sha = merged.path("sha").asText();
        requireSha(sha);
        return sha;
    }

    /** UFD is additional evidence, not a replacement for compiling the merged backend. */
    String requireBackendMergeValidation(GitHubRepositoryRef repository, long number,
            String branch, String head, JsonNode pr) {
        if (!"jlnieto".equals(repository.owner()) || !"atenea".equals(repository.repo())) return null;
        String base=canonicalMain(repository);
        String workflow=".github/workflows/backend-merge-validation-v1.yml";
        String preview=pr.path("merge_commit_sha").asText();
        if(preview.isBlank()) throw new GitHubIntegrationException("PR_MERGEABILITY_PENDING: GitHub está calculando la combinación exacta");
        requireSha(preview);
        JsonNode commit=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(
            "/repos/jlnieto/atenea/git/commits/"+preview),null);
        if(!preview.equals(commit.path("sha").asText()) || !commit.path("parents").isArray() || commit.path("parents").size()!=2
                || !base.equals(commit.path("parents").get(0).path("sha").asText())
                || !head.equals(commit.path("parents").get(1).path("sha").asText()))
            throw new GitHubIntegrationException("PR_BASE_VALIDATION_MOVED: falta la combinación de main actual y este head");
        // An older head may legitimately predate this workflow. The approved
        // authority must survive in the exact merge preview, not in that old head.
        JsonNode authority=content(repository,workflow,base), candidate=content(repository,workflow,preview);
        if (!authority.path("sha").asText().matches("[0-9a-f]{40}")
                || !authority.path("sha").asText().equals(candidate.path("sha").asText()))
            throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE: falta el workflow backend aprobado");
        JsonNode runs=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(
            "/repos/jlnieto/atenea/actions/workflows/backend-merge-validation-v1.yml/runs?head_sha="+head+"&event=pull_request&per_page=100"),null);
        if (!runs.path("workflow_runs").isArray() || runs.path("workflow_runs").size()>=100)
            throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        JsonNode latest=null;
        for (var run:runs.path("workflow_runs")) {
            boolean owned=false;
            for (var item:run.path("pull_requests"))
                if (item.path("number").asLong()==number && head.equals(item.path("head").path("sha").asText())) owned=true;
            if (owned && head.equals(run.path("head_sha").asText()) && branch.equals(run.path("head_branch").asText())
                    && "pull_request".equals(run.path("event").asText()) && workflow.equals(run.path("path").asText())
                    && "jlnieto/atenea".equals(run.path("head_repository").path("full_name").asText())
                    && (latest==null || run.path("id").asLong()>latest.path("id").asLong())) latest=run;
        }
        if (latest==null) throw new GitHubIntegrationException("CI_PENDING: falta la validación backend combinada con main");
        long id=latest.path("id").asLong();if(id<1) throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        JsonNode jobs=sendJsonRequest("GET",properties.getApiBaseUrl().resolve(
            "/repos/jlnieto/atenea/actions/runs/"+id+"/jobs?filter=latest&per_page=100"),null);
        if (!jobs.path("jobs").isArray() || jobs.path("total_count").asInt(-1)<1 || jobs.path("total_count").asInt()>100)
            throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        var matches=new ArrayList<JsonNode>();
        for(var job:jobs.path("jobs")) if (("backend-merge-"+base).equals(job.path("name").asText())) matches.add(job);
        if(matches.size()!=1) throw new GitHubIntegrationException("PR_BASE_VALIDATION_MOVED: hay que actualizar y validar la base actual");
        var job=matches.getFirst();
        if(!"completed".equals(job.path("status").asText())) throw new GitHubIntegrationException("CI_PENDING: comprobando el backend con main");
        if(!"success".equals(job.path("conclusion").asText())) throw new GitHubIntegrationException("CI_FAILED: falló el backend combinado con main");
        if(!"completed".equals(latest.path("status").asText()) || !"success".equals(latest.path("conclusion").asText()))
            throw new GitHubIntegrationException("CI_PENDING: falta el cierre de la validación backend");
        return base;
    }

    private void requireExactPullRequest(GitHubRepositoryRef repository, long number, String branch, String sha, JsonNode pr) {
        String repo = repository.owner() + "/" + repository.repo();
        if (number <= 0 || number != pr.path("number").asLong()
                || !repo.equals(pr.path("base").path("repo").path("full_name").asText())
                || !repo.equals(pr.path("head").path("repo").path("full_name").asText())
                || !"main".equals(pr.path("base").path("ref").asText())
                || !branch.equals(pr.path("head").path("ref").asText())
                || !sha.equals(pr.path("head").path("sha").asText())
                || !("https://github.com/" + repo + "/pull/" + number).equals(pr.path("html_url").asText())) {
            throw new GitHubIntegrationException("PR_OWNERSHIP_MISMATCH");
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) throw new GitHubIntegrationException("COMMIT_IDENTITY_INVALID");
    }

    public List<GitHubPullRequest> findOpenPullRequests(
            GitHubRepositoryRef repository,
            String headBranch,
            String baseBranch) {
        ensureConfigured();
        String query = "state=open&head=" + encode(repository.owner() + ":" + headBranch)
                + "&base=" + encode(baseBranch);
        JsonNode response = sendJsonRequest(
                "GET",
                properties.getApiBaseUrl().resolve(
                        "/repos/" + repository.owner() + "/" + repository.repo()
                                + "/pulls?" + query),
                null);
        if (!response.isArray()) {
            throw new GitHubIntegrationException(
                    "GitHub did not return a pull request collection");
        }
        List<GitHubPullRequest> result = new ArrayList<>();
        response.forEach(item -> result.add(toPullRequest(item)));
        return List.copyOf(result);
    }

    public GitHubRepositoryRef resolveRepository(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new GitHubIntegrationException("Git remote 'origin' is blank; cannot resolve GitHub repository");
        }

        Matcher httpsMatcher = HTTPS_REMOTE.matcher(remoteUrl.trim());
        if (httpsMatcher.matches()) {
            return new GitHubRepositoryRef(httpsMatcher.group(1), httpsMatcher.group(2));
        }

        Matcher sshMatcher = SSH_REMOTE.matcher(remoteUrl.trim());
        if (sshMatcher.matches()) {
            return new GitHubRepositoryRef(sshMatcher.group(1), sshMatcher.group(2));
        }

        throw new GitHubIntegrationException("Git remote '" + remoteUrl + "' is not a supported GitHub origin URL");
    }

    public long extractPullRequestNumber(String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            throw new GitHubIntegrationException("Pull request URL is blank; cannot synchronize GitHub pull request");
        }

        String normalizedUrl = pullRequestUrl.trim();
        int marker = normalizedUrl.indexOf("/pull/");
        if (marker < 0) {
            throw new GitHubIntegrationException("Pull request URL '" + pullRequestUrl + "' is not a supported GitHub pull request URL");
        }

        String numberPart = normalizedUrl.substring(marker + "/pull/".length());
        int slashIndex = numberPart.indexOf('/');
        if (slashIndex >= 0) {
            numberPart = numberPart.substring(0, slashIndex);
        }

        try {
            return Long.parseLong(numberPart);
        } catch (NumberFormatException exception) {
            throw new GitHubIntegrationException("Pull request URL '" + pullRequestUrl + "' does not contain a valid pull request number");
        }
    }

    private void ensureConfigured() {
        if (configuredToken() == null) {
            throw new GitHubIntegrationException("GitHub token is not configured");
        }

        Instant tokenExpiresAt = properties.getTokenExpiresAt();
        if (tokenExpiresAt != null && !tokenExpiresAt.isAfter(Instant.now())) {
            throw new GitHubIntegrationException("GitHub token is expired according to configuration (" + tokenExpiresAt + ")");
        }
    }

    JsonNode sendJsonRequest(String method, URI uri, String body) {
        return sendJsonRequest(method, uri, body, false);
    }

    JsonNode buildApi(String path) {
        if (!path.startsWith("/repos/jlnieto/atenea/actions/")) throw new GitHubIntegrationException("BUILD_EVIDENCE_INVALID");
        return sendJsonRequest("GET",properties.getApiBaseUrl().resolve(path),null);
    }

    /** Download one digest-bound diagnostic, with no credentials forwarded to blob storage. */
    JsonNode buildDiagnostic(long artifactId, String archiveSha256) {
        if(artifactId<1 || archiveSha256==null || !archiveSha256.matches("[0-9a-f]{64}"))
            throw new GitHubIntegrationException("BUILD_EVIDENCE_INVALID");
        try {
            var request=HttpRequest.newBuilder(properties.getApiBaseUrl().resolve(
                "/repos/jlnieto/atenea/actions/artifacts/"+artifactId+"/zip"))
                .timeout(properties.getReadTimeout()).header("Authorization","Bearer "+configuredToken()).GET().build();
            var redirect=httpClient.send(request,HttpResponse.BodyHandlers.discarding());
            if(redirect.statusCode()!=302) throw new GitHubIntegrationException("BUILD_DIAGNOSTIC_UNAVAILABLE");
            URI location=URI.create(redirect.headers().firstValue("Location").orElseThrow());
            String host=location.getHost();
            if(!"https".equals(location.getScheme()) || location.getUserInfo()!=null || location.getFragment()!=null
                    || (location.getPort()!=-1 && location.getPort()!=443) || host==null
                    || !(host.endsWith(".blob.core.windows.net") || host.endsWith(".githubusercontent.com")))
                throw new GitHubIntegrationException("BUILD_DIAGNOSTIC_UNAVAILABLE");
            var response=httpClient.send(HttpRequest.newBuilder(location).timeout(properties.getReadTimeout()).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            byte[] archive;
            try(var input=response.body()) {
                if(response.statusCode()!=200) throw new GitHubIntegrationException("BUILD_DIAGNOSTIC_UNAVAILABLE");
                archive=input.readNBytes(131073);
            }
            if(archive.length>131072 || !archiveSha256.equals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(archive))))
                throw new GitHubIntegrationException("BUILD_EVIDENCE_INVALID");
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                var entry=zip.getNextEntry();
                if(entry==null || !"diagnostic.json".equals(entry.getName()) || entry.isDirectory())
                    throw new GitHubIntegrationException("BUILD_EVIDENCE_INVALID");
                byte[] data=zip.readNBytes(32769);
                if(data.length>32768 || zip.getNextEntry()!=null) throw new GitHubIntegrationException("BUILD_EVIDENCE_INVALID");
                return objectMapper.readTree(data);
            }
        } catch(GitHubIntegrationException failure) { throw failure; }
        catch(Exception failure) { throw new GitHubIntegrationException("BUILD_DIAGNOSTIC_UNAVAILABLE"); }
    }

    private JsonNode sendJsonRequest(String method, URI uri, String body, boolean missingAllowed) {
        try {
            String token = configuredToken();
            if (token == null) {
                throw new GitHubIntegrationException("GitHub token is not configured");
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(properties.getReadTimeout())
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28");

            if (body != null) {
                requestBuilder.header("Content-Type", "application/json");
            }

            HttpRequest request = requestBuilder.method(
                    method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (response.statusCode() == 204) return objectMapper.nullNode();
                return objectMapper.readTree(response.body());
            }
            if (missingAllowed && response.statusCode() == 404) return objectMapper.missingNode();

            throw classifyError(response.statusCode(), response.body());
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to call GitHub: " + exception.getMessage(), exception);
        }
    }

    private String configuredToken() {
        String configured = properties.getToken();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (fileToken != null) {
            return fileToken;
        }
        String tokenFile = properties.getTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (fileToken != null) {
                return fileToken;
            }
            try {
                String loaded = Files.readString(Path.of(tokenFile.trim())).trim();
                if (!loaded.isBlank()) {
                    fileToken = loaded;
                }
                return fileToken;
            } catch (Exception exception) {
                throw new GitHubIntegrationException("GitHub token file could not be read", exception);
            }
        }
    }

    private GitHubIntegrationException classifyError(int statusCode, String responseBody) {
        String detail = extractErrorMessage(responseBody);
        if (statusCode == 401) {
            return new GitHubIntegrationException("GitHub token is invalid or expired: " + detail);
        }
        if (statusCode == 403) {
            return new GitHubIntegrationException("GitHub token is not authorized for this action or repository: " + detail);
        }
        if (statusCode == 404) {
            return new GitHubIntegrationException("GitHub repository or pull request was not found, or the token lacks access: " + detail);
        }
        if (statusCode == 422) {
            return new GitHubIntegrationException("GitHub rejected the pull request request: " + detail);
        }
        return new GitHubIntegrationException("GitHub request failed with status " + statusCode + ": " + detail);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode message = json.get("message");
            if (message != null && !message.isNull() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
        }
        return responseBody == null || responseBody.isBlank()
                ? "unknown GitHub error"
                : responseBody.replaceAll("\\s+", " ").trim();
    }

    private GitHubPullRequest toPullRequest(JsonNode json) {
        return new GitHubPullRequest(
                json.path("number").asLong(),
                json.path("html_url").asText(null),
                json.path("state").asText(null),
                json.path("merged").asBoolean(false),
                json.path("base").path("repo").path("full_name").asText(null),
                json.path("base").path("ref").asText(null),
                json.path("head").path("repo").path("full_name").asText(null),
                json.path("head").path("ref").asText(null),
                json.path("head").path("sha").asText(null),
                json.path("draft").asBoolean(false)
        );
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to encode GitHub JSON payload", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
