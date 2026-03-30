import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useNotificationCenter } from '../notifications/NotificationCenter';
import { Card } from '../components/Card';

export function NotificationsScreen({
  onOpenNotification,
}: {
  onOpenNotification: (notificationId: string) => void;
}) {
  const { notifications, dismissNotification, clearNotifications } = useNotificationCenter();

  return (
    <View style={styles.container}>
      <Card
        title="Notifications"
        subtitle="Recent operator signals persisted locally for this signed-in operator."
      >
        <View style={styles.headerRow}>
          <Text style={styles.meta}>{notifications.length} stored</Text>
          {notifications.length > 0 ? (
            <Pressable onPress={clearNotifications} style={styles.clearButton}>
              <Text style={styles.clearLabel}>Clear all</Text>
            </Pressable>
          ) : null}
        </View>
      </Card>

      {notifications.length === 0 ? (
        <Card title="No notifications" subtitle="Push and in-app notifications will appear here as they arrive.">
          <Text style={styles.meta}>This view is backed by local secure persistence.</Text>
        </Card>
      ) : null}

      {notifications.map((notification) => (
        <Card key={notification.id} title={notification.title} subtitle={notification.body || 'No extra details'}>
          <Text style={styles.meta}>
            {notification.payload?.type ?? 'PUSH'} · {new Date(notification.receivedAt).toLocaleString()}
          </Text>
          <View style={styles.actions}>
            <Pressable onPress={() => onOpenNotification(notification.id)} style={styles.primaryButton}>
              <Text style={styles.primaryLabel}>Open</Text>
            </Pressable>
            <Pressable onPress={() => dismissNotification(notification.id)} style={styles.secondaryButton}>
              <Text style={styles.secondaryLabel}>Dismiss</Text>
            </Pressable>
          </View>
        </Card>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  clearButton: {
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: '#f0e1c8',
  },
  clearLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6d4f2f',
  },
  primaryButton: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#2e6a57',
  },
  primaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#f6f1e8',
  },
  secondaryButton: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#f0e1c8',
  },
  secondaryLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6d4f2f',
  },
});
