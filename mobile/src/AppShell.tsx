import { useMemo } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { usePendingActionCenter } from './actions/PendingActionCenter';
import { useNotificationCenter } from './notifications/NotificationCenter';
import { BillingScreen } from './screens/BillingScreen';
import { ConversationScreen } from './screens/ConversationScreen';
import { InboxScreen } from './screens/InboxScreen';
import { NotificationsScreen } from './screens/NotificationsScreen';
import { ProjectsScreen } from './screens/ProjectsScreen';
import { SessionScreen } from './screens/SessionScreen';

export type AppTabId = 'inbox' | 'notifications' | 'projects' | 'session' | 'conversation' | 'billing';

type AppShellProps = {
  activeTab: AppTabId;
  onChangeTab: (tab: AppTabId) => void;
  selectedSessionId: number | null;
  onSelectSession: (sessionId: number | null) => void;
  operatorName: string;
  onLogout: () => void;
};

const TABS: Array<{ id: AppTabId; label: string }> = [
  { id: 'inbox', label: 'Inbox' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'projects', label: 'Projects' },
  { id: 'session', label: 'Session' },
  { id: 'billing', label: 'Billing' },
];

export function AppShell({
  activeTab,
  onChangeTab,
  selectedSessionId,
  onSelectSession,
  operatorName,
  onLogout,
}: AppShellProps) {
  const { notifications, routeToNotification, dismissNotification, clearNotifications } = useNotificationCenter();
  const { pendingAction, clearPendingAction } = usePendingActionCenter();

  const sessionHint = useMemo(() => {
    if (selectedSessionId == null) {
      return 'Select a session from Inbox or Projects.';
    }
    return `Session ${selectedSessionId}`;
  }, [selectedSessionId]);

  const openSession = (sessionId: number) => {
    onSelectSession(sessionId);
    onChangeTab('session');
  };

  const openConversation = () => {
    onChangeTab('conversation');
  };

  const openNotificationById = (notificationId: string) => {
    const notification = notifications.find((entry) => entry.id === notificationId);
    if (!notification) {
      return;
    }
    const route = routeToNotification(notification);
    if (route?.sessionId != null) {
      onSelectSession(route.sessionId);
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

  return (
    <View style={styles.container}>
      {activeTab !== 'conversation' ? (
        <View style={styles.header}>
          <View style={styles.headerRow}>
            <View style={styles.headerCopy}>
              <Text style={styles.eyebrow}>Atenea Mobile</Text>
              <Text style={styles.title}>Operator Console</Text>
              <Text style={styles.subtitle}>
                Native shell for full mobile operation over session-first backend contracts.
              </Text>
            </View>
            <Pressable onPress={onLogout} style={styles.logoutButton}>
              <Text style={styles.logoutLabel}>Sign out</Text>
            </Pressable>
          </View>
        </View>
      ) : null}

      {activeTab !== 'conversation' ? (
        <>
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

          <View style={styles.sessionHintCard}>
            <Text style={styles.sessionHintLabel}>Current session</Text>
            <Text style={styles.sessionHintValue}>{sessionHint}</Text>
            <Text style={styles.operatorValue}>Operator: {operatorName}</Text>
          </View>

          {pendingAction ? (
            <View style={styles.pendingActionCard}>
              <Text style={styles.pendingActionLabel}>Pending recovery</Text>
              <Text style={styles.pendingActionTitle}>{pendingAction.label}</Text>
              <Text style={styles.pendingActionMeta}>
                Started {new Date(pendingAction.startedAt).toLocaleString()}
              </Text>
              <Text style={styles.pendingActionHint}>{pendingAction.recoveryHint}</Text>
              <View style={styles.pendingActionButtons}>
                <Pressable onPress={recoverPendingAction} style={styles.pendingPrimaryButton}>
                  <Text style={styles.pendingPrimaryLabel}>Open context</Text>
                </Pressable>
                <Pressable onPress={clearPendingAction} style={styles.pendingSecondaryButton}>
                  <Text style={styles.pendingSecondaryLabel}>Dismiss</Text>
                </Pressable>
              </View>
            </View>
          ) : null}

          {notifications.length > 0 ? (
            <View style={styles.notificationRail}>
              <View style={styles.notificationRailHeader}>
                <Text style={styles.notificationRailTitle}>Recent notifications</Text>
                <View style={styles.notificationRailActions}>
                  <Text style={styles.notificationRailMeta}>{notifications.length} stored</Text>
                  <Pressable onPress={clearNotifications} style={styles.notificationRailClear}>
                    <Text style={styles.notificationRailClearLabel}>Clear</Text>
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
                    <Text style={styles.notificationDismissLabel}>Dismiss</Text>
                  </Pressable>
                </View>
              ))}
            </View>
          ) : null}
        </>
      ) : null}

      {activeTab === 'conversation' ? (
        <View style={styles.conversationContent}>
          <ConversationScreen sessionId={selectedSessionId} onBackToSession={() => onChangeTab('session')} />
        </View>
      ) : (
        <ScrollView contentContainerStyle={styles.content}>
          {activeTab === 'inbox' ? (
            <InboxScreen onOpenSession={openSession} />
          ) : null}
          {activeTab === 'notifications' ? (
            <NotificationsScreen onOpenNotification={openNotificationById} />
          ) : null}
          {activeTab === 'projects' ? (
            <ProjectsScreen onOpenSession={openSession} />
          ) : null}
          {activeTab === 'session' ? (
            <SessionScreen sessionId={selectedSessionId} onOpenConversation={openConversation} />
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
    paddingTop: 12,
    paddingBottom: 16,
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
    lineHeight: 20,
    color: '#5d4a35',
  },
  tabBar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    paddingHorizontal: 16,
    paddingVertical: 14,
    backgroundColor: '#f8f3eb',
    borderBottomWidth: 1,
    borderBottomColor: '#e4d7c3',
  },
  tab: {
    paddingHorizontal: 14,
    paddingVertical: 10,
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
    marginHorizontal: 16,
    marginTop: 14,
    padding: 14,
    borderRadius: 16,
    backgroundColor: '#fffaf2',
    borderWidth: 1,
    borderColor: '#e6d8c0',
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
    marginHorizontal: 16,
    marginTop: 14,
    padding: 14,
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
    marginHorizontal: 16,
    marginTop: 14,
    padding: 14,
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
    padding: 12,
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
    gap: 16,
  },
  conversationContent: {
    flex: 1,
    paddingHorizontal: 6,
    paddingTop: 6,
    paddingBottom: 0,
    backgroundColor: '#3f3f3f',
  },
});
