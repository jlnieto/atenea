import DOMPurify from "dompurify";
import { marked } from "marked";
import {
  AlertTriangle,
  ArrowLeft,
  BarChart3,
  Check,
  ChevronRight,
  Circle,
  ClipboardCheck,
  Code2,
  Command,
  Database,
  FileUp,
  GitBranch,
  Home,
  Lock,
  LogOut,
  Menu,
  MessageSquare,
  MonitorCheck,
  Paperclip,
  RefreshCw,
  Search,
  Server,
  Settings,
  ShieldCheck,
  TerminalSquare,
  Upload,
  X
} from "lucide-react";
import React, { FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { api } from "./api";
import {
  ApiError,
  AuthSession,
  CodexCatalog,
  CodexCatalogModel,
  CodexActivationAuthorization,
  CodexAdministratorInventory,
  CodexProgressReplay,
  CodexRecoveryAction,
  CodexRollbackAuthorization,
  CodexRunDetail,
  CodexSettings,
  CodexUpdateActivation,
  CodexUpdatePlan,
  CodexUpdateRollback,
  CodexUpdateStage,
  CoreCommandResponse,
  CoreCommandSummary,
  CoreScope,
  LegacyRemoteClosePlan,
  ManagedHost,
  MobileApiCostsOverview,
  MobileConversationTurn,
  MobileProjectOverview,
  MobileRescueConversation,
  MobileSessionEvent,
  MobileSessionOperatorState,
  MobileSessionSummary,
  MobileUpload,
  MobileWorkSessionConversation,
  OperationsHostStatus,
  OperationsIncident,
  SessionDeliverable,
  SessionDeliverableSummary,
  SessionDeliverablesView,
  SessionTurnAttachment,
  WorkSessionAttachment,
  WorkSessionAttachmentCapability,
  WorkSessionPreview
} from "./types";

type RouteName =
  | "home"
  | "projects"
  | "health"
  | "core"
  | "operations"
  | "files"
  | "costs"
  | "diagnostics"
  | "codex-admin"
  | "settings"
  | "session"
  | "conversation"
  | "rescue";

interface Route {
  name: RouteName;
  projectId?: number;
  sessionId?: number;
  rescueSessionId?: number;
}

type Level = "ok" | "warning" | "critical" | "unknown" | "running" | "neutral";

const navGroups: { title: string; items: { route: RouteName; label: string; icon: ReactNode; adminOnly?: boolean }[] }[] = [
  {
    title: "Trabajo",
    items: [
      { route: "home", label: "Inicio", icon: <Home /> },
      { route: "projects", label: "Proyectos", icon: <GitBranch /> },
      { route: "core", label: "Core", icon: <Command /> }
    ]
  },
  {
    title: "Operación",
    items: [
      { route: "health", label: "Estado", icon: <MonitorCheck /> },
      { route: "operations", label: "Operaciones", icon: <Server /> },
      { route: "files", label: "Archivos", icon: <FileUp /> }
    ]
  },
  {
    title: "Sistema",
    items: [
      { route: "costs", label: "Costes API", icon: <BarChart3 /> },
      { route: "diagnostics", label: "Diagnóstico", icon: <TerminalSquare /> },
      { route: "codex-admin", label: "Versiones Codex", icon: <ShieldCheck />, adminOnly: true },
      { route: "settings", label: "Ajustes", icon: <Settings /> }
    ]
  }
];

marked.use({
  gfm: true,
  breaks: true
});

export function App() {
  const [session, setSession] = useState<AuthSession | null>(api.currentSession);
  const [route, setRoute] = useState<Route>(() => readRoute());

  useEffect(() => {
    const unsubscribe = api.subscribe(setSession);
    return () => {
      unsubscribe();
    };
  }, []);
  useEffect(() => {
    const onHash = () => setRoute(readRoute());
    window.addEventListener("hashchange", onHash);
    if (!window.location.hash) {
      navigate({ name: "home" });
    }
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  if (!session) {
    return <LoginScreen />;
  }

  return <Shell session={session} route={route} />;
}

function Shell({ session, route }: { session: AuthSession; route: Route }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [health, setHealth] = useState<HealthOverview | null>(null);
  const [healthLoading, setHealthLoading] = useState(false);

  async function refreshHealth() {
    setHealthLoading(true);
    try {
      setHealth(await loadHealthOverview());
    } finally {
      setHealthLoading(false);
    }
  }

  useEffect(() => {
    refreshHealth();
    const timer = window.setInterval(refreshHealth, 90_000);
    return () => window.clearInterval(timer);
  }, []);

  const immersive = route.name === "conversation" || route.name === "rescue";

  return (
    <div className={`app-shell ${immersive ? "app-shell--immersive" : ""}`}>
      {!immersive && (
        <aside className={`sidebar ${menuOpen ? "is-open" : ""}`}>
          <div className="sidebar__brand">
            <div className="brand-mark">A</div>
            <div>
              <strong>Atenea Console</strong>
              <span>{session.operator.displayName || session.operator.email}</span>
            </div>
            <button className="icon-button sidebar__close" onClick={() => setMenuOpen(false)} aria-label="Cerrar menú">
              <X />
            </button>
          </div>
          <nav className="nav">
            {navGroups.map((group) => (
              <div className="nav__group" key={group.title}>
                <span className="nav__group-label">{group.title}</span>
                {group.items.filter((item) => !item.adminOnly
                  || session.operator.codexOperationsRole === "PLATFORM_ADMINISTRATOR"
                  || !session.operator.codexOperationsRole).map((item) => (
                  <button
                    className={`nav__item ${route.name === item.route ? "is-active" : ""}`}
                    key={item.route}
                    type="button"
                    onClick={() => {
                      setMenuOpen(false);
                      navigate({ name: item.route });
                    }}
                  >
                    {item.icon}
                    <span>{item.label}</span>
                  </button>
                ))}
              </div>
            ))}
          </nav>
          <button className="nav__item nav__item--logout" type="button" onClick={() => api.logout()}>
            <LogOut />
            <span>Salir</span>
          </button>
        </aside>
      )}

      <main className="main">
        {!immersive && (
          <header className="topbar">
            <button className="icon-button topbar__menu" onClick={() => setMenuOpen(true)} aria-label="Abrir menú">
              <Menu />
            </button>
            <div>
              <span className="eyebrow">Atenea</span>
              <h1>{routeTitle(route)}</h1>
            </div>
            <button className="health-indicator" onClick={() => navigate({ name: "health" })} type="button">
              <StatusDot level={healthSnapshot(health, healthLoading).level} />
              <span>{healthSnapshot(health, healthLoading).label}</span>
            </button>
          </header>
        )}
        <section className="content">
          <RouteContent route={route} refreshHealth={refreshHealth} />
        </section>
      </main>
      {!immersive && menuOpen && <button className="scrim" onClick={() => setMenuOpen(false)} aria-label="Cerrar menú" />}
    </div>
  );
}

function RouteContent({ route, refreshHealth }: { route: Route; refreshHealth: () => Promise<void> }) {
  switch (route.name) {
    case "home":
      return <HomeScreen />;
    case "projects":
      return <ProjectsScreen />;
    case "health":
      return <HealthScreen onChanged={refreshHealth} />;
    case "core":
      return <CoreScreen />;
    case "operations":
      return <OperationsScreen onChanged={refreshHealth} />;
    case "files":
      return <FilesScreen />;
    case "costs":
      return <CostsScreen />;
    case "diagnostics":
      return <DiagnosticsScreen />;
    case "codex-admin":
      return <CodexAdministrationScreen />;
    case "settings":
      return <SettingsScreen />;
    case "session":
      return route.sessionId ? <WorkSessionScreen sessionId={route.sessionId} projectId={route.projectId} /> : <MissingContext />;
    case "conversation":
      return route.sessionId ? <ConversationScreen sessionId={route.sessionId} projectId={route.projectId} /> : <MissingContext />;
    case "rescue":
      return route.projectId ? <RescueScreen projectId={route.projectId} rescueSessionId={route.rescueSessionId} /> : <MissingContext />;
    default:
      return <HomeScreen />;
  }
}

function LoginScreen() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [backendStatus, setBackendStatus] = useState<"checking" | "online" | "offline">("checking");

  useEffect(() => {
    api.health()
      .then(() => setBackendStatus("online"))
      .catch(() => setBackendStatus("offline"));
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      await api.login(email.trim(), password);
    } catch (loginError) {
      setError(errorMessage(loginError));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login">
      <section className="login__panel">
        <div className="login__brand">
          <div className="brand-mark brand-mark--large">A</div>
          <div>
            <span className="eyebrow">Atenea Console</span>
            <h1>Acceso de operador</h1>
          </div>
        </div>
        <p className="login__copy">
          Consola privada para operar proyectos, sesiones de trabajo, Core y estado del servidor.
        </p>
        <div className="login__status">
          <StatusDot level={backendStatus === "online" ? "ok" : backendStatus === "offline" ? "critical" : "unknown"} />
          <span>
            {backendStatus === "online" && "Backend disponible"}
            {backendStatus === "offline" && "Backend no disponible"}
            {backendStatus === "checking" && "Comprobando backend"}
          </span>
        </div>
        <form onSubmit={submit} className="form">
          <label className="field">
            <span>Operador</span>
            <input value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" />
          </label>
          <label className="field">
            <span>Contraseña</span>
            <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" />
          </label>
          {error && <InlineError>{error}</InlineError>}
          <button className="button button--primary" disabled={loading || !email.trim() || !password} type="submit">
            <Lock />
            {loading ? "Validando..." : "Entrar"}
          </button>
        </form>
      </section>
    </main>
  );
}

function HomeScreen() {
  const [projects, setProjects] = useState<MobileProjectOverview[]>([]);
  const [costs, setCosts] = useState<MobileApiCostsOverview | null>(null);
  const [command, setCommand] = useState<CoreCommandResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [nextProjects, nextCosts] = await Promise.all([
        api.projects(),
        api.costsOverview(7).catch(() => null)
      ]);
      setProjects(nextProjects);
      setCosts(nextCosts);
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  const active = projects.filter((project) => project.session && project.session.status !== "CLOSED");
  const attention = projects.filter((project) =>
    project.session?.runInProgress || project.session?.closeBlockedState || project.session?.pullRequestStatus === "OPEN"
  );

  return (
    <Page>
      <Panel className="command-panel">
        <div>
          <span className="eyebrow">Entrada rápida</span>
          <h2>Atenea Core</h2>
        </div>
        <CoreComposer
          scope="GLOBAL"
          placeholder="Escribe una instrucción global: revisa estado, abre una sesión, comprueba operación..."
          onCommand={setCommand}
        />
        {command && <CommandCard command={command} onChanged={setCommand} />}
      </Panel>
      <div className="grid grid--3">
        <MetricCard label="Proyectos" value={projects.length} detail={loading ? "Cargando cartera" : "Registrados"} />
        <MetricCard label="Sesiones activas" value={active.length} detail="WorkSession abiertas o en curso" level={active.length ? "running" : "neutral"} />
        <MetricCard label="Atención" value={attention.length} detail="Runs, PR o cierres bloqueados" level={attention.length ? "warning" : "ok"} />
      </div>
      {error && <InlineError>{error}</InlineError>}
      <div className="grid grid--2">
        <Panel>
          <PanelHeader title="Trabajo reciente" eyebrow="Proyectos" action={<Button variant="ghost" onClick={() => navigate({ name: "projects" })}>Abrir proyectos</Button>} />
          <List>
            {projects.slice(0, 8).map((project) => (
              <ProjectRow project={project} key={project.projectId} />
            ))}
          </List>
        </Panel>
        <Panel>
          <PanelHeader title="Coste API" eyebrow="Últimos 7 días" action={<Button variant="ghost" onClick={() => navigate({ name: "costs" })}>Ver costes</Button>} />
          {costs ? (
            <>
              <div className="hero-number">{formatMoney(costs.total, costs.currency)}</div>
              <List>
                {costs.providers.map((provider) => (
                  <Row
                    key={provider.provider}
                    title={provider.provider}
                    detail={provider.status}
                    meta={formatMoney(provider.total, provider.currency)}
                    level={provider.configured ? "ok" : "warning"}
                  />
                ))}
              </List>
            </>
          ) : (
            <EmptyState title="Sin lectura de costes" detail="El backend no devolvió datos de coste para este rango." />
          )}
        </Panel>
      </div>
    </Page>
  );
}

function ProjectsScreen() {
  const [projects, setProjects] = useState<MobileProjectOverview[]>([]);
  const [filter, setFilter] = useState<"all" | "active" | "attention">("all");
  const [query, setQuery] = useState("");
  const [titleByProject, setTitleByProject] = useState<Record<number, string>>({});
  const [loadingProjectId, setLoadingProjectId] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      setProjects(await api.projects());
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  const visible = projects.filter((project) => {
    const matchesQuery = `${project.projectName} ${project.description || ""}`.toLowerCase().includes(query.toLowerCase());
    if (!matchesQuery) {
      return false;
    }
    if (filter === "active") {
      return Boolean(project.session && project.session.status !== "CLOSED");
    }
    if (filter === "attention") {
      return Boolean(project.session?.runInProgress || project.session?.closeBlockedState || !project.session);
    }
    return true;
  });

  async function openSession(project: MobileProjectOverview) {
    setLoadingProjectId(project.projectId);
    setError("");
    try {
      const title = titleByProject[project.projectId]?.trim() || null;
      const result = await api.resolveWorkSession(project.projectId, title);
      navigate({ name: "session", projectId: project.projectId, sessionId: result.view.session.id });
    } catch (openError) {
      setError(errorMessage(openError));
    } finally {
      setLoadingProjectId(null);
    }
  }

  async function openRescue(project: MobileProjectOverview) {
    setLoadingProjectId(project.projectId);
    setError("");
    try {
      const result = await api.resolveRescueSession(project.projectId);
      navigate({ name: "rescue", projectId: project.projectId, rescueSessionId: result.view.session.id });
    } catch (openError) {
      setError(errorMessage(openError));
    } finally {
      setLoadingProjectId(null);
    }
  }

  return (
    <Page>
      <Toolbar>
        <div className="search">
          <Search />
          <input placeholder="Filtrar proyectos" value={query} onChange={(event) => setQuery(event.target.value)} />
        </div>
        <Segmented
          value={filter}
          options={[
            ["all", "Todos"],
            ["active", "Activos"],
            ["attention", "Atención"]
          ]}
          onChange={(value) => setFilter(value as typeof filter)}
        />
        <Button onClick={load} disabled={loading} icon={<RefreshCw />}>{loading ? "Actualizando" : "Actualizar"}</Button>
      </Toolbar>
      {error && <InlineError>{error}</InlineError>}
      <div className="project-grid">
        {visible.map((project) => (
          <article className="project-card" key={project.projectId}>
            <div className="project-card__top">
              <div>
                <span className="eyebrow">Proyecto #{project.projectId}</span>
                <h2>{project.projectName}</h2>
              </div>
              <StatusPill level={projectLevel(project)}>{projectLabel(project)}</StatusPill>
            </div>
            <p>{project.description || "Repositorio registrado en Atenea."}</p>
            <div className="facts">
              <Fact label="Base" value={project.defaultBaseBranch || "-"} />
              <Fact label="Sesión" value={project.session ? `#${project.session.sessionId}` : "Sin sesión"} />
              <Fact label="PR" value={project.session?.pullRequestStatus || "-"} />
            </div>
            {project.session ? (
              <div className="session-mini">
                <strong>{project.session.title}</strong>
                <span>{project.session.lastActivityAt ? formatRelative(project.session.lastActivityAt) : "Sin actividad reciente"}</span>
              </div>
            ) : (
              <label className="field">
                <span>Título de nueva sesión</span>
                <input
                  value={titleByProject[project.projectId] || ""}
                  onChange={(event) => setTitleByProject({ ...titleByProject, [project.projectId]: event.target.value })}
                  placeholder="Preparar siguiente cambio"
                />
              </label>
            )}
            <div className="button-row">
              <Button
                variant="primary"
                disabled={loadingProjectId === project.projectId}
                onClick={() => openSession(project)}
              >
                {project.session ? "Abrir sesión" : "Crear sesión"}
              </Button>
              <Button variant="ghost" disabled={loadingProjectId === project.projectId} onClick={() => openRescue(project)}>
                Rescate
              </Button>
            </div>
          </article>
        ))}
      </div>
    </Page>
  );
}

function WorkSessionScreen({ sessionId, projectId }: { sessionId: number; projectId?: number }) {
  const [summary, setSummary] = useState<MobileSessionSummary | null>(null);
  const [deliverables, setDeliverables] = useState<SessionDeliverablesView | null>(null);
  const [events, setEvents] = useState<MobileSessionEvent[]>([]);
  const [preview, setPreview] = useState<WorkSessionPreview | null>(null);
  const [selected, setSelected] = useState<SessionDeliverable | null>(null);
  const [command, setCommand] = useState<CoreCommandResponse | null>(null);
  const [billingReference, setBillingReference] = useState("");
  const [loading, setLoading] = useState(false);
  const [operatorRefreshGeneration, setOperatorRefreshGeneration] = useState(0);
  const [error, setError] = useState("");

  async function load(explicitOperatorRefresh = false) {
    setLoading(true);
    setError("");
    try {
      const [nextSummary, nextDeliverables, nextEvents, nextPreview] = await Promise.all([
        api.workSessionSummary(sessionId),
        api.sessionDeliverables(sessionId),
        api.sessionEvents(sessionId).catch(() => ({ events: [] })),
        api.workSessionPreview(sessionId).catch((previewError) => {
          if (previewError instanceof ApiError && previewError.status === 404) {
            return null;
          }
          throw previewError;
        })
      ]);
      setSummary(nextSummary);
      if (explicitOperatorRefresh) {
        setOperatorRefreshGeneration((generation) => generation + 1);
      }
      setDeliverables(nextDeliverables);
      setEvents(nextEvents.events || []);
      setPreview(nextPreview);
      if (!selected && nextDeliverables.deliverables[0]) {
        selectDeliverable(nextDeliverables.deliverables[0]);
      }
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    const timer = window.setInterval(() => {
      if (summary?.conversation.runInProgress || summary?.operatorState.surfaceEnabled || command?.confirmation) {
        load();
      }
    }, 8000);
    return () => window.clearInterval(timer);
  }, [sessionId, summary?.conversation.runInProgress, summary?.operatorState.surfaceEnabled,
    command?.confirmation?.confirmationToken]);

  async function selectDeliverable(item: SessionDeliverableSummary) {
    try {
      setSelected(await api.sessionDeliverable(sessionId, item.id));
    } catch (loadError) {
      setError(errorMessage(loadError));
    }
  }

  async function runAction(input: string) {
    setError("");
    try {
      const response = await api.runCoreCommand(input, "SESSION", projectId, sessionId);
      setCommand(response);
      if (!response.confirmation && !response.clarification) {
        await load();
      }
    } catch (actionError) {
      setError(errorMessage(actionError));
    }
  }

  if (!summary) {
    return <LoadingState error={error} onRetry={load} />;
  }

  const conversation = summary.conversation;
  const session = conversation.session;
  const actions = summary.actions;

  return (
    <Page>
      <Toolbar>
        <Button variant="ghost" icon={<ArrowLeft />} onClick={() => navigate({ name: "projects" })}>Proyectos</Button>
        <Button icon={<MessageSquare />} onClick={() => navigate({ name: "conversation", projectId, sessionId })}>Conversación</Button>
        <Button icon={<RefreshCw />} disabled={loading} onClick={() => load(true)}>{loading ? "Actualizando" : "Actualizar"}</Button>
      </Toolbar>
      {error && <InlineError>{error}</InlineError>}
      <section className="session-hero">
        <div>
          <span className="eyebrow">WorkSession #{session.id}</span>
          <h2>{session.title}</h2>
          <p>{summary.insights.latestProgress || conversation.lastAgentResponse || "Sesión lista para operar."}</p>
        </div>
        <StatusPill level={sessionLevel(session.operationalState)}>{session.operationalState}</StatusPill>
      </section>
      <RemoteCloseOperatorPanel
        state={summary.operatorState}
        currentWorkSessionId={sessionId}
        refreshGeneration={operatorRefreshGeneration}
        onChanged={load}
      />
      <PreviewPanel preview={preview} onRefresh={load} />
      {command && <CommandCard command={command} onChanged={setCommand} afterResolve={load} />}
      {session.closeBlockedReason && (
        <Banner level="warning" title="Cierre bloqueado">
          {session.closeBlockedReason} {session.closeBlockedAction ? `Siguiente acción: ${session.closeBlockedAction}` : ""}
        </Banner>
      )}
      <div className="grid grid--3">
        <MetricCard label="Branch" value={session.workspaceBranch || session.baseBranch || "-"} detail={session.baseBranch ? `Base ${session.baseBranch}` : "Sin base registrada"} />
        <MetricCard label="PR" value={session.pullRequestStatus || "Sin publicar"} detail={session.pullRequestUrl || "Pull request no disponible"} level={session.pullRequestStatus === "MERGED" ? "ok" : session.pullRequestStatus ? "warning" : "neutral"} />
        <MetricCard label="Run" value={conversation.latestRun?.status || "Sin runs"} detail={conversation.latestRun?.errorSummary || conversation.latestRun?.outputSummary || "Codex en reposo"} level={conversation.runInProgress ? "running" : "neutral"} />
      </div>
      <div className="grid grid--2">
        <Panel>
          <PanelHeader title="Acciones Core" eyebrow="Sesión" />
          <div className="button-grid">
            <Button disabled={!actions.canPublish} onClick={() => runAction("publica la pr")} icon={<GitBranch />}>Publicar PR</Button>
            <Button disabled={!actions.canSyncPullRequest} onClick={() => runAction("sincroniza la pull request")} icon={<RefreshCw />}>Sync PR</Button>
            <Button disabled={!actions.canClose} onClick={() => runAction("cierra la sesión de trabajo")} icon={<Check />}>Cerrar</Button>
            <Button disabled={!actions.canGenerateDeliverables} onClick={() => runAction("genera el ticket de trabajo")} icon={<ClipboardCheck />}>Ticket</Button>
            <Button disabled={!actions.canGenerateDeliverables} onClick={() => runAction("genera el desglose de trabajo")} icon={<ClipboardCheck />}>Desglose</Button>
            <Button disabled={!actions.canGenerateDeliverables} onClick={() => runAction("genera el presupuesto")} icon={<BarChart3 />}>Pricing</Button>
          </div>
          <CoreComposer
            compact
            scope="SESSION"
            projectId={projectId}
            workSessionId={sessionId}
            placeholder="Acción contextual vía Core..."
            onCommand={async (response) => {
              setCommand(response);
              if (!response.confirmation && !response.clarification) {
                await load();
              }
            }}
          />
        </Panel>
        <Panel>
          <PanelHeader title="Siguiente paso" eyebrow="Insights" />
          <List>
            <Row title="Progreso" detail={summary.insights.latestProgress || "Sin progreso resumido"} />
            <Row title="Bloqueo" detail={summary.insights.currentBlocker?.summary || "Sin bloqueo actual"} level={summary.insights.currentBlocker ? "warning" : "ok"} />
            <Row title="Recomendación" detail={summary.insights.nextStepRecommended || "Continuar cuando haya una decisión operativa."} />
          </List>
        </Panel>
      </div>
      <div className="grid grid--2 grid--wide-left">
        <Panel>
          <PanelHeader title="Deliverables" eyebrow="Evidencia y pricing" />
          <div className="deliverables">
            <div className="deliverables__list">
              {(deliverables?.deliverables || []).map((item) => (
                <button
                  className={`deliverable-item ${selected?.id === item.id ? "is-active" : ""}`}
                  key={item.id}
                  onClick={() => selectDeliverable(item)}
                >
                  <strong>{deliverableLabel(item.type)} v{item.version}</strong>
                  <span>{item.preview || item.title}</span>
                  <div>
                    <StatusPill level={item.approved ? "ok" : item.status === "FAILED" ? "critical" : "neutral"}>
                      {item.approved ? "APROBADO" : item.status}
                    </StatusPill>
                  </div>
                </button>
              ))}
            </div>
            <div className="deliverables__detail">
              {selected ? (
                <>
                  <div className="detail-title">
                    <h3>{selected.title}</h3>
                    <StatusPill level={selected.approved ? "ok" : "neutral"}>{selected.approved ? "APROBADO" : selected.status}</StatusPill>
                  </div>
                  <div className="button-row">
                    <Button
                      disabled={!actions.canApproveDeliverables || selected.approved}
                      onClick={() => runAction(`aprueba el deliverable ${selected.id}`)}
                    >
                      Aprobar
                    </Button>
                    {selected.type === "PRICE_ESTIMATE" && (
                      <>
                        <input
                          className="inline-input"
                          placeholder="Referencia factura"
                          value={billingReference}
                          onChange={(event) => setBillingReference(event.target.value)}
                        />
                        <Button
                          disabled={!actions.canMarkApprovedPriceEstimateBilled || !billingReference.trim()}
                          onClick={() => runAction(`marca el deliverable ${selected.id} como facturado con referencia ${billingReference.trim()}`)}
                        >
                          Facturado
                        </Button>
                      </>
                    )}
                  </div>
                  {selected.errorMessage && <InlineError>{selected.errorMessage}</InlineError>}
                  <Markdown content={selected.contentMarkdown || selected.generationNotes || "Sin contenido generado."} />
                </>
              ) : (
                <EmptyState title="Selecciona una versión" detail="Las versiones generadas y aprobadas quedan auditables por sesión." />
              )}
            </div>
          </div>
        </Panel>
        <Panel>
          <PanelHeader title="Timeline" eyebrow="Eventos recientes" />
          <Timeline events={events} />
        </Panel>
      </div>
    </Page>
  );
}

function PreviewPanel({
  preview,
  onRefresh
}: {
  preview: WorkSessionPreview | null;
  onRefresh: () => void;
}) {
  const ready = preview?.primaryAction === "OPEN"
    && preview.state === "READY"
    && Boolean(preview.privateUrl);
  const waiting = preview?.state === "STARTING" || preview?.state === "RECONCILING";
  const level: Level = ready
    ? "ok"
    : waiting
      ? "running"
      : preview?.state === "BLOCKED"
        ? "critical"
        : preview
          ? "warning"
          : "neutral";

  function openPrivatePreview() {
    if (preview?.privateUrl) {
      window.open(preview.privateUrl, "_blank", "noopener,noreferrer");
    }
  }

  return (
    <Panel className="preview-panel">
      <div className="preview-panel__copy">
        <div className="preview-panel__title">
          <span className="eyebrow">Acceso tailnet</span>
          <h2>Preview privado</h2>
          <StatusPill level={level}>{preview?.state || "SIN PREVIEW"}</StatusPill>
        </div>
        <p>
          {preview?.failureReason
            || preview?.nextAction
            || "Inícialo desde el runtime asignado a esta WorkSession."}
        </p>
        {preview && (
          <span className="preview-panel__expiry">
            {ready ? "Lease" : "Estado retenido"} · {formatAbsoluteDate(preview.leaseExpiresAt)}
          </span>
        )}
      </div>
      {ready ? (
        <Button variant="primary" icon={<MonitorCheck />} onClick={openPrivatePreview}>
          Abrir preview
        </Button>
      ) : waiting ? (
        <Button variant="primary" disabled>
          Preparando ruta
        </Button>
      ) : (
        <Button onClick={onRefresh}>
          Actualizar estado
        </Button>
      )}
    </Panel>
  );
}

type PendingImageStatus = "UPLOADING" | "READY" | "ERROR";

interface PendingImage {
  localId: string;
  filename: string;
  sizeBytes: number;
  previewUrl: string;
  status: PendingImageStatus;
  attachment?: WorkSessionAttachment;
  error?: string;
}

function ConversationScreen({ sessionId, projectId }: { sessionId: number; projectId?: number }) {
  const [conversation, setConversation] = useState<MobileWorkSessionConversation | null>(null);
  const [operatorState, setOperatorState] = useState<MobileSessionOperatorState | null>(null);
  const [attachmentCapability, setAttachmentCapability] = useState<WorkSessionAttachmentCapability | null>(null);
  const [pendingImages, setPendingImages] = useState<PendingImage[]>([]);
  const [turnRequestId, setTurnRequestId] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [attachmentCapabilityLoading, setAttachmentCapabilityLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const [attachmentError, setAttachmentError] = useState("");
  const [profile, setProfile] = useState<ExecutionProfileView | null>(null);
  const [draftModel, setDraftModel] = useState("");
  const [draftEffort, setDraftEffort] = useState("");
  const [profileLoading, setProfileLoading] = useState(true);
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileUnavailable, setProfileUnavailable] = useState(false);
  const [profileError, setProfileError] = useState("");
  const [runDetail, setRunDetail] = useState<CodexRunDetail | null>(null);
  const [runProgress, setRunProgress] = useState<CodexProgressReplay | null>(null);
  const [runProgressError, setRunProgressError] = useState("");
  const [recoveryPending, setRecoveryPending] = useState(false);
  const [recoveryNotice, setRecoveryNotice] = useState("");
  const [recoveryError, setRecoveryError] = useState("");
  const [operatorRefreshGeneration, setOperatorRefreshGeneration] = useState(0);
  const uploadInProgress = useRef(false);
  const submitInProgress = useRef(false);
  const turnRequestIdRef = useRef<string | null>(null);
  const previewUrls = useRef(new Set<string>());

  async function loadProfile() {
    setProfileLoading(true);
    setProfileError("");
    try {
      const next = await loadExecutionProfile(sessionId, projectId);
      setProfile(next);
      setDraftModel(next.model.modelId);
      setDraftEffort(next.reasoningEffort);
      setProfileUnavailable(false);
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 404) {
        setProfile(null);
        setProfileUnavailable(true);
      } else {
        setProfileError(`No se pudo confirmar el perfil. ${errorMessage(loadError)}`);
      }
    } finally {
      setProfileLoading(false);
    }
  }

  async function load(explicitOperatorRefresh = false) {
    setError("");
    setAttachmentError("");
    setAttachmentCapabilityLoading(true);
    const [summaryResult, capabilityResult] = await Promise.allSettled([
      api.workSessionSummary(sessionId),
      api.workSessionAttachmentCapability(sessionId)
    ]);
    if (summaryResult.status === "fulfilled") {
      setConversation(summaryResult.value.conversation);
      setOperatorState(summaryResult.value.operatorState);
      if (explicitOperatorRefresh) {
        setOperatorRefreshGeneration((generation) => generation + 1);
      }
    } else {
      setError(errorMessage(summaryResult.reason));
    }
    if (capabilityResult.status === "fulfilled") {
      setAttachmentCapability(capabilityResult.value);
    } else {
      setAttachmentCapability(null);
      setAttachmentError("No se pudo comprobar si esta sesión admite imágenes.");
    }
    setAttachmentCapabilityLoading(false);
    await loadProfile();
  }

  useEffect(() => {
    previewUrls.current.forEach((url) => URL.revokeObjectURL(url));
    previewUrls.current.clear();
    setPendingImages([]);
    setOperatorState(null);
    setTurnRequestId(null);
    turnRequestIdRef.current = null;
    uploadInProgress.current = false;
    submitInProgress.current = false;
    load();
  }, [sessionId]);

  useEffect(() => () => {
    previewUrls.current.forEach((url) => URL.revokeObjectURL(url));
    previewUrls.current.clear();
  }, []);

  useEffect(() => {
    if (!operatorState?.surfaceEnabled) {
      return;
    }
    const timer = window.setInterval(async () => {
      try {
        const next = await api.workSessionSummary(sessionId);
        setConversation(next.conversation);
        setOperatorState(next.operatorState);
      } catch (refreshError) {
        setError(`No se pudo actualizar el estado operativo. ${errorMessage(refreshError)}`);
      }
    }, 8_000);
    return () => window.clearInterval(timer);
  }, [sessionId, operatorState?.surfaceEnabled]);

  useEffect(() => {
    const runId = conversation?.latestRun?.id;
    if (!runId) {
      setRunDetail(null);
      setRunProgress(null);
      setRunProgressError("");
      return;
    }
    const currentRunId = runId;
    let active = true;
    async function refreshRun() {
      try {
        const [detail, progress] = await Promise.all([
          api.codexRunDetail(currentRunId),
          api.codexRunProgress(currentRunId)
        ]);
        if (active) {
          setRunDetail(detail);
          setRunProgress(progress);
          setRunProgressError("");
        }
      } catch (refreshError) {
        if (active && (!(refreshError instanceof ApiError) || refreshError.status !== 404)) {
          setRunProgressError(`No se pudo actualizar la ejecución. ${errorMessage(refreshError)}`);
        }
      }
    }
    refreshRun();
    const timer = conversation?.runInProgress ? window.setInterval(refreshRun, 3_000) : undefined;
    return () => {
      active = false;
      if (timer) window.clearInterval(timer);
    };
  }, [conversation?.latestRun?.id, conversation?.runInProgress]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const submittedMessage = message.trim();
    const readyImages = pendingImages.filter((image) => image.status === "READY" && image.attachment);
    const attachmentIds = readyImages.map((image) => image.attachment!.id);
    if (!submittedMessage || submitInProgress.current || pendingImages.some((image) => image.status !== "READY")) {
      return;
    }
    const requestId = turnRequestIdRef.current || crypto.randomUUID();
    turnRequestIdRef.current = requestId;
    setTurnRequestId(requestId);
    submitInProgress.current = true;
    setLoading(true);
    setError("");
    try {
      const response = await api.createWorkSessionTurn(sessionId, {
        message: submittedMessage,
        clientRequestId: requestId,
        attachmentIds
      });
      const acceptedTurn = [...response.recentTurns].reverse().find((turn) => turn.actor === "OPERATOR");
      const acceptedIds = acceptedTurn?.attachments.map((attachment) => attachment.id) || [];
      if (!acceptedTurn
          || acceptedTurn.messageText !== submittedMessage
          || acceptedIds.length !== attachmentIds.length
          || acceptedIds.some((id, index) => id !== attachmentIds[index])) {
        throw new Error("Atenea no confirmó todavía el turno enviado. Reintenta sin cambiar el mensaje ni las imágenes.");
      }
      setConversation(response);
      setMessage("");
      pendingImages.forEach((image) => {
        URL.revokeObjectURL(image.previewUrl);
        previewUrls.current.delete(image.previewUrl);
      });
      setPendingImages([]);
      turnRequestIdRef.current = null;
      setTurnRequestId(null);
    } catch (submitError) {
      setError(errorMessage(submitError));
    } finally {
      submitInProgress.current = false;
      setLoading(false);
    }
  }

  function invalidateTurnRequest() {
    turnRequestIdRef.current = null;
    setTurnRequestId(null);
  }

  function changeMessage(value: string) {
    if (value !== message && !loading) {
      invalidateTurnRequest();
    }
    setMessage(value);
  }

  async function applyProfile() {
    if (!profile || !draftModel || !draftEffort) {
      return;
    }
    setProfileSaving(true);
    setProfileError("");
    try {
      await api.updateSessionCodexSettings(
        sessionId,
        draftModel,
        draftEffort,
        profile.catalog.catalogRevision
      );
      await loadProfile();
    } catch (saveError) {
      setProfileError(`El perfil no se aplicó. ${errorMessage(saveError)} Revisa la selección e inténtalo de nuevo.`);
    } finally {
      setProfileSaving(false);
    }
  }

  async function requestRecovery(action: CodexRecoveryAction) {
    if (!runDetail) {
      return;
    }
    setRecoveryPending(true);
    setRecoveryNotice("");
    setRecoveryError("");
    try {
      const response = await api.requestCodexRecovery(runDetail.runId, sessionId, action);
      if (response.state === "REJECTED") {
        setRecoveryError(`${response.summary || "La operación fue rechazada."} ${nextActionLabel(response.requiredNextAction || "NONE")}.`);
      } else {
        setRecoveryNotice(response.summary || recoveryRequestedLabel(action));
      }
    } catch (requestError) {
      setRecoveryError(recoveryErrorMessage(requestError));
    } finally {
      setRecoveryPending(false);
    }
  }

  const selectedModel = profile?.catalog.models.find((model) => model.modelId === draftModel) || null;
  const profileDirty = Boolean(profile && (
    draftModel !== profile.model.modelId || draftEffort !== profile.reasoningEffort
  ));

  function changeModel(modelId: string) {
    setDraftModel(modelId);
    const model = profile?.catalog.models.find((item) => item.modelId === modelId);
    if (model && !model.efforts.includes(draftEffort)) {
      setDraftEffort(model.defaultEffort);
    }
  }

  async function uploadPendingImages(files: File[]) {
    if (!files.length || uploadInProgress.current || submitInProgress.current) {
      return;
    }
    if (attachmentCapability?.state !== "READY") {
      setAttachmentError("Las imágenes no están disponibles para esta sesión.");
      return;
    }
    uploadInProgress.current = true;
    invalidateTurnRequest();
    setUploading(true);
    setAttachmentError("");
    let nextImages = [...pendingImages];
    try {
      for (const file of files) {
        if (nextImages.length >= attachmentCapability.maxAttachmentsPerTurn) {
          const imageLabel = attachmentCapability.maxAttachmentsPerTurn === 1 ? "imagen" : "imágenes";
          throw new Error(`Puedes seleccionar hasta ${attachmentCapability.maxAttachmentsPerTurn} ${imageLabel} por mensaje.`);
        }
        const previewUrl = URL.createObjectURL(file);
        previewUrls.current.add(previewUrl);
        const pending: PendingImage = {
          localId: crypto.randomUUID(),
          filename: file.name || "imagen",
          sizeBytes: file.size,
          previewUrl,
          status: "UPLOADING"
        };
        nextImages = [...nextImages, pending];
        setPendingImages(nextImages);
        try {
          if (!attachmentCapability.acceptedContentTypes.includes(file.type)) {
            throw new Error("Usa una imagen PNG, JPEG o WebP.");
          }
          if (file.size > attachmentCapability.maxFileBytes) {
            throw new Error(`La imagen supera el máximo de ${formatBytes(attachmentCapability.maxFileBytes)}.`);
          }
          const selectedBytes = nextImages
            .filter((image) => image.localId !== pending.localId && image.status === "READY")
            .reduce((total, image) => total + image.sizeBytes, 0);
          if (selectedBytes + file.size > attachmentCapability.maxAttachmentBytesPerTurn) {
            throw new Error(`Las imágenes del mensaje superan ${formatBytes(attachmentCapability.maxAttachmentBytesPerTurn)}.`);
          }
          if (file.size > attachmentCapability.remainingSessionBytes) {
            throw new Error("La sesión no tiene cuota suficiente para esta imagen.");
          }
          const attachment = await api.uploadWorkSessionAttachment(sessionId, {
            file,
            idempotencyKey: crypto.randomUUID()
          });
          nextImages = nextImages.map((image) => image.localId === pending.localId
            ? { ...image, status: "READY", attachment }
            : image);
          setPendingImages(nextImages);
        } catch (uploadError) {
          const message = errorMessage(uploadError);
          nextImages = nextImages.map((image) => image.localId === pending.localId
            ? { ...image, status: "ERROR", error: message }
            : image);
          setPendingImages(nextImages);
          setAttachmentError(message);
        }
      }
    } catch (uploadError) {
      setAttachmentError(errorMessage(uploadError));
    } finally {
      uploadInProgress.current = false;
      setUploading(false);
    }
  }

  function pickPendingImages(event: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files || []);
    event.target.value = "";
    void uploadPendingImages(files);
  }

  function pastePendingImages(event: React.ClipboardEvent<HTMLTextAreaElement>) {
    const files = Array.from(event.clipboardData.items)
      .filter((item) => item.kind === "file" && item.type.startsWith("image/"))
      .map((item) => item.getAsFile())
      .filter((file): file is File => file !== null);
    if (!files.length) {
      return;
    }
    event.preventDefault();
    if (uploadInProgress.current) {
      setAttachmentError("Espera a que termine la subida actual y vuelve a pegar la imagen.");
      return;
    }
    void uploadPendingImages(files);
  }

  function removePendingImage(localId: string) {
    invalidateTurnRequest();
    setPendingImages((current) => {
      const removed = current.find((image) => image.localId === localId);
      if (removed) {
        URL.revokeObjectURL(removed.previewUrl);
        previewUrls.current.delete(removed.previewUrl);
      }
      return current.filter((image) => image.localId !== localId);
    });
    setAttachmentError("");
  }

  async function downloadTurnAttachment(attachment: SessionTurnAttachment) {
    setError("");
    try {
      const blob = await api.downloadWorkSessionAttachment(sessionId, attachment.id);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = attachment.originalFilename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (downloadError) {
      setError(`No se pudo descargar la imagen. ${errorMessage(downloadError)}`);
    }
  }

  return (
    <ConversationLayout
      title={conversation?.session.title || `WorkSession #${sessionId}`}
      subtitle="Conversación de sesión vía Atenea Core"
      back={() => navigate({ name: "session", projectId, sessionId })}
      refresh={() => load(true)}
    >
      {error && <InlineError>{error}</InlineError>}
      {operatorState && (
        <RemoteCloseOperatorPanel
          state={operatorState}
          currentWorkSessionId={sessionId}
          refreshGeneration={operatorRefreshGeneration}
          onChanged={load}
          dark
        />
      )}
      {(runDetail || runProgressError) && (
        <RunProgressPanel
          detail={runDetail}
          progress={runProgress}
          error={runProgressError || recoveryError}
          notice={recoveryNotice}
          pending={recoveryPending}
          retryOverride={operatorState?.surfaceEnabled
            ? operatorState.state === "CAPACITY_RELEASED"
              ? "Usa «Reintentar tarea» en el estado operativo"
              : "Reconciliar el cierre antes de reintentar"
            : null}
          onRecovery={requestRecovery}
        />
      )}
      <TurnList turns={conversation?.recentTurns || []} onDownloadAttachment={downloadTurnAttachment} />
      <form className="conversation-composer" onSubmit={submit}>
        {!profileUnavailable && (
          <ExecutionProfileControl
            profile={profile}
            selectedModel={selectedModel}
            draftModel={draftModel}
            draftEffort={draftEffort}
            dirty={profileDirty}
            loading={profileLoading}
            saving={profileSaving}
            error={profileError}
            onModelChange={changeModel}
            onEffortChange={setDraftEffort}
            onApply={applyProfile}
          />
        )}
        <AttachmentComposerState
          capability={attachmentCapability}
          loading={attachmentCapabilityLoading}
          uploading={uploading}
          disabled={loading}
          error={attachmentError}
          selectedCount={pendingImages.filter((image) => image.status === "READY").length}
          onPick={pickPendingImages}
        />
        <PendingImageChips images={pendingImages} locked={loading} onRemove={removePendingImage} />
        <div className="conversation-composer__input">
          <textarea disabled={!conversation?.canCreateTurn || loading} value={message} onChange={(event) => changeMessage(event.target.value)} onPaste={pastePendingImages} placeholder={conversation?.canCreateTurn ? "Instrucción para Codex dentro de esta sesión..." : "Espera a que termine la ejecución actual..."} />
          <Button variant="primary" disabled={loading || uploading || pendingImages.some((image) => image.status !== "READY") || !message.trim() || !conversation?.canCreateTurn || profileDirty || Boolean(profileError)}>{loading ? "Enviando" : turnRequestId ? "Reintentar envío" : "Enviar"}</Button>
        </div>
      </form>
    </ConversationLayout>
  );
}

function RemoteCloseOperatorPanel({
  state,
  currentWorkSessionId,
  refreshGeneration,
  onChanged,
  dark = false
}: {
  state: MobileSessionOperatorState;
  currentWorkSessionId: number;
  refreshGeneration: number;
  onChanged: () => void | Promise<void>;
  dark?: boolean;
}) {
  const [plan, setPlan] = useState<LegacyRemoteClosePlan | null>(null);
  const [pending, setPending] = useState(false);
  const [requiresRefresh, setRequiresRefresh] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const planIdempotencyKey = useRef<string | null>(null);
  const confirmationIdempotencyKey = useRef<string | null>(null);
  const confirmationRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setPlan(null);
    setRequiresRefresh(false);
    setNotice("");
    setError("");
    planIdempotencyKey.current = null;
    confirmationIdempotencyKey.current = null;
  }, [currentWorkSessionId, state.targetWorkSessionId, state.state, refreshGeneration]);

  useEffect(() => {
    if (plan) {
      confirmationRef.current?.scrollIntoView({ block: "nearest" });
    }
  }, [plan]);

  if (!state.surfaceEnabled) {
    return null;
  }

  const roleAllowed = operatorHasRole(state.requiredRole);
  const actionAvailable = state.primaryActionAvailable && roleAllowed &&
    remoteCloseActionMatchesState(state) && !requiresRefresh;
  const legacyConfirmationRequired = state.primaryAction === "RECONCILE_REMOTE_CLOSE"
    && ["LEGACY_CLOSE_REQUIRED", "CLOSED_OWNER_BLOCKS_CAPACITY", "REMOTE_CLOSE_BLOCKED"].includes(state.state);
  const level = remoteCloseStateLevel(state.state);
  const actionLabel = state.primaryActionLabel || remoteCloseFallbackActionLabel(state.primaryAction);

  async function runPrimaryAction() {
    if (!actionAvailable || pending) {
      return;
    }
    setPending(true);
    setError("");
    setNotice("");
    try {
      if (state.primaryAction === "RETRY_AGENT_RUN" && state.targetAgentRunId) {
        const response = await api.requestCodexRecovery(
          state.targetAgentRunId,
          currentWorkSessionId,
          "RETRY"
        );
        if (response.state === "REJECTED") {
          throw new Error("rejected");
        }
        setNotice("Reintento solicitado. La tarea original y sus adjuntos permanecen conservados.");
        await onChanged();
        return;
      }
      if (state.primaryAction === "RECONCILE_REMOTE_CLOSE" && state.targetWorkSessionId) {
        if (legacyConfirmationRequired) {
          planIdempotencyKey.current ||= crypto.randomUUID();
          const nextPlan = await api.createLegacyRemoteClosePlan(
            state.targetWorkSessionId,
            planIdempotencyKey.current
          );
          if (nextPlan.state !== "READY_FOR_CONFIRMATION" || nextPlan.consumed) {
            throw new Error("stale-plan");
          }
          setPlan(nextPlan);
          return;
        }
        await api.resumeWorkSessionClose(state.targetWorkSessionId);
        setNotice("Reconciliación solicitada. Se mantendrá la misma operación de cierre.");
        await onChanged();
      }
    } catch (actionError) {
      if (actionError instanceof ApiError && actionError.status === 409) {
        setPlan(null);
        setRequiresRefresh(true);
        planIdempotencyKey.current = null;
        confirmationIdempotencyKey.current = null;
      }
      setError(remoteCloseActionError(actionError));
    } finally {
      setPending(false);
    }
  }

  async function confirmLegacyReconciliation() {
    if (!plan || !actionAvailable || pending || !state.targetWorkSessionId) {
      return;
    }
    setPending(true);
    setError("");
    setNotice("");
    try {
      confirmationIdempotencyKey.current ||= crypto.randomUUID();
      const operation = await api.confirmLegacyRemoteClose(
        state.targetWorkSessionId,
        plan,
        confirmationIdempotencyKey.current
      );
      if (operation.state === "BLOCKED") {
        setPlan(null);
        setRequiresRefresh(true);
        planIdempotencyKey.current = null;
        confirmationIdempotencyKey.current = null;
        throw new Error("blocked");
      }
      setPlan(null);
      setNotice(operation.state === "RELEASED"
        ? "Capacidad liberada. Ya puedes actualizar el estado antes de decidir si reintentas."
        : "Reconciliación iniciada. Atenea confirmará la liberación sin repetir la operación.");
      await onChanged();
    } catch (actionError) {
      if (actionError instanceof ApiError && actionError.status === 409) {
        setPlan(null);
        setRequiresRefresh(true);
        planIdempotencyKey.current = null;
        confirmationIdempotencyKey.current = null;
      }
      setError(remoteCloseActionError(actionError));
    } finally {
      setPending(false);
    }
  }

  function cancelConfirmation() {
    setPlan(null);
    setError("");
    confirmationIdempotencyKey.current = null;
  }

  return (
    <section
      className={`remote-close-state remote-close-state--${level}${dark ? " remote-close-state--dark" : ""}`}
      aria-label="Estado operativo del cierre remoto"
      aria-live="polite"
    >
      <div className="remote-close-state__copy">
        <span className="eyebrow">Estado operativo</span>
        <div className="remote-close-state__title">
          <h2>{state.title}</h2>
          <StatusPill level={level}>{remoteCloseStateLabel(state.state)}</StatusPill>
        </div>
        {state.blocker && <p>{state.blocker}</p>}
        {!roleAllowed && state.requiredRole && (
          <p className="remote-close-state__role">Requiere {operatorRoleLabel(state.requiredRole)}.</p>
        )}
        {state.state === "CAPACITY_RELEASED" && (
          <p>El reintento es una decisión explícita: no se ha vuelto a enviar ninguna instrucción.</p>
        )}
      </div>
      <div className="remote-close-state__action">
        {!plan && state.primaryAction !== "NONE" && state.primaryAction !== "WAIT" && (
          <Button
            variant="primary"
            disabled={!actionAvailable || pending}
            onClick={runPrimaryAction}
          >
            {pending ? "Comprobando…" : actionLabel}
          </Button>
        )}
        {state.primaryAction === "WAIT" && <strong>Esperando confirmación segura</strong>}
      </div>
      {plan && (
        <div
          className="remote-close-confirmation"
          ref={confirmationRef}
          role="group"
          aria-label="Confirmar reconciliación del cierre"
        >
          <strong>Confirma la liberación de esta sesión cerrada</strong>
          <span>Se retirará únicamente su ownership remoto activo. El historial, Git, runs y adjuntos permanecerán conservados.</span>
          <small>Confirmación disponible hasta {formatAbsoluteDate(plan.expiresAt)}.</small>
          <div className="button-row">
            <Button variant="primary" disabled={pending} onClick={confirmLegacyReconciliation}>
              {pending ? "Confirmando…" : "Confirmar reconciliación"}
            </Button>
            <Button variant="ghost" disabled={pending} onClick={cancelConfirmation}>Cancelar</Button>
          </div>
        </div>
      )}
      {error && <span className="remote-close-state__error" role="alert">{error}</span>}
      {notice && <span className="remote-close-state__notice" role="status">{notice}</span>}
    </section>
  );
}

function operatorHasRole(requiredRole?: MobileSessionOperatorState["requiredRole"]) {
  if (!requiredRole) {
    return true;
  }
  const actualRole = api.currentSession?.operator.codexOperationsRole;
  if (!actualRole) {
    return false;
  }
  const rank = {
    ROUTINE_OPERATOR: 1,
    PRIVILEGED_OPERATOR: 2,
    PLATFORM_ADMINISTRATOR: 3
  } as const;
  return rank[actualRole] >= rank[requiredRole];
}

function remoteCloseActionMatchesState(state: MobileSessionOperatorState) {
  if (state.primaryAction === "RETRY_AGENT_RUN") {
    return state.state === "CAPACITY_RELEASED" && Boolean(state.targetAgentRunId);
  }
  if (state.primaryAction === "RECONCILE_REMOTE_CLOSE") {
    return ["CLOSING_REMOTE", "LEGACY_CLOSE_REQUIRED", "CLOSED_OWNER_BLOCKS_CAPACITY", "REMOTE_CLOSE_BLOCKED"].includes(state.state)
      && Boolean(state.targetWorkSessionId);
  }
  return false;
}

function operatorRoleLabel(role: NonNullable<MobileSessionOperatorState["requiredRole"]>) {
  return ({
    ROUTINE_OPERATOR: "permiso de operador",
    PRIVILEGED_OPERATOR: "permiso de operador privilegiado",
    PLATFORM_ADMINISTRATOR: "administración de plataforma"
  } as const)[role];
}

function remoteCloseStateLevel(state: MobileSessionOperatorState["state"]): Level {
  if (state === "CAPACITY_RELEASED") return "ok";
  if (["REMOTE_CLOSE_BLOCKED", "OWNERSHIP_REVIEW_REQUIRED"].includes(state)) return "critical";
  return "warning";
}

function remoteCloseStateLabel(state: MobileSessionOperatorState["state"]) {
  return ({
    CLOSING_REMOTE: "EN CURSO",
    REMOTE_CLOSE_BLOCKED: "BLOQUEADO",
    LEGACY_CLOSE_REQUIRED: "CONFIRMACIÓN",
    CLOSED_OWNER_BLOCKS_CAPACITY: "BLOQUEO",
    CLOSED_OWNER_RECONCILING: "EN CURSO",
    CAPACITY_RELEASED: "LISTA",
    OWNERSHIP_REVIEW_REQUIRED: "REVISIÓN",
    DEFAULT: "LISTA",
    RUNNING: "EN CURSO",
    CLOSED: "CERRADA"
  } as const)[state];
}

function remoteCloseFallbackActionLabel(action: MobileSessionOperatorState["primaryAction"]) {
  return ({
    RECONCILE_REMOTE_CLOSE: "Reconciliar cierre",
    RETRY_AGENT_RUN: "Reintentar tarea",
    CONTACT_PLATFORM_ADMINISTRATOR: "Contactar con administración",
    WAIT: "Esperar actualización",
    NONE: ""
  } as const)[action];
}

function remoteCloseActionError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 403) return "No tienes el permiso requerido para esta acción.";
    if (error.status === 404) return "La sesión ya no está disponible. Actualiza antes de continuar.";
    if (error.status === 409) return "El estado cambió o la confirmación caducó. Actualiza y genera una nueva confirmación.";
    if (error.status === 422) return "La reconciliación no cumple las condiciones seguras. Actualiza el estado.";
  }
  return "La acción no pudo confirmarse. El estado se conserva; actualiza antes de volver a intentarlo.";
}

function RunProgressPanel({
  detail,
  progress,
  error,
  notice,
  pending,
  retryOverride,
  onRecovery
}: {
  detail: CodexRunDetail | null;
  progress: CodexProgressReplay | null;
  error: string;
  notice: string;
  pending: boolean;
  retryOverride: string | null;
  onRecovery: (action: CodexRecoveryAction) => void;
}) {
  if (!detail) {
    return <section className="run-progress run-progress--error" role="alert">{error}</section>;
  }
  const state = progress?.currentState || detail.currentState || detail.status;
  const elapsed = progress?.elapsedMillis ?? detail.elapsedMillis;
  const nextAction = progress?.requiredNextAction || detail.requiredNextAction || "NONE";
  const events = (progress?.events || []).slice(-6);
  const action = recoveryAction(detail, nextAction, state, Boolean(retryOverride));
  return (
    <section className="run-progress" aria-label="Ejecución actual">
      <div className="run-progress__summary">
        <div className="run-progress__title">
          <span className="eyebrow">Ejecución actual</span>
          <h2>{progressStateLabel(state)}</h2>
        </div>
        <StatusPill level={runStateLevel(state)}>{state}</StatusPill>
        <div className="run-progress__fact">
          <span>Tiempo</span>
          <strong>{formatElapsed(elapsed)}</strong>
        </div>
        <div className="run-progress__fact">
          <span>Perfil efectivo</span>
          <strong>{detail.modelId || "Sin confirmar"} · {detail.reasoningEffort || "-"}</strong>
          <small>Codex {detail.codexVersion || "-"}</small>
        </div>
        <div className="run-progress__next">
          <span>Siguiente acción</span>
          <strong>{retryOverride && nextAction === "RETRY"
            ? retryOverride
            : nextActionLabel(nextAction)}</strong>
          {action && (
            <Button variant="primary" disabled={pending} onClick={() => onRecovery(action)}>
              {pending ? "Solicitando…" : recoveryActionLabel(action)}
            </Button>
          )}
        </div>
      </div>
      {progress?.latestEvent && (
        <p className="run-progress__latest">
          <strong>Último evento:</strong> {progress.latestEvent.message}
        </p>
      )}
      {events.length > 0 && (
        <ol className="run-progress__timeline" aria-label="Progreso reciente">
          {events.map((event) => (
            <li key={event.sequence}>
              <span aria-hidden="true" />
              <div>
                <strong>{progressStateLabel(event.category)}</strong>
                <small>{event.message}</small>
              </div>
              <time dateTime={event.occurredAt}>{formatRelative(event.occurredAt)}</time>
            </li>
          ))}
        </ol>
      )}
      {error && <span className="run-progress__error" role="alert">{error}</span>}
      {notice && <span className="run-progress__notice" role="status">{notice}</span>}
    </section>
  );
}

function recoveryAction(
  detail: CodexRunDetail,
  nextAction: string,
  state: string,
  suppressRetry = false
): CodexRecoveryAction | null {
  if (nextAction === "REQUEST_RECONCILIATION") return "RECONCILE";
  if (nextAction === "RETRY" && !suppressRetry) return "RETRY";
  if (!["COMPLETED", "SUCCEEDED", "FAILED", "CANCELLED", "RECONCILING"].includes(state)
      && !["SUCCEEDED", "FAILED", "CANCELLED"].includes(detail.status)) return "CANCEL";
  return null;
}

function recoveryActionLabel(action: CodexRecoveryAction) {
  return ({ CANCEL: "Cancelar ejecución", RETRY: "Reintentar", RECONCILE: "Solicitar reconciliación" })[action];
}

function recoveryRequestedLabel(action: CodexRecoveryAction) {
  return ({
    CANCEL: "Cancelación solicitada. El estado se actualizará al confirmarse.",
    RETRY: "Reintento solicitado. Espera a que aparezca la nueva ejecución.",
    RECONCILE: "Reconciliación solicitada. Espera la actualización del estado."
  })[action];
}

function recoveryErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 403) return "No tienes permiso para esta acción. Solicítala a un operador autorizado.";
    if (error.status === 404) return "La ejecución ya no está disponible. Actualiza la conversación antes de continuar.";
    if (error.status === 409) return "El estado de la ejecución ha cambiado. Actualiza y vuelve a elegir la acción aplicable.";
    return `La acción no se pudo solicitar. ${error.message} Revisa el estado e inténtalo de nuevo.`;
  }
  return "La acción no se pudo solicitar. Actualiza el estado e inténtalo de nuevo.";
}

function progressStateLabel(state: string) {
  return ({
    ACCEPTED: "Aceptada",
    QUEUED: "En cola",
    PREPARING_WORKSPACE: "Preparando workspace",
    CODEX_STARTED: "Codex iniciado",
    INSPECTING_PROJECT: "Revisando proyecto",
    RUNNING_COMMAND: "Ejecutando comprobación",
    CHECKING: "Comprobando cambios",
    WAITING: "Esperando",
    RECONCILING: "Reconciliando",
    FINALIZING: "Finalizando",
    COMPLETED: "Completada",
    SUCCEEDED: "Completada",
    FAILED: "Fallida",
    CANCELLED: "Cancelada"
  } as Record<string, string>)[state] || state;
}

function runStateLevel(state: string): Level {
  if (["FAILED"].includes(state)) return "critical";
  if (["WAITING", "RECONCILING"].includes(state)) return "warning";
  if (["COMPLETED", "SUCCEEDED"].includes(state)) return "ok";
  if (["CANCELLED"].includes(state)) return "neutral";
  return "running";
}

function nextActionLabel(action: string) {
  return ({
    NONE: "Ninguna; puedes continuar",
    WAIT: "Esperar la siguiente actualización",
    CANCEL: "Cancelar si ya no necesitas el resultado",
    RETRY: "Reintentar de forma segura",
    REQUEST_RECONCILIATION: "Solicitar reconciliación",
    CONTACT_PLATFORM_ADMINISTRATOR: "Contactar con administración"
  } as Record<string, string>)[action] || action;
}

function formatElapsed(milliseconds: number) {
  const seconds = Math.max(0, Math.floor((milliseconds || 0) / 1_000));
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return minutes ? `${minutes} min ${String(remainder).padStart(2, "0")} s` : `${remainder} s`;
}

interface ExecutionProfileView {
  catalog: CodexCatalog;
  model: CodexCatalogModel;
  modelSource: string;
  reasoningEffort: string;
  effortSource: string;
}

async function loadExecutionProfile(sessionId: number, projectId?: number): Promise<ExecutionProfileView> {
  const [catalog, sessionSettings, projectSettings] = await Promise.all([
    api.codexCatalog(),
    api.sessionCodexSettings(sessionId),
    projectId ? api.projectCodexSettings(projectId) : Promise.resolve<CodexSettings | null>(null)
  ]);
  const available = catalog.models.filter((model) => model.availability === "AVAILABLE");
  const modelId = sessionSettings.modelId || projectSettings?.modelId || available[0]?.modelId;
  const model = available.find((item) => item.modelId === modelId);
  if (!model) {
    throw new Error("El modelo efectivo no está disponible en el catálogo actual.");
  }
  const reasoningEffort = sessionSettings.reasoningEffort
    || projectSettings?.reasoningEffort
    || model.defaultEffort;
  if (!model.efforts.includes(reasoningEffort)) {
    throw new Error("El esfuerzo efectivo no es compatible con el modelo actual.");
  }
  return {
    catalog,
    model,
    modelSource: sessionSettings.modelId ? "Sesión" : projectSettings?.modelId ? "Proyecto" : "Worker",
    reasoningEffort,
    effortSource: sessionSettings.reasoningEffort ? "Sesión" : projectSettings?.reasoningEffort ? "Proyecto" : "Worker"
  };
}

function ExecutionProfileControl({
  profile,
  selectedModel,
  draftModel,
  draftEffort,
  dirty,
  loading,
  saving,
  error,
  onModelChange,
  onEffortChange,
  onApply
}: {
  profile: ExecutionProfileView | null;
  selectedModel: CodexCatalogModel | null;
  draftModel: string;
  draftEffort: string;
  dirty: boolean;
  loading: boolean;
  saving: boolean;
  error: string;
  onModelChange: (value: string) => void;
  onEffortChange: (value: string) => void;
  onApply: () => void;
}) {
  if (loading) {
    return <section className="execution-profile execution-profile--loading" aria-label="Perfil de próxima ejecución">Confirmando perfil de ejecución…</section>;
  }
  if (!profile) {
    return <section className="execution-profile execution-profile--error" role="alert">{error || "Perfil de ejecución no disponible."}</section>;
  }
  return (
    <section className="execution-profile" aria-label="Perfil de próxima ejecución">
      <div className="execution-profile__title">
        <span className="eyebrow">Próxima ejecución</span>
        <strong>{dirty ? "Cambios sin aplicar" : "Perfil listo"}</strong>
      </div>
      <label>
        <span>Modelo</span>
        <select value={draftModel} onChange={(event) => onModelChange(event.target.value)}>
          {profile.catalog.models.filter((model) => model.availability === "AVAILABLE").map((model) => (
            <option key={model.modelId} value={model.modelId}>{model.displayName}</option>
          ))}
        </select>
      </label>
      <label>
        <span>Esfuerzo</span>
        <select value={draftEffort} onChange={(event) => onEffortChange(event.target.value)}>
          {(selectedModel?.efforts || []).map((effort) => <option key={effort} value={effort}>{effort}</option>)}
        </select>
      </label>
      <div className="execution-profile__meta">
        <strong>Codex {profile.catalog.codexVersion}</strong>
        <span>Origen: modelo {profile.modelSource} · esfuerzo {profile.effortSource}</span>
        {error && <span className="execution-profile__error" role="alert">{error}</span>}
      </div>
      {dirty && <Button disabled={saving} onClick={onApply}>{saving ? "Aplicando…" : "Aplicar perfil"}</Button>}
    </section>
  );
}

function AttachmentComposerState({
  capability,
  loading,
  uploading,
  disabled,
  error,
  selectedCount,
  onPick
}: {
  capability: WorkSessionAttachmentCapability | null;
  loading: boolean;
  uploading: boolean;
  disabled: boolean;
  error: string;
  selectedCount: number;
  onPick: (event: React.ChangeEvent<HTMLInputElement>) => void;
}) {
  const ready = capability?.state === "READY";
  const blockedTitle = (() => {
    switch (capability?.blockedReason) {
      case "SESSION_NOT_ELIGIBLE":
        return "Sesión sin imágenes";
      case "OWNERSHIP_INVALID":
        return "Sesión no válida para imágenes";
      case "SESSION_QUOTA_EXHAUSTED":
        return "Cuota de imágenes agotada";
      case "WORKER_UNAVAILABLE":
        return "Imágenes temporalmente no disponibles";
      case "WORKER_UNSUPPORTED":
        return "AX42 necesita actualizarse";
      default:
        return "Solo texto";
    }
  })();
  const title = loading
    ? "Comprobando imágenes"
    : uploading
      ? "Subiendo imagen"
    : error
      ? selectedCount ? "Revisa las imágenes" : "No se pudo añadir la imagen"
      : selectedCount
        ? selectedCount === 1 ? "1 imagen lista" : `${selectedCount} imágenes listas`
      : ready
        ? "Imágenes disponibles"
        : blockedTitle;
  const detail = loading
    ? "Puedes seguir preparando el mensaje."
    : uploading
      ? "La imagen se seleccionará al terminar."
    : error
      ? selectedCount
        ? `${error} Quita una imagen o elige otra.`
        : `${error} Elige otra imagen o continúa con texto.`
      : selectedCount
        ? "Seleccionadas para el próximo mensaje."
      : capability
        ? `${capability.message} ${capability.nextAction}`
        : "Continúa con texto.";
  const visualState = loading ? "loading" : error ? "error" : ready ? "ready" : "blocked";
  return (
    <section
      className={`attachment-composer-state attachment-composer-state--${visualState}`}
      aria-label="Estado de imágenes del mensaje"
      aria-live="polite"
    >
      <span className="attachment-composer-state__indicator" aria-hidden="true" />
      <div className="attachment-composer-state__copy">
        <strong>{title}</strong>
        <span>{detail}</span>
      </div>
      {ready && (
        <label className={`attachment-composer-action ${uploading || disabled ? "is-disabled" : ""}`}>
          <Paperclip />
          <span>{uploading ? "Subiendo…" : "Añadir imagen"}</span>
          <input
            aria-label="Seleccionar imágenes"
            type="file"
            accept={capability.acceptedContentTypes.join(",")}
            multiple
            disabled={uploading || disabled}
            onChange={onPick}
          />
        </label>
      )}
    </section>
  );
}

function PendingImageChips({ images, locked, onRemove }: { images: PendingImage[]; locked: boolean; onRemove: (localId: string) => void }) {
  if (!images.length) {
    return null;
  }
  return (
    <ul className="pending-image-list" aria-label="Imágenes seleccionadas">
      {images.map((image) => (
        <li className={`pending-image pending-image--${image.status.toLowerCase()}`} key={image.localId}>
          <img src={image.previewUrl} alt="" />
          <span className="pending-image__copy">
            <strong title={image.filename}>{image.filename}</strong>
            <small>
              {formatBytes(image.sizeBytes)} · {image.status === "UPLOADING" ? "Subiendo" : image.status === "READY" ? "Lista" : "Error"}
            </small>
            {image.error && <em title={image.error}>{image.error}</em>}
          </span>
          <button
            type="button"
            aria-label={`Quitar ${image.filename}`}
            disabled={locked || image.status === "UPLOADING"}
            onClick={() => onRemove(image.localId)}
          >
            <X />
          </button>
        </li>
      ))}
    </ul>
  );
}

function CoreScreen() {
  const [scope, setScope] = useState<CoreScope>("GLOBAL");
  const [command, setCommand] = useState<CoreCommandResponse | null>(null);
  const [history, setHistory] = useState<CoreCommandSummary[]>([]);
  const [error, setError] = useState("");

  async function loadHistory() {
    try {
      setHistory(await api.coreHistory());
    } catch (loadError) {
      setError(errorMessage(loadError));
    }
  }

  useEffect(() => {
    loadHistory();
  }, []);

  return (
    <Page>
      <Panel className="command-panel">
        <PanelHeader title="Core" eyebrow="Consola" />
        <Segmented
          value={scope}
          options={[
            ["GLOBAL", "Global"],
            ["PROJECT", "Proyecto"],
            ["SESSION", "Sesión"]
          ]}
          onChange={(value) => setScope(value as CoreScope)}
        />
        <CoreComposer
          scope={scope}
          placeholder="Escribe una instrucción a Atenea Core..."
          onCommand={(response) => {
            setCommand(response);
            loadHistory();
          }}
        />
        {error && <InlineError>{error}</InlineError>}
        {command && <CommandCard command={command} onChanged={setCommand} afterResolve={loadHistory} />}
      </Panel>
      <Panel>
        <PanelHeader title="Historial reciente" eyebrow="CoreCommand" action={<Button variant="ghost" onClick={loadHistory}>Actualizar</Button>} />
        <List>
          {history.map((item) => (
            <Row
              key={item.commandId}
              title={`#${item.commandId} · ${item.rawInput}`}
              detail={item.operatorMessage || item.speakableMessage || item.resultSummary || item.errorMessage || "-"}
              meta={formatRelative(item.createdAt)}
              level={item.status === "FAILED" ? "critical" : item.status === "SUCCEEDED" ? "ok" : "running"}
            />
          ))}
        </List>
      </Panel>
    </Page>
  );
}

interface HealthOverview {
  hosts: OperationsHostStatus[];
  incidents: OperationsIncident[];
  errors: string[];
}

function HealthScreen({ onChanged }: { onChanged: () => Promise<void> }) {
  const [overview, setOverview] = useState<HealthOverview | null>(null);
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    const next = await loadHealthOverview();
    setOverview(next);
    await onChanged();
    setLoading(false);
  }

  useEffect(() => {
    load();
  }, []);

  const snapshot = healthSnapshot(overview, loading);

  return (
    <Page>
      <Toolbar>
        <Button onClick={load} icon={<RefreshCw />} disabled={loading}>{loading ? "Actualizando" : "Actualizar"}</Button>
      </Toolbar>
      <section className="session-hero">
        <div>
          <span className="eyebrow">Estado global</span>
          <h2>{snapshot.title}</h2>
          <p>{snapshot.detail}</p>
        </div>
        <StatusPill level={snapshot.level}>{snapshot.label}</StatusPill>
      </section>
      {overview?.errors.map((error) => <InlineError key={error}>{error}</InlineError>)}
      <div className="grid grid--2">
        {overview?.hosts.map((host) => <HostPanel status={host} key={host.host.id} />)}
      </div>
      <Panel>
        <PanelHeader title="Incidencias abiertas" eyebrow="Operaciones" />
        <List>
          {(overview?.incidents || []).map((incident) => (
            <Row key={incident.id} title={incident.title} detail={incident.summary || incident.hostName || "-"} meta={incident.severity} level="warning" />
          ))}
        </List>
      </Panel>
    </Page>
  );
}

function OperationsScreen({ onChanged }: { onChanged: () => Promise<void> }) {
  const [hosts, setHosts] = useState<ManagedHost[]>([]);
  const [selectedHostId, setSelectedHostId] = useState<number | null>(null);
  const [status, setStatus] = useState<OperationsHostStatus | null>(null);
  const [incidents, setIncidents] = useState<OperationsIncident[]>([]);
  const [command, setCommand] = useState<CoreCommandResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function load(hostId = selectedHostId) {
    setLoading(true);
    setError("");
    try {
      const nextHosts = await api.operationsHosts();
      setHosts(nextHosts);
      const nextHostId = hostId || nextHosts[0]?.id || null;
      setSelectedHostId(nextHostId);
      const [nextStatus, nextIncidents] = await Promise.all([
        nextHostId ? api.operationsHostStatus(nextHostId) : Promise.resolve(null),
        api.operationsIncidents().then((response) => response.incidents || [])
      ]);
      setStatus(nextStatus);
      setIncidents(nextIncidents);
      await onChanged();
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function run(input: string) {
    try {
      const response = await api.runCoreCommand(input, "GLOBAL");
      setCommand(response);
      if (!response.confirmation && !response.clarification) {
        await load();
      }
    } catch (runError) {
      setError(errorMessage(runError));
    }
  }

  return (
    <Page>
      <Toolbar>
        <select value={selectedHostId || ""} onChange={(event) => load(Number(event.target.value))}>
          {hosts.map((host) => <option value={host.id} key={host.id}>{host.name}</option>)}
        </select>
        <Button icon={<RefreshCw />} onClick={() => load()} disabled={loading}>{loading ? "Actualizando" : "Actualizar"}</Button>
        <Button onClick={() => run("revisa el dedicado")}>Diagnóstico</Button>
        <Button onClick={() => run("comprueba apache en el dedicado")}>Apache</Button>
        <Button variant="danger" onClick={() => run("recupera apache en el dedicado")}>Recuperar Apache</Button>
      </Toolbar>
      {error && <InlineError>{error}</InlineError>}
      {command && <CommandCard command={command} onChanged={setCommand} afterResolve={() => load()} />}
      {status ? <HostPanel status={status} expanded /> : <EmptyState title="Sin host seleccionado" detail="Registra un host gestionado para operar desde Atenea." />}
      <Panel>
        <PanelHeader title="Incidencias" eyebrow="Abiertas" />
        <List>
          {incidents.map((incident) => (
            <Row key={incident.id} title={incident.title} detail={incident.summary || incident.serviceName || incident.hostName || "-"} meta={incident.status} level="warning" />
          ))}
        </List>
      </Panel>
    </Page>
  );
}

function FilesScreen() {
  const [upload, setUpload] = useState<MobileUpload | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function onFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      setUpload(await api.upload(file));
    } catch (uploadError) {
      setError(errorMessage(uploadError));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Page>
      <Panel>
        <PanelHeader title="Archivos" eyebrow="Inbox operativo" />
        <label className="upload-zone">
          <Upload />
          <strong>{loading ? "Subiendo..." : "Subir archivo"}</strong>
          <span>Imágenes, PDF, hojas de cálculo u otros ficheros para que Codex los consulte en el workspace.</span>
          <input type="file" onChange={onFile} disabled={loading} />
        </label>
        {error && <InlineError>{error}</InlineError>}
        {upload && (
          <List>
            <Row title={upload.originalFilename} detail={upload.storedPath} meta={formatBytes(upload.sizeBytes)} level="ok" />
            <Row title="latest.json" detail={upload.latestMetadataPath} meta={formatRelative(upload.uploadedAt)} />
          </List>
        )}
      </Panel>
    </Page>
  );
}

function CostsScreen() {
  const [overview, setOverview] = useState<MobileApiCostsOverview | null>(null);
  const [billing, setBilling] = useState<unknown>(null);
  const [days, setDays] = useState(30);
  const [error, setError] = useState("");

  async function load() {
    setError("");
    try {
      const [costs, billingSummary] = await Promise.all([
        api.costsOverview(days),
        api.billingQueueSummary().catch(() => null)
      ]);
      setOverview(costs);
      setBilling(billingSummary);
    } catch (loadError) {
      setError(errorMessage(loadError));
    }
  }

  useEffect(() => {
    load();
  }, [days]);

  return (
    <Page>
      <Toolbar>
        <Segmented value={String(days)} options={[["7", "7 días"], ["30", "30 días"], ["90", "90 días"]]} onChange={(value) => setDays(Number(value))} />
        <Button icon={<RefreshCw />} onClick={load}>Actualizar</Button>
      </Toolbar>
      {error && <InlineError>{error}</InlineError>}
      <div className="grid grid--3">
        <MetricCard label="Total" value={overview ? formatMoney(overview.total, overview.currency) : "-"} detail="Periodo seleccionado" />
        <MetricCard label="Proveedores" value={overview?.providers.length || 0} detail="Lecturas configuradas" />
        <MetricCard label="Auth Codex" value={overview?.codexAuthStatuses.filter((status) => status.compliant).length || 0} detail="Servidores conformes" />
      </div>
      <div className="grid grid--2">
        <Panel>
          <PanelHeader title="Proveedores" eyebrow="Coste" />
          <List>
            {overview?.providers.map((provider) => (
              <Row key={provider.provider} title={provider.provider} detail={provider.status} meta={formatMoney(provider.total, provider.currency)} level={provider.configured ? "ok" : "warning"} />
            ))}
          </List>
        </Panel>
        <Panel>
          <PanelHeader title="Auth Codex" eyebrow="Modo requerido" />
          <List>
            {overview?.codexAuthStatuses.map((status) => (
              <Row key={status.server} title={status.server} detail={`${status.status} · requerido ${status.requiredAuthMode}`} meta={status.authMode || "-"} level={status.compliant ? "ok" : "critical"} />
            ))}
          </List>
        </Panel>
      </div>
      <Panel>
        <PanelHeader title="Billing" eyebrow="Resumen backend" />
        <pre className="json-preview">{JSON.stringify(billing, null, 2)}</pre>
      </Panel>
    </Page>
  );
}

function DiagnosticsScreen() {
  const [health, setHealth] = useState<unknown>(null);
  const [me, setMe] = useState<unknown>(null);
  const [error, setError] = useState("");

  async function load() {
    setError("");
    try {
      const [nextHealth, nextMe] = await Promise.all([
        api.health().catch((e) => ({ error: errorMessage(e) })),
        api.me().catch((e) => ({ error: errorMessage(e) }))
      ]);
      setHealth(nextHealth);
      setMe(nextMe);
    } catch (loadError) {
      setError(errorMessage(loadError));
    }
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <Page>
      <Toolbar>
        <Button icon={<RefreshCw />} onClick={load}>Actualizar</Button>
      </Toolbar>
      {error && <InlineError>{error}</InlineError>}
      <div className="grid grid--2">
        <Panel>
          <PanelHeader title="Backend" eyebrow="Actuator" />
          <pre className="json-preview">{JSON.stringify(health, null, 2)}</pre>
        </Panel>
        <Panel>
          <PanelHeader title="Sesión" eyebrow="Operador" />
          <pre className="json-preview">{JSON.stringify(me, null, 2)}</pre>
        </Panel>
      </div>
      <Panel>
        <PanelHeader title="Cliente web" eyebrow="Runtime" />
        <List>
          <Row title="User agent" detail={navigator.userAgent} />
          <Row title="Ruta" detail={window.location.hash || "#home"} />
          <Row title="Build" detail="Atenea Web Console v1" />
        </List>
      </Panel>
    </Page>
  );
}

type CodexAdminAction = "load" | "plan" | "stage" | "authorize-activation" | "activate" | "authorize-rollback" | "rollback";

function CodexAdministrationScreen() {
  const [inventory, setInventory] = useState<CodexAdministratorInventory | null>(null);
  const [plan, setPlan] = useState<CodexUpdatePlan | null>(null);
  const [stage, setStage] = useState<CodexUpdateStage | null>(null);
  const [activationAuthorization, setActivationAuthorization] = useState<CodexActivationAuthorization | null>(null);
  const [activation, setActivation] = useState<CodexUpdateActivation | null>(null);
  const [rollbackAuthorization, setRollbackAuthorization] = useState<CodexRollbackAuthorization | null>(null);
  const [rollback, setRollback] = useState<CodexUpdateRollback | null>(null);
  const [busy, setBusy] = useState<CodexAdminAction | null>("load");
  const [error, setError] = useState("");

  async function load() {
    setBusy("load");
    setError("");
    try {
      setInventory(await api.codexAdministratorInventory());
    } catch (loadError) {
      const message = loadError instanceof ApiError && loadError.status === 403
        ? "Acceso restringido. Esta superficie requiere administración de plataforma."
        : errorMessage(loadError);
      setError(message);
    } finally {
      setBusy(null);
    }
  }

  useEffect(() => {
    load();
  }, []);

  const worker = inventory?.workers[0] || null;

  async function perform(action: CodexAdminAction, operation: () => Promise<void>) {
    setBusy(action);
    setError("");
    try {
      await operation();
    } catch (operationError) {
      setError(errorMessage(operationError));
    } finally {
      setBusy(null);
    }
  }

  if (!inventory) {
    return <LoadingState error={error} onRetry={load} />;
  }

  const updatesReady = inventory.managedUpdatesEnabled && worker?.healthy && worker?.enabled;
  const next = codexAdminNextStep(inventory, plan, stage, activationAuthorization, activation, rollbackAuthorization, rollback);

  return (
    <Page>
      <Banner title={next.title} level={next.level}>{next.detail}</Banner>
      {error && <InlineError>{error}</InlineError>}

      <div className="grid grid--3 grid--compact">
        <MetricCard
          label="Versión actual"
          value={worker?.currentVersion || "—"}
          detail={worker ? `${worker.workerId} · ${worker.compatibilityState}` : "Sin worker registrado"}
          level={worker?.healthy ? "ok" : "critical"}
        />
        <MetricCard
          label="Versión anterior"
          value={worker?.previousVersion || "—"}
          detail="Retenida para rollback exacto"
        />
        <MetricCard
          label="Actualizaciones"
          value={inventory.managedUpdatesEnabled ? "Habilitadas" : "Desactivadas"}
          detail={inventory.managedUpdatesEnabled ? "Operación administrativa disponible" : "Sin cambios permitidos"}
          level={inventory.managedUpdatesEnabled ? "warning" : "neutral"}
        />
      </div>

      <div className="grid grid--wide-left">
        <Panel className="codex-admin-flow">
          <PanelHeader
            title="Flujo controlado"
            eyebrow="Una acción cada vez"
            action={<Button variant="ghost" icon={<RefreshCw />} onClick={load} disabled={busy !== null}>Actualizar estado</Button>}
          />
          <div className="codex-admin-steps">
            <CodexAdminStep
              number="1"
              title="Planificar"
              state={plan?.state || "PENDIENTE"}
              detail={plan ? `Candidato ${plan.candidate.codexVersion}` : "Calcula candidato, compatibilidad e impacto sin instalar."}
              action={<Button variant="primary" onClick={() => perform("plan", async () => {
                if (!worker) return;
                setPlan(await api.createCodexUpdatePlan(worker.workerId));
              })} disabled={!updatesReady || busy !== null || !!plan}>Crear plan</Button>}
            />
            <CodexAdminStep
              number="2"
              title="Verificar candidato"
              state={stage?.state || "PENDIENTE"}
              detail={stage ? "Release y esquemas verificados; enlaces sin cambios." : "Instala en staging y valida la release sin activarla."}
              action={<Button onClick={() => perform("stage", async () => {
                if (plan) setStage(await api.stageCodexUpdate(plan));
              })} disabled={!plan || plan.state !== "READY" || busy !== null || !!stage}>Verificar</Button>}
            />
            <CodexAdminStep
              number="3"
              title="Autorizar activación"
              state={activationAuthorization ? (activationAuthorization.consumedAt ? "CONSUMIDA" : "AUTORIZADA") : "PENDIENTE"}
              detail={activationAuthorization
                ? `Expira ${formatAbsoluteDate(activationAuthorization.expiresAt)}`
                : "Crea una autorización separada, exacta y de diez minutos."}
              action={<Button onClick={() => perform("authorize-activation", async () => {
                if (plan) setActivationAuthorization(await api.authorizeCodexActivation(plan));
              })} disabled={!stage || stage.state !== "STAGED" || busy !== null || !!activationAuthorization}>Autorizar</Button>}
            />
            <CodexAdminStep
              number="4"
              title="Activar"
              state={activation?.state || "PENDIENTE"}
              detail={activation ? `Codex ${activation.current.codexVersion} activo.` : "Exige cero ejecuciones activas y supera contratos, health y canary."}
              action={<Button variant="primary" onClick={() => perform("activate", async () => {
                if (!plan || !activationAuthorization) return;
                const result = await api.activateCodexUpdate(plan, activationAuthorization.authorizationId);
                setActivation(result);
                setInventory(await api.codexAdministratorInventory());
              })} disabled={!activationAuthorization || !!activationAuthorization.consumedAt || busy !== null || !!activation}>Activar versión</Button>}
            />
            <CodexAdminStep
              number="5"
              title="Rollback"
              state={rollback?.state || (rollbackAuthorization ? "AUTORIZADO" : "DISPONIBLE TRAS ACTIVAR")}
              detail={rollback
                ? `Restaurado Codex ${rollback.current.codexVersion}; ${rollback.appServerServicesRestarted} App Servers reiniciados.`
                : rollbackAuthorization
                  ? `Autorización válida hasta ${formatAbsoluteDate(rollbackAuthorization.expiresAt)}.`
                  : "Requiere una autorización nueva y restaura sólo la versión anterior exacta."}
              action={rollback ? (
                <StatusPill level="ok">Finalizado</StatusPill>
              ) : rollbackAuthorization ? (
                <Button variant="danger" onClick={() => perform("rollback", async () => {
                  if (!activation) return;
                  const result = await api.rollbackCodexUpdate(activation.activationId, rollbackAuthorization.authorizationId);
                  setRollback(result);
                  setInventory(await api.codexAdministratorInventory());
                })} disabled={busy !== null || !!rollback}>Ejecutar rollback</Button>
              ) : (
                <Button onClick={() => perform("authorize-rollback", async () => {
                  if (activation) setRollbackAuthorization(await api.authorizeCodexRollback(activation.activationId));
                })} disabled={!activation || busy !== null}>Autorizar rollback</Button>
              )}
            />
          </div>
        </Panel>

        <Panel className="codex-admin-assurance">
          <PanelHeader title="Impacto y garantías" eyebrow="Antes de actuar" />
          <List>
            <Row title="Ejecuciones activas" detail="Deben ser cero para activar o revertir." level="ok" />
            <Row title="Servicio afectado" detail="Sólo el worker Codex; nunca runtimes de proyecto." level="ok" />
            <Row title="Autorizaciones" detail="Separadas, de un uso y con caducidad de diez minutos." level="ok" />
            <Row title="Valores sensibles" detail="No se muestran URLs, comandos, rutas ni credenciales." level="ok" />
          </List>
          {plan && (
            <div className="codex-admin-gates">
              <span className="eyebrow">Compatibilidad</span>
              {plan.gates.map((gate) => (
                <div key={gate.gate}>
                  <span>{gate.gate.replaceAll("_", " ")}</span>
                  <StatusPill level={gate.state === "PASS" ? "ok" : "critical"}>{gate.state}</StatusPill>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </div>
    </Page>
  );
}

function CodexAdminStep({ number, title, state, detail, action }: {
  number: string;
  title: string;
  state: string;
  detail: string;
  action: ReactNode;
}) {
  const level: Level = ["READY", "STAGED", "AUTHORIZED", "AUTORIZADA", "ACTIVATED", "ROLLED_BACK"].includes(state)
    ? "ok"
    : state === "BLOCKED" ? "critical" : "neutral";
  return (
    <section className="codex-admin-step">
      <span className="codex-admin-step__number">{number}</span>
      <div>
        <div className="codex-admin-step__title">
          <strong>{title}</strong>
          <StatusPill level={level}>{state.replaceAll("_", " ")}</StatusPill>
        </div>
        <p>{detail}</p>
      </div>
      {action}
    </section>
  );
}

function codexAdminNextStep(
  inventory: CodexAdministratorInventory,
  plan: CodexUpdatePlan | null,
  stage: CodexUpdateStage | null,
  activationAuthorization: CodexActivationAuthorization | null,
  activation: CodexUpdateActivation | null,
  rollbackAuthorization: CodexRollbackAuthorization | null,
  rollback: CodexUpdateRollback | null
): { title: string; detail: string; level: Level } {
  if (!inventory.managedUpdatesEnabled) return { title: "Actualizaciones desactivadas", detail: "No se puede modificar Codex hasta habilitar el control administrativo.", level: "neutral" };
  if (rollback) return { title: "Rollback completado", detail: "La versión anterior exacta vuelve a estar activa. Revisa el inventario antes de otra operación.", level: "ok" };
  if (rollbackAuthorization) return { title: "Rollback autorizado", detail: "Siguiente acción: ejecutar el rollback antes de que caduque la autorización.", level: "warning" };
  if (activation) return { title: "Versión activada", detail: "La activación pasó sus gates. El rollback exacto permanece disponible con una autorización nueva.", level: "ok" };
  if (activationAuthorization) return { title: "Activación autorizada", detail: "Siguiente acción: activar antes de que caduque la autorización.", level: "warning" };
  if (stage) return { title: "Candidato verificado", detail: "Siguiente acción: autorizar la activación. Autorizar todavía no activa nada.", level: "ok" };
  if (plan) return { title: "Plan preparado", detail: "Siguiente acción: verificar el candidato sin cambiar los enlaces activos.", level: plan.state === "READY" ? "ok" : "critical" };
  return { title: "Listo para planificar", detail: "Crea un plan de sólo lectura para conocer candidato, compatibilidad e impacto.", level: "ok" };
}

function SettingsScreen() {
  return (
    <Page>
      <Panel>
        <PanelHeader title="Ajustes" eyebrow="Operador" />
        <List>
          <Row title="Sesión" detail="Los tokens se guardan en sessionStorage y se refrescan automáticamente." level="ok" />
          <Row title="API" detail={window.location.origin} />
          <Row title="Web Console" detail="Interfaz React/TypeScript servida por Spring Boot." />
        </List>
        <Button variant="danger" icon={<LogOut />} onClick={() => api.logout()}>Cerrar sesión</Button>
      </Panel>
    </Page>
  );
}

function RescueScreen({ projectId, rescueSessionId }: { projectId: number; rescueSessionId?: number }) {
  const [conversation, setConversation] = useState<MobileRescueConversation | null>(null);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function load() {
    setError("");
    try {
      if (rescueSessionId) {
        setConversation(await api.rescueConversation(rescueSessionId));
      } else {
        const result = await api.resolveRescueSession(projectId);
        setConversation(result.view);
        navigate({ name: "rescue", projectId, rescueSessionId: result.view.session.id });
      }
    } catch (loadError) {
      setError(errorMessage(loadError));
    }
  }

  useEffect(() => {
    load();
  }, [projectId, rescueSessionId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!conversation || !message.trim()) {
      return;
    }
    setLoading(true);
    try {
      const response = await api.createRescueTurn(conversation.session.id, message.trim());
      setConversation(response.view);
      setMessage("");
    } catch (submitError) {
      setError(errorMessage(submitError));
    } finally {
      setLoading(false);
    }
  }

  return (
    <ConversationLayout
      title={conversation?.session.title || "Rescate operativo"}
      subtitle={conversation?.session.projectName || "Canal de rescate"}
      back={() => navigate({ name: "projects" })}
      refresh={load}
    >
      {error && <InlineError>{error}</InlineError>}
      <TurnList turns={conversation?.turns || []} />
      <form className="conversation-composer" onSubmit={submit}>
        <textarea value={message} onChange={(event) => setMessage(event.target.value)} placeholder="Instrucción de rescate..." />
        <Button variant="primary" disabled={loading || !message.trim()}>{loading ? "Enviando" : "Enviar"}</Button>
      </form>
    </ConversationLayout>
  );
}

function CoreComposer({
  scope,
  projectId,
  workSessionId,
  placeholder,
  compact,
  onCommand
}: {
  scope: CoreScope;
  projectId?: number | null;
  workSessionId?: number | null;
  placeholder: string;
  compact?: boolean;
  onCommand: (command: CoreCommandResponse) => void | Promise<void>;
}) {
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!input.trim()) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      const response = await api.runCoreCommand(input.trim(), scope, projectId, workSessionId);
      await onCommand(response);
      setInput("");
    } catch (submitError) {
      setError(errorMessage(submitError));
    } finally {
      setLoading(false);
    }
  }

  return (
    <form className={`core-composer ${compact ? "core-composer--compact" : ""}`} onSubmit={submit}>
      <textarea value={input} onChange={(event) => setInput(event.target.value)} placeholder={placeholder} rows={compact ? 2 : 4} />
      <Button variant="primary" disabled={loading || !input.trim()} icon={<Command />}>{loading ? "Ejecutando" : "Ejecutar"}</Button>
      {error && <InlineError>{error}</InlineError>}
    </form>
  );
}

function CommandCard({ command, onChanged, afterResolve }: { command: CoreCommandResponse; onChanged: (command: CoreCommandResponse) => void; afterResolve?: () => void | Promise<void> }) {
  const [token, setToken] = useState(command.confirmation?.confirmationToken || "");
  const [loading, setLoading] = useState(false);
  const message = command.operatorMessage || command.speakableMessage || command.resultSummary || command.errorMessage || "Comando procesado.";

  async function confirm() {
    if (!token) {
      return;
    }
    setLoading(true);
    try {
      const response = await api.confirmCoreCommand(command.commandId, token);
      onChanged(response);
      await afterResolve?.();
    } finally {
      setLoading(false);
    }
  }

  return (
    <article className="command-card">
      <div className="command-card__top">
        <div>
          <span className="eyebrow">CoreCommand #{command.commandId}</span>
          <h3>{command.status}</h3>
        </div>
        <StatusPill level={command.status === "FAILED" ? "critical" : command.confirmation ? "warning" : "ok"}>{command.status}</StatusPill>
      </div>
      <p>{message}</p>
      {command.confirmation && (
        <div className="confirmation-box">
          <strong>{command.confirmation.message || "Confirmación requerida"}</strong>
          <input value={token} onChange={(event) => setToken(event.target.value)} />
          <Button variant="primary" onClick={confirm} disabled={loading}>{loading ? "Confirmando" : "Confirmar"}</Button>
        </div>
      )}
      {command.clarification && (
        <div className="clarification-box">
          <strong>{command.clarification.message || "Aclaración requerida"}</strong>
          <div className="button-row">
            {command.clarification.options.map((option) => (
              <Button key={`${option.type}-${option.targetId}-${option.label}`} onClick={() => {}}>
                {option.label}
              </Button>
            ))}
          </div>
        </div>
      )}
    </article>
  );
}

function HostPanel({ status, expanded }: { status: OperationsHostStatus; expanded?: boolean }) {
  const unhealthy = status.websiteChecks.filter((check) => !check.healthy);
  const level = status.openIncidents.length || unhealthy.length ? "critical" : status.hostStatusRun?.status === "FAILED" ? "warning" : "ok";
  return (
    <Panel>
      <PanelHeader
        title={status.host.name}
        eyebrow={status.host.environment || "Host"}
        action={<StatusPill level={level}>{levelLabel(level)}</StatusPill>}
      />
      <div className="grid grid--3 grid--compact">
        <MetricCard label="Servicios" value={status.services.length} detail="Registrados" />
        <MetricCard label="Webs OK" value={status.websiteChecks.filter((check) => check.healthy).length} detail={`${unhealthy.length} con problema`} level={unhealthy.length ? "critical" : "ok"} />
        <MetricCard label="Incidencias" value={status.openIncidents.length} detail="Abiertas" level={status.openIncidents.length ? "warning" : "ok"} />
      </div>
      {expanded && status.hostStatusRun?.report && (
        <div className="report">
          <strong>{status.hostStatusRun.report.summary || status.hostStatusRun.stdoutSummary || "Reporte operativo"}</strong>
          {status.hostStatusRun.report.steps.map((step, index) => (
            <Row key={index} title={step.name || `Paso ${index + 1}`} detail={step.detail || "-"} meta={step.status || "-"} level={step.status === "FAILED" ? "critical" : "neutral"} />
          ))}
        </div>
      )}
      <List>
        {unhealthy.map((check) => (
          <Row key={check.websiteId} title={check.name} detail={check.error || `${check.statusCode || "-"} · ${check.durationMillis} ms`} meta={check.state} level="critical" />
        ))}
      </List>
    </Panel>
  );
}

function ConversationLayout({ title, subtitle, back, refresh, children }: { title: string; subtitle: string; back: () => void; refresh: () => void; children: ReactNode }) {
  return (
    <div className="conversation-shell">
      <header className="conversation-header">
        <button className="icon-button" onClick={back} aria-label="Volver"><ArrowLeft /></button>
        <div>
          <span className="eyebrow">{subtitle}</span>
          <h1>{title}</h1>
        </div>
        <button className="icon-button" onClick={refresh} aria-label="Actualizar"><RefreshCw /></button>
      </header>
      <main className="conversation-body">{children}</main>
    </div>
  );
}

function TurnList({
  turns,
  onDownloadAttachment
}: {
  turns: MobileConversationTurn[];
  onDownloadAttachment?: (attachment: SessionTurnAttachment) => void;
}) {
  if (!turns.length) {
    return <EmptyState title="Sin conversación visible" detail="Envía una instrucción para iniciar el historial." />;
  }
  return (
    <ol className="turn-list">
      {turns.map((turn) => (
        <li className={`turn turn--${turn.actor.toLowerCase()}`} key={turn.id}>
          <div className="turn__meta">
            <strong>{turn.actor}</strong>
            <span>
              {turn.executionProfile && (
                <em className="turn__profile">
                  {turn.executionProfile.modelId} · {turn.executionProfile.reasoningEffort} · Codex {turn.executionProfile.codexVersion}
                </em>
              )}
              {formatRelative(turn.createdAt)}
            </span>
          </div>
          <Markdown content={turn.messageText} />
          {onDownloadAttachment && (turn.attachments || []).length > 0 && (
            <ul className="turn-attachment-list" aria-label={`Imágenes del turno ${turn.id}`}>
              {turn.attachments.map((attachment) => (
                <li key={attachment.id}>
                  <button type="button" onClick={() => onDownloadAttachment(attachment)}>
                    <Paperclip />
                    <span>
                      <strong title={attachment.originalFilename}>{attachment.originalFilename}</strong>
                      <small>{formatBytes(attachment.sizeBytes)} · Imagen {attachment.position + 1}</small>
                    </span>
                    <em>Descargar</em>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ol>
  );
}

function Timeline({ events }: { events: MobileSessionEvent[] }) {
  if (!events.length) {
    return <EmptyState title="Sin eventos" detail="El timeline se poblará con turns, runs, publish, cierre y deliverables." />;
  }
  return (
    <ol className="timeline">
      {events.map((event, index) => (
        <li key={`${event.type}-${event.at}-${index}`}>
          <span>{event.type}</span>
          <strong>{event.title}</strong>
          <p>{event.details || formatRelative(event.at)}</p>
        </li>
      ))}
    </ol>
  );
}

function Markdown({ content }: { content: string }) {
  const html = useMemo(() => DOMPurify.sanitize(marked.parse(content, { async: false })), [content]);
  return <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />;
}

function Page({ children }: { children: ReactNode }) {
  return <div className="page">{children}</div>;
}

function Panel({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`panel ${className}`}>{children}</section>;
}

function PanelHeader({ title, eyebrow, action }: { title: string; eyebrow?: string; action?: ReactNode }) {
  return (
    <div className="panel__header">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h2>{title}</h2>
      </div>
      {action}
    </div>
  );
}

function Toolbar({ children }: { children: ReactNode }) {
  return <div className="toolbar">{children}</div>;
}

function Button({ children, onClick, disabled, variant = "default", icon }: { children: ReactNode; onClick?: () => void; disabled?: boolean; variant?: "default" | "primary" | "danger" | "ghost"; icon?: ReactNode }) {
  return (
    <button className={`button button--${variant}`} type={onClick ? "button" : "submit"} onClick={onClick} disabled={disabled}>
      {icon}
      <span>{children}</span>
    </button>
  );
}

function StatusPill({ children, level }: { children: ReactNode; level: Level }) {
  return <span className={`status-pill status-pill--${level}`}>{children}</span>;
}

function StatusDot({ level }: { level: Level }) {
  return <Circle className={`status-dot status-dot--${level}`} fill="currentColor" />;
}

function MetricCard({ label, value, detail, level = "neutral" }: { label: string; value: ReactNode; detail: string; level?: Level }) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong className={`metric-card__value metric-card__value--${level}`}>{value}</strong>
      <p>{detail}</p>
    </article>
  );
}

function Fact({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="fact">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function List({ children }: { children: ReactNode }) {
  return <div className="list">{children}</div>;
}

function Row({ title, detail, meta, level = "neutral" }: { title: string; detail?: string | null; meta?: string | null; level?: Level }) {
  return (
    <div className="row">
      <StatusDot level={level} />
      <div>
        <strong>{title}</strong>
        {detail && <span>{detail}</span>}
      </div>
      {meta && <em>{meta}</em>}
    </div>
  );
}

function ProjectRow({ project }: { project: MobileProjectOverview }) {
  return (
    <button className="row row--button" onClick={() => project.session ? navigate({ name: "session", projectId: project.projectId, sessionId: project.session.sessionId }) : navigate({ name: "projects" })}>
      <StatusDot level={projectLevel(project)} />
      <div>
        <strong>{project.projectName}</strong>
        <span>{project.session?.title || project.description || "Sin sesión abierta"}</span>
      </div>
      <ChevronRight />
    </button>
  );
}

function Segmented({ value, options, onChange }: { value: string; options: [string, string][]; onChange: (value: string) => void }) {
  return (
    <div className="segmented">
      {options.map(([optionValue, label]) => (
        <button className={value === optionValue ? "is-active" : ""} key={optionValue} type="button" onClick={() => onChange(optionValue)}>
          {label}
        </button>
      ))}
    </div>
  );
}

function InlineError({ children }: { children: ReactNode }) {
  return <div className="inline-error"><AlertTriangle /> {children}</div>;
}

function Banner({ title, children, level }: { title: string; children: ReactNode; level: Level }) {
  return (
    <div className={`banner banner--${level}`}>
      <strong>{title}</strong>
      <span>{children}</span>
    </div>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="empty-state">
      <ShieldCheck />
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

function LoadingState({ error, onRetry }: { error?: string; onRetry: () => void }) {
  return (
    <Page>
      <Panel>
        {error ? <InlineError>{error}</InlineError> : <EmptyState title="Cargando" detail="Leyendo el estado operativo." />}
        <Button onClick={onRetry}>Reintentar</Button>
      </Panel>
    </Page>
  );
}

function MissingContext() {
  return (
    <Page>
      <EmptyState title="Falta contexto" detail="Vuelve a Proyectos y abre una sesión concreta." />
    </Page>
  );
}

async function loadHealthOverview(): Promise<HealthOverview> {
  const errors: string[] = [];
  let hosts: ManagedHost[] = [];
  try {
    hosts = await api.operationsHosts();
  } catch (error) {
    errors.push(errorMessage(error));
  }
  const statuses = await Promise.all(hosts.map(async (host) => {
    try {
      return await api.operationsHostStatus(host.id);
    } catch (error) {
      errors.push(`${host.name}: ${errorMessage(error)}`);
      return null;
    }
  }));
  let incidents: OperationsIncident[] = [];
  try {
    incidents = (await api.operationsIncidents()).incidents || [];
  } catch (error) {
    errors.push(errorMessage(error));
  }
  return { hosts: statuses.filter(Boolean) as OperationsHostStatus[], incidents, errors };
}

function healthSnapshot(overview: HealthOverview | null, loading: boolean): { level: Level; label: string; title: string; detail: string } {
  if (loading) {
    return { level: "unknown", label: "...", title: "Actualizando", detail: "Leyendo servidores, webs e incidencias." };
  }
  if (!overview) {
    return { level: "unknown", label: "-", title: "Sin datos", detail: "Aún no hay lectura operativa." };
  }
  const issues = overview.errors.length + overview.incidents.length + overview.hosts.reduce((total, host) => total + host.openIncidents.length + host.websiteChecks.filter((check) => !check.healthy).length, 0);
  if (issues > 0) {
    return { level: "critical", label: String(issues), title: `${issues} puntos requieren revisión`, detail: "Hay incidencias, webs degradadas o errores de lectura." };
  }
  if (!overview.hosts.length) {
    return { level: "unknown", label: "-", title: "Sin hosts", detail: "No hay servidores gestionados registrados." };
  }
  return { level: "ok", label: "OK", title: "Operación estable", detail: `${overview.hosts.length} host(s) sin incidencias abiertas.` };
}

function readRoute(): Route {
  const hash = window.location.hash.replace(/^#\/?/, "") || "home";
  const [name, ...parts] = hash.split("/");
  if (name === "session") {
    return { name, projectId: toNumber(parts[0]), sessionId: toNumber(parts[1]) };
  }
  if (name === "conversation") {
    return { name, projectId: toNumber(parts[0]), sessionId: toNumber(parts[1]) };
  }
  if (name === "rescue") {
    return { name, projectId: toNumber(parts[0]), rescueSessionId: toNumber(parts[1]) };
  }
  const known: RouteName[] = ["home", "projects", "health", "core", "operations", "files", "costs", "diagnostics", "codex-admin", "settings"];
  return { name: known.includes(name as RouteName) ? name as RouteName : "home" };
}

function navigate(route: Route) {
  let next = `#/${route.name}`;
  if (route.name === "session" || route.name === "conversation") {
    next += `/${route.projectId || 0}/${route.sessionId || 0}`;
  }
  if (route.name === "rescue") {
    next += `/${route.projectId || 0}`;
    if (route.rescueSessionId) {
      next += `/${route.rescueSessionId}`;
    }
  }
  window.location.hash = next;
}

function routeTitle(route: Route) {
  const titles: Record<RouteName, string> = {
    home: "Inicio",
    projects: "Proyectos",
    health: "Estado",
    core: "Core",
    operations: "Operaciones",
    files: "Archivos",
    costs: "Costes API",
    diagnostics: "Diagnóstico",
    "codex-admin": "Versiones Codex",
    settings: "Ajustes",
    session: "WorkSession",
    conversation: "Conversación",
    rescue: "Rescate"
  };
  return titles[route.name];
}

function projectLevel(project: MobileProjectOverview): Level {
  if (!project.session) {
    return "neutral";
  }
  if (project.session.runInProgress) {
    return "running";
  }
  if (project.session.closeBlockedState) {
    return "warning";
  }
  if (project.session.status === "CLOSED") {
    return "neutral";
  }
  return "ok";
}

function projectLabel(project: MobileProjectOverview) {
  if (!project.session) return "Sin sesión";
  if (project.session.runInProgress) return "RUNNING";
  if (project.session.closeBlockedState) return "Bloqueo";
  return project.session.status;
}

function sessionLevel(state: string): Level {
  if (state === "RUNNING") return "running";
  if (state === "CLOSING") return "warning";
  if (state === "CLOSED") return "neutral";
  return "ok";
}

function levelLabel(level: Level) {
  return ({ ok: "OK", warning: "Atención", critical: "Crítico", unknown: "Sin datos", running: "En curso", neutral: "Info" } as const)[level];
}

function deliverableLabel(type: string) {
  return {
    WORK_TICKET: "Ticket",
    WORK_BREAKDOWN: "Desglose",
    PRICE_ESTIMATE: "Pricing"
  }[type] || type;
}

function toNumber(value?: string) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Operación fallida.";
}

function formatRelative(value?: string | null) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const diff = Date.now() - date.getTime();
  const minutes = Math.round(diff / 60_000);
  if (minutes < 1) return "ahora";
  if (minutes < 60) return `hace ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `hace ${hours} h`;
  return date.toLocaleDateString();
}

function formatAbsoluteDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("es-ES", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

function formatMoney(value: number, currency = "EUR") {
  return new Intl.NumberFormat("es-ES", { style: "currency", currency: currency || "EUR" }).format(value || 0);
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
