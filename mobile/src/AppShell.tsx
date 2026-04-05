import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { usePendingActionCenter } from './actions/PendingActionCenter';
import { fetchJson } from './api/client';
import { MobileInboxResponse, MobileProjectOverview } from './api/types';
import { useNotificationCenter } from './notifications/NotificationCenter';
import { BillingScreen } from './screens/BillingScreen';
import { ConversationScreen } from './screens/ConversationScreen';
import { CoreConsoleScreen } from './screens/CoreConsoleScreen';
import { InboxScreen } from './screens/InboxScreen';
import { NotificationsScreen } from './screens/NotificationsScreen';
import { ProjectsScreen } from './screens/ProjectsScreen';
import { SessionScreen } from './screens/SessionScreen';
import { StatePill } from './components/StatePill';
import { useCoreCommandCenter } from './core/useCoreCommandCenter';
import { useRemoteResource } from './hooks/useRemoteResource';

export type AppTabId = 'core' | 'inbox' | 'notifications' | 'projects' | 'session' | 'conversation' | 'billing';

type AppShellProps = {
  activeTab: AppTabId;
  onChangeTab: (tab: AppTabId) => void;
  selectedProjectId: number | null;
  onSelectProject: (projectId: number | null) => void;
  selectedSessionId: number | null;
  onSelectSession: (sessionId: number | null) => void;
  operatorKey: string;
  operatorName: string;
  onLogout: () => void;
};

type RecentQueueEvent = {
  id: string;
  projectId: number;
  projectName: string;
  sessionId: number;
  sessionTitle: string;
  type: 'RESPUESTA_NUEVA' | 'NECESITA_REVISION';
  summary: string;
  createdAt: string;
};

const TABS: Array<{ id: AppTabId; label: string }> = [
  { id: 'core', label: 'Core' },
  { id: 'session', label: 'Sesión' },
  { id: 'projects', label: 'Proyectos' },
  { id: 'inbox', label: 'Inbox' },
  { id: 'billing', label: 'Facturación' },
  { id: 'notifications', label: 'Alertas' },
];

export function AppShell({
  activeTab,
  onChangeTab,
  selectedProjectId,
  onSelectProject,
  selectedSessionId,
  onSelectSession,
  operatorKey,
  operatorName,
  onLogout,
}: AppShellProps) {
  const {
    notifications,
    consumePendingRoute,
    routeToNotification,
    dismissNotification,
    clearNotifications,
    publishAppNotification,
  } = useNotificationCenter();
  const { pendingAction, clearPendingAction } = usePendingActionCenter();
  const [seenSessionActivity, setSeenSessionActivity] = useState<Record<number, string>>({});
  const [announcedSessionState, setAnnouncedSessionState] = useState<Record<number, string>>({});
  const [conversationAutoReadRequest, setConversationAutoReadRequest] = useState<{ token: number; mode: 'brief' | 'full' } | null>(null);
  const [recentQueueEvents, setRecentQueueEvents] = useState<RecentQueueEvent[]>([]);
  const hydratedSeenSessionsRef = useRef(false);
  const hydratedAnnouncementStateRef = useRef(false);
  const { data: workQueueProjects, reload: reloadWorkQueue } = useRemoteResource(
    () => fetchJson<MobileProjectOverview[]>('/api/mobile/projects/overview'),
    [],
    { refreshIntervalMs: 15000 }
  );
  const { data: inboxSummary } = useRemoteResource(
    () => fetchJson<MobileInboxResponse>('/api/mobile/inbox'),
    [],
    { refreshIntervalMs: 10000 }
  );
  const {
    activeCommand,
    clearActiveCommand,
    confirmActiveCommand,
    historyVersion,
    resolveClarification,
    runCommand,
    runVoiceCommand,
  } = useCoreCommandCenter({
    operatorKey,
    selectedProjectId,
    selectedSessionId,
    onSelectProject,
    onSelectSession,
    onAttentionRequired: () => onChangeTab('core'),
  });

  useEffect(() => {
    if (hydratedSeenSessionsRef.current || !workQueueProjects?.length) {
      return;
    }
    const initialSeen: Record<number, string> = {};
    for (const project of workQueueProjects) {
      if (project.session?.sessionId != null && project.session.lastActivityAt) {
        initialSeen[project.session.sessionId] = project.session.lastActivityAt;
      }
    }
    hydratedSeenSessionsRef.current = true;
    setSeenSessionActivity(initialSeen);
  }, [workQueueProjects]);

  useEffect(() => {
    if (selectedSessionId == null || !workQueueProjects?.length) {
      return;
    }
    const activeSession = workQueueProjects.find((project) => project.session?.sessionId === selectedSessionId)?.session;
    if (!activeSession?.lastActivityAt) {
      return;
    }
    const latestActivityAt = activeSession.lastActivityAt;
    setSeenSessionActivity((current) => {
      if (current[selectedSessionId] === latestActivityAt) {
        return current;
      }
      return {
        ...current,
        [selectedSessionId]: latestActivityAt,
      };
    });
  }, [selectedSessionId, workQueueProjects]);

  const sessionHint = useMemo(() => (
    selectedSessionId == null
      ? 'Sin sesión activa.'
      : `Sesión ${selectedSessionId}`
  ), [selectedSessionId]);

  const projectHint = useMemo(() => (
    selectedProjectId == null
      ? 'Sin proyecto activo.'
      : `Proyecto ${selectedProjectId}`
  ), [selectedProjectId]);

  const workflowHint = useMemo(() => {
    if (selectedSessionId != null) {
      return 'Core opera la sesión activa. Session revisa estado y Conversation continúa el trabajo.';
    }
    if (selectedProjectId != null) {
      return 'Proyecto activo. Usa Core o abre una sesión para continuar.';
    }
    return 'Empieza por Core o Proyectos para fijar contexto.';
  }, [selectedProjectId, selectedSessionId]);

  const openSession = (sessionId: number) => {
    const sessionActivity = workQueueProjects
      ?.find((project) => project.session?.sessionId === sessionId)
      ?.session?.lastActivityAt;
    if (sessionActivity) {
      setSeenSessionActivity((current) => ({
        ...current,
        [sessionId]: sessionActivity,
      }));
    }
    onSelectSession(sessionId);
    onChangeTab('session');
  };

  const openQueueConversation = (projectId: number, sessionId: number) => {
    const sessionActivity = workQueueProjects
      ?.find((project) => project.session?.sessionId === sessionId)
      ?.session?.lastActivityAt;
    if (sessionActivity) {
      setSeenSessionActivity((current) => ({
        ...current,
        [sessionId]: sessionActivity,
      }));
    }
    onSelectProject(projectId);
    onSelectSession(sessionId);
    onChangeTab('conversation');
  };

  useEffect(() => {
    const route = consumePendingRoute();
    if (route == null) {
      return;
    }
    if (route.projectId != null) {
      onSelectProject(route.projectId);
    }
    if (route.sessionId != null) {
      onSelectSession(route.sessionId);
    }
    if (route.tab === 'conversation' && route.autoReadMode) {
      setConversationAutoReadRequest({
        token: Date.now(),
        mode: route.autoReadMode,
      });
    }
    onChangeTab(route.tab);
  }, [consumePendingRoute, notifications, onChangeTab, onSelectProject, onSelectSession]);

  const openConversation = () => {
    onChangeTab('conversation');
  };

  const openNotificationById = (notificationId: string) => {
    const notification = notifications.find((entry) => entry.id === notificationId);
    if (!notification) {
      return;
    }
    const route = routeToNotification(notification);
    if (route?.projectId != null) {
      onSelectProject(route.projectId);
    }
    if (route?.sessionId != null) {
      onSelectSession(route.sessionId);
    }
    if (route?.tab === 'conversation' && route.autoReadMode) {
      setConversationAutoReadRequest({
        token: Date.now(),
        mode: route.autoReadMode,
      });
    }
    if (route != null) {
      onChangeTab(route.tab);
      return;
    }
    onChangeTab('inbox');
  };

  const openNotification = (index: number) => {
    const notification = notifications[index];
    if (!notification) {
      return;
    }
    openNotificationById(notification.id);
  };

  const recoverPendingAction = () => {
    if (pendingAction?.sessionId != null) {
      onSelectSession(pendingAction.sessionId);
      onChangeTab('session');
      return;
    }
    onChangeTab('projects');
  };

  const workQueue = useMemo(() => {
    const attentionBySessionId = new Map<number, MobileInboxResponse['items']>();
    for (const item of inboxSummary?.items ?? []) {
      if (item.sessionId == null) {
        continue;
      }
      const entries = attentionBySessionId.get(item.sessionId) ?? [];
      entries.push(item);
      attentionBySessionId.set(item.sessionId, entries);
    }

    return (workQueueProjects ?? [])
      .filter((project) => project.session && project.session.status !== 'CLOSED')
      .map((project) => {
        const session = project.session!;
        const attentionItems = attentionBySessionId.get(session.sessionId) ?? [];
        const topAttention = attentionItems[0] ?? null;
        const seenActivityAt = seenSessionActivity[session.sessionId] ?? null;
        const hasNewResponse = session.lastActivityAt != null
          && seenActivityAt != null
          && session.lastActivityAt > seenActivityAt
          && !session.runInProgress;
        const needsReview = attentionItems.length > 0;
        const queueState = session.runInProgress
          ? 'ESPERANDO_RESPUESTA'
          : needsReview
            ? 'NECESITA_REVISION'
            : hasNewResponse
              ? 'RESPUESTA_NUEVA'
              : 'EN_ESPERA';
        return {
          projectId: project.projectId,
          projectName: project.projectName,
          sessionId: session.sessionId,
          sessionTitle: session.title,
          runInProgress: session.runInProgress,
          pullRequestStatus: session.pullRequestStatus,
          lastActivityAt: session.lastActivityAt,
          queueState,
          attentionCount: attentionItems.length,
          attentionLabel: topAttention?.action ?? topAttention?.title ?? null,
          hasNewResponse,
          selected: selectedSessionId === session.sessionId,
        };
      })
      .sort((left, right) => {
        const rank = (value: string) => {
          if (value === 'NECESITA_REVISION') {
            return 0;
          }
          if (value === 'RESPUESTA_NUEVA') {
            return 1;
          }
          if (value === 'ESPERANDO_RESPUESTA') {
            return 2;
          }
          return 3;
        };
        const stateRank = rank(left.queueState) - rank(right.queueState);
        if (stateRank !== 0) {
          return stateRank;
        }
        if (left.selected !== right.selected) {
          return left.selected ? -1 : 1;
        }
        return (right.lastActivityAt ?? '').localeCompare(left.lastActivityAt ?? '');
      });
  }, [inboxSummary, workQueueProjects, selectedSessionId, seenSessionActivity]);

  useEffect(() => {
    if (!hydratedAnnouncementStateRef.current) {
      const baseline: Record<number, string> = {};
      for (const item of workQueue) {
        baseline[item.sessionId] = `${item.queueState}:${item.lastActivityAt ?? ''}:${item.attentionLabel ?? ''}`;
      }
      hydratedAnnouncementStateRef.current = true;
      setAnnouncedSessionState(baseline);
      return;
    }

    const sessionsToAnnounce = workQueue.filter((item) =>
      (item.queueState === 'RESPUESTA_NUEVA' || item.queueState === 'NECESITA_REVISION') && item.lastActivityAt != null
    );
    for (const item of sessionsToAnnounce) {
      const announcementKey = `${item.queueState}:${item.lastActivityAt ?? ''}:${item.attentionLabel ?? ''}`;
      if (announcedSessionState[item.sessionId] === announcementKey) {
        continue;
      }
      setAnnouncedSessionState((current) => ({
        ...current,
        [item.sessionId]: announcementKey,
      }));
      const title = item.queueState === 'NECESITA_REVISION'
        ? `${item.projectName}: necesita revisión`
        : `${item.projectName}: respuesta nueva`;
      const body = item.queueState === 'NECESITA_REVISION'
        ? item.attentionLabel
          ? `Revisa esto ahora: ${item.attentionLabel}`
          : 'La sesión requiere revisión antes de seguir.'
        : item.attentionLabel
          ? `Resultado listo. ${item.attentionLabel}`
          : 'Codex ha terminado y hay una respuesta nueva para revisar.';
      const eventId = `session-update-${item.sessionId}-${announcementKey}`;
      setRecentQueueEvents((current) => {
        const next: RecentQueueEvent[] = [
          {
            id: eventId,
            projectId: item.projectId,
            projectName: item.projectName,
            sessionId: item.sessionId,
            sessionTitle: item.sessionTitle,
            type: item.queueState as 'RESPUESTA_NUEVA' | 'NECESITA_REVISION',
            summary: body,
            createdAt: new Date().toISOString(),
          },
          ...current.filter((entry) => entry.id !== eventId),
        ];
        return next.slice(0, 6);
      });
      void publishAppNotification({
        id: eventId,
        title,
        body,
        route: {
          tab: 'conversation',
          projectId: item.projectId,
          sessionId: item.sessionId,
          autoReadMode: 'brief',
        },
      });
    }
  }, [announcedSessionState, publishAppNotification, workQueue]);

  return (
    <View style={styles.container}>
      {activeTab !== 'conversation' ? (
        <View style={styles.header}>
          <View style={styles.headerRow}>
            <View style={styles.headerCopy}>
              <Text style={styles.eyebrow}>Atenea Mobile</Text>
              <Text style={styles.title}>Consola operador</Text>
              <Text style={styles.subtitle}>
                Superficie móvil para revisar contexto y operar sesiones a través de Atenea Core.
              </Text>
            </View>
            <Pressable onPress={onLogout} style={styles.logoutButton}>
              <Text style={styles.logoutLabel}>Cerrar sesión</Text>
            </Pressable>
          </View>
        </View>
      ) : null}

      {activeTab !== 'conversation' ? (
        <View style={styles.tabBar}>
          {TABS.map((tab) => (
            <Pressable
              key={tab.id}
              onPress={() => onChangeTab(tab.id)}
              style={[styles.tab, activeTab === tab.id && styles.tabActive]}
            >
              <Text style={[styles.tabLabel, activeTab === tab.id && styles.tabLabelActive]}>
                {tab.label}
              </Text>
            </Pressable>
          ))}
        </View>
      ) : null}

      {activeTab === 'conversation' ? (
        <View style={styles.conversationContent}>
          <ConversationScreen
            key={`conversation-${selectedSessionId ?? 'none'}-${conversationAutoReadRequest?.token ?? 'idle'}`}
            projectId={selectedProjectId}
            sessionId={selectedSessionId}
            autoReadRequest={conversationAutoReadRequest}
            onBackToSession={() => onChangeTab('session')}
            onOpenCore={() => onChangeTab('core')}
            onRunCommand={runCommand}
            onRunVoiceCommand={runVoiceCommand}
          />
        </View>
      ) : activeTab === 'session' ? (
        <View key={`session-${selectedSessionId ?? 'none'}`} style={styles.sessionContent}>
          <SessionScreen
            projectId={selectedProjectId}
            sessionId={selectedSessionId}
            onOpenConversation={openConversation}
            onOpenCore={() => onChangeTab('core')}
            onOpenSession={openSession}
            onRunCommand={runCommand}
          />
        </View>
      ) : activeTab === 'core' ? (
        <ScrollView
          key="tab-core"
          style={styles.contentScroll}
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
        >
          <View style={styles.sessionHintCard}>
            <Text style={styles.sessionHintLabel}>Contexto activo</Text>
            <Text style={styles.sessionHintValue}>{sessionHint}</Text>
            <Text style={styles.operatorValue}>{projectHint}</Text>
            <Text style={styles.operatorValue}>{workflowHint}</Text>
            <Text style={styles.operatorValue}>Operador: {operatorName}</Text>
          </View>

          {workQueue.length > 0 ? (
            <View style={styles.workQueueCard}>
              <View style={styles.workQueueHeader}>
                <View style={styles.workQueueHeaderCopy}>
                  <Text style={styles.workQueueTitle}>Radar de trabajo</Text>
                  <Text style={styles.workQueueSubtitle}>
                    Tus sesiones activas para alternar entre proyectos sin perder contexto.
                  </Text>
                </View>
                <Pressable onPress={() => void reloadWorkQueue()}>
                  <Text style={styles.workQueueRefresh}>Actualizar</Text>
                </Pressable>
              </View>
              <View style={styles.workQueueSummaryRow}>
                <StatePill label={`${workQueue.filter((item) => item.queueState === 'NECESITA_REVISION').length} revisión`} tone="warning" />
                <StatePill label={`${workQueue.filter((item) => item.queueState === 'RESPUESTA_NUEVA').length} nuevas`} tone="good" />
                <StatePill label={`${workQueue.filter((item) => item.queueState === 'ESPERANDO_RESPUESTA').length} esperando`} />
              </View>
              {workQueue.map((item) => (
                <View
                  key={item.sessionId}
                  style={[styles.workQueueItem, item.selected && styles.workQueueItemSelected]}
                >
                  <View style={styles.workQueueItemTop}>
                    <View style={styles.workQueueItemCopy}>
                      <Text style={styles.workQueueProject}>{item.projectName}</Text>
                      <Text style={styles.workQueueSession}>
                        Sesión {item.sessionId} · {item.sessionTitle}
                      </Text>
                    </View>
                    <View style={styles.workQueuePills}>
                      <StatePill
                        label={
                          item.queueState === 'NECESITA_REVISION'
                            ? 'NECESITA REVISIÓN'
                            : item.queueState === 'RESPUESTA_NUEVA'
                              ? 'RESPUESTA NUEVA'
                              : item.queueState === 'ESPERANDO_RESPUESTA'
                                ? 'ESPERANDO RESPUESTA'
                                : 'EN ESPERA'
                        }
                        tone={
                          item.queueState === 'NECESITA_REVISION'
                            ? 'warning'
                            : item.queueState === 'RESPUESTA_NUEVA'
                              ? 'good'
                              : 'default'
                        }
                      />
                      {item.pullRequestStatus ? <StatePill label={item.pullRequestStatus} /> : null}
                    </View>
                  </View>
                  <Text style={styles.workQueueMeta}>
                    {item.queueState === 'RESPUESTA_NUEVA'
                      ? 'Hay una respuesta nueva pendiente de revisar.'
                      : item.attentionLabel
                        ? `Siguiente atención: ${item.attentionLabel}`
                        : item.runInProgress
                          ? 'Codex sigue trabajando en esta sesión.'
                          : 'Lista para revisar la última respuesta y decidir el siguiente paso.'}
                  </Text>
                  <Text style={styles.workQueueMeta}>
                    {item.lastActivityAt
                      ? `Última actividad: ${new Date(item.lastActivityAt).toLocaleString()}`
                      : 'Sin actividad reciente registrada.'}
                  </Text>
                  <View style={styles.workQueueActions}>
                    <Pressable
                      onPress={() => {
                        onSelectProject(item.projectId);
                        openSession(item.sessionId);
                      }}
                      style={styles.workQueuePrimary}
                    >
                      <Text style={styles.workQueuePrimaryLabel}>Abrir sesión</Text>
                    </Pressable>
                    <Pressable
                      onPress={() => openQueueConversation(item.projectId, item.sessionId)}
                      style={styles.workQueueSecondary}
                    >
                      <Text style={styles.workQueueSecondaryLabel}>Ir a conversación</Text>
                    </Pressable>
                  </View>
                </View>
              ))}
            </View>
          ) : null}

          {recentQueueEvents.length > 0 ? (
            <View style={styles.recentQueueCard}>
              <View style={styles.recentQueueHeader}>
                <View style={styles.recentQueueHeaderCopy}>
                  <Text style={styles.recentQueueTitle}>Terminados recientemente</Text>
                  <Text style={styles.recentQueueSubtitle}>
                    Bandeja corta de lo último que ha cambiado para que proceses resultados por orden de llegada.
                  </Text>
                </View>
              </View>
              {recentQueueEvents.map((event) => (
                <View key={event.id} style={styles.recentQueueItem}>
                  <View style={styles.recentQueueTop}>
                    <View style={styles.recentQueueCopy}>
                      <Text style={styles.recentQueueProject}>{event.projectName}</Text>
                      <Text style={styles.recentQueueMeta}>
                        Sesión {event.sessionId} · {event.sessionTitle} · {new Date(event.createdAt).toLocaleTimeString()}
                      </Text>
                    </View>
                    <StatePill
                      label={event.type === 'NECESITA_REVISION' ? 'NECESITA REVISIÓN' : 'RESPUESTA NUEVA'}
                      tone={event.type === 'NECESITA_REVISION' ? 'warning' : 'good'}
                    />
                  </View>
                  <Text style={styles.recentQueueSummary}>{event.summary}</Text>
                  <View style={styles.recentQueueActions}>
                    <Pressable
                      onPress={() => {
                        onSelectProject(event.projectId);
                        openSession(event.sessionId);
                      }}
                      style={styles.recentQueuePrimary}
                    >
                      <Text style={styles.recentQueuePrimaryLabel}>Abrir sesión</Text>
                    </Pressable>
                    <Pressable
                      onPress={() => openQueueConversation(event.projectId, event.sessionId)}
                      style={styles.recentQueueSecondary}
                    >
                      <Text style={styles.recentQueueSecondaryLabel}>Ir a conversación</Text>
                    </Pressable>
                  </View>
                </View>
              ))}
            </View>
          ) : null}

          {pendingAction ? (
            <View style={styles.pendingActionCard}>
              <Text style={styles.pendingActionLabel}>Recuperación pendiente</Text>
              <Text style={styles.pendingActionTitle}>{pendingAction.label}</Text>
              <Text style={styles.pendingActionMeta}>Iniciada {new Date(pendingAction.startedAt).toLocaleString()}</Text>
              <Text style={styles.pendingActionHint}>{pendingAction.recoveryHint}</Text>
              <View style={styles.pendingActionButtons}>
                <Pressable onPress={recoverPendingAction} style={styles.pendingPrimaryButton}>
                  <Text style={styles.pendingPrimaryLabel}>Abrir contexto</Text>
                </Pressable>
                <Pressable onPress={clearPendingAction} style={styles.pendingSecondaryButton}>
                  <Text style={styles.pendingSecondaryLabel}>Descartar</Text>
                </Pressable>
              </View>
            </View>
          ) : null}

          {notifications.length > 0 ? (
            <View style={styles.notificationRail}>
              <View style={styles.notificationRailHeader}>
                <Text style={styles.notificationRailTitle}>Alertas recientes</Text>
                <View style={styles.notificationRailActions}>
                  <Text style={styles.notificationRailMeta}>{notifications.length} guardadas</Text>
                  <Pressable onPress={clearNotifications} style={styles.notificationRailClear}>
                    <Text style={styles.notificationRailClearLabel}>Limpiar</Text>
                  </Pressable>
                </View>
              </View>
              {notifications.slice(0, 3).map((notification, index) => (
                <View key={notification.id} style={styles.notificationCard}>
                  <Pressable onPress={() => openNotification(index)} style={styles.notificationOpen}>
                    <Text style={styles.notificationTitle}>{notification.title}</Text>
                    {notification.body ? (
                      <Text style={styles.notificationBody}>{notification.body}</Text>
                    ) : null}
                    <Text style={styles.notificationMeta}>
                      {notification.payload?.type ?? 'PUSH'} · {new Date(notification.receivedAt).toLocaleTimeString()}
                    </Text>
                  </Pressable>
                  <Pressable onPress={() => dismissNotification(notification.id)} style={styles.notificationDismiss}>
                    <Text style={styles.notificationDismissLabel}>Descartar</Text>
                  </Pressable>
                </View>
              ))}
            </View>
          ) : null}

          <CoreConsoleScreen
            activeCommand={activeCommand}
            historyVersion={historyVersion}
            operatorKey={operatorKey}
            selectedProjectId={selectedProjectId}
            selectedSessionId={selectedSessionId}
            onClearActiveCommand={clearActiveCommand}
            onConfirmActiveCommand={confirmActiveCommand}
            onOpenSession={openSession}
            onResolveClarification={resolveClarification}
            onRunCommand={runCommand}
            onRunVoiceCommand={runVoiceCommand}
          />
        </ScrollView>
      ) : (
        <ScrollView
          key={`tab-${activeTab}`}
          style={styles.contentScroll}
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
        >
          {activeTab === 'inbox' ? (
            <InboxScreen onOpenSession={openSession} />
          ) : null}
          {activeTab === 'notifications' ? (
            <NotificationsScreen onOpenNotification={openNotificationById} />
          ) : null}
          {activeTab === 'projects' ? (
            <ProjectsScreen
              selectedProjectId={selectedProjectId}
              onOpenCore={() => onChangeTab('core')}
              onOpenSession={openSession}
              onSelectProject={onSelectProject}
              onRunCommand={runCommand}
            />
          ) : null}
          {activeTab === 'billing' ? (
            <BillingScreen onOpenSession={openSession} />
          ) : null}
        </ScrollView>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f3efe6',
  },
  header: {
    paddingHorizontal: 20,
    paddingTop: 14,
    paddingBottom: 18,
    backgroundColor: '#efe0c8',
    borderBottomWidth: 1,
    borderBottomColor: '#d4c2a3',
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
  },
  headerCopy: {
    flex: 1,
  },
  eyebrow: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.1,
    textTransform: 'uppercase',
    color: '#6b4f2a',
  },
  title: {
    marginTop: 6,
    fontSize: 30,
    fontWeight: '800',
    color: '#2e2117',
  },
  subtitle: {
    marginTop: 8,
    fontSize: 14,
    lineHeight: 21,
    color: '#5d4a35',
  },
  tabBar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#f8f3eb',
    borderBottomWidth: 1,
    borderBottomColor: '#e4d7c3',
  },
  tab: {
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#e8ddcd',
  },
  tabActive: {
    backgroundColor: '#2e6a57',
  },
  tabLabel: {
    fontSize: 14,
    fontWeight: '700',
    color: '#5d4a35',
  },
  tabLabelActive: {
    color: '#f7f3ea',
  },
  sessionHintCard: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#fffaf2',
    borderWidth: 1,
    borderColor: '#e6d8c0',
    gap: 2,
  },
  workQueueCard: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#eef7f3',
    borderWidth: 1,
    borderColor: '#cfe0d8',
    gap: 10,
  },
  recentQueueCard: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#fff8ef',
    borderWidth: 1,
    borderColor: '#ebdcc7',
    gap: 10,
  },
  recentQueueHeader: {
    gap: 4,
  },
  recentQueueHeaderCopy: {
    gap: 4,
  },
  recentQueueTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#7a4f18',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  recentQueueSubtitle: {
    fontSize: 13,
    lineHeight: 18,
    color: '#755d41',
  },
  recentQueueItem: {
    gap: 8,
    padding: 13,
    borderRadius: 14,
    backgroundColor: '#fffdf9',
    borderWidth: 1,
    borderColor: '#f0e3d1',
  },
  recentQueueTop: {
    gap: 8,
  },
  recentQueueCopy: {
    gap: 2,
  },
  recentQueueProject: {
    fontSize: 15,
    fontWeight: '800',
    color: '#2e2117',
  },
  recentQueueMeta: {
    fontSize: 12,
    color: '#836b4f',
  },
  recentQueueSummary: {
    fontSize: 13,
    lineHeight: 18,
    color: '#5f4b34',
  },
  recentQueueActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  recentQueuePrimary: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#2e6a57',
  },
  recentQueuePrimaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#f6f1e8',
  },
  recentQueueSecondary: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#f1e4cf',
  },
  recentQueueSecondaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6c4f2b',
  },
  workQueueHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: 10,
  },
  workQueueHeaderCopy: {
    flex: 1,
    gap: 4,
  },
  workQueueTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#225140',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  workQueueSubtitle: {
    fontSize: 13,
    lineHeight: 18,
    color: '#49675c',
  },
  workQueueRefresh: {
    fontSize: 12,
    fontWeight: '800',
    color: '#2e6a57',
  },
  workQueueSummaryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  workQueueItem: {
    gap: 8,
    padding: 13,
    borderRadius: 14,
    backgroundColor: '#fcfffd',
    borderWidth: 1,
    borderColor: '#dce8e1',
  },
  workQueueItemSelected: {
    borderColor: '#2e6a57',
    backgroundColor: '#f4fbf8',
  },
  workQueueItemTop: {
    gap: 8,
  },
  workQueueItemCopy: {
    gap: 2,
  },
  workQueueProject: {
    fontSize: 15,
    fontWeight: '800',
    color: '#21352c',
  },
  workQueueSession: {
    fontSize: 12,
    color: '#61786e',
  },
  workQueuePills: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  workQueueMeta: {
    fontSize: 13,
    lineHeight: 18,
    color: '#50665d',
  },
  workQueueActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  workQueuePrimary: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#2e6a57',
  },
  workQueuePrimaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#f6f1e8',
  },
  workQueueSecondary: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#dcece5',
  },
  workQueueSecondaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#2e6a57',
  },
  sessionHintLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#7a6547',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  sessionHintValue: {
    marginTop: 6,
    fontSize: 16,
    fontWeight: '700',
    color: '#2e2117',
  },
  operatorValue: {
    marginTop: 8,
    fontSize: 13,
    color: '#705b42',
  },
  pendingActionCard: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#fff5e5',
    borderWidth: 1,
    borderColor: '#e8cfa5',
    gap: 8,
  },
  pendingActionLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#8a5b1f',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  pendingActionTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: '#2e2117',
  },
  pendingActionMeta: {
    fontSize: 12,
    color: '#8a6c4a',
  },
  pendingActionHint: {
    fontSize: 13,
    lineHeight: 18,
    color: '#5d4a35',
  },
  pendingActionButtons: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  pendingPrimaryButton: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#2e6a57',
  },
  pendingPrimaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#f6f1e8',
  },
  pendingSecondaryButton: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#f0e1c8',
  },
  pendingSecondaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6d4f2f',
  },
  notificationRail: {
    padding: 15,
    borderRadius: 16,
    backgroundColor: '#fff7eb',
    borderWidth: 1,
    borderColor: '#e6d2b2',
    gap: 10,
  },
  notificationRailHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  notificationRailActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  notificationRailTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#7b4f1d',
    textTransform: 'uppercase',
    letterSpacing: 0.9,
  },
  notificationRailMeta: {
    fontSize: 12,
    color: '#8a6c4a',
  },
  notificationRailClear: {
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: '#f0e1c8',
  },
  notificationRailClearLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6d4f2f',
  },
  notificationCard: {
    gap: 8,
    padding: 13,
    borderRadius: 14,
    backgroundColor: '#fffdf8',
    borderWidth: 1,
    borderColor: '#efdfc4',
  },
  notificationOpen: {
    gap: 4,
  },
  notificationTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: '#2e2117',
  },
  notificationBody: {
    fontSize: 13,
    lineHeight: 18,
    color: '#5d4a35',
  },
  notificationMeta: {
    fontSize: 12,
    fontWeight: '700',
    color: '#8a6c4a',
  },
  notificationDismiss: {
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: '#f0e1c8',
  },
  notificationDismissLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6d4f2f',
  },
  logoutButton: {
    alignSelf: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 999,
    backgroundColor: '#2d2218',
  },
  logoutLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#f7f3ea',
  },
  content: {
    padding: 16,
    paddingBottom: 28,
    gap: 16,
  },
  contentScroll: {
    flex: 1,
  },
  conversationContent: {
    flex: 1,
    paddingHorizontal: 6,
    paddingTop: 6,
    paddingBottom: 0,
    backgroundColor: '#3f3f3f',
  },
  sessionContent: {
    flex: 1,
    padding: 16,
  },
});
