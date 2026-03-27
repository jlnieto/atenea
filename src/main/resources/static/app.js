(function () {
    const HISTORY_PAGE_LIMIT = 20;
    const RUNNING_POLL_INTERVAL_MS = 3000;
    const RECENT_ACTIVITY_WINDOW_MS = 60 * 60 * 1000;
    const STALE_ACTIVITY_WINDOW_MS = 24 * 60 * 60 * 1000;

    const state = {
        projects: [],
        selectedProjectId: null,
        selectedProject: null,
        sessionId: null,
        sessionOpen: false,
        conversationView: null,
        turns: [],
        hasOlderHistory: false,
        sessionActionError: null,
        isSubmittingTurn: false,
        isRefreshingSession: false,
        isClosingSession: false,
        isLoadingOlderTurns: false,
        runningPollTimeoutId: null,
        projectFilter: "all",
    };

    const elements = {
        projectList: document.getElementById("project-list"),
        projectFilters: Array.from(document.querySelectorAll(".project-filter")),
        projectsLoading: document.getElementById("projects-loading"),
        projectsError: document.getElementById("projects-error"),
        refreshProjects: document.getElementById("refresh-projects"),
        refreshSession: document.getElementById("refresh-session"),
        closeSession: document.getElementById("close-session"),
        projectKicker: document.getElementById("project-kicker"),
        projectTitle: document.getElementById("project-title"),
        projectSubtitle: document.getElementById("project-subtitle"),
        projectPlaceholder: document.getElementById("project-placeholder"),
        sessionBootstrap: document.getElementById("session-bootstrap"),
        sessionBootstrapCopy: document.getElementById("session-bootstrap-copy"),
        sessionBootstrapForm: document.getElementById("session-bootstrap-form"),
        sessionBootstrapError: document.getElementById("session-bootstrap-error"),
        sessionScreen: document.getElementById("session-screen"),
        sessionStateTitle: document.getElementById("session-state-title"),
        sessionStatePill: document.getElementById("session-state-pill"),
        factSessionTitle: document.getElementById("fact-session-title"),
        factBaseBranch: document.getElementById("fact-base-branch"),
        factCurrentBranch: document.getElementById("fact-current-branch"),
        factCanCreateTurn: document.getElementById("fact-can-create-turn"),
        repoStateSummary: document.getElementById("repo-state-summary"),
        repoStateDetail: document.getElementById("repo-state-detail"),
        latestRunSummary: document.getElementById("latest-run-summary"),
        latestRunDetail: document.getElementById("latest-run-detail"),
        sessionErrorBanner: document.getElementById("session-error-banner"),
        sessionResponseBanner: document.getElementById("session-response-banner"),
        loadOlderTurns: document.getElementById("load-older-turns"),
        conversationEmpty: document.getElementById("conversation-empty"),
        conversationHistoryNote: document.getElementById("conversation-history-note"),
        conversationTimeline: document.getElementById("conversation-timeline"),
        conversationStatus: document.getElementById("conversation-status"),
        composerForm: document.getElementById("composer-form"),
        composerMessage: document.getElementById("composer-message"),
        composerStatus: document.getElementById("composer-status"),
        composerError: document.getElementById("composer-error"),
        sendTurn: document.getElementById("send-turn"),
        sessionTitle: document.getElementById("session-title"),
        sessionBaseBranch: document.getElementById("session-base-branch"),
        sessionClosedCta: document.getElementById("session-closed-cta"),
        startNewSession: document.getElementById("start-new-session"),
    };

    elements.refreshProjects.addEventListener("click", loadProjects);
    elements.projectFilters.forEach((button) => {
        button.addEventListener("click", () => {
            state.projectFilter = button.dataset.filter || "all";
            renderProjectFilters();
            renderProjectList();
        });
    });
    elements.refreshSession.addEventListener("click", () => refreshCurrentSession());
    elements.closeSession.addEventListener("click", closeCurrentSession);
    elements.loadOlderTurns.addEventListener("click", loadOlderTurns);
    elements.sessionBootstrapForm.addEventListener("submit", submitSessionBootstrap);
    elements.composerForm.addEventListener("submit", submitTurn);
    elements.startNewSession.addEventListener("click", showBootstrapForNewSession);
    window.addEventListener("hashchange", handleHashChange);

    init();

    async function init() {
        renderProjectFilters();
        await loadProjects();
        handleHashChange();
    }

    function handleHashChange() {
        const projectId = parseProjectIdFromHash();
        if (projectId) {
            selectProject(projectId);
        } else {
            render();
        }
    }

    function parseProjectIdFromHash() {
        const match = window.location.hash.match(/^#project\/(\d+)$/);
        return match ? Number(match[1]) : null;
    }

    async function loadProjects() {
        elements.projectsLoading.classList.remove("hidden");
        elements.projectsError.classList.add("hidden");
        try {
            const projects = await apiGet("/api/projects/overview");
            state.projects = projects;
            syncSelectedProject();
            elements.projectsLoading.classList.add("hidden");
            renderProjectFilters();
            renderProjectList();

            if (!state.selectedProjectId && projects.length > 0 && !parseProjectIdFromHash()) {
                window.location.hash = `#project/${projects[0].project.id}`;
            } else {
                renderProjectList();
                render();
            }
        } catch (error) {
            elements.projectsLoading.classList.add("hidden");
            elements.projectsError.textContent = error.message;
            elements.projectsError.classList.remove("hidden");
        }
    }

    function syncSelectedProject() {
        if (!state.selectedProjectId) {
            return;
        }

        state.selectedProject = state.projects.find((entry) => entry.project.id === state.selectedProjectId) || null;
        if (state.selectedProject) {
            return;
        }

        state.selectedProjectId = null;
        state.sessionId = null;
        state.sessionOpen = false;
        state.conversationView = null;
        state.turns = [];
        state.hasOlderHistory = false;
        state.sessionActionError = null;
        stopRunningPolling();
        window.location.hash = "";
    }

    function renderProjectList() {
        elements.projectList.innerHTML = "";
        const visibleProjects = getVisibleProjects();

        if (!visibleProjects.length) {
            const emptyState = document.createElement("div");
            emptyState.className = "sidebar__state";
            emptyState.textContent = describeProjectFilterEmptyState();
            elements.projectList.append(emptyState);
            return;
        }

        visibleProjects.forEach((entry) => {
            const cardPresentation = describeProjectCard(entry);
            const button = document.createElement("button");
            button.type = "button";
            button.className = "project-card";
            button.dataset.priority = cardPresentation.priority;
            button.dataset.activity = cardPresentation.activityTone;
            if (entry.project.id === state.selectedProjectId) {
                button.classList.add("is-active");
            }
            if (cardPresentation.attentionNeeded) {
                button.classList.add("needs-attention");
            }
            button.addEventListener("click", () => {
                window.location.hash = `#project/${entry.project.id}`;
            });

            const status = document.createElement("span");
            status.className = "project-card__status";
            status.dataset.tone = cardPresentation.tone;
            status.textContent = cardPresentation.status;

            const name = document.createElement("span");
            name.className = "project-card__name";
            name.textContent = entry.project.name;

            const detail = document.createElement("span");
            detail.className = "project-card__detail";
            detail.textContent = cardPresentation.detail;

            const activity = document.createElement("span");
            activity.className = "project-card__activity";
            activity.textContent = cardPresentation.activityLabel;

            button.append(status, name, detail, activity);
            elements.projectList.append(button);
        });
    }

    function renderProjectFilters() {
        elements.projectFilters.forEach((button) => {
            button.classList.toggle("is-active", button.dataset.filter === state.projectFilter);
        });
    }

    async function selectProject(projectId) {
        if (!state.projects.length) {
            return;
        }

        state.selectedProjectId = projectId;
        state.selectedProject = state.projects.find((entry) => entry.project.id === projectId) || null;
        state.sessionId = null;
        state.sessionOpen = false;
        state.conversationView = null;
        state.turns = [];
        state.hasOlderHistory = false;
        state.sessionActionError = null;
        stopRunningPolling();
        clearInlineError(elements.sessionBootstrapError);
        clearInlineError(elements.composerError);
        elements.sessionBootstrapForm.reset();
        renderProjectList();
        render();

        if (!state.selectedProject) {
            return;
        }

        if (state.selectedProject.workSession && state.selectedProject.workSession.current) {
            await bootstrapConversationView({});
        }
    }

    function render() {
        renderHeader();
        renderMainPanels();
    }

    function renderHeader() {
        if (!state.selectedProject) {
            elements.projectKicker.textContent = "Select a project";
            elements.projectTitle.textContent = "Atenea";
            elements.projectSubtitle.textContent = "Choose a project to start or continue a WorkSession.";
            elements.refreshSession.classList.add("hidden");
            elements.closeSession.classList.add("hidden");
            return;
        }

        elements.projectKicker.textContent = "Project";
        elements.projectTitle.textContent = state.selectedProject.project.name;
        elements.projectSubtitle.textContent = state.selectedProject.project.repoPath;
        elements.refreshSession.classList.toggle("hidden", !state.sessionId);
        elements.closeSession.classList.toggle("hidden", !state.sessionOpen);
    }

    function renderMainPanels() {
        const hasProject = Boolean(state.selectedProject);
        const hasConversationView = Boolean(state.conversationView);

        elements.projectPlaceholder.classList.toggle("hidden", hasProject);
        elements.sessionBootstrap.classList.toggle("hidden", !hasProject || hasConversationView || state.sessionOpen);
        elements.sessionScreen.classList.toggle("hidden", !hasConversationView);

        if (!hasProject) {
            stopRunningPolling();
            return;
        }

        if (!hasConversationView) {
            renderBootstrapPanel();
            return;
        }

        renderSessionScreen();
    }

    function renderBootstrapPanel() {
        const workSession = state.selectedProject.workSession;
        if (workSession) {
            elements.sessionBootstrapCopy.textContent =
                `The project has no open WorkSession. Latest known session is ${workSession.status} and title "${workSession.title}". Start a new session to continue working conversationally.`;
        } else {
            elements.sessionBootstrapCopy.textContent =
                "This project has no WorkSession yet. Start the first session to open the conversational workflow.";
        }
    }

    function renderSessionScreen() {
        const { view } = state.conversationView;
        const session = view.session;
        const composerState = getComposerState(view);
        state.sessionId = session.id;
        state.sessionOpen = session.status === "OPEN";

        elements.sessionStateTitle.textContent = `Session #${session.id}`;
        elements.sessionStatePill.textContent = session.operationalState;
        elements.sessionStatePill.dataset.state = session.operationalState;
        elements.factSessionTitle.textContent = session.title || "-";
        elements.factBaseBranch.textContent = session.baseBranch || "-";
        elements.factCurrentBranch.textContent = session.repoState.currentBranch || "Unknown";
        elements.factCanCreateTurn.textContent = describeTurnStatus(view);
        elements.repoStateSummary.textContent = describeRepoState(session.repoState);
        elements.repoStateDetail.textContent = describeRepoDetail(session.repoState);
        elements.latestRunSummary.textContent = describeLatestRun(view.latestRun);
        elements.latestRunDetail.textContent = describeLatestRunDetail(view.latestRun);
        toggleBanner(elements.sessionErrorBanner, state.sessionActionError || view.lastError, state.sessionActionError ? "" : "Session error: ");
        toggleBanner(elements.sessionResponseBanner, view.lastAgentResponse, "Latest successful response: ");

        renderConversation(state.turns);
        renderConversationHistoryNote();
        elements.loadOlderTurns.classList.toggle("hidden", !state.hasOlderHistory);
        elements.loadOlderTurns.disabled = state.isLoadingOlderTurns || state.isRefreshingSession;
        elements.loadOlderTurns.textContent = state.isLoadingOlderTurns ? "Loading..." : "Load older turns";
        elements.composerMessage.disabled = composerState.disabled;
        elements.sendTurn.disabled = composerState.disabled;
        elements.sendTurn.textContent = state.isSubmittingTurn ? "Sending..." : "Send instruction";
        elements.closeSession.disabled = !state.sessionOpen || state.isClosingSession || state.isSubmittingTurn;
        elements.closeSession.textContent = state.isClosingSession ? "Closing..." : "Close session";
        elements.refreshSession.classList.remove("hidden");
        elements.refreshSession.disabled = state.isRefreshingSession || state.isSubmittingTurn || state.isClosingSession;
        elements.refreshSession.textContent = state.isRefreshingSession ? "Refreshing..." : "Refresh session";
        elements.closeSession.classList.toggle("hidden", !state.sessionOpen);
        toggleComposerStatus(composerState);
        renderConversationStatus(session, composerState);
        elements.sessionClosedCta.classList.toggle("hidden", session.operationalState !== "CLOSED");

        if (session.operationalState === "RUNNING") {
            scheduleRunningPolling();
        } else {
            stopRunningPolling();
        }
    }

    function renderConversation(turns) {
        elements.conversationTimeline.innerHTML = "";
        elements.conversationEmpty.classList.toggle("hidden", turns.length !== 0);
        turns.forEach((turn) => {
            const item = document.createElement("li");
            item.className = "timeline-item";
            item.dataset.actor = turn.actor;

            const meta = document.createElement("div");
            meta.className = "timeline-item__meta";
            meta.innerHTML = `<strong>${turn.actor}</strong><span>${formatTimestamp(turn.createdAt)}</span>`;

            const body = document.createElement("p");
            body.className = "timeline-item__body";
            body.textContent = turn.messageText || "";

            item.append(meta, body);
            elements.conversationTimeline.append(item);
        });
    }

    async function bootstrapConversationView(payload) {
        clearInlineError(elements.sessionBootstrapError);
        try {
            const response = await apiPost(
                `/api/projects/${state.selectedProject.project.id}/sessions/resolve/conversation-view`,
                payload
            );
            state.conversationView = response.view;
            state.sessionId = response.view.view.session.id;
            state.sessionOpen = response.view.view.session.status === "OPEN";
            state.turns = response.view.recentTurns.slice();
            state.hasOlderHistory = response.view.historyTruncated;
            state.sessionActionError = null;
            render();
            await loadProjects();
        } catch (error) {
            showInlineError(elements.sessionBootstrapError, error.message);
        }
    }

    async function refreshCurrentSession(options) {
        if (!state.sessionId) {
            return;
        }

        const settings = options || {};
        clearInlineError(elements.composerError);
        state.isRefreshingSession = true;
        if (!settings.preserveError) {
            state.sessionActionError = null;
        }
        render();

        try {
            const response = await apiGet(`/api/sessions/${state.sessionId}/conversation-view`);
            state.conversationView = response;
            state.turns = response.recentTurns.slice();
            state.hasOlderHistory = response.historyTruncated;
            state.sessionActionError = null;
            await loadProjects();
        } catch (error) {
            state.sessionActionError = error.message;
            stopRunningPolling();
        } finally {
            state.isRefreshingSession = false;
            render();
        }
    }

    async function submitSessionBootstrap(event) {
        event.preventDefault();
        if (!state.selectedProject) {
            return;
        }

        const payload = {
            title: elements.sessionTitle.value.trim(),
            baseBranch: elements.sessionBaseBranch.value.trim() || null,
        };

        await bootstrapConversationView(payload);
    }

    async function submitTurn(event) {
        event.preventDefault();
        if (!state.sessionId || state.isSubmittingTurn) {
            return;
        }

        clearInlineError(elements.composerError);
        const message = elements.composerMessage.value.trim();
        if (!message) {
            showInlineError(elements.composerError, "Instruction cannot be empty.");
            return;
        }
        try {
            state.isSubmittingTurn = true;
            state.sessionActionError = null;
            render();
            await apiPost(`/api/sessions/${state.sessionId}/turns`, {
                message,
            });
            elements.composerMessage.value = "";
            await refreshCurrentSession({ preserveError: true });
        } catch (error) {
            showInlineError(elements.composerError, error.message);
        } finally {
            state.isSubmittingTurn = false;
            render();
        }
    }

    async function closeCurrentSession() {
        if (!state.sessionId || state.isClosingSession) {
            return;
        }

        clearInlineError(elements.composerError);
        state.isClosingSession = true;
        state.sessionActionError = null;
        render();
        try {
            await apiPost(`/api/sessions/${state.sessionId}/close`, null);
            await refreshCurrentSession({ preserveError: true });
        } catch (error) {
            state.sessionActionError = error.message;
        } finally {
            state.isClosingSession = false;
            render();
        }
    }

    async function loadOlderTurns() {
        if (!state.sessionId || !state.turns.length || state.isLoadingOlderTurns) {
            return;
        }

        const oldestTurn = state.turns[0];
        state.isLoadingOlderTurns = true;
        state.sessionActionError = null;
        render();

        try {
            const olderTurns = await apiGet(
                `/api/sessions/${state.sessionId}/turns?beforeTurnId=${encodeURIComponent(oldestTurn.id)}&limit=${HISTORY_PAGE_LIMIT}`
            );
            if (!olderTurns.length) {
                state.hasOlderHistory = false;
                render();
                return;
            }

            state.turns = olderTurns.concat(state.turns);
            state.conversationView.recentTurns = state.turns;
            state.hasOlderHistory = olderTurns.length === HISTORY_PAGE_LIMIT;
            renderSessionScreen();
        } catch (error) {
            state.sessionActionError = error.message;
            render();
        } finally {
            state.isLoadingOlderTurns = false;
            render();
        }
    }

    async function apiGet(url) {
        const response = await fetch(toAbsoluteUrl(url), {
            headers: { Accept: "application/json" },
        });
        return handleResponse(response);
    }

    async function apiPost(url, payload) {
        const response = await fetch(toAbsoluteUrl(url), {
            method: "POST",
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json",
            },
            body: payload == null ? "{}" : JSON.stringify(payload),
        });
        return handleResponse(response);
    }

    async function handleResponse(response) {
        const contentType = response.headers.get("content-type") || "";
        const body = contentType.includes("application/json") ? await response.json() : null;
        if (!response.ok) {
            const message = body && body.message ? body.message : `Request failed with status ${response.status}`;
            throw new Error(message);
        }
        return body;
    }

    function toAbsoluteUrl(url) {
        return new URL(url, window.location.href);
    }

    function toggleBanner(element, value, prefix) {
        if (!value) {
            element.classList.add("hidden");
            element.textContent = "";
            return;
        }
        element.textContent = `${prefix}${value}`;
        element.classList.remove("hidden");
    }

    function toggleComposerStatus(composerState) {
        if (!composerState.reason) {
            elements.composerStatus.classList.add("hidden");
            elements.composerStatus.textContent = "";
            return;
        }

        elements.composerStatus.textContent = composerState.reason;
        elements.composerStatus.classList.remove("hidden");
    }

    function showInlineError(element, message) {
        element.textContent = message;
        element.classList.remove("hidden");
    }

    function clearInlineError(element) {
        element.textContent = "";
        element.classList.add("hidden");
    }

    function describeProjectCard(entry) {
        if (!entry.workSession) {
            return {
                status: "No session",
                detail: "No WorkSession yet for this project.",
                tone: "idle",
                priority: "idle",
                attentionNeeded: false,
                activityTone: "idle",
                activityLabel: "No recent activity",
            };
        }

        const workSession = entry.workSession;
        const detailParts = [];

        if (workSession.title) {
            detailParts.push(workSession.title);
        }
        if (workSession.currentBranch) {
            detailParts.push(workSession.currentBranch);
        }
        if (!workSession.repoValid) {
            detailParts.push("repo blocked");
        } else if (!workSession.workingTreeClean) {
            detailParts.push("dirty tree");
        }

        const detail = detailParts.length ? detailParts.join(" · ") : "WorkSession available for this project.";
        const activityLabel = describeActivity(workSession.lastActivityAt);
        const recentActivity = hasRecentActivity(workSession.lastActivityAt);
        const activityTone = recentActivity ? "recent" : "idle";

        if (workSession.runInProgress) {
            return {
                status: "RUNNING",
                detail,
                tone: "running",
                priority: "running",
                attentionNeeded: true,
                activityTone,
                activityLabel,
            };
        }

        if (workSession.status === "CLOSED") {
            return {
                status: "Session closed",
                detail,
                tone: !workSession.repoValid ? "blocked" : "closed",
                priority: !workSession.repoValid ? "blocked" : "idle",
                attentionNeeded: !workSession.repoValid,
                activityTone,
                activityLabel,
            };
        }

        if (!workSession.repoValid) {
            return {
                status: "Repo blocked",
                detail,
                tone: "blocked",
                priority: "blocked",
                attentionNeeded: true,
                activityTone,
                activityLabel,
            };
        }

        if (recentActivity) {
            return {
                status: "Open session",
                detail,
                tone: !workSession.workingTreeClean ? "warning" : "ready",
                priority: "active",
                attentionNeeded: true,
                activityTone,
                activityLabel,
            };
        }

        return {
            status: "Open session",
            detail,
            tone: !workSession.workingTreeClean ? "warning" : "ready",
            priority: !workSession.workingTreeClean ? "active" : "idle",
            attentionNeeded: !workSession.workingTreeClean,
            activityTone: !workSession.workingTreeClean ? "recent" : activityTone,
            activityLabel,
        };
    }

    function describeRepoState(repoState) {
        if (!repoState.repoValid) {
            return "Repository not operational";
        }
        if (repoState.runInProgress) {
            return "Operational · run in progress";
        }
        return repoState.workingTreeClean ? "Operational · clean tree" : "Operational · dirty tree";
    }

    function describeRepoDetail(repoState) {
        if (!repoState.repoValid) {
            return "The configured repository is not currently usable for WorkSession operations.";
        }
        const branch = repoState.currentBranch || "unknown branch";
        return `Current branch ${branch}. ${repoState.workingTreeClean ? "Working tree clean." : "Working tree has changes."}`;
    }

    function describeLatestRun(run) {
        if (!run) {
            return "No runs yet";
        }
        return `Run #${run.id} · ${run.status}`;
    }

    function describeLatestRunDetail(run) {
        if (!run) {
            return "No turn has been executed in this session yet.";
        }
        if (run.errorSummary) {
            return run.errorSummary;
        }
        if (run.outputSummary) {
            return run.outputSummary;
        }
        return "Run in progress.";
    }

    function formatTimestamp(value) {
        if (!value) {
            return "-";
        }
        const date = new Date(value);
        return date.toLocaleString();
    }

    function renderConversationHistoryNote() {
        if (!state.conversationView || !state.hasOlderHistory) {
            elements.conversationHistoryNote.classList.add("hidden");
            elements.conversationHistoryNote.textContent = "";
            return;
        }

        const recentTurnLimit = state.conversationView.recentTurnLimit || state.turns.length;
        elements.conversationHistoryNote.textContent =
            `Showing latest ${Math.max(recentTurnLimit, state.turns.length)} visible turns. Load older turns to inspect earlier session history.`;
        elements.conversationHistoryNote.classList.remove("hidden");
    }

    function renderConversationStatus(session, composerState) {
        if (!composerState.reason) {
            elements.conversationStatus.classList.add("hidden");
            elements.conversationStatus.textContent = "";
            return;
        }

        if (session.operationalState === "RUNNING") {
            elements.conversationStatus.textContent = "A run is currently in progress. This view refreshes automatically until the session returns to IDLE or CLOSED.";
        } else if (session.operationalState === "CLOSED") {
            elements.conversationStatus.textContent = "This session is closed. Start a new WorkSession below to continue on the same project.";
        } else {
            elements.conversationStatus.textContent = composerState.reason;
        }
        elements.conversationStatus.classList.remove("hidden");
    }

    function getComposerState(view) {
        const session = view.session;
        if (state.isSubmittingTurn) {
            return {
                disabled: true,
                reason: "Sending instruction to Codex. Wait for the current request to finish.",
            };
        }
        if (session.operationalState === "RUNNING") {
            return {
                disabled: true,
                reason: "This session is RUNNING. Wait until it returns to IDLE before sending another instruction.",
            };
        }
        if (session.operationalState === "CLOSED") {
            return {
                disabled: true,
                reason: "This session is CLOSED. Start a new session to continue working on this project.",
            };
        }
        if (!session.repoState.repoValid) {
            return {
                disabled: true,
                reason: "The repository is not operational for WorkSession work right now. Fix the repository state before sending another instruction.",
            };
        }
        if (!view.canCreateTurn) {
            return {
                disabled: true,
                reason: "This session does not currently accept a new turn.",
            };
        }
        return {
            disabled: false,
            reason: null,
        };
    }

    function describeTurnStatus(view) {
        const session = view.session;
        if (state.isSubmittingTurn) {
            return "Sending...";
        }
        if (session.operationalState === "RUNNING") {
            return "Waiting for current run";
        }
        if (session.operationalState === "CLOSED") {
            return "Session closed";
        }
        if (!session.repoState.repoValid) {
            return "Repository not operational";
        }
        if (!view.canCreateTurn) {
            return "Temporarily unavailable";
        }
        return "Ready";
    }

    function scheduleRunningPolling() {
        if (state.runningPollTimeoutId || state.isRefreshingSession || state.isSubmittingTurn || state.isClosingSession) {
            return;
        }

        state.runningPollTimeoutId = window.setTimeout(async () => {
            state.runningPollTimeoutId = null;
            if (!state.conversationView || state.conversationView.view.session.operationalState !== "RUNNING") {
                return;
            }
            await refreshCurrentSession();
        }, RUNNING_POLL_INTERVAL_MS);
    }

    function stopRunningPolling() {
        if (!state.runningPollTimeoutId) {
            return;
        }
        window.clearTimeout(state.runningPollTimeoutId);
        state.runningPollTimeoutId = null;
    }

    function showBootstrapForNewSession() {
        if (!state.conversationView) {
            return;
        }

        const session = state.conversationView.view.session;
        state.conversationView = null;
        state.sessionId = null;
        state.sessionOpen = false;
        state.turns = [];
        state.hasOlderHistory = false;
        state.sessionActionError = null;
        stopRunningPolling();
        clearInlineError(elements.sessionBootstrapError);
        clearInlineError(elements.composerError);
        elements.sessionTitle.value = session.title || "";
        elements.sessionBaseBranch.value = session.baseBranch || "";
        render();
        elements.sessionTitle.focus();
    }

    function getVisibleProjects() {
        const sortedProjects = state.projects
            .slice()
            .sort((left, right) => compareProjectPriority(left, right));

        return sortedProjects.filter((entry) => matchesProjectFilter(entry));
    }

    function compareProjectPriority(left, right) {
        const leftRank = getProjectPriorityRank(left);
        const rightRank = getProjectPriorityRank(right);
        if (leftRank !== rightRank) {
            return leftRank - rightRank;
        }

        const leftActivity = getProjectLastActivityTimestamp(left);
        const rightActivity = getProjectLastActivityTimestamp(right);
        if (leftActivity !== rightActivity) {
            return rightActivity - leftActivity;
        }

        return left.project.name.localeCompare(right.project.name);
    }

    function getProjectPriorityRank(entry) {
        const workSession = entry.workSession;
        if (!workSession) {
            return 50;
        }
        if (workSession.runInProgress) {
            return 0;
        }
        if (!workSession.repoValid) {
            return 1;
        }
        if (workSession.current && hasRecentActivity(workSession.lastActivityAt)) {
            return 2;
        }
        if (workSession.current && !workSession.workingTreeClean) {
            return 3;
        }
        if (workSession.current) {
            return 4;
        }
        if (workSession.status === "CLOSED") {
            return 20;
        }
        return 30;
    }

    function getProjectLastActivityTimestamp(entry) {
        const value = entry.workSession && entry.workSession.lastActivityAt;
        return value ? new Date(value).getTime() : 0;
    }

    function matchesProjectFilter(entry) {
        if (state.projectFilter === "all") {
            return true;
        }

        const workSession = entry.workSession;
        if (state.projectFilter === "active") {
            return Boolean(workSession && (workSession.current || workSession.runInProgress || hasRecentActivity(workSession.lastActivityAt)));
        }

        if (state.projectFilter === "attention") {
            return Boolean(workSession && (
                workSession.runInProgress
                || !workSession.repoValid
                || (workSession.current && hasRecentActivity(workSession.lastActivityAt))
            ));
        }

        return true;
    }

    function describeProjectFilterEmptyState() {
        if (state.projectFilter === "active") {
            return "No active projects right now.";
        }
        if (state.projectFilter === "attention") {
            return "No projects need attention right now.";
        }
        return "No projects available.";
    }

    function hasRecentActivity(value) {
        if (!value) {
            return false;
        }
        return Date.now() - new Date(value).getTime() <= RECENT_ACTIVITY_WINDOW_MS;
    }

    function describeActivity(value) {
        if (!value) {
            return "No recent activity";
        }

        const timestamp = new Date(value).getTime();
        const deltaMs = Date.now() - timestamp;
        if (Number.isNaN(timestamp)) {
            return "No recent activity";
        }
        if (deltaMs > STALE_ACTIVITY_WINDOW_MS) {
            return "No recent activity";
        }
        return `Updated ${formatRelativeTime(value)}`;
    }

    function formatRelativeTime(value) {
        const timestamp = new Date(value).getTime();
        const deltaSeconds = Math.round((timestamp - Date.now()) / 1000);
        const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

        const units = [
            { unit: "day", seconds: 86400 },
            { unit: "hour", seconds: 3600 },
            { unit: "minute", seconds: 60 },
            { unit: "second", seconds: 1 },
        ];

        for (const candidate of units) {
            if (Math.abs(deltaSeconds) >= candidate.seconds || candidate.unit === "second") {
                return rtf.format(Math.round(deltaSeconds / candidate.seconds), candidate.unit);
            }
        }

        return "recently";
    }
})();
