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
        isRefreshingDeliverables: false,
        generatingDeliverableType: null,
        approvingDeliverableId: null,
        deliverablesView: null,
        approvedDeliverablesView: null,
        deliverableHistoryView: null,
        approvedPriceEstimateSummary: null,
        projectApprovedPriceEstimates: [],
        selectedDeliverableId: null,
        selectedDeliverable: null,
        deliverablesError: null,
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
        projectPricingPanel: document.getElementById("project-pricing-panel"),
        projectPricingEmpty: document.getElementById("project-pricing-empty"),
        projectPricingList: document.getElementById("project-pricing-list"),
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
        refreshDeliverables: document.getElementById("refresh-deliverables"),
        deliverablesError: document.getElementById("deliverables-error"),
        deliverablesSummary: document.getElementById("deliverables-summary"),
        generateWorkTicket: document.getElementById("generate-work-ticket"),
        generateWorkBreakdown: document.getElementById("generate-work-breakdown"),
        generatePriceEstimate: document.getElementById("generate-price-estimate"),
        deliverablesList: document.getElementById("deliverables-list"),
        deliverableDetail: document.getElementById("deliverable-detail"),
        sessionTitle: document.getElementById("session-title"),
        sessionBaseBranch: document.getElementById("session-base-branch"),
        sessionBaseBranchHelp: document.getElementById("session-base-branch-help"),
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
    elements.refreshDeliverables.addEventListener("click", () => refreshDeliverables());
    elements.generateWorkTicket.addEventListener("click", () => generateDeliverable("WORK_TICKET"));
    elements.generateWorkBreakdown.addEventListener("click", () => generateDeliverable("WORK_BREAKDOWN"));
    elements.generatePriceEstimate.addEventListener("click", () => generateDeliverable("PRICE_ESTIMATE"));
    window.addEventListener("hashchange", handleHashChange);

    init();

    async function init() {
        renderProjectFilters();
        await loadProjects();
        handleHashChange();
    }

    function renderTurnMessage(messageText) {
        const normalizedText = messageText || "";
        if (!window.marked || !window.DOMPurify) {
            return escapeHtml(normalizedText).replace(/\n/g, "<br>");
        }

        const renderedHtml = window.marked.parse(normalizedText, {
            breaks: true,
            gfm: true,
        });
        const sanitizedHtml = window.DOMPurify.sanitize(renderedHtml);
        const container = document.createElement("div");
        container.innerHTML = sanitizedHtml;
        container.querySelectorAll("a").forEach((link) => {
            const rawHref = link.getAttribute("href") || "";
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            if (!isTechnicalLink(rawHref)) {
                return;
            }
            decorateTechnicalLink(link, rawHref);
        });
        return container.innerHTML;
    }

    function isTechnicalLink(href) {
        if (!href) {
            return false;
        }
        return href.startsWith("/workspace/")
            || href.startsWith("file://")
            || /^\/.+#L\d+$/i.test(href)
            || /^\/.+#L\d+C\d+$/i.test(href);
    }

    function decorateTechnicalLink(link, rawHref) {
        const label = link.textContent || rawHref;
        const destination = document.createElement("span");
        destination.className = "technical-link__path";
        destination.textContent = rawHref;

        const labelSpan = document.createElement("span");
        labelSpan.className = "technical-link__label";
        labelSpan.textContent = label;

        link.classList.add("technical-link");
        link.textContent = "";
        link.append(labelSpan, destination);
    }

    function escapeHtml(value) {
        return value
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/\"/g, "&quot;")
            .replace(/'/g, "&#39;");
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
        state.projectApprovedPriceEstimates = [];
        resetDeliverablesState();
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
        state.projectApprovedPriceEstimates = [];
        resetDeliverablesState();
        stopRunningPolling();
        clearInlineError(elements.sessionBootstrapError);
        clearInlineError(elements.composerError);
        elements.sessionBootstrapForm.reset();
        renderProjectList();
        render();

        if (!state.selectedProject) {
            return;
        }

        await refreshProjectApprovedPriceEstimates(state.selectedProject.project.id);

        if (state.selectedProject.workSession && state.selectedProject.workSession.current) {
            await bootstrapConversationView({});
        }
    }

    function render() {
        renderHeader();
        renderMainPanels();
        renderProjectPricingPanel();
    }

    function renderHeader() {
        if (!state.selectedProject) {
            elements.projectKicker.textContent = "Select a project";
            elements.projectTitle.textContent = "Atenea";
            elements.projectSubtitle.textContent = "Choose a project to start or continue a WorkSession.";
            elements.sessionBaseBranchHelp.textContent = "";
            elements.refreshSession.classList.add("hidden");
            elements.closeSession.classList.add("hidden");
            return;
        }

        elements.projectKicker.textContent = "Project";
        elements.projectTitle.textContent = state.selectedProject.project.name;
        elements.projectSubtitle.textContent = state.selectedProject.project.repoPath;
        renderSessionBaseBranchHelp();
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

    function renderProjectPricingPanel() {
        const hasProject = Boolean(state.selectedProject);
        elements.projectPricingPanel.classList.toggle("hidden", !hasProject);
        if (!hasProject) {
            elements.projectPricingList.innerHTML = "";
            elements.projectPricingEmpty.classList.add("hidden");
            return;
        }

        const summaries = state.projectApprovedPriceEstimates || [];
        elements.projectPricingList.innerHTML = "";
        elements.projectPricingEmpty.classList.toggle("hidden", summaries.length !== 0);
        summaries.forEach((summary) => {
            const card = document.createElement("div");
            card.className = "project-pricing-card";

            const top = document.createElement("div");
            top.className = "deliverable-card__top";

            const title = document.createElement("div");
            title.className = "deliverable-card__title";
            title.textContent = `Session #${summary.sessionId} · v${summary.version}`;

            const approvedAt = document.createElement("span");
            approvedAt.className = "deliverable-card__updated";
            approvedAt.textContent = `Approved ${formatRelativeTime(summary.approvedAt)}`;
            top.append(title, approvedAt);

            const metrics = document.createElement("div");
            metrics.className = "deliverable-structured-summary__grid";
            metrics.append(
                buildStructuredMetric("Recommended", formatMoney(summary.recommendedPrice, summary.currency)),
                buildStructuredMetric("Range", `${formatMoney(summary.minimumPrice, summary.currency)} to ${formatMoney(summary.maximumPrice, summary.currency)}`),
                buildStructuredMetric("Hours", formatHours(summary.equivalentHours)),
                buildStructuredMetric("Confidence", summary.confidence || "-")
            );

            card.append(top, metrics);
            elements.projectPricingList.append(card);
        });
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

    function renderSessionBaseBranchHelp() {
        const defaultBaseBranch = state.selectedProject?.project?.defaultBaseBranch;
        if (defaultBaseBranch) {
            elements.sessionBaseBranch.placeholder = defaultBaseBranch;
            elements.sessionBaseBranchHelp.textContent =
                `If left empty, Atenea will use the project's default base branch: ${defaultBaseBranch}.`;
            return;
        }

        elements.sessionBaseBranch.placeholder = "main";
        elements.sessionBaseBranchHelp.textContent =
            "If left empty, Atenea will use the project's default base branch when it is configured.";
    }

    function renderSessionScreen() {
        const { view } = state.conversationView;
        const session = view.session;
        const composerState = getComposerState(view);
        const actionErrorMessage = describeSessionActionError(state.sessionActionError);
        const closeBlockedMessage = describeCloseBlockedMessage(session);
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
        toggleBanner(
            elements.sessionErrorBanner,
            actionErrorMessage || closeBlockedMessage || view.lastError,
            actionErrorMessage || closeBlockedMessage ? "" : "Session error: "
        );
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
        renderDeliverablesPanel();

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

            const body = document.createElement("div");
            body.className = "timeline-item__body";
            body.innerHTML = renderTurnMessage(turn.messageText);

            item.append(meta, body);
            elements.conversationTimeline.append(item);
        });
    }

    function renderDeliverablesPanel() {
        renderDeliverablesSummary();
        renderApprovedPriceEstimateSummary();
        renderDeliverablesList();
        renderDeliverableDetail();
        toggleInlineError(elements.deliverablesError, state.deliverablesError);
        elements.refreshDeliverables.disabled = !state.sessionId || state.isRefreshingDeliverables;
        elements.refreshDeliverables.textContent = state.isRefreshingDeliverables ? "Refreshing..." : "Refresh deliverables";
        updateGenerateButton(elements.generateWorkTicket, "WORK_TICKET", "Generate ticket", "Regenerate ticket");
        updateGenerateButton(elements.generateWorkBreakdown, "WORK_BREAKDOWN", "Generate breakdown", "Regenerate breakdown");
        updateGenerateButton(elements.generatePriceEstimate, "PRICE_ESTIMATE", "Generate pricing", "Regenerate pricing");
    }

    function renderDeliverablesSummary() {
        const view = state.deliverablesView;
        const approvedView = state.approvedDeliverablesView;
        elements.deliverablesSummary.innerHTML = "";

        const chips = [
            buildSummaryChip(
                "Latest generated",
                view && view.lastGeneratedAt ? formatTimestamp(view.lastGeneratedAt) : "None yet"
            ),
            buildSummaryChip(
                "Core present",
                view && view.allCoreDeliverablesPresent ? "Yes" : "Not complete"
            ),
            buildSummaryChip(
                "Core approved",
                approvedView && approvedView.allCoreDeliverablesApproved ? "Yes" : "Pending"
            ),
        ];
        chips.forEach((chip) => elements.deliverablesSummary.append(chip));
    }

    function renderApprovedPriceEstimateSummary() {
        const existing = document.getElementById("approved-price-estimate-summary");
        if (existing) {
            existing.remove();
        }

        const summary = state.approvedPriceEstimateSummary;
        if (!summary) {
            return;
        }

        const card = document.createElement("div");
        card.id = "approved-price-estimate-summary";
        card.className = "deliverable-structured-summary";

        const title = document.createElement("strong");
        title.className = "deliverable-history__title";
        title.textContent = `Approved pricing baseline · v${summary.version}`;
        card.append(title);

        const grid = document.createElement("div");
        grid.className = "deliverable-structured-summary__grid";
        grid.append(
            buildStructuredMetric("Recommended", formatMoney(summary.recommendedPrice, summary.currency)),
            buildStructuredMetric("Range", `${formatMoney(summary.minimumPrice, summary.currency)} to ${formatMoney(summary.maximumPrice, summary.currency)}`),
            buildStructuredMetric("Equivalent hours", formatHours(summary.equivalentHours)),
            buildStructuredMetric("Approved", formatTimestamp(summary.approvedAt))
        );
        card.append(grid);
        elements.deliverablesSummary.after(card);
    }

    function buildSummaryChip(label, value) {
        const item = document.createElement("div");
        item.className = "deliverables-summary__item";

        const labelNode = document.createElement("span");
        labelNode.className = "deliverables-summary__label";
        labelNode.textContent = label;

        const valueNode = document.createElement("strong");
        valueNode.className = "deliverables-summary__value";
        valueNode.textContent = value;

        item.append(labelNode, valueNode);
        return item;
    }

    function renderDeliverablesList() {
        elements.deliverablesList.innerHTML = "";
        const summaries = state.deliverablesView ? state.deliverablesView.deliverables || [] : [];

        if (!summaries.length) {
            const empty = document.createElement("div");
            empty.className = "deliverables-empty";
            empty.textContent = "No deliverables have been generated for this session yet.";
            elements.deliverablesList.append(empty);
            return;
        }

        summaries.forEach((summary) => {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "deliverable-card";
            if (summary.id === state.selectedDeliverableId) {
                card.classList.add("is-active");
            }
            card.addEventListener("click", () => selectDeliverable(summary.id));

            const top = document.createElement("div");
            top.className = "deliverable-card__top";

            const title = document.createElement("div");
            title.className = "deliverable-card__title";
            title.textContent = deliverableTypeLabel(summary.type);

            const meta = document.createElement("span");
            meta.className = "deliverable-card__meta";
            meta.textContent = `v${summary.version}`;

            top.append(title, meta);

            const badges = document.createElement("div");
            badges.className = "deliverable-card__badges";
            badges.append(
                buildDeliverableBadge(summary.status, "status"),
                buildDeliverableBadge(summary.approved ? "APPROVED" : "PENDING REVIEW", summary.approved ? "approved" : "review")
            );
            if (!summary.approved && summary.latestApprovedDeliverableId) {
                const approvedSummary = getApprovedSummary(summary.type);
                if (approvedSummary && approvedSummary.id !== summary.id) {
                    badges.append(buildDeliverableBadge(`Approved v${approvedSummary.version}`, "approved-reference"));
                }
            }

            const preview = document.createElement("p");
            preview.className = "deliverable-card__preview";
            preview.textContent = summary.preview || summary.title || "No preview available.";

            const updated = document.createElement("span");
            updated.className = "deliverable-card__updated";
            updated.textContent = `Updated ${formatRelativeTime(summary.updatedAt)}`;

            card.append(top, badges, preview, updated);
            elements.deliverablesList.append(card);
        });
    }

    function renderDeliverableDetail() {
        elements.deliverableDetail.innerHTML = "";
        if (!state.selectedDeliverable) {
            const empty = document.createElement("div");
            empty.className = "deliverable-detail__empty";
            empty.textContent = "Select a deliverable version to inspect its full content and approval status.";
            elements.deliverableDetail.append(empty);
            return;
        }

        const deliverable = state.selectedDeliverable;

        const header = document.createElement("div");
        header.className = "deliverable-detail__header";

        const titleBlock = document.createElement("div");
        const eyebrow = document.createElement("p");
        eyebrow.className = "eyebrow";
        eyebrow.textContent = deliverableTypeLabel(deliverable.type);
        const heading = document.createElement("h4");
        heading.textContent = `${deliverable.title || deliverableTypeLabel(deliverable.type)} · v${deliverable.version}`;
        titleBlock.append(eyebrow, heading);

        const actionBlock = document.createElement("div");
        actionBlock.className = "deliverable-detail__actions";
        actionBlock.append(buildDeliverableBadge(deliverable.status, "status"));
        if (deliverable.approved) {
            actionBlock.append(buildDeliverableBadge("APPROVED", "approved"));
        } else {
            const approveButton = document.createElement("button");
            approveButton.type = "button";
            approveButton.className = "primary-button";
            approveButton.textContent = state.approvingDeliverableId === deliverable.id ? "Approving..." : "Approve version";
            approveButton.disabled = deliverable.status !== "SUCCEEDED" || state.approvingDeliverableId === deliverable.id;
            approveButton.addEventListener("click", () => approveDeliverable(deliverable.id));
            actionBlock.append(approveButton);
        }

        header.append(titleBlock, actionBlock);

        const meta = document.createElement("div");
        meta.className = "deliverable-detail__meta";
        meta.innerHTML = `
            <span>Model: <strong>${escapeHtml(deliverable.model || "-")}</strong></span>
            <span>Prompt: <strong>${escapeHtml(deliverable.promptVersion || "-")}</strong></span>
            <span>Updated: <strong>${escapeHtml(formatTimestamp(deliverable.updatedAt))}</strong></span>
        `;

        const notes = document.createElement("p");
        notes.className = "deliverable-detail__notes";
        notes.textContent = deliverable.errorMessage || deliverable.generationNotes || "No generation notes.";

        const structuredSummary = renderDeliverableStructuredSummary(deliverable);
        const versionHistory = renderDeliverableVersionHistory(deliverable);
        const body = document.createElement("div");
        body.className = "deliverable-detail__body timeline-item__body";
        body.innerHTML = renderTurnMessage(deliverable.contentMarkdown || "No markdown content generated.");

        header.querySelector("h4").classList.add("deliverable-detail__title");
        elements.deliverableDetail.append(header, meta, notes);
        if (structuredSummary) {
            elements.deliverableDetail.append(structuredSummary);
        }
        elements.deliverableDetail.append(versionHistory, body);
    }

    function renderDeliverableStructuredSummary(deliverable) {
        if (deliverable.type !== "PRICE_ESTIMATE" || !deliverable.contentJson) {
            return null;
        }
        let priceEstimate;
        try {
            priceEstimate = JSON.parse(deliverable.contentJson);
        } catch (error) {
            return null;
        }

        const card = document.createElement("div");
        card.className = "deliverable-structured-summary";

        const title = document.createElement("strong");
        title.className = "deliverable-history__title";
        title.textContent = "Structured pricing summary";
        card.append(title);

        const grid = document.createElement("div");
        grid.className = "deliverable-structured-summary__grid";
        grid.append(
            buildStructuredMetric("Recommended", formatMoney(priceEstimate.recommendedPrice, priceEstimate.currency)),
            buildStructuredMetric("Range", `${formatMoney(priceEstimate.minimumPrice, priceEstimate.currency)} to ${formatMoney(priceEstimate.maximumPrice, priceEstimate.currency)}`),
            buildStructuredMetric("Equivalent hours", formatHours(priceEstimate.equivalentHours)),
            buildStructuredMetric("Confidence", priceEstimate.confidence || "-")
        );
        card.append(grid);

        return card;
    }

    function buildStructuredMetric(label, value) {
        const item = document.createElement("div");
        item.className = "deliverable-structured-summary__item";

        const labelNode = document.createElement("span");
        labelNode.className = "deliverables-summary__label";
        labelNode.textContent = label;

        const valueNode = document.createElement("strong");
        valueNode.className = "deliverables-summary__value";
        valueNode.textContent = value;

        item.append(labelNode, valueNode);
        return item;
    }

    function renderDeliverableVersionHistory(deliverable) {
        const container = document.createElement("div");
        container.className = "deliverable-history";

        const title = document.createElement("strong");
        title.className = "deliverable-history__title";
        title.textContent = "Version history";
        container.append(title);

        const versions = state.deliverableHistoryView && state.deliverableHistoryView.versions
            ? state.deliverableHistoryView.versions
            : [];
        if (!versions.length) {
            const empty = document.createElement("div");
            empty.className = "deliverable-detail__empty";
            empty.textContent = "No version history available for this deliverable type.";
            container.append(empty);
            return container;
        }

        const list = document.createElement("div");
        list.className = "deliverable-history__list";
        versions.forEach((versionSummary) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "deliverable-history__item";
            if (versionSummary.id === deliverable.id) {
                button.classList.add("is-active");
            }
            button.addEventListener("click", () => selectDeliverable(versionSummary.id));

            const label = document.createElement("span");
            label.className = "deliverable-history__label";
            label.textContent = `v${versionSummary.version}`;

            const meta = document.createElement("span");
            meta.className = "deliverable-history__meta";
            meta.textContent = versionSummary.approved
                ? `Approved ${formatRelativeTime(versionSummary.updatedAt)}`
                : `${versionSummary.status} · ${formatRelativeTime(versionSummary.updatedAt)}`;

            button.append(label, meta);
            if (state.deliverableHistoryView.latestApprovedDeliverableId === versionSummary.id) {
                button.append(buildDeliverableBadge("LATEST APPROVED", "approved-reference"));
            } else if (state.deliverableHistoryView.latestGeneratedDeliverableId === versionSummary.id) {
                button.append(buildDeliverableBadge("LATEST GENERATED", "status"));
            }
            list.append(button);
        });
        container.append(list);

        if (state.deliverableHistoryView.latestApprovedDeliverableId
            && state.deliverableHistoryView.latestApprovedDeliverableId !== state.deliverableHistoryView.latestGeneratedDeliverableId) {
            const approvedSummary = versions.find((versionSummary) =>
                versionSummary.id === state.deliverableHistoryView.latestApprovedDeliverableId);
            if (approvedSummary) {
                const notice = document.createElement("p");
                notice.className = "deliverable-history__notice";
                notice.textContent = `Latest generated version differs from the approved baseline: current approved version is v${approvedSummary.version}.`;
                container.append(notice);
            }
        }

        return container;
    }

    function buildDeliverableBadge(text, tone) {
        const badge = document.createElement("span");
        badge.className = "deliverable-badge";
        badge.dataset.tone = tone;
        badge.textContent = text;
        return badge;
    }

    function updateGenerateButton(button, type, generateLabel, regenerateLabel) {
        const hasVersion = hasDeliverableType(type);
        button.disabled = !state.sessionId || state.generatingDeliverableType !== null;
        if (state.generatingDeliverableType === type) {
            button.textContent = "Generating...";
            return;
        }
        button.textContent = hasVersion ? regenerateLabel : generateLabel;
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
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
            render();
            await loadProjects();
        } catch (error) {
            showInlineError(elements.sessionBootstrapError, error.message);
        }
    }

    async function refreshProjectApprovedPriceEstimates(projectId) {
        if (!projectId) {
            state.projectApprovedPriceEstimates = [];
            render();
            return;
        }

        try {
            const response = await apiGet(`/api/projects/${projectId}/approved-price-estimates`);
            state.projectApprovedPriceEstimates = response.approvedPriceEstimates || [];
        } catch (error) {
            state.projectApprovedPriceEstimates = [];
        } finally {
            renderProjectPricingPanel();
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
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
            if (!settings.preserveError) {
                state.sessionActionError = null;
            }
            await loadProjects();
        } catch (error) {
            state.sessionActionError = toSessionActionError(error);
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
            const response = await apiPost(`/api/sessions/${state.sessionId}/turns/conversation-view`, {
                message,
            });
            state.conversationView = response.view;
            state.turns = response.view.recentTurns.slice();
            state.hasOlderHistory = response.view.historyTruncated;
            state.sessionOpen = response.view.view.session.status === "OPEN";
            elements.composerMessage.value = "";
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
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
            const response = await apiPost(`/api/sessions/${state.sessionId}/close/conversation-view`, null);
            state.conversationView = response.view;
            state.turns = response.view.recentTurns.slice();
            state.hasOlderHistory = response.view.historyTruncated;
            state.sessionOpen = response.view.view.session.status === "OPEN";
            state.sessionActionError = null;
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
            await loadProjects();
        } catch (error) {
            state.sessionActionError = toSessionActionError(error);
            if (error.api && error.api.state) {
                await refreshCurrentSession({ preserveError: true });
            }
        } finally {
            state.isClosingSession = false;
            render();
        }
    }

    async function refreshDeliverables(options) {
        const settings = options || {};
        const sessionId = settings.sessionId || state.sessionId;
        if (!sessionId) {
            resetDeliverablesState();
            render();
            return;
        }

        if (!settings.silent) {
            state.isRefreshingDeliverables = true;
            state.deliverablesError = null;
            render();
        }

        try {
            const [deliverablesView, approvedDeliverablesView, approvedPriceEstimateSummary] = await Promise.all([
                apiGet(`/api/sessions/${sessionId}/deliverables`),
                apiGet(`/api/sessions/${sessionId}/deliverables/approved`),
                apiGet(`/api/sessions/${sessionId}/deliverables/price-estimate/approved-summary`).catch((error) => {
                    if (error.api && /Approved PRICE_ESTIMATE/.test(error.message || "")) {
                        return null;
                    }
                    throw error;
                }),
            ]);
            state.deliverablesView = deliverablesView;
            state.approvedDeliverablesView = approvedDeliverablesView;
            state.approvedPriceEstimateSummary = approvedPriceEstimateSummary;
            const selectedId = resolveSelectedDeliverableId(deliverablesView);
            state.selectedDeliverableId = selectedId;
            const selectedType = resolveSelectedDeliverableTypeFromView(deliverablesView, selectedId);
            if (selectedId && selectedType) {
                const [selectedDeliverable, deliverableHistoryView] = await Promise.all([
                    apiGet(`/api/sessions/${sessionId}/deliverables/${selectedId}`),
                    apiGet(`/api/sessions/${sessionId}/deliverables/types/${selectedType}/history`),
                ]);
                state.selectedDeliverable = selectedDeliverable;
                state.deliverableHistoryView = deliverableHistoryView;
            } else {
                state.selectedDeliverable = null;
                state.deliverableHistoryView = null;
            }
            state.deliverablesError = null;
        } catch (error) {
            state.deliverablesError = error.message;
        } finally {
            state.isRefreshingDeliverables = false;
            render();
        }
    }

    async function selectDeliverable(deliverableId) {
        if (!state.sessionId || !deliverableId || state.selectedDeliverableId === deliverableId) {
            return;
        }

        state.selectedDeliverableId = deliverableId;
        state.deliverablesError = null;
        render();

        try {
            const type = resolveSelectedDeliverableTypeFromView(state.deliverablesView, deliverableId);
            const [selectedDeliverable, deliverableHistoryView] = await Promise.all([
                apiGet(`/api/sessions/${state.sessionId}/deliverables/${deliverableId}`),
                type
                    ? apiGet(`/api/sessions/${state.sessionId}/deliverables/types/${type}/history`)
                    : Promise.resolve(state.deliverableHistoryView),
            ]);
            state.selectedDeliverable = selectedDeliverable;
            state.deliverableHistoryView = deliverableHistoryView;
        } catch (error) {
            state.deliverablesError = error.message;
            state.selectedDeliverable = null;
        } finally {
            render();
        }
    }

    async function generateDeliverable(type) {
        if (!state.sessionId || state.generatingDeliverableType) {
            return;
        }

        state.generatingDeliverableType = type;
        state.deliverablesError = null;
        render();
        try {
            const response = await apiPost(`/api/sessions/${state.sessionId}/deliverables/${type}/generate`, null);
            state.selectedDeliverableId = response.id;
            state.selectedDeliverable = response;
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
        } catch (error) {
            state.deliverablesError = error.message;
        } finally {
            state.generatingDeliverableType = null;
            render();
        }
    }

    async function approveDeliverable(deliverableId) {
        if (!state.sessionId || !deliverableId || state.approvingDeliverableId) {
            return;
        }

        state.approvingDeliverableId = deliverableId;
        state.deliverablesError = null;
        render();
        try {
            const response = await apiPost(`/api/sessions/${state.sessionId}/deliverables/${deliverableId}/approve`, null);
            state.selectedDeliverableId = response.id;
            state.selectedDeliverable = response;
            await refreshDeliverables({ silent: true, sessionId: state.sessionId });
            await refreshProjectApprovedPriceEstimates(state.selectedProjectId);
        } catch (error) {
            state.deliverablesError = error.message;
        } finally {
            state.approvingDeliverableId = null;
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
            state.sessionActionError = toSessionActionError(error);
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
            const error = new Error(message);
            error.api = body;
            throw error;
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

    function toggleInlineError(element, message) {
        if (!message) {
            clearInlineError(element);
            return;
        }
        showInlineError(element, message);
    }

    function clearInlineError(element) {
        element.textContent = "";
        element.classList.add("hidden");
    }

    function resetDeliverablesState() {
        state.isRefreshingDeliverables = false;
        state.generatingDeliverableType = null;
        state.approvingDeliverableId = null;
        state.deliverablesView = null;
        state.approvedDeliverablesView = null;
        state.deliverableHistoryView = null;
        state.approvedPriceEstimateSummary = null;
        state.selectedDeliverableId = null;
        state.selectedDeliverable = null;
        state.deliverablesError = null;
    }

    function resolveSelectedDeliverableId(deliverablesView) {
        const deliverables = deliverablesView && deliverablesView.deliverables ? deliverablesView.deliverables : [];
        if (!deliverables.length) {
            return null;
        }
        const existing = deliverables.find((deliverable) => deliverable.id === state.selectedDeliverableId);
        if (existing) {
            return existing.id;
        }
        return deliverables[0].id;
    }

    function resolveSelectedDeliverableTypeFromView(deliverablesView, deliverableId) {
        if (!deliverablesView || !deliverablesView.deliverables || !deliverableId) {
            return null;
        }
        const summary = deliverablesView.deliverables.find((deliverable) => deliverable.id === deliverableId);
        return summary ? summary.type : null;
    }

    function hasDeliverableType(type) {
        const deliverables = state.deliverablesView && state.deliverablesView.deliverables
            ? state.deliverablesView.deliverables
            : [];
        return deliverables.some((deliverable) => deliverable.type === type);
    }

    function getApprovedSummary(type) {
        const approved = state.approvedDeliverablesView && state.approvedDeliverablesView.deliverables
            ? state.approvedDeliverablesView.deliverables
            : [];
        return approved.find((deliverable) => deliverable.type === type) || null;
    }

    function deliverableTypeLabel(type) {
        switch (type) {
            case "WORK_TICKET":
                return "Work ticket";
            case "WORK_BREAKDOWN":
                return "Work breakdown";
            case "PRICE_ESTIMATE":
                return "Price estimate";
            default:
                return type || "Deliverable";
        }
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

    function toSessionActionError(error) {
        if (!error) {
            return null;
        }
        if (typeof error === "string") {
            return { message: error };
        }

        const api = error.api || {};
        return {
            message: error.message || "Request failed",
            state: api.state || null,
            reason: api.reason || null,
            action: api.action || null,
            retryable: typeof api.retryable === "boolean" ? api.retryable : null,
        };
    }

    function describeSessionActionError(actionError) {
        if (!actionError) {
            return "";
        }

        const parts = [actionError.reason || actionError.message];
        if (actionError.action) {
            parts.push(`Next action: ${actionError.action}`);
        }
        if (actionError.retryable === true) {
            parts.push("Retry is expected once that condition is resolved.");
        } else if (actionError.retryable === false && actionError.state) {
            parts.push("This usually needs a manual repository or pull request recovery step before retrying close.");
        }
        return parts.join(" ");
    }

    function describeCloseBlockedMessage(session) {
        if (session.operationalState !== "CLOSING" || !session.closeBlockedReason) {
            return "";
        }

        const parts = [`Close blocked: ${session.closeBlockedReason}`];
        if (session.closeBlockedAction) {
            parts.push(`Next action: ${session.closeBlockedAction}`);
        }
        if (session.closeRetryable === true) {
            parts.push("Retry is expected once that condition is resolved.");
        } else {
            parts.push("Manual intervention is required before retrying close.");
        }
        return parts.join(" ");
    }

    function formatTimestamp(value) {
        if (!value) {
            return "-";
        }
        const date = new Date(value);
        return date.toLocaleString();
    }

    function formatMoney(value, currency) {
        if (typeof value !== "number") {
            return "-";
        }
        return new Intl.NumberFormat(undefined, {
            style: "currency",
            currency: currency || "EUR",
            maximumFractionDigits: 2,
        }).format(value);
    }

    function formatHours(value) {
        if (typeof value !== "number") {
            return "-";
        }
        return `${value} h`;
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
        } else if (session.operationalState === "CLOSING") {
            elements.conversationStatus.textContent = describeCloseBlockedMessage(session) || "This session is closing.";
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
        if (session.operationalState === "CLOSING") {
            return {
                disabled: true,
                reason: describeCloseBlockedMessage(session) || "This session is CLOSING and does not accept new instructions.",
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
        if (session.operationalState === "CLOSING") {
            return "Close blocked";
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
        resetDeliverablesState();
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
        if (!value) {
            return "just now";
        }
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
