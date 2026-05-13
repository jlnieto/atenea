import { useEffect, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { usePendingActionCenter } from '../actions/PendingActionCenter';
import { AteneaApiError, fetchJson } from '../api/client';
import {
  MobileProjectOverview,
} from '../api/types';
import { labelPullRequestStatus, labelSessionStatus, tonePullRequestStatus, toneSessionStatus } from '../core/presentation';
import { formatAbsoluteAndRelativeTime, formatDurationSince } from '../core/time';
import { buildActivateProjectCommand, buildOpenSessionCommand } from '../core/phrases';
import { RunCoreCommandOptions } from '../core/useCoreCommandCenter';
import { ActionButton } from '../components/ActionButton';
import { IconActionLink } from '../components/IconActionLink';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function ProjectsScreen({
  selectedProjectId,
  sessionSignals,
  onOpenCore,
  onOpenRescue,
  onOpenSession,
  onSelectProject,
  onRunCommand,
}: {
  selectedProjectId: number | null;
  sessionSignals: Record<number, {
    queueState: string;
    hasNewResponse: boolean;
    attentionLabel: string | null;
    lastActivityAt: string | null;
  }>;
  onOpenCore: () => void;
  onOpenRescue: (projectId: number) => void;
  onOpenSession: (sessionId: number) => void;
  onSelectProject: (projectId: number | null) => void;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<unknown>;
}) {
  const [now, setNow] = useState(() => Date.now());
  const hasRunningSessions = (data?: MobileProjectOverview[] | null) =>
    (data ?? []).some((project) => project.session?.runInProgress);
  const { data, error, loading, reload } = useRemoteResource(
    () => fetchJson<MobileProjectOverview[]>('/api/mobile/projects/overview'),
    [],
    { refreshIntervalMs: 5000 }
  );
  const [drafts, setDrafts] = useState<Record<number, { title?: string }>>({});
  const [pendingProjectAction, setPendingProjectAction] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<AteneaApiError | Error | null>(null);
  const [sessionDraftProject, setSessionDraftProject] = useState<MobileProjectOverview | null>(null);
  const { pendingAction, startPendingAction, clearPendingAction } = usePendingActionCenter();

  useEffect(() => {
    if (!hasRunningSessions(data)) {
      return undefined;
    }

    const intervalId = setInterval(() => {
      setNow(Date.now());
    }, 1000);

    return () => clearInterval(intervalId);
  }, [data]);

  const updateDraft = (projectId: number, patch: { title?: string }) => {
    setDrafts((current) => ({
      ...current,
      [projectId]: {
        ...current[projectId],
        ...patch,
      },
    }));
  };

  const activateProject = async (project: MobileProjectOverview) => {
    setPendingProjectAction(`activate-${project.projectId}`);
    setMutationError(null);
    startPendingAction({
      label: 'Activar contexto de proyecto',
      scope: 'project',
      projectId: project.projectId,
      startedAt: new Date().toISOString(),
      recoveryHint: 'Actualiza Proyectos antes de reintentar la activación para ver la última sesión activa y el estado real del proyecto.',
    });
    try {
      await onRunCommand({
        input: buildActivateProjectCommand(project.projectName),
        projectId: project.projectId,
        workSessionId: null,
      });
      onSelectProject(project.projectId);
      await reload();
    } catch (resolveError) {
      setMutationError(resolveError instanceof Error ? resolveError : new Error('La activación del proyecto ha fallado'));
    } finally {
      clearPendingAction();
      setPendingProjectAction(null);
    }
  };

  const resolveSession = async (project: MobileProjectOverview) => {
    const projectId = project.projectId;
    const draft = drafts[projectId];
    setPendingProjectAction(`session-${projectId}`);
    setMutationError(null);
    startPendingAction({
      label: 'Abrir sesión vía Atenea Core',
      scope: 'project',
      projectId,
      startedAt: new Date().toISOString(),
      recoveryHint: 'Actualiza Proyectos antes de reintentar la apertura para confirmar si el proyecto ya tiene una sesión activa.',
    });

    try {
      await onRunCommand({
        input: buildOpenSessionCommand(project.projectName, draft?.title),
        projectId,
        workSessionId: null,
        onSucceeded: (response) => {
          if (response.result?.targetId != null) {
            onSelectProject(projectId);
            onOpenSession(response.result.targetId);
          }
        },
      });
      await reload();
      setSessionDraftProject(null);
    } catch (resolveError) {
      setMutationError(resolveError instanceof Error ? resolveError : new Error('La apertura de sesión ha fallado'));
    } finally {
      clearPendingAction();
      setPendingProjectAction(null);
    }
  };

  const projectPendingRecovery = pendingAction?.scope === 'project' ? pendingAction : null;

  if (loading) {
    return <LoadingBlock label="Cargando proyectos..." />;
  }

  if (error) {
    return <Card title="Proyectos no disponibles" subtitle={error}><Text style={styles.meta}>Revisa la conectividad con el backend.</Text></Card>;
  }

  if (sessionDraftProject) {
    return (
      <View style={styles.newSessionScreen}>
        <View style={styles.newSessionHeader}>
          <Pressable
            onPress={() => {
              if (pendingProjectAction == null) {
                setSessionDraftProject(null);
                setMutationError(null);
              }
            }}
            style={styles.backButton}
          >
            <Text style={styles.backButtonLabel}>Volver</Text>
          </Pressable>
          <View style={styles.newSessionHeaderCopy}>
            <Text style={styles.newSessionKicker}>Nueva sesión</Text>
            <Text style={styles.newSessionTitle}>{sessionDraftProject.projectName}</Text>
          </View>
        </View>

        <ScrollView
          style={styles.newSessionScroll}
          contentContainerStyle={styles.newSessionContent}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="interactive"
        >
          <View style={styles.newSessionSummary}>
            <Text style={styles.newSessionSummaryLabel}>Proyecto</Text>
            <Text style={styles.newSessionSummaryValue}>{sessionDraftProject.projectName}</Text>
            {sessionDraftProject.description ? (
              <Text style={styles.newSessionSummaryMeta}>{sessionDraftProject.description}</Text>
            ) : null}
          </View>

          <View style={styles.newSessionField}>
            <Text style={styles.newSessionLabel}>Título de sesión</Text>
            <TextInput
              value={drafts[sessionDraftProject.projectId]?.title ?? ''}
              onChangeText={(title) => updateDraft(sessionDraftProject.projectId, { title })}
              placeholder="Ej. Prueba repo sucio en rama antigua"
              placeholderTextColor="#8b7c6b"
              style={styles.newSessionInput}
              autoFocus
              autoCapitalize="sentences"
              returnKeyType="done"
              onSubmitEditing={() => {
                if (pendingProjectAction == null) {
                  void resolveSession(sessionDraftProject);
                }
              }}
            />
          </View>

          {mutationError ? (
            <View style={styles.newSessionErrorBox}>
              <Text style={styles.newSessionErrorTitle}>
                {mutationError instanceof AteneaApiError ? mutationError.title : 'No se ha podido abrir la sesión'}
              </Text>
              <Text style={styles.newSessionErrorText}>{mutationError.message}</Text>
              {mutationError instanceof AteneaApiError && mutationError.detail ? (
                <Text style={styles.newSessionErrorDetail}>{mutationError.detail}</Text>
              ) : null}
              <View style={styles.newSessionErrorActions}>
                <ActionButton
                  label="Abrir rescate"
                  tone="warning"
                  onPress={() => {
                    onSelectProject(sessionDraftProject.projectId);
                    onOpenRescue(sessionDraftProject.projectId);
                  }}
                />
              </View>
            </View>
          ) : null}
        </ScrollView>

        <View style={styles.newSessionFooter}>
          <ActionButton
            label="Cancelar"
            tone="secondary"
            onPress={() => {
              setSessionDraftProject(null);
              setMutationError(null);
            }}
            disabled={pendingProjectAction != null}
          />
          <ActionButton
            label={pendingProjectAction === `session-${sessionDraftProject.projectId}` ? 'Abriendo...' : 'Abrir sesión'}
            onPress={() => void resolveSession(sessionDraftProject)}
            disabled={pendingProjectAction != null}
            prominence="high"
          />
        </View>
      </View>
    );
  }

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={styles.container}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode="interactive"
    >
      <View style={styles.headerRow}>
        <Text style={styles.meta}>La activación de contexto y la apertura de sesión pasan por Atenea Core.</Text>
        <View style={styles.headerActions}>
          <IconActionLink label="Actualizar" icon="refresh" onPress={() => void reload()} />
          <IconActionLink label="Abrir Core" icon="spark" onPress={onOpenCore} />
        </View>
      </View>
      {pendingProjectAction != null ? (
        <Text style={styles.pendingNotice}>
          Acción de proyecto en curso. Si la app se interrumpe, actualiza Proyectos antes de reintentar.
        </Text>
      ) : null}
      {projectPendingRecovery ? (
        <View style={styles.recoveryCard}>
          <Text style={styles.recoveryTitle}>Acción recuperada: {projectPendingRecovery.label}</Text>
          <Text style={styles.meta}>{projectPendingRecovery.recoveryHint}</Text>
          <Pressable onPress={clearPendingAction}>
            <Text style={styles.link}>Ocultar aviso</Text>
          </Pressable>
        </View>
      ) : null}
      {data?.map((project) => (
        (() => {
          const sessionSignal = project.session?.sessionId != null
            ? sessionSignals[project.session.sessionId] ?? null
            : null;
          return (
            <Card
              key={project.projectId}
              title={project.projectName}
              subtitle={project.description || project.defaultBaseBranch || 'Sin descripción del proyecto'}
            >
              <View style={styles.sessionRow}>
                <StatePill
                  label={selectedProjectId === project.projectId ? 'PROYECTO ACTIVO' : 'PROYECTO'}
                  tone={selectedProjectId === project.projectId ? 'good' : 'default'}
                />
                <Pressable onPress={() => void activateProject(project)}>
                  <Text style={styles.link}>
                    {selectedProjectId === project.projectId ? 'Refrescar contexto' : 'Activar en Core'}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() => {
                    onSelectProject(project.projectId);
                    onOpenRescue(project.projectId);
                  }}
                >
                  <Text style={styles.warningLink}>Rescate</Text>
                </Pressable>
              </View>
              {project.session && project.session.status !== 'CLOSED' ? (
                <>
                  <View style={styles.sessionRow}>
                    <StatePill
                      label={project.session.closeBlockedState ? 'CIERRE BLOQUEADO' : labelSessionStatus(project.session.status)}
                      tone={project.session.closeBlockedState ? 'danger' : toneSessionStatus(project.session.status)}
                    />
                    {project.session.pullRequestStatus ? (
                      <StatePill
                        label={labelPullRequestStatus(project.session.pullRequestStatus)}
                        tone={tonePullRequestStatus(project.session.pullRequestStatus)}
                      />
                    ) : null}
                    {project.session.runInProgress ? (
                      <StatePill
                        label={`EN CURSO${project.session.lastActivityAt ? ` · ${formatDurationSince(project.session.lastActivityAt, now)}` : ''}`}
                        tone="info"
                      />
                    ) : null}
                    {sessionSignal?.hasNewResponse ? (
                      <StatePill label="RESPUESTA NUEVA" tone="good" />
                    ) : null}
                    {project.session.pullRequestStatus === 'MERGED' && !project.session.runInProgress && !project.session.closeBlockedState && !sessionSignal?.hasNewResponse ? (
                      <StatePill label="LISTA PARA CERRAR" tone="good" />
                    ) : null}
                  </View>
                  <Text style={styles.meta}>{project.session.title}</Text>
                  {project.session.runInProgress ? (
                    <View style={styles.runningPanel}>
                      <Text style={styles.runningText}>
                        {project.session.lastActivityAt
                          ? `Codex sigue trabajando desde hace ${formatDurationSince(project.session.lastActivityAt, now)}. No parece un fallo.`
                          : 'Codex sigue trabajando en esta sesión. No parece un fallo.'}
                      </Text>
                      {project.session.lastActivityAt ? (
                        <Text style={styles.runningMeta}>
                          Último movimiento: {formatAbsoluteAndRelativeTime(project.session.lastActivityAt, now)}
                        </Text>
                      ) : null}
                    </View>
                  ) : null}
                  {sessionSignal?.hasNewResponse ? (
                    <View style={styles.responsePanel}>
                      <Text style={styles.responseTitle}>Respuesta nueva pendiente</Text>
                      <Text style={styles.responseText}>
                        {sessionSignal.lastActivityAt
                          ? `Codex ya respondió. Última actualización: ${formatAbsoluteAndRelativeTime(sessionSignal.lastActivityAt, now)}.`
                          : 'Codex ya respondió y la sesión espera tu siguiente decisión.'}
                      </Text>
                    </View>
                  ) : null}
                  {project.session.closeBlockedState ? (
                    <View style={styles.blockedPanel}>
                      <Text style={styles.blockedTitle}>Cierre bloqueado</Text>
                      {project.session.closeBlockedState ? (
                        <Text style={styles.blockedText}>Estado: {project.session.closeBlockedState}</Text>
                      ) : null}
                      <Text style={styles.blockedText}>
                        {project.session.closeBlockedState
                          ? 'Hace falta desbloquear la sesión antes de reconciliar y cerrar.'
                          : 'La sesión no puede cerrarse todavía.'}
                      </Text>
                    </View>
                  ) : null}
                  {project.session.pullRequestStatus === 'MERGED' && !project.session.runInProgress && !project.session.closeBlockedState && !sessionSignal?.hasNewResponse ? (
                    <View style={styles.readyPanel}>
                      <Text style={styles.readyTitle}>Lista para cerrar</Text>
                      <Text style={styles.readyText}>
                        La pull request ya está mergeada. Queda pendiente reconciliar el repo y cerrar la sesión.
                      </Text>
                    </View>
                  ) : null}
                  <Pressable
                    onPress={() => {
                      onSelectProject(project.projectId);
                      onOpenSession(project.session!.sessionId);
                    }}
                  >
                    <Text style={styles.link}>
                      {sessionSignal?.hasNewResponse ? `Leer respuesta en sesión ${project.session.sessionId}` : `Abrir sesión ${project.session.sessionId}`}
                    </Text>
                  </Pressable>
                </>
              ) : (
                <>
                  {project.session?.status === 'CLOSED' ? (
                    <Text style={styles.meta}>
                      La última sesión {project.session.sessionId} está cerrada. Abre una nueva sesión vía Core para continuar.
                    </Text>
                  ) : (
                    <Text style={styles.meta}>Todavía no hay sesión activa.</Text>
                  )}
                  <ActionButton
                    label={pendingProjectAction === `session-${project.projectId}` ? 'Abriendo...' : 'Preparar sesión'}
                    onPress={() => {
                      setMutationError(null);
                      setSessionDraftProject(project);
                    }}
                    disabled={pendingProjectAction != null}
                    prominence="high"
                  />
                </>
              )}
            </Card>
          );
        })()
      ))}
      {mutationError ? <Text style={styles.error}>{mutationError.message}</Text> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {
    flex: 1,
  },
  container: {
    padding: 16,
    paddingBottom: 120,
    gap: 14,
  },
  newSessionScreen: {
    flex: 1,
    backgroundColor: '#f3efe6',
  },
  newSessionHeader: {
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: '#f8f1e7',
    borderBottomWidth: 1,
    borderBottomColor: '#dfd2bd',
  },
  backButton: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 12,
    backgroundColor: '#fffdf8',
    borderWidth: 1,
    borderColor: '#8b765a',
  },
  backButtonLabel: {
    fontSize: 13,
    fontWeight: '900',
    color: '#2f2419',
  },
  newSessionHeaderCopy: {
    flex: 1,
    minWidth: 0,
  },
  newSessionKicker: {
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0.8,
    textTransform: 'uppercase',
    color: '#745b3c',
  },
  newSessionTitle: {
    marginTop: 3,
    fontSize: 20,
    fontWeight: '900',
    color: '#241c14',
  },
  newSessionScroll: {
    flex: 1,
  },
  newSessionContent: {
    padding: 16,
    paddingBottom: 36,
    gap: 16,
  },
  newSessionSummary: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#fffdf8',
    borderWidth: 1,
    borderColor: '#e2d4bd',
    gap: 4,
  },
  newSessionSummaryLabel: {
    fontSize: 12,
    fontWeight: '900',
    textTransform: 'uppercase',
    letterSpacing: 0.8,
    color: '#745b3c',
  },
  newSessionSummaryValue: {
    fontSize: 17,
    fontWeight: '900',
    color: '#241c14',
  },
  newSessionSummaryMeta: {
    fontSize: 13,
    lineHeight: 18,
    color: '#705b42',
  },
  newSessionField: {
    gap: 8,
  },
  newSessionLabel: {
    fontSize: 13,
    fontWeight: '900',
    color: '#2f2419',
  },
  newSessionInput: {
    minHeight: 54,
    borderWidth: 1.5,
    borderColor: '#8b765a',
    borderRadius: 15,
    paddingHorizontal: 14,
    paddingVertical: 13,
    backgroundColor: '#ffffff',
    fontSize: 16,
    fontWeight: '700',
    color: '#211910',
  },
  newSessionFooter: {
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 14,
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 10,
    backgroundColor: '#fffdf8',
    borderTopWidth: 1,
    borderTopColor: '#dfd2bd',
  },
  newSessionErrorBox: {
    padding: 14,
    borderRadius: 15,
    backgroundColor: '#fde8e3',
    borderWidth: 1,
    borderColor: '#d9988c',
    gap: 4,
  },
  newSessionErrorTitle: {
    fontSize: 13,
    fontWeight: '900',
    color: '#7b2517',
  },
  newSessionErrorText: {
    fontSize: 13,
    lineHeight: 18,
    color: '#7b2517',
  },
  newSessionErrorDetail: {
    marginTop: 4,
    fontSize: 12,
    lineHeight: 17,
    color: '#9b5245',
  },
  newSessionErrorActions: {
    marginTop: 8,
    flexDirection: 'row',
    justifyContent: 'flex-start',
  },
  sessionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 8,
    flexWrap: 'wrap',
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    flexWrap: 'wrap',
  },
  meta: {
    fontSize: 13,
    lineHeight: 18,
    color: '#705b42',
  },
  runningPanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#fff3cf',
    borderWidth: 1,
    borderColor: '#efd28f',
  },
  runningText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#6a4914',
  },
  runningMeta: {
    fontSize: 12,
    color: '#8b6d34',
  },
  responsePanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#e2f3e8',
    borderWidth: 1,
    borderColor: '#b7d8c0',
  },
  responseTitle: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '800',
    color: '#21573c',
  },
  responseText: {
    fontSize: 12,
    lineHeight: 17,
    color: '#476d55',
  },
  blockedPanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#fde8e3',
    borderWidth: 1,
    borderColor: '#e6b0a4',
  },
  blockedTitle: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '800',
    color: '#7b2517',
  },
  blockedText: {
    fontSize: 12,
    lineHeight: 17,
    color: '#975042',
  },
  readyPanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#e2f3e8',
    borderWidth: 1,
    borderColor: '#b7d8c0',
  },
  readyTitle: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '800',
    color: '#21573c',
  },
  readyText: {
    fontSize: 12,
    lineHeight: 17,
    color: '#476d55',
  },
  input: {
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 11,
    backgroundColor: '#fffdf8',
    fontSize: 14,
    color: '#2d2218',
  },
  sessionComposerOverlay: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  sessionComposerBackdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(32, 25, 18, 0.42)',
  },
  sessionComposerPanel: {
    position: 'absolute',
    left: 12,
    right: 12,
    padding: 18,
    gap: 14,
    borderRadius: 22,
    backgroundColor: '#fffdf8',
    borderWidth: 1,
    borderColor: '#e2d4bd',
  },
  sessionComposerBody: {
    gap: 14,
  },
  sessionComposerKicker: {
    fontSize: 12,
    fontWeight: '900',
    letterSpacing: 0.8,
    textTransform: 'uppercase',
    color: '#745b3c',
  },
  sessionComposerTitle: {
    fontSize: 21,
    fontWeight: '900',
    color: '#241c14',
  },
  sessionComposerInput: {
    minHeight: 52,
    borderWidth: 1.5,
    borderColor: '#8b765a',
    borderRadius: 15,
    paddingHorizontal: 14,
    paddingVertical: 13,
    backgroundColor: '#ffffff',
    fontSize: 16,
    fontWeight: '700',
    color: '#211910',
  },
  sessionComposerActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 10,
    flexWrap: 'wrap',
  },
  sessionComposerError: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#9f3024',
  },
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
  },
  warningLink: {
    fontSize: 13,
    fontWeight: '900',
    color: '#855200',
  },
  error: {
    fontSize: 13,
    fontWeight: '700',
    color: '#9f3024',
  },
  pendingNotice: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#6a4d1f',
  },
  recoveryCard: {
    gap: 6,
    padding: 13,
    borderRadius: 12,
    backgroundColor: '#fff7eb',
    borderWidth: 1,
    borderColor: '#e6d2b2',
  },
  recoveryTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#7b4f1d',
  },
});
