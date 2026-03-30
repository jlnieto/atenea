import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { usePendingActionCenter } from '../actions/PendingActionCenter';
import { confirmAction } from '../actions/confirm';
import { fetchJson, postJson } from '../api/client';
import {
  MobileProjectOverview,
  ResolveSessionRequest,
  ResolveSessionResponse,
} from '../api/types';
import { ActionButton } from '../components/ActionButton';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function ProjectsScreen({ onOpenSession }: { onOpenSession: (sessionId: number) => void }) {
  const { data, error, loading, reload } = useRemoteResource(
    () => fetchJson<MobileProjectOverview[]>('/api/mobile/projects/overview'),
    [],
    { refreshIntervalMs: 15000 }
  );
  const [drafts, setDrafts] = useState<Record<number, ResolveSessionRequest>>({});
  const [pendingProjectId, setPendingProjectId] = useState<number | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const { pendingAction, startPendingAction, clearPendingAction } = usePendingActionCenter();

  const updateDraft = (projectId: number, patch: ResolveSessionRequest) => {
    setDrafts((current) => ({
      ...current,
      [projectId]: {
        ...current[projectId],
        ...patch,
      },
    }));
  };

  const resolveSession = async (projectId: number) => {
    const draft = drafts[projectId];
    const confirmed = await confirmAction(
      'Resolve session?',
      `This will open or reuse the active WorkSession for this project${draft?.baseBranch ? ` on base branch ${draft.baseBranch.trim()}` : ''}.`
    );
    if (!confirmed) {
      return;
    }

    setPendingProjectId(projectId);
    setMutationError(null);
    startPendingAction({
      label: 'Resolve session',
      scope: 'project',
      projectId,
      startedAt: new Date().toISOString(),
      recoveryHint: 'Refresh Projects before retrying resolve so you can confirm whether the project already has an active session.',
    });

    try {
      const response = await postJson<ResolveSessionResponse, ResolveSessionRequest>(
        `/api/mobile/projects/${projectId}/sessions/resolve`,
        draft
      );
      onOpenSession(response.view.view.session.id);
      await reload();
    } catch (resolveError) {
      setMutationError(resolveError instanceof Error ? resolveError.message : 'Resolve failed');
    } finally {
      clearPendingAction();
      setPendingProjectId(null);
    }
  };

  const projectPendingRecovery = pendingAction?.scope === 'project' ? pendingAction : null;

  if (loading) {
    return <LoadingBlock label="Loading projects..." />;
  }

  if (error) {
    return <Card title="Projects unavailable" subtitle={error}><Text style={styles.meta}>Check backend connectivity.</Text></Card>;
  }

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <Text style={styles.meta}>Projects refresh automatically every 15s.</Text>
        <Pressable onPress={() => void reload()}>
          <Text style={styles.link}>Refresh</Text>
        </Pressable>
      </View>
      {pendingProjectId != null ? (
        <Text style={styles.pendingNotice}>
          Resolving session for project {pendingProjectId}. If the app is interrupted, refresh Projects before trying again.
        </Text>
      ) : null}
      {projectPendingRecovery ? (
        <View style={styles.recoveryCard}>
          <Text style={styles.recoveryTitle}>Recovered pending action: {projectPendingRecovery.label}</Text>
          <Text style={styles.meta}>{projectPendingRecovery.recoveryHint}</Text>
          <Pressable onPress={clearPendingAction}>
            <Text style={styles.link}>Dismiss recovery notice</Text>
          </Pressable>
        </View>
      ) : null}
      {data?.map((project) => (
        <Card
          key={project.projectId}
          title={project.projectName}
          subtitle={project.description || project.defaultBaseBranch || 'No project description'}
        >
          {project.session && project.session.status !== 'CLOSED' ? (
            <>
              <View style={styles.sessionRow}>
                <StatePill label={project.session.status} tone={project.session.closeBlockedState ? 'warning' : 'default'} />
                {project.session.pullRequestStatus ? (
                  <StatePill label={project.session.pullRequestStatus} tone={project.session.pullRequestStatus === 'MERGED' ? 'good' : 'default'} />
                ) : null}
              </View>
              <Text style={styles.meta}>{project.session.title}</Text>
              <Pressable onPress={() => onOpenSession(project.session!.sessionId)}>
                <Text style={styles.link}>Open session {project.session.sessionId}</Text>
              </Pressable>
            </>
          ) : (
            <>
              {project.session?.status === 'CLOSED' ? (
                <Text style={styles.meta}>
                  Last session {project.session.sessionId} is closed. Resolve a new session to continue working.
                </Text>
              ) : (
                <Text style={styles.meta}>No session yet.</Text>
              )}
              <TextInput
                value={drafts[project.projectId]?.title ?? ''}
                onChangeText={(title) => updateDraft(project.projectId, { title })}
                placeholder="Session title"
                placeholderTextColor="#8b7c6b"
                style={styles.input}
              />
              <TextInput
                value={drafts[project.projectId]?.baseBranch ?? project.defaultBaseBranch ?? ''}
                onChangeText={(baseBranch) => updateDraft(project.projectId, { baseBranch })}
                placeholder={project.defaultBaseBranch || 'Base branch'}
                placeholderTextColor="#8b7c6b"
                style={styles.input}
                autoCapitalize="none"
                autoCorrect={false}
              />
              <ActionButton
                label={pendingProjectId === project.projectId ? 'Resolving...' : 'Resolve session'}
                onPress={() => void resolveSession(project.projectId)}
                disabled={pendingProjectId != null}
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
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  input: {
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
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
    padding: 12,
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
