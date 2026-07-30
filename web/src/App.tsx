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
  RefreshCw,
  Search,
  Server,
  Settings,
  ShieldCheck,
  TerminalSquare,
  Upload,
  X
} from "lucide-react";
import React, { FormEvent, ReactNode, useEffect, useMemo, useState } from "react";
import { api } from "./api";
import {
  ApiError,
  AuthSession,
  CoreCommandResponse,
  CoreCommandSummary,
  CoreScope,
  ManagedHost,
  MobileApiCostsOverview,
  MobileConversationTurn,
  MobileProjectOverview,
  MobileRescueConversation,
  MobileSessionEvent,
  MobileSessionSummary,
  MobileUpload,
  MobileWorkSessionConversation,
  OperationsHostStatus,
  OperationsIncident,
  SessionDeliverable,
  SessionDeliverableSummary,
  SessionDeliverablesView,
  WorkSessionAttachment,
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

const navGroups: { title: string; items: { route: RouteName; label: string; icon: ReactNode }[] }[] = [
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
                {group.items.map((item) => (
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
  const [error, setError] = useState("");

  async function load() {
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
      if (summary?.conversation.runInProgress || command?.confirmation) {
        load();
      }
    }, 8000);
    return () => window.clearInterval(timer);
  }, [sessionId, command?.confirmation?.confirmationToken]);

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
        <Button icon={<RefreshCw />} disabled={loading} onClick={load}>{loading ? "Actualizando" : "Actualizar"}</Button>
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

function ConversationScreen({ sessionId, projectId }: { sessionId: number; projectId?: number }) {
  const [conversation, setConversation] = useState<MobileWorkSessionConversation | null>(null);
  const [attachments, setAttachments] = useState<WorkSessionAttachment[]>([]);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [attachmentsLoading, setAttachmentsLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const [attachmentError, setAttachmentError] = useState("");

  async function load() {
    setError("");
    setAttachmentsLoading(true);
    try {
      const [nextConversation, nextAttachments] = await Promise.all([
        api.workSessionConversation(sessionId),
        api.workSessionAttachments(sessionId)
      ]);
      setConversation(nextConversation);
      setAttachments(nextAttachments);
    } catch (loadError) {
      setError(errorMessage(loadError));
    } finally {
      setAttachmentsLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, [sessionId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!message.trim()) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      const response = await api.createWorkSessionTurn(sessionId, message.trim());
      setConversation(response);
      setMessage("");
    } catch (submitError) {
      setError(errorMessage(submitError));
    } finally {
      setLoading(false);
    }
  }

  async function uploadAttachment(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) {
      return;
    }
    setUploading(true);
    setAttachmentError("");
    try {
      await api.uploadWorkSessionAttachment(sessionId, file);
      setAttachments(await api.workSessionAttachments(sessionId));
    } catch (uploadError) {
      setAttachmentError(errorMessage(uploadError));
    } finally {
      setUploading(false);
    }
  }

  async function downloadAttachment(attachment: WorkSessionAttachment) {
    setAttachmentError("");
    try {
      const blob = await api.downloadWorkSessionAttachment(sessionId, attachment.id);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = attachment.originalFilename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (downloadError) {
      setAttachmentError(errorMessage(downloadError));
    }
  }

  return (
    <ConversationLayout
      title={conversation?.session.title || `WorkSession #${sessionId}`}
      subtitle="Conversación de sesión vía Atenea Core"
      back={() => navigate({ name: "session", projectId, sessionId })}
      refresh={load}
    >
      {error && <InlineError>{error}</InlineError>}
      <AttachmentPanel
        attachments={attachments}
        loading={attachmentsLoading}
        uploading={uploading}
        error={attachmentError}
        onUpload={uploadAttachment}
        onDownload={downloadAttachment}
      />
      <TurnList turns={conversation?.recentTurns || []} />
      <form className="conversation-composer" onSubmit={submit}>
        <textarea value={message} onChange={(event) => setMessage(event.target.value)} placeholder="Instrucción para Codex dentro de esta sesión..." />
        <Button variant="primary" disabled={loading || !message.trim()}>{loading ? "Enviando" : "Enviar"}</Button>
      </form>
    </ConversationLayout>
  );
}

function AttachmentPanel({
  attachments,
  loading,
  uploading,
  error,
  onUpload,
  onDownload
}: {
  attachments: WorkSessionAttachment[];
  loading: boolean;
  uploading: boolean;
  error: string;
  onUpload: (event: React.ChangeEvent<HTMLInputElement>) => void;
  onDownload: (attachment: WorkSessionAttachment) => void;
}) {
  const state = uploading
    ? "Subiendo"
    : loading
      ? "Cargando"
      : attachments.length
        ? `${attachments.length} retenido${attachments.length === 1 ? "" : "s"}`
        : "Sin adjuntos";
  return (
    <section className="attachment-panel" aria-label="Adjuntos de la WorkSession">
      <div className="attachment-panel__header">
        <div>
          <span className="eyebrow">WorkSession actual</span>
          <h2>Adjuntos</h2>
          <p>{state} · máximo 16 MiB · PNG, JPEG, WebP, texto, JSON, PDF o ZIP</p>
        </div>
        <label className={`button button--primary ${uploading ? "is-disabled" : ""}`}>
          <Upload />
          <span>{uploading ? "Subiendo…" : "Adjuntar archivo"}</span>
          <input
            aria-label="Seleccionar adjunto"
            type="file"
            accept=".png,.jpg,.jpeg,.webp,.txt,.json,.pdf,.zip,image/png,image/jpeg,image/webp,text/plain,application/json,application/pdf,application/zip"
            disabled={uploading}
            onChange={onUpload}
          />
        </label>
      </div>
      {error && <InlineError>{error}</InlineError>}
      {!loading && attachments.length > 0 && (
        <div className="attachment-list">
          {attachments.map((attachment) => (
            <button
              className="attachment-item"
              type="button"
              onClick={() => onDownload(attachment)}
              key={attachment.id}
            >
              <span>
                <strong>{attachment.originalFilename}</strong>
                <small>{attachment.kind} · {formatBytes(attachment.sizeBytes)}</small>
              </span>
              <em>Descargar</em>
            </button>
          ))}
        </div>
      )}
    </section>
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

function TurnList({ turns }: { turns: MobileConversationTurn[] }) {
  if (!turns.length) {
    return <EmptyState title="Sin conversación visible" detail="Envía una instrucción para iniciar el historial." />;
  }
  return (
    <ol className="turn-list">
      {turns.map((turn) => (
        <li className={`turn turn--${turn.actor.toLowerCase()}`} key={turn.id}>
          <div className="turn__meta">
            <strong>{turn.actor}</strong>
            <span>{formatRelative(turn.createdAt)}</span>
          </div>
          <Markdown content={turn.messageText} />
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
  const known: RouteName[] = ["home", "projects", "health", "core", "operations", "files", "costs", "diagnostics", "settings"];
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
