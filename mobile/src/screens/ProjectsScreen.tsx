import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { usePendingActionCenter } from '../actions/PendingActionCenter';
import { fetchJson } from '../api/client';
import {
  MobileProjectOverview,
} from '../api/types';
import { buildActivateProjectCommand, buildOpenSessionCommand } from '../core/phrases';
import { RunCoreCommandOptions } from '../core/useCoreCommandCenter';
import { ActionButton } from '../components/ActionButton';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function ProjectsScreen({
  selectedProjectId,
  onOpenCore,
  onOpenSession,
  onSelectProject,
  onRunCommand,
}: {
  selectedProjectId: number | null;
  onOpenCore: () => void;
  onOpenSession: (sessionId: number) => void;
  onSelectProject: (projectId: number | null) => void;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<unknown>;
}) {
  const { data, error, loading, reload } = useRemoteResource(
    () => fetchJson<MobileProjectOverview[]>('/api/mobile/projects/overview'),
    [],
    { refreshIntervalMs: 15000 }
  );
  const [drafts, setDrafts] = useState<Record<number, { title?: string }>>({});
  const [pendingProjectAction, setPendingProjectAction] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const { pendingAction, startPendingAction, clearPendingAction } = usePendingActionCenter();

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
      label: 'Activate project context',
      scope: 'project',
      projectId: project.projectId,
      startedAt: new Date().toISOString(),
      recoveryHint: 'Refresh Projects before retrying project activation so the latest active session and project status are visible.',
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
      setMutationError(resolveError instanceof Error ? resolveError.message : 'Project activation failed');
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
      label: 'Resolve session through Atenea Core',
      scope: 'project',
      projectId,
      startedAt: new Date().toISOString(),
      recoveryHint: 'Refresh Projects before retrying session open so you can confirm whether the project already has an active session.',
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
    } catch (resolveError) {
      setMutationError(resolveError instanceof Error ? resolveError.message : 'Resolve failed');
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

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <Text style={styles.meta}>La activación de contexto y la apertura de sesión pasan por Atenea Core.</Text>
        <View style={styles.headerActions}>
          <Pressable onPress={() => void reload()}>
            <Text style={styles.link}>Actualizar</Text>
          </Pressable>
          <Pressable onPress={onOpenCore}>
            <Text style={styles.link}>Abrir Core</Text>
          </Pressable>
        </View>
      </View>
      {pendingProjectAction != null ? (
        <Text style={styles.pendingNotice}>
          Acción de proyecto en curso. Si la app se interrumpe, actualiza Projects antes de reintentar.
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
        <Card
          key={project.projectId}
          title={project.projectName}
          subtitle={project.description || project.defaultBaseBranch || 'No project description'}
        >
          <View style={styles.headerRow}>
            <StatePill
              label={selectedProjectId === project.projectId ? 'PROYECTO ACTIVO' : 'PROYECTO'}
              tone={selectedProjectId === project.projectId ? 'good' : 'default'}
            />
            <Pressable onPress={() => void activateProject(project)}>
              <Text style={styles.link}>
                {selectedProjectId === project.projectId ? 'Refrescar contexto' : 'Activar en Core'}
              </Text>
            </Pressable>
          </View>
          {project.session && project.session.status !== 'CLOSED' ? (
            <>
              <View style={styles.sessionRow}>
                <StatePill label={project.session.status} tone={project.session.closeBlockedState ? 'warning' : 'default'} />
                {project.session.pullRequestStatus ? (
                  <StatePill label={project.session.pullRequestStatus} tone={project.session.pullRequestStatus === 'MERGED' ? 'good' : 'default'} />
                ) : null}
              </View>
              <Text style={styles.meta}>{project.session.title}</Text>
              <Pressable
                onPress={() => {
                  onSelectProject(project.projectId);
                  onOpenSession(project.session!.sessionId);
                }}
              >
                <Text style={styles.link}>Abrir sesión {project.session.sessionId}</Text>
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
              <TextInput
                value={drafts[project.projectId]?.title ?? ''}
                onChangeText={(title) => updateDraft(project.projectId, { title })}
                placeholder="Título de sesión"
                placeholderTextColor="#8b7c6b"
                style={styles.input}
              />
              <ActionButton
                label={pendingProjectAction === `session-${project.projectId}` ? 'Abriendo...' : 'Abrir sesión vía Core'}
                onPress={() => void resolveSession(project)}
                disabled={pendingProjectAction != null}
              />
            </>
          )}
        </Card>
      ))}
      {mutationError ? <Text style={styles.error}>{mutationError}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
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
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
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
