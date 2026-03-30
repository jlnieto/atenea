import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fetchJson } from '../api/client';
import { BillingQueueResponse } from '../api/types';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function BillingScreen({ onOpenSession }: { onOpenSession: (sessionId: number) => void }) {
  const { data, error, loading } = useRemoteResource(
    () => fetchJson<BillingQueueResponse>('/api/mobile/billing/queue?billingStatus=READY'),
    [],
    { refreshIntervalMs: 15000 }
  );

  if (loading) {
    return <LoadingBlock label="Loading billing queue..." />;
  }

  if (error) {
    return <Card title="Billing unavailable" subtitle={error}><Text style={styles.meta}>Check backend connectivity.</Text></Card>;
  }

  return (
    <View style={styles.container}>
      <Card title="Billing Queue" subtitle="Approved pricing baselines ready for operator follow-up.">
        <StatePill label={`${data?.items.length ?? 0} ready`} tone="warning" />
      </Card>
      {data?.items.map((item) => (
        <Card key={`${item.sessionId}-${item.deliverableId}`} title={item.projectName} subtitle={item.sessionTitle}>
          <View style={styles.row}>
            <Text style={styles.amount}>{item.recommendedPrice} {item.currency}</Text>
            <StatePill label={item.billingStatus} tone="warning" />
          </View>
          <Text style={styles.meta}>PR: {item.pullRequestStatus || 'n/a'}</Text>
          <Pressable onPress={() => onOpenSession(item.sessionId)}>
            <Text style={styles.link}>Open session {item.sessionId}</Text>
          </Pressable>
        </Card>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  amount: {
    fontSize: 18,
    fontWeight: '800',
    color: '#2d2218',
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
  },
});
