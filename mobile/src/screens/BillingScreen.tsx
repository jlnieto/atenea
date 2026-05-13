import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fetchJson } from '../api/client';
import { BillingQueueResponse } from '../api/types';
import { labelBillingStatus, labelPullRequestStatus, toneBillingStatus, tonePullRequestStatus } from '../core/presentation';
import { Card } from '../components/Card';
import { IconActionLink } from '../components/IconActionLink';
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
    return <LoadingBlock label="Cargando cola de facturación..." />;
  }

  if (error) {
    return <Card title="Facturación no disponible" subtitle={error}><Text style={styles.meta}>Revisa la conectividad con el backend.</Text></Card>;
  }

  return (
    <View style={styles.container}>
      <Card title="Cola de facturación" subtitle="Baselines de pricing aprobados listos para seguimiento operativo.">
        <StatePill label={`${data?.items.length ?? 0} listas`} tone="warning" />
      </Card>
      {data?.items.map((item) => (
        <Card key={`${item.sessionId}-${item.deliverableId}`} title={item.projectName} subtitle={item.sessionTitle}>
          <View style={styles.row}>
            <Text style={styles.amount}>{item.recommendedPrice} {item.currency}</Text>
            <StatePill label={labelBillingStatus(item.billingStatus)} tone={toneBillingStatus(item.billingStatus)} />
          </View>
          <View style={styles.row}>
            <Text style={styles.meta}>Estado de facturación listo para seguimiento.</Text>
            <StatePill
              label={labelPullRequestStatus(item.pullRequestStatus)}
              tone={tonePullRequestStatus(item.pullRequestStatus)}
            />
          </View>
          <IconActionLink label={`Abrir sesión ${item.sessionId}`} icon="arrow-right" onPress={() => onOpenSession(item.sessionId)} />
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
