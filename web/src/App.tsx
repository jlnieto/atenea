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
  ManagedHost,
  MobileApiCostsOverview,
  MobileConversationTurn,
  MobileProjectOverview,
  MobileDevelopmentChange,
  MobileSessionEvent,
  MobileSessionOperatorState,
  MobileSessionSummary,
  MobileUpload,
  MobileWorkSessionConversation,
  OperatorSessionInventory,
  OperationsHostStatus,
  OperationsIncident,
  SessionDeliverable,
  SessionDeliverableSummary,
  SessionDeliverablesView,
  SessionTurnAttachment,
  WorkSessionAttachment,
  WorkSessionAttachmentCapability,
  WorkSessionPreview,
  WebAuthnControlledResetStatus,
  WebAuthnCredentialInventory,
  WebAuthnProviderCategory
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
  | "security"
  | "devices"
  | "session"
  | "conversation"
  | "changes";

interface Route {
  name: RouteName;
  projectId?: number;
  sessionId?: number;

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
  const [authReady, setAuthReady] = useState(false);
  const [route, setRoute] = useState<Route>(() => readRoute());

  useEffect(() => {
    const unsubscribe = api.subscribe(setSession);
    api.bootstrap().finally(() => setAuthReady(true));
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

  if (!authReady) {
    return <AuthRestoringScreen />;
  }

  if (!session) {
    return <LoginScreen />;
  }

  return <Shell session={session} route={route} />;
}

function AuthRestoringScreen() {
  return (
    <main className="login">
      <section className="login__panel" aria-live="polite">
        <span className="eyebrow">Atenea Console</span>
        <h1>Recuperando sesión segura</h1>
        <p className="login__copy">Comprobando la cookie protegida del navegador.</p>
      </section>
    </main>
  );
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

  const immersive = route.name === "conversation";

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
                    className={`nav__item ${route.name === item.route || (item.route === "settings" && (route.name === "security" || route.name === "devices")) ? "is-active" : ""}`}
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
    case "security":
      return <SecurityAccessScreen />;
    case "devices":
      return <DevicesAccessScreen />;
    case "session":
      return route.sessionId ? <WorkSessionScreen sessionId={route.sessionId} projectId={route.projectId} /> : <MissingContext />;
    case "conversation":
      return route.sessionId ? <ConversationScreen sessionId={route.sessionId} projectId={route.projectId} /> : <MissingContext />;
    case "changes":
      return route.projectId ? <ChangesScreen projectId={route.projectId} /> : <MissingContext />;
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

  async function passkeyLogin() {
    setLoading(true);
    setError("");
    try {
      await api.loginWithPasskey();
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
          Consola privada para trabajar por Cambios y operar los proyectos autorizados.
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
          <button className="button" disabled={loading} type="button" onClick={passkeyLogin}>
            <ShieldCheck />
            Entrar con passkey
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
    project.session?.recoveryPending || project.session?.runInProgress ||
      project.session?.closeBlockedState || project.session?.pullRequestStatus === "OPEN"
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
  const [titleByProject, setTitleByProject] = useState<Record<number, string>>({});
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState("");
  const attempts = useRef(new Map<number, { title: string; keys: { create: string; provision: string; session: string } }>());
  async function load() {
    try { setProjects(await api.projects()); } catch (e) { setError(errorMessage(e)); }
  }
  useEffect(() => { load(); }, []);
  async function start(projectId: number) {
    setPending(projectId); setError("");
    try {
      const title = titleByProject[projectId]?.trim();
      if (!title || title.length > 200) throw new Error("Indica un título de entre 1 y 200 caracteres.");
      let attempt = attempts.current.get(projectId);
      if (!attempt) {
        attempt = { title, keys: { create: crypto.randomUUID(), provision: crypto.randomUUID(), session: crypto.randomUUID() } };
        attempts.current.set(projectId, attempt);
      }
      const sessionId = await api.startChange(projectId, attempt.title, attempt.keys);
      attempts.current.delete(projectId);
      navigate({ name: "conversation", projectId, sessionId });
    } catch (e) { setError(errorMessage(e)); }
    finally { setPending(null); }
  }
  return <Page>
    <Toolbar><Button onClick={load} disabled={pending !== null} icon={<RefreshCw />}>Actualizar</Button></Toolbar>
    {error && <InlineError>{error}</InlineError>}
    <div className="project-grid">{projects.map(project => <article className="project-card" key={project.projectId}>
      <h2>{project.projectName}</h2><p>{project.description}</p>
      <label className="field"><span>Título del nuevo cambio</span>
        <input value={titleByProject[project.projectId] || ""} disabled={pending !== null || attempts.current.has(project.projectId)}
          onChange={e => setTitleByProject({ ...titleByProject, [project.projectId]: e.target.value })} />
      </label>
      <div className="button-row">
        <Button variant="primary" disabled={pending !== null || !titleByProject[project.projectId]?.trim()} onClick={() => start(project.projectId)}>
          {pending === project.projectId ? "Preparando cambio..." : attempts.current.has(project.projectId) ? "Continuar preparación" : "Nuevo cambio"}
        </Button>
        <Button onClick={() => navigate({ name: "changes", projectId: project.projectId })}>Ver cambios</Button>
      </div>
      {project.session && <div className="session-mini"><strong>{project.session.title}</strong>
        <Button onClick={() => navigate({ name: "conversation", projectId: project.projectId, sessionId: project.session!.sessionId })}>Continuar</Button>
      </div>}
    </article>)}</div>
  </Page>;
}

function ChangesScreen({ projectId }: { projectId: number }) {
  const [changes, setChanges] = useState<MobileDevelopmentChange[]>([]);
  const [history, setHistory] = useState(false);
  const [error, setError] = useState("");
  async function load() {
    try { setChanges(await api.changes(projectId)); } catch (e) { setError(errorMessage(e)); }
  }
  useEffect(() => { load(); }, [projectId]);
  const historical = (change: MobileDevelopmentChange) => ["COMPLETED", "ABANDONED"].includes(change.status);
  return <Page><Toolbar>
    <Button onClick={() => navigate({ name: "projects" })}>Proyectos</Button>
    <Button onClick={() => setHistory(false)}>Activos ({changes.filter(c => !historical(c)).length})</Button>
    <Button onClick={() => setHistory(true)}>Historial ({changes.filter(historical).length})</Button>
    <Button onClick={load}>Actualizar</Button>
  </Toolbar>{error && <InlineError>{error}</InlineError>}
  {changes.filter(c => historical(c) === history).map(change => {
    const sessionId = historical(change) ? change.completedSessionId ?? change.archivedSessionId : change.activeSessionId;
    return <Panel key={change.changeKey}><h2>{change.title}</h2><p>{change.status}</p>
      {sessionId && <Button onClick={() => navigate({ name: "conversation", projectId, sessionId })}>
        {history ? "Ver conversación" : "Continuar"}
      </Button>}
    </Panel>;
  })}</Page>;
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
  const [pending, setPending] = useState(false);
  const [requiresRefresh, setRequiresRefresh] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    setRequiresRefresh(false);
    setNotice("");
    setError("");
  }, [currentWorkSessionId, state.targetWorkSessionId, state.state, refreshGeneration]);

  if (!state.surfaceEnabled) {
    return null;
  }

  const roleAllowed = operatorHasRole(state.requiredRole);
  const actionAvailable = state.primaryActionAvailable && roleAllowed &&
    remoteCloseActionMatchesState(state) && !requiresRefresh &&
    (state.primaryAction !== "RECONCILE_REMOTE_CLOSE" || state.targetWorkSessionId === currentWorkSessionId);
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
        if (state.targetWorkSessionId !== currentWorkSessionId) return;
        await api.resumeWorkSessionClose(state.targetWorkSessionId);
        setNotice("Reconciliación solicitada. Se mantendrá la misma operación de cierre.");
        await onChanged();
      }
    } catch (actionError) {
      if (actionError instanceof ApiError && actionError.status === 409) {
        setRequiresRefresh(true);
      }
      setError(remoteCloseActionError(actionError));
    } finally {
      setPending(false);
    }
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
        {state.primaryAction !== "NONE" && state.primaryAction !== "WAIT" && (
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
    return state.state === "CLOSING_REMOTE" && Boolean(state.targetWorkSessionId);
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
    if (error.status === 409) return "El estado cambió. Actualiza antes de continuar.";
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
        <PanelHeader title="Ajustes" eyebrow="Cuenta" />
        <Row title="Sesión actual" detail={api.currentSession?.operator.displayName || api.currentSession?.operator.email || "Activa"} level="ok" />
        <p className="muted">Gestiona cómo accedes a Atenea y dónde tienes sesiones abiertas.</p>
        <div className="settings-links">
          <button type="button" className="settings-link" onClick={() => navigate({ name: "security" })}>
            <strong>Seguridad y acceso</strong>
            <span>Passkeys, TOTP y recuperación</span>
          </button>
          <button type="button" className="settings-link" onClick={() => navigate({ name: "devices" })}>
            <strong>Dispositivos con acceso</strong>
            <span>Sesiones activas y cierre remoto</span>
          </button>
        </div>
        <Button variant="danger" icon={<LogOut />} onClick={() => api.logout()}>Cerrar sesión</Button>
      </Panel>
    </Page>
  );
}

function DevicesAccessScreen() {
  const [sessions, setSessions] = useState<OperatorSessionInventory[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [pendingRevoke, setPendingRevoke] = useState<OperatorSessionInventory | "others" | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [historyVisibleCount, setHistoryVisibleCount] = useState(20);

  async function loadSessions() {
    setLoading(true);
    setError("");
    try {
      setSessions(await api.sessions());
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { loadSessions(); }, []);

  async function confirmRevoke() {
    if (!pendingRevoke) return;
    setBusy(true);
    setError("");
    try {
      if (pendingRevoke === "others") await api.revokeOtherSessions();
      else await api.revokeSession(pendingRevoke.familyId);
      setPendingRevoke(null);
      await loadSessions();
    } catch (revokeError) {
      setError(errorMessage(revokeError));
    } finally {
      setBusy(false);
    }
  }

  const active = sessions.filter((item) => item.state === "ACTIVE")
    .sort((left, right) => Number(right.current) - Number(left.current));
  const history = sessions.filter((item) => item.state !== "ACTIVE");

  return (
    <Page>
      <Panel>
        <PanelHeader title="Dispositivos con acceso" eyebrow="Cuenta" action={<Button onClick={() => navigate({ name: "settings" })}>Volver a Ajustes</Button>} />
        <p className="muted">Revisa dónde está abierta tu cuenta y cierra los accesos que no reconozcas.</p>
        {loading && <p className="muted">Cargando sesiones…</p>}
        {error && <InlineError>{error}</InlineError>}
        {!loading && !error && active.length === 0 && <p className="muted">No hay sesiones activas.</p>}
        {active.map((item) => (
          <div className="security-session" key={item.familyId}>
            <div>
              <strong>{item.deviceLabel}{item.current ? " · Esta sesión" : ""}</strong>
              <span>{item.clientType} · Último uso {formatAbsoluteDate(item.lastUsedAt)}</span>
            </div>
            {!item.current && <Button variant="danger" disabled={busy} onClick={() => setPendingRevoke(item)}>Cerrar acceso</Button>}
          </div>
        ))}
        {active.some((item) => !item.current) && (
          <Button disabled={busy} onClick={() => setPendingRevoke("others")}>Cerrar las demás sesiones</Button>
        )}
        {history.length > 0 && (
          <details className="security-history" onToggle={(event) => setShowHistory(event.currentTarget.open)}>
            <summary>Historial de sesiones ({history.length})</summary>
            {showHistory && history.slice(0, historyVisibleCount).map((item) => (
              <div className="security-session" key={item.familyId}>
                <div><strong>{item.deviceLabel}</strong><span>{item.clientType} · {item.state === "REVOKED" ? "Revocada" : "Caducada"} · Último uso {formatAbsoluteDate(item.lastUsedAt)}</span></div>
              </div>
            ))}
            {showHistory && history.length > historyVisibleCount && (
              <Button onClick={() => setHistoryVisibleCount((count) => count + 20)}>Mostrar más sesiones</Button>
            )}
          </details>
        )}
        {pendingRevoke && (
          <div className="security-confirm" role="group" aria-label="Confirmar cierre de acceso">
            <strong>{pendingRevoke === "others" ? "¿Cerrar todas las demás sesiones?" : `¿Cerrar el acceso de ${pendingRevoke.deviceLabel}?`}</strong>
            <p>Los dispositivos afectados tendrán que iniciar sesión de nuevo.</p>
            <div className="button-row">
              <Button variant="danger" disabled={busy} onClick={confirmRevoke}>Confirmar cierre</Button>
              <Button disabled={busy} onClick={() => setPendingRevoke(null)}>Cancelar</Button>
            </div>
          </div>
        )}
      </Panel>
    </Page>
  );
}

function SecurityAccessScreen() {
  const [error, setError] = useState("");
  const [factorNotice, setFactorNotice] = useState("");
  const [factorPending, setFactorPending] = useState(false);
  const [totp, setTotp] = useState<{ enrollmentId: string; secret: string } | null>(null);
  const [totpCode, setTotpCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [passkeys, setPasskeys] = useState<WebAuthnCredentialInventory | null>(null);
  const [passkeyReset, setPasskeyReset] = useState<WebAuthnControlledResetStatus | null>(null);
  const [passkeyLoading, setPasskeyLoading] = useState(true);
  const [passkeyError, setPasskeyError] = useState("");
  const [passkeyNotice, setPasskeyNotice] = useState("");
  const [providerCategory, setProviderCategory] = useState<WebAuthnProviderCategory>(
    "UNKNOWN"
  );
  const [selectedPasskeyRecordId, setSelectedPasskeyRecordId] = useState("");
  const [pendingPasskeyRevoke, setPendingPasskeyRevoke] = useState<{ recordId: string; label: string } | null>(null);
  const [pendingResetCommit, setPendingResetCommit] = useState(false);
  const [showRevokedPasskeys, setShowRevokedPasskeys] = useState(false);
  const [revokedPasskeyVisibleCount, setRevokedPasskeyVisibleCount] = useState(20);

  async function loadPasskeys() {
    setPasskeyLoading(true);
    setPasskeyError("");
    try {
      const [inventory, reset] = await Promise.all([
        api.passkeyInventory(),
        api.passkeyResetStatus()
      ]);
      if (!inventory || !Array.isArray(inventory.credentials)
        || typeof inventory.readOnly !== "boolean"
        || !["DISABLED", "ACTION_REQUIRED", "READY"].includes(inventory.state)) {
        throw new Error("El inventario de passkeys no devolvió un estado válido.");
      }
      if (!reset || !["DISABLED", "BLOCKED", "REGISTER_NEW", "PROVE_NEW", "COMMIT_READY", "COMPLETE"].includes(reset.state)
        || reset.targetProvider !== "1Password") {
        throw new Error("El reinicio de passkeys no devolvió un estado válido.");
      }
      setPasskeys(inventory);
      setPasskeyReset(reset);
      setSelectedPasskeyRecordId((current) => inventory.credentials.some(
        (credential) => credential.recordId === current && credential.state === "ACTIVE"
      ) ? current : "");
    } catch (loadError) {
      setPasskeyError(errorMessage(loadError));
    } finally {
      setPasskeyLoading(false);
    }
  }

  useEffect(() => {
    loadPasskeys();
  }, []);

  async function registerPasskey() {
    setFactorPending(true);
    setFactorNotice("");
    try {
      await api.registerPasskey(
        passkeys?.state === "DISABLED" ? undefined : providerCategory
      );
      setFactorNotice("Passkey registrada. Las sesiones anteriores quedan invalidadas.");
      await loadPasskeys();
    } catch (factorError) {
      setError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function verifyPasskey() {
    if (!selectedPasskeyRecordId) return;
    setFactorPending(true);
    setPasskeyError("");
    setPasskeyNotice("");
    try {
      const verified = await api.verifyPasskeyOwnership(
        selectedPasskeyRecordId,
        providerCategory
      );
      setPasskeyNotice(`${verified.label} verificada sin exponer su identificador.`);
      setSelectedPasskeyRecordId("");
      await loadPasskeys();
    } catch (factorError) {
      setPasskeyError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function revokePasskey(recordId: string) {
    setFactorPending(true);
    setPasskeyError("");
    try {
      await api.revokePasskey(recordId);
      setPendingPasskeyRevoke(null);
      setPasskeyNotice(
        "Passkey revocada en Atenea. Elimínala también del proveedor si ya no la necesitas."
      );
      await loadPasskeys();
    } catch (factorError) {
      setPasskeyError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function signalAcceptedPasskeys() {
    setFactorPending(true);
    setPasskeyError("");
    try {
      const result = await api.signalAcceptedPasskeys();
      setPasskeyNotice(result === "SIGNALED"
        ? "El proveedor recibió el inventario activo completo."
        : "Este navegador no admite Signal API. Revisa las passkeys manualmente en el proveedor.");
    } catch (factorError) {
      setPasskeyError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function advancePasskeyReset() {
    if (!passkeyReset || passkeyReset.state === "DISABLED"
      || passkeyReset.state === "BLOCKED" || passkeyReset.state === "COMPLETE") return;
    setFactorPending(true);
    setPasskeyError("");
    setPasskeyNotice("");
    try {
      if (passkeyReset.state === "REGISTER_NEW") {
        await api.registerPasskey("ONE_PASSWORD");
        setPasskeyNotice("Passkey nueva registrada. Verifícala antes de sustituir las históricas.");
      } else if (passkeyReset.state === "PROVE_NEW") {
        if (!passkeyReset.candidateRecordId) {
          throw new Error("Falta la referencia segura de la passkey nueva.");
        }
        await api.verifyPasskeyOwnership(passkeyReset.candidateRecordId, "ONE_PASSWORD");
        setPasskeyNotice("Passkey nueva verificada. Las históricas aún no han cambiado.");
      } else if (passkeyReset.state === "COMMIT_READY") {
        if (!passkeyReset.candidateRecordId) {
          throw new Error("Falta la referencia segura de la passkey verificada.");
        }
        await api.commitPasskeyReset(passkeyReset.candidateRecordId);
        setPasskeyNotice("Reinicio completado: queda activa la nueva passkey de 1Password.");
      }
      await loadPasskeys();
      setPendingResetCommit(false);
    } catch (factorError) {
      setPasskeyError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function beginTotp() {
    setFactorPending(true);
    setError("");
    try {
      const enrollment = await api.beginTotpEnrollment();
      setTotp({ enrollmentId: enrollment.enrollmentId, secret: enrollment.secret });
      setFactorNotice("Alta pendiente: verifica un código para activarla.");
    } catch (factorError) {
      setError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  async function activateTotp() {
    if (!totp) return;
    setFactorPending(true);
    try {
      const result = await api.activateTotpEnrollment(totp.enrollmentId, totpCode);
      setRecoveryCodes(result.recoveryCodes);
      setTotp(null);
      setTotpCode("");
      setFactorNotice("TOTP activo. Guarda ahora los códigos: sólo se muestran una vez.");
    } catch (factorError) {
      setError(errorMessage(factorError));
    } finally {
      setFactorPending(false);
    }
  }

  return (
    <Page>
      <Panel>
        <PanelHeader title="Seguridad y acceso" eyebrow="Cuenta" action={<Button onClick={() => navigate({ name: "settings" })}>Volver a Ajustes</Button>} />
        <div className="security-section" aria-label="Factores de seguridad">
          <div>
            <span className="eyebrow">Factores</span>
            <h3>Factores de acceso</h3>
            <p className="muted">Gestiona las passkeys y el respaldo TOTP de tu cuenta.</p>
          </div>
          {factorNotice && <p className="notice notice--ok">{factorNotice}</p>}
          {error && <InlineError>{error}</InlineError>}
          {passkeyNotice && <p className="notice notice--ok">{passkeyNotice}</p>}
          {passkeyLoading && <p className="muted">Cargando passkeys sanitizadas…</p>}
          {passkeyError && <InlineError>{passkeyError}</InlineError>}
          {passkeyReset && passkeyReset.state !== "DISABLED" && (
            <div className="factor-enrollment" aria-label="Reinicio controlado de passkeys">
              <div className="factor-enrollment__heading">
                <span className="eyebrow">Reinicio controlado</span>
                <strong>{passkeyResetStateLabel(passkeyReset)}</strong>
              </div>
              <p>{passkeyReset.nextAction}</p>
              {passkeyReset.state !== "BLOCKED" && passkeyReset.state !== "COMPLETE" && (
                <Button
                  variant="primary"
                  disabled={factorPending}
                  icon={<ShieldCheck />}
                  onClick={() => passkeyReset.state === "COMMIT_READY" ? setPendingResetCommit(true) : advancePasskeyReset()}
                >
                  {passkeyResetActionLabel(passkeyReset)}
                </Button>
              )}
            </div>
          )}
          {passkeys && (
            <div className={`passkey-state passkey-state--${passkeys.state.toLowerCase()}`}>
              <div>
                <strong>{passkeyStateLabel(passkeys)}</strong>
                <span>{passkeys.nextAction}</span>
              </div>
              <StatusPill level={passkeys.readOnly ? "warning" : passkeys.independentDomainsReady ? "ok" : passkeys.state === "DISABLED" ? "neutral" : "warning"}>
                {passkeys.readOnly ? "Solo lectura" : passkeys.independentDomainsReady ? "2 dominios" : passkeys.state === "DISABLED" ? "Desactivado" : "Pendiente"}
              </StatusPill>
            </div>
          )}
          {passkeys && passkeys.state !== "DISABLED"
            && (!passkeyReset || passkeyReset.state === "DISABLED") && (
            <>
              <label className="field passkey-provider">
                <span>{passkeys.readOnly
                  ? "Proveedor usado en esta prueba"
                  : "Proveedor que vas a usar"}</span>
                <select
                  aria-label="Proveedor de passkey"
                  value={providerCategory}
                  onChange={(event) => setProviderCategory(event.target.value as WebAuthnProviderCategory)}
                >
                  <option value="GOOGLE_PASSWORD_MANAGER">Google Password Manager</option>
                  <option value="ONE_PASSWORD">1Password</option>
                  <option value="UNKNOWN">Proveedor desconocido</option>
                </select>
              </label>
              {passkeys.readOnly && (
                <Button
                  variant="primary"
                  disabled={factorPending || !selectedPasskeyRecordId}
                  icon={<ShieldCheck />}
                  onClick={verifyPasskey}
                >
                  Verificar passkey seleccionada
                </Button>
              )}
              <div className="passkey-inventory" aria-label="Inventario sanitizado de passkeys">
                {passkeys.credentials.every((credential) => credential.state !== "ACTIVE") && (
                  <p className="muted">No hay passkeys activas.</p>
                )}
                {passkeys.credentials.filter((credential) => credential.state === "ACTIVE").map((credential) => (
                  <div className="security-session" key={credential.recordId}>
                    <div>
                      <strong>{credential.label}</strong>
                      <span>
                        Activa
                        {credential.backupEligible ? " · Sincronizable" : " · No sincronizable"}
                        {credential.lastVerifiedAt
                          ? ` · Verificada ${formatAbsoluteDate(credential.lastVerifiedAt)}`
                          : " · Sin verificar"}
                      </span>
                    </div>
                    {passkeys.readOnly && (
                      <label className="passkey-choice">
                        <input
                          type="radio"
                          name="passkey-record"
                          value={credential.recordId}
                          aria-label={`Verificar ${credential.label}`}
                          checked={selectedPasskeyRecordId === credential.recordId}
                          onChange={() => setSelectedPasskeyRecordId(credential.recordId)}
                        />
                        <span>Elegir para verificar</span>
                      </label>
                    )}
                    {!passkeys.readOnly && (
                      <Button
                        variant="danger"
                        disabled={factorPending}
                        onClick={() => setPendingPasskeyRevoke({ recordId: credential.recordId, label: credential.label })}
                      >
                        Revocar en Atenea
                      </Button>
                    )}
                  </div>
                ))}
              </div>
              {passkeys.credentials.some((credential) => credential.state === "REVOKED") && (
                <details className="security-history" onToggle={(event) => setShowRevokedPasskeys(event.currentTarget.open)}>
                  <summary>Passkeys revocadas ({passkeys.credentials.filter((credential) => credential.state === "REVOKED").length})</summary>
                  {showRevokedPasskeys && passkeys.credentials.filter((credential) => credential.state === "REVOKED").slice(0, revokedPasskeyVisibleCount).map((credential) => (
                    <div className="security-session" key={credential.recordId}>
                      <strong>{credential.label}</strong><span>Revocada</span>
                    </div>
                  ))}
                  {showRevokedPasskeys && passkeys.credentials.filter((credential) => credential.state === "REVOKED").length > revokedPasskeyVisibleCount && (
                    <Button onClick={() => setRevokedPasskeyVisibleCount((count) => count + 20)}>Mostrar más passkeys</Button>
                  )}
                </details>
              )}
            </>
          )}
          <div className="button-row">
            {passkeys && !passkeys.readOnly
              && (!passkeyReset || passkeyReset.state === "DISABLED") && (
              <Button variant="primary" disabled={factorPending} icon={<ShieldCheck />} onClick={registerPasskey}>Registrar passkey</Button>
            )}
            {passkeys?.signallingEnabled && !passkeys.readOnly
              && (!passkeyReset || passkeyReset.state === "DISABLED") && (
              <Button disabled={factorPending} onClick={signalAcceptedPasskeys}>
                Sincronizar inventario activo
              </Button>
            )}
            {passkeys && !passkeys.readOnly && !totp
              && (!passkeyReset || passkeyReset.state === "DISABLED") && (
              <Button disabled={factorPending} onClick={beginTotp}>Preparar TOTP</Button>
            )}
          </div>
          {totp && !passkeys?.readOnly
            && (!passkeyReset || passkeyReset.state === "DISABLED") && (
            <div className="factor-enrollment">
              <strong>Alta TOTP pendiente</strong>
              <p>Introduce este secreto en tu authenticator y verifica un código de 6 dígitos.</p>
              <code>{totp.secret}</code>
              <label className="field">
                <span>Código TOTP</span>
                <input inputMode="numeric" maxLength={6} value={totpCode} onChange={(event) => setTotpCode(event.target.value.replace(/\D/g, ""))} />
              </label>
              <div className="button-row">
                <Button variant="primary" disabled={factorPending || totpCode.length !== 6} onClick={activateTotp}>Activar TOTP</Button>
                <Button onClick={async () => { await api.cancelTotpEnrollment(totp.enrollmentId); setTotp(null); }}>Cancelar</Button>
              </div>
            </div>
          )}
          {recoveryCodes.length > 0 && (
            <div className="factor-enrollment" aria-label="Códigos de recuperación de una sola visualización">
              <strong>Códigos de recuperación</strong>
              <p>Guárdalos fuera de Atenea. Cada código se usa una sola vez.</p>
              <div className="recovery-code-grid">{recoveryCodes.map((code) => <code key={code}>{code}</code>)}</div>
              <Button onClick={() => setRecoveryCodes([])}>Ya los he guardado</Button>
            </div>
          )}
        </div>
        {pendingPasskeyRevoke && (
          <div className="security-confirm" role="group" aria-label="Confirmar revocación de passkey">
            <strong>¿Revocar {pendingPasskeyRevoke.label}?</strong>
            <p>Dejarás de poder usar esta passkey para entrar en Atenea.</p>
            <div className="button-row">
              <Button variant="danger" disabled={factorPending} onClick={() => revokePasskey(pendingPasskeyRevoke.recordId)}>Confirmar revocación</Button>
              <Button onClick={() => setPendingPasskeyRevoke(null)}>Cancelar</Button>
            </div>
          </div>
        )}
        {pendingResetCommit && (
          <div className="security-confirm" role="group" aria-label="Confirmar sustitución de passkeys">
            <strong>¿Sustituir las passkeys históricas?</strong>
            <p>Se revocarán las passkeys anteriores y quedará activa la nueva passkey verificada.</p>
            <div className="button-row">
              <Button variant="danger" disabled={factorPending} onClick={advancePasskeyReset}>Confirmar sustitución</Button>
              <Button disabled={factorPending} onClick={() => setPendingResetCommit(false)}>Cancelar</Button>
            </div>
          </div>
        )}
      </Panel>
    </Page>
  );
}

function passkeyStateLabel(inventory: WebAuthnCredentialInventory) {
  if (inventory.state === "DISABLED") return "Inventario correctivo desactivado";
  if (inventory.readOnly) return "Discovery de passkeys: solo lectura";
  if (inventory.independentDomainsReady) return "Passkeys independientes verificadas";
  return "Falta independencia entre proveedores";
}

function passkeyResetStateLabel(reset: WebAuthnControlledResetStatus) {
  switch (reset.state) {
    case "REGISTER_NEW": return "1 de 3 · Registrar en 1Password";
    case "PROVE_NEW": return "2 de 3 · Verificar la passkey nueva";
    case "COMMIT_READY": return "3 de 3 · Sustituir las históricas";
    case "COMPLETE": return "Reinicio completado";
    case "BLOCKED": return "Reinicio bloqueado";
    default: return "Reinicio desactivado";
  }
}

function passkeyResetActionLabel(reset: WebAuthnControlledResetStatus) {
  switch (reset.state) {
    case "REGISTER_NEW": return "Registrar passkey en 1Password";
    case "PROVE_NEW": return "Verificar passkey nueva";
    case "COMMIT_READY": return "Sustituir passkeys históricas";
    default: return "Sin acción disponible";
  }
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
  if (name === "changes") {
    return { name, projectId: toNumber(parts[0]) };
  }
  const known: RouteName[] = ["home", "projects", "health", "core", "operations", "files", "costs", "diagnostics", "codex-admin", "settings", "security", "devices"];
  return { name: known.includes(name as RouteName) ? name as RouteName : "home" };
}

function navigate(route: Route) {
  let next = `#/${route.name}`;
  if (route.name === "session" || route.name === "conversation") {
    next += `/${route.projectId || 0}/${route.sessionId || 0}`;
  }
  if (route.name === "changes") next += `/${route.projectId || 0}`;
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
    security: "Seguridad y acceso",
    devices: "Dispositivos con acceso",
    session: "Cambio",
    conversation: "Conversación",
    changes: "Cambios"
  };
  return titles[route.name];
}

function projectLevel(project: MobileProjectOverview): Level {
  if (!project.session) {
    return "neutral";
  }
  if (project.session.recoveryPending) {
    return "warning";
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
  if (project.session.recoveryPending) return "Pendiente";
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
