package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.atenea.persistence.worksession.ValidationOperationKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Synthetic peers, NOT Platform implementations or evidence of a real deployment. */
final class MobileLifecyclePeers implements AutoCloseable {
    static final String BASE="1".repeat(40), HEAD="2".repeat(40), MERGE="3".repeat(40), PREVIEW="8".repeat(40);
    static final String TREE="4".repeat(64), TOKEN="synthetic-worker-token-"+"t".repeat(32);
    final ObjectMapper mapper=new ObjectMapper();
    final Path directory;
    final Path tokenFile;
    final UnixDomainSocketAddress address;
    final HttpServer http;
    final ServerSocketChannel socket;
    final ExecutorService threads=Executors.newCachedThreadPool();
    final List<String> effects=new CopyOnWriteArrayList<>(), requests=new CopyOnWriteArrayList<>();
    final List<Throwable> failures=new CopyOnWriteArrayList<>();
    final Map<String,JsonNode> checks=new ConcurrentHashMap<>(), publications=new ConcurrentHashMap<>(), releases=new ConcurrentHashMap<>();
    UUID change,remote;
    String branch,workspace;
    List<String> files=List.of();
    volatile boolean prCreated,draft,merged,published,lostCheckReply,lostMergeReply,lostReleaseReply,failCheck,unhealthy;
    volatile boolean busyApp,foreignSource;
    String deployedApp=BASE,deployedAndroid=BASE;
    UUID appPlan=UUID.randomUUID(),appExecution=UUID.randomUUID(),androidPlan=UUID.randomUUID(),androidExecution=UUID.randomUUID();

    MobileLifecyclePeers() throws Exception {
        directory=Files.createTempDirectory("atenea-lifecycle-");
        tokenFile=directory.resolve("synthetic.token");Files.writeString(tokenFile,TOKEN);
        address=UnixDomainSocketAddress.of(directory.resolve("publisher.sock"));
        socket=ServerSocketChannel.open(StandardProtocolFamily.UNIX);socket.bind(address);
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.setExecutor(threads);http.createContext("/",this::handle);http.start();
        threads.submit(()->{
            while(socket.isOpen()) {
                try(var channel=socket.accept()) {
                    var reader=new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),StandardCharsets.UTF_8));
                    JsonNode body=mapper.readTree(reader.readLine());
                    String operation=body.path("operation").asText();requests.add("PUBLISHER "+operation);
                    JsonNode response=publisher(body);
                    // The peer committed once; only its acknowledgement is lost.
                    if(operation.equals("EXECUTE") && lostReleaseReply) {lostReleaseReply=false;continue;}
                    var buffer=ByteBuffer.wrap((mapper.writeValueAsString(response)+"\n").getBytes(StandardCharsets.UTF_8));
                    while(buffer.hasRemaining())channel.write(buffer);
                } catch(Exception failure) {if(socket.isOpen())failures.add(failure);}
            }
        });
    }
    String url() {return "http://127.0.0.1:"+http.getAddress().getPort();}
    void reset(UUID change,UUID remote,List<String> files) {
        this.change=change;this.remote=remote;this.files=files;
        branch="atenea/change-"+change;workspace="remote:ax42-01:change:"+change;
        prCreated=false;draft=true;merged=false;published=false;
        lostCheckReply=false;lostMergeReply=false;lostReleaseReply=false;failCheck=false;unhealthy=false;busyApp=false;foreignSource=false;
        deployedApp=BASE;deployedAndroid=BASE;
        checks.clear();publications.clear();releases.clear();effects.clear();requests.clear();failures.clear();
    }
    private void handle(HttpExchange exchange) {
        try {
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            requests.add(method+" "+path);
            String expected=path.startsWith("/v1/")?TOKEN:"synthetic-github-token";
            assertEquals("Bearer "+expected,exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode body=method.equals("GET")?mapper.createObjectNode():mapper.readTree(exchange.getRequestBody());
            JsonNode response;
            int status=200;
            if(path.startsWith("/v1/")) {
                response=worker(path,body);
                if(path.endsWith("validations/inspect") && response==null) {
                    status=404;response=mapper.createObjectNode().put("schemaVersion","worker-error-v1")
                        .put("code","VALIDATION_NOT_FOUND").put("category","VALIDATION").put("retryable",false)
                        .put("nextAction","NONE").putNull("blockerSessionId");
                } else if(path.endsWith("validations/start") && lostCheckReply) {
                    lostCheckReply=false;status=503;response=mapper.createObjectNode().put("schemaVersion","worker-error-v1")
                        .put("code","REMOTE_WORKER_UNAVAILABLE").put("category","TRANSPORT").put("retryable",true)
                        .put("nextAction","REQUEST_RECONCILIATION").putNull("blockerSessionId");
                }
            } else {
                response=github(method,path,body);
                if(path.endsWith("/merge") && lostMergeReply) {lostMergeReply=false;status=503;response=mapper.createObjectNode();}
            }
            if(response==null) {status=404;response=mapper.createObjectNode().put("message","Synthetic route absent");}
            byte[] bytes=mapper.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        } catch(Throwable failure) {failures.add(failure);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
        finally {exchange.close();}
    }
    private JsonNode worker(String path,JsonNode body) throws Exception {
        if(path.equals("/v1/project-workspaces/source-tree-fingerprint")) {
            assertOwner(body);
            return mapper.createObjectNode().put("state","observed").put("sessionId",remote.toString())
                .put("workspaceIdentity",foreignSource?"remote:foreign:change:"+change:workspace).put("projectId","atenea").put("headCommit",published?HEAD:BASE)
                .put("fingerprintSha256",TREE).put("stagedChangeCount",0).put("unstagedChangeCount",published?0:1)
                .put("untrackedChangeCount",0).put("valuesExposed",false);
        }
        if(path.endsWith("/validations/inspect")) {assertOwner(body);return checks.get(body.path("operationId").asText());}
        if(path.endsWith("/validations/start")) {
            assertOwner(body);assertEquals(TREE,body.path("sourceTreeFingerprintSha256").asText());
            String id=body.path("operationId").asText();
            if(checks.containsKey(id))return checks.get(id);
            var kind=ValidationOperationKind.valueOf(body.path("operation").asText());
            assertEquals(kind.definitionRevision(),body.path("definitionRevision").asText());
            effects.add("CHECK "+kind);boolean failed=failCheck;
            var result=mapper.createObjectNode().put("schemaVersion",1).put("protocolVersion","closed-validation-broker/v1")
                .put("operationId",id).put("sessionId",remote.toString()).put("workspaceIdentity",workspace).put("projectId","atenea")
                .put("sourceRevision",BASE).put("sourceTreeFingerprintSha256",TREE).put("validationDefinition",kind.name())
                .put("definitionRevision",kind.definitionRevision()).put("state",failed?"CANDIDATE_FAILED":"SUCCEEDED")
                .put("terminalCause",failed?"CANDIDATE":"NONE").put("transportState","CONFIRMED").put("exitCode",failed?1:0)
                .put("durationMillis",100).put("artifactManifestSha256","a".repeat(64)).put("summary","Closed validation "+(failed?"failed":"passed"))
                .put("createdAt",Instant.now().toString()).put("startedAt",Instant.now().toString()).put("finishedAt",Instant.now().toString())
                .put("updatedAt",Instant.now().toString()).put("revision",2).put("valuesExposed",false);
            checks.put(id,result);return result;
        }
        if(path.equals("/v1/development-changes/branches/publish")) {
            assertEquals(change.toString(),body.path("changeKey").asText());assertEquals(workspace,body.path("workspaceIdentity").asText());
            String id=body.path("operationId").asText();
            if(publications.containsKey(id))return publications.get(id);
            ObjectNode result=((ObjectNode)body).deepCopy();result.remove("repository");
            result.put("state","PUBLISHED").put("publishedHeadSha",HEAD).put("remoteDisposition","CREATED")
                .put("publicationReceiptSha256","b".repeat(64)).put("valuesExposed",false);
            effects.add("PUSH");published=true;publications.put(id,result);return result;
        }
        if(path.equals("/v1/development-changes/workspaces/inspect")) {
            assertEquals(change.toString(),body.path("changeKey").asText());
            return ((ObjectNode)body).deepCopy().put("state","OWNED").put("sourceCommit",published?HEAD:BASE)
                .putNull("sourceFingerprintSha256").put("workspaceDirty",!published).put("retainedDraft",false).put("valuesExposed",false);
        }
        if(path.equals("/v1/project-workspaces/release")) {
            assertOwner(body);assertEquals(change.toString(),body.path("changeKey").asText());
            String id=body.path("operationId").asText();
            if(releases.containsKey(id))return releases.get(id);
            var result=((ObjectNode)body).deepCopy().put("workerId","ax42-01").put("schemaVersion","project-workspace-release-v1")
                .put("state","RELEASED").put("revision",6).put("requestFingerprintSha256",hash(body))
                .put("ownershipFingerprintSha256","c".repeat(64)).put("valuesExposed",false);
            var removed=result.putObject("removed");
            Set.of("runtimeContainers","runtimeNetworks","sessionImages","previewResources","brokerResources","browserProcesses")
                .forEach(key->removed.put(key,0));
            var released=result.putObject("released");Set.of("registration","normalAdmission","heavyAdmission","allocation").forEach(key->released.put(key,false));
            var retained=result.putObject("retained");Set.of("workspaceRecord","worktree","git","turns","agentRuns","attachments","logs","artifacts","backups","policyVolumes")
                .forEach(key->retained.put(key,true));
            result.put("receiptSha256",hash(result));effects.add("CLOSE");releases.put(id,result);return result;
        }
        return null;
    }
    private void assertOwner(JsonNode body) {
        assertEquals(remote.toString(),body.path("sessionId").asText());assertEquals(workspace,body.path("workspaceIdentity").asText());
        assertEquals("atenea",body.path("projectId").asText());
    }
    private JsonNode github(String method,String path,JsonNode body) {
        String root="/repos/jlnieto/atenea";
        if(path.equals(root+"/git/ref/heads/main"))return mapper.createObjectNode().set("object",mapper.createObjectNode().put("sha",merged?MERGE:BASE));
        if(path.equals(root+"/git/commits/"+PREVIEW))return mapper.createObjectNode().put("sha",PREVIEW)
            .set("parents",mapper.createArrayNode().add(mapper.createObjectNode().put("sha",BASE)).add(mapper.createObjectNode().put("sha",HEAD)));
        if(path.equals(root+"/contents/.github/workflows/backend-merge-validation-v1.yml"))return mapper.createObjectNode().put("sha","a".repeat(40));
        if(path.equals(root+"/actions/workflows/ufd-validation-v1.yml/runs"))return run("ufd-validation-v1.yml","push",4);
        if(path.equals(root+"/actions/workflows/backend-merge-validation-v1.yml/runs"))return run("backend-merge-validation-v1.yml","pull_request",5);
        if(path.equals(root+"/actions/runs/5/jobs"))return mapper.createObjectNode().put("total_count",1).set("jobs",mapper.createArrayNode()
            .add(mapper.createObjectNode().put("name","backend-merge-"+BASE).put("status","completed").put("conclusion","success")));
        if(path.equals(root+"/commits/"+HEAD+"/check-runs"))return mapper.createObjectNode().put("total_count",1).set("check_runs",mapper.createArrayNode()
            .add(mapper.createObjectNode().put("status","completed").put("conclusion","success")));
        if(path.equals(root+"/commits/"+HEAD+"/status"))return mapper.createObjectNode().put("total_count",0).put("state","pending");
        if(path.equals("/graphql")) {draft=false;effects.add("READY_PR");return mapper.createObjectNode().set("data",mapper.createObjectNode()
            .set("markPullRequestReadyForReview",mapper.createObjectNode().set("pullRequest",mapper.createObjectNode().put("isDraft",false))));}
        if(path.equals(root+"/pulls/42/merge")) {
            assertEquals("PUT",method);assertEquals(HEAD,body.path("sha").asText());assertFalse(body.has("force"));
            assertFalse(merged,"the same PR must never be merged twice");merged=true;effects.add("MERGE");
            return mapper.createObjectNode().put("merged",true).put("sha",MERGE);
        }
        if(path.equals(root+"/pulls")) {
            if(method.equals("POST")) {assertFalse(prCreated);assertEquals(branch,body.path("head").asText());prCreated=true;effects.add("CREATE_PR");return pr();}
            return prCreated?mapper.createArrayNode().add(pr()):mapper.createArrayNode();
        }
        if(path.equals(root+"/pulls/42"))return pr();
        if(path.equals(root+"/pulls/42/files")) {var list=mapper.createArrayNode();files.forEach(file->list.add(mapper.createObjectNode().put("filename",file)));return list;}
        return null;
    }
    private JsonNode run(String workflow,String event,int id) {
        var value=mapper.createObjectNode().put("id",id).put("head_sha",HEAD).put("head_branch",branch).put("event",event)
            .put("path",".github/workflows/"+workflow).put("status","completed").put("conclusion","success");
        value.putObject("head_repository").put("full_name","jlnieto/atenea");
        value.putArray("pull_requests").addObject().put("number",42).putObject("head").put("sha",HEAD);
        return mapper.createObjectNode().set("workflow_runs",mapper.createArrayNode().add(value));
    }
    private JsonNode pr() {
        var pr=mapper.createObjectNode().put("number",42).put("html_url","https://github.com/jlnieto/atenea/pull/42")
            .put("state",merged?"closed":"open").put("node_id","PR_synthetic").put("draft",draft).put("merged",merged)
            .put("merge_commit_sha",merged?MERGE:PREVIEW).put("mergeable",true).put("mergeable_state","clean").put("changed_files",files.size());
        pr.putObject("base").put("ref","main").putObject("repo").put("full_name","jlnieto/atenea");
        pr.putObject("head").put("ref",branch).put("sha",HEAD).putObject("repo").put("full_name","jlnieto/atenea");return pr;
    }
    private JsonNode publisher(JsonNode body) {
        String operation=body.path("operation").asText();
        if(operation.equals("OBSERVE_APP") && busyApp)return mapper.createObjectNode().put("ok",false).put("errorCode","APP_OBSERVATION_BUSY");
        JsonNode result;
        if(operation.equals("OBSERVE_APP") || operation.equals("OBSERVE_ANDROID")) {
            boolean android=operation.endsWith("ANDROID");
            var observation=mapper.createObjectNode().put("protocol",android?"atenea-android-observation/v1":"atenea-app-observation/v1")
                .put("target",android?"ANDROID_STABLE":"APP_PROD").put("state","OBSERVED").put("sourceCommit",android?deployedAndroid:deployedApp)
                .put("observedAt",Instant.now().getEpochSecond()).put("finishedAt",Instant.now().getEpochSecond())
                .put("planId",(android?androidPlan:appPlan).toString()).put("operationId",(android?androidExecution:appExecution).toString())
                .put("receiptSha256","a".repeat(64));
            if(android)observation.put("versionName",deployedAndroid.equals(MERGE)?"0.5.135":"0.5.134")
                .put("versionCode",deployedAndroid.equals(MERGE)?168:167).put("apkSha256","b".repeat(64)).put("certificateSha256","c".repeat(64));
            else observation.put("healthy",!unhealthy).put("imageSha256","d".repeat(64));
            result=observation;
        } else if(operation.equals("PLAN")) {
            assertEquals(MERGE,body.path("sourceCommit").asText());
            String id=body.path("planId").asText();
            if(!releases.containsKey("plan:"+id)) {releases.put("plan:"+id,plan(body));effects.add("PLAN "+body.path("target").asText());}
            result=releases.get("plan:"+id);
        } else if(operation.equals("INSPECT"))result=releases.get("plan:"+body.path("planId").asText());
        else if(operation.equals("EXECUTE")) {
            String id=body.path("planId").asText();var original=(ObjectNode)releases.get("plan:"+id);assertNotNull(original);
            assertEquals(original.path("planSha256"),body.path("planSha256"));
            if(!original.path("state").asText().equals("SUCCEEDED")) {
                original.put("state","SUCCEEDED").put("operationId",body.path("operationId").asText())
                    .put("effectiveSourceCommit",MERGE).put("finishedAt",Instant.now().getEpochSecond()).put("resultSha256","e".repeat(64));
                boolean android=original.path("target").asText().equals("ANDROID_STABLE");
                if(android) {deployedAndroid=MERGE;androidPlan=UUID.fromString(id);androidExecution=UUID.fromString(body.path("operationId").asText());}
                else {deployedApp=MERGE;appPlan=UUID.fromString(id);appExecution=UUID.fromString(body.path("operationId").asText());}
                effects.add("DEPLOY "+original.path("target").asText());
            } else assertEquals(original.path("operationId"),body.path("operationId"));
            result=original;
        } else return mapper.createObjectNode().put("ok",false).put("errorCode","SYNTHETIC_OPERATION_ABSENT");
        return mapper.createObjectNode().put("ok",true).set("result",result);
    }
    private ObjectNode plan(JsonNode body) {
        var result=mapper.createObjectNode().put("protocol","atenea-release/v1").put("planId",body.path("planId").asText())
            .put("target",body.path("target").asText()).put("sourceCommit",MERGE).put("artifactSha256","a".repeat(64))
            .put("predecessorCommit",BASE).put("planSha256","b".repeat(64)).put("state","READY").putNull("errorCode").putNull("operationId")
            .putNull("versionCode").putNull("versionName").put("createdAt",Instant.now().getEpochSecond())
            .put("expiresAt",Instant.now().plusSeconds(600).getEpochSecond()).putNull("finishedAt").putNull("effectiveSourceCommit").putNull("resultSha256");
        if(body.path("target").asText().equals("ANDROID_STABLE"))result.put("versionCode",168).put("versionName","0.5.135");return result;
    }
    private String hash(JsonNode value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value))));}
    private Object canonical(JsonNode value) {
        if(!value.isObject())return mapper.convertValue(value,Object.class);
        var sorted=new TreeMap<String,Object>();value.fields().forEachRemaining(field->sorted.put(field.getKey(),canonical(field.getValue())));return sorted;
    }
    @Override public void close() throws Exception {
        http.stop(0);socket.close();threads.shutdownNow();
        assertTrue(threads.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
        Files.deleteIfExists(directory.resolve("publisher.sock"));
        Files.delete(tokenFile);Files.delete(directory);
    }
}
