import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fetchJson } from '../api/client';
import { MobileInboxResponse } from '../api/types';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function InboxScreen({ onOpenSession }: { onOpenSession: (sessionId: number) => void }) {
  const { data, error, loading, reload } = useRemoteResource(
    () => fetchJson<MobileInboxResponse>('/api/mobile/inbox'),
    [],
    { refreshIntervalMs: 10000 }
  );

  if (loading) {
    return <LoadingBlock label="Loading mobile inbox..." />;
  }

  if (error) {
    return (
      <Card title="Inbox unavailable" subtitle={error}>
        <Pressable onPress={() => void reload()}>
          <Text style={styles.reload}>Retry</Text>
        </Pressable>
      </Card>
    );
  }

  return (
    <View style={styles.container}>
      <Card title="Attention Queue" subtitle="Runs, close blocks and billing-ready items that deserve immediate mobile attention.">
        <View style={styles.summaryRow}>
          <StatePill label={`${data?.summary.runInProgressCount ?? 0} running`} />
          <StatePill label={`${data?.summary.closeBlockedCount ?? 0} blocked`} tone="warning" />
          <StatePill label={`${data?.summary.billingReadyCount ?? 0} billing`} tone="good" />
          <Pressable onPress={() => void reload()}>
            <Text style={styles.link}>Refresh</Text>
          </Pressable>
        </View>
      </Card>

      {data?.items.map((item, index) => (
        <Card key={`${item.type}-${item.sessionId ?? index}`} title={item.title} subtitle={item.message}>
          <View style={styles.itemRow}>
            <StatePill
              label={item.type}
              tone={item.severity === 'warning' ? 'warning' : 'default'}
            />
            {item.sessionId != null ? (
              <Pressable onPress={() => onOpenSession(item.sessionId!)}>
                <Text style={styles.link}>Open session</Text>
              </Pressable>
            ) : null}
          </View>
          <Text style={styles.meta}>
            {item.projectName} {item.sessionTitle ? `· ${item.sessionTitle}` : ''}
          </Text>
          {item.action ? <Text style={styles.action}>Next: {item.action}</Text> : null}
        </Card>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
  },
  summaryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  itemRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  action: {
    fontSize: 13,
    fontWeight: '700',
    color: '#2e2117',
  },
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
  },
  reload: {
    fontSize: 14,
    fontWeight: '800',
    color: '#2e6a57',
  },
});
