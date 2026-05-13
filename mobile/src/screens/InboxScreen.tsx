import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { fetchJson } from '../api/client';
import { MobileInboxResponse } from '../api/types';
import { formatAbsoluteAndRelativeTime, formatDurationSince } from '../core/time';
import { Card } from '../components/Card';
import { IconActionLink } from '../components/IconActionLink';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function InboxScreen({
  onOpenSession,
  pendingResponses,
}: {
  onOpenSession: (sessionId: number) => void;
  pendingResponses: Array<{
    projectId: number;
    projectName: string;
    sessionId: number;
    sessionTitle: string;
    updatedAt: string | null;
  }>;
}) {
  const [now, setNow] = useState(() => Date.now());
  const { data, error, loading, reload } = useRemoteResource(
    () => fetchJson<MobileInboxResponse>('/api/mobile/inbox'),
    [],
    { refreshIntervalMs: 5000 }
  );

  useEffect(() => {
    if ((data?.summary.runInProgressCount ?? 0) === 0) {
      return undefined;
    }

    const intervalId = setInterval(() => {
      setNow(Date.now());
    }, 1000);

    return () => clearInterval(intervalId);
  }, [data?.summary.runInProgressCount]);

  if (loading) {
    return <LoadingBlock label="Cargando inbox móvil..." />;
  }

  if (error) {
    return (
      <Card title="Inbox no disponible" subtitle={error}>
        <IconActionLink label="Reintentar" icon="refresh" onPress={() => void reload()} />
      </Card>
    );
  }

  return (
    <View style={styles.container}>
      <Card title="Cola de atención" subtitle="Sesiones en curso, bloqueos de cierre y trabajo listo para facturar o rematar desde móvil.">
        <View style={styles.summaryRow}>
          <StatePill label={`${pendingResponses.length} nuevas`} tone="good" />
          <StatePill label={`${data?.summary.runInProgressCount ?? 0} en curso`} tone="info" />
          <StatePill label={`${data?.summary.closeBlockedCount ?? 0} bloqueadas`} tone="danger" />
          <StatePill label={`${data?.summary.billingReadyCount ?? 0} facturación`} tone="good" />
          <IconActionLink label="Actualizar" icon="refresh" onPress={() => void reload()} />
        </View>
        {pendingResponses.length > 0 ? (
          <View style={styles.responseSummary}>
            <Text style={styles.responseSummaryText}>
              Hay {pendingResponses.length} respuesta{pendingResponses.length === 1 ? '' : 's'} nueva{pendingResponses.length === 1 ? '' : 's'} pendiente{pendingResponses.length === 1 ? '' : 's'} de lectura.
            </Text>
            <Text style={styles.responseSummaryMeta}>
              Entra en la conversación o en la sesión para decidir el siguiente prompt.
            </Text>
          </View>
        ) : null}
        {(data?.summary.runInProgressCount ?? 0) > 0 ? (
          <View style={styles.runningSummary}>
            <Text style={styles.runningSummaryText}>
              Hay {data?.summary.runInProgressCount} ejecución{data?.summary.runInProgressCount === 1 ? '' : 'es'} en curso.
              Si tarda varios minutos, no implica fallo por sí mismo.
            </Text>
            <Text style={styles.runningSummaryMeta}>
              El inbox se refresca cada 5s mientras haya runs activos.
            </Text>
          </View>
        ) : null}
      </Card>

      {pendingResponses.map((item) => (
        <Card
          key={`response-${item.sessionId}`}
          title={`${item.projectName}: respuesta nueva`}
          subtitle={item.sessionTitle}
        >
          <View style={styles.itemRow}>
            <StatePill label="RESPUESTA NUEVA" tone="good" />
            <IconActionLink label="Abrir sesión" icon="arrow-right" onPress={() => onOpenSession(item.sessionId)} />
          </View>
          <View style={styles.readyItemPanel}>
            <Text style={styles.readyItemTitle}>Pendiente de lectura</Text>
            <Text style={styles.readyItemText}>
              {item.updatedAt
                ? `Codex ya respondió. Última actualización: ${formatAbsoluteAndRelativeTime(item.updatedAt, now)}.`
                : 'Codex ya dejó una respuesta nueva para revisar.'}
            </Text>
          </View>
        </Card>
      ))}

      {data?.items.map((item, index) => (
        <Card key={`${item.type}-${item.sessionId ?? index}`} title={item.title} subtitle={item.message}>
          <View style={styles.itemRow}>
            <StatePill
              label={inboxStatusLabel(item.type)}
              tone={inboxTone(item.type, item.severity)}
            />
            {item.type === 'RUN_IN_PROGRESS' && item.updatedAt ? (
              <StatePill
                label={`EN CURSO · ${formatDurationSince(item.updatedAt, now)}`}
                tone="info"
              />
            ) : null}
            {item.sessionId != null ? (
              <IconActionLink label="Abrir sesión" icon="arrow-right" onPress={() => onOpenSession(item.sessionId!)} />
            ) : null}
          </View>
          <Text style={styles.meta}>
            {item.projectName} {item.sessionTitle ? `· ${item.sessionTitle}` : ''}
          </Text>
          {renderInboxContext(item, now)}
          {item.action ? <Text style={styles.action}>Siguiente: {item.action}</Text> : null}
        </Card>
      ))}
    </View>
  );
}

function inboxTone(type: string, severity: string) {
  if (type === 'RUN_IN_PROGRESS' || type === 'PULL_REQUEST_OPEN') {
    return 'info' as const;
  }
  if (type === 'READY_TO_CLOSE' || type === 'BILLING_READY') {
    return 'good' as const;
  }
  if (type === 'CLOSE_BLOCKED') {
    return 'danger' as const;
  }
  return severity === 'warning' ? 'warning' as const : 'default' as const;
}

function inboxStatusLabel(type: string) {
  switch (type) {
    case 'RUN_IN_PROGRESS':
      return 'RUN ACTIVO';
    case 'CLOSE_BLOCKED':
      return 'CIERRE BLOQUEADO';
    case 'READY_TO_CLOSE':
      return 'LISTA PARA CERRAR';
    case 'PULL_REQUEST_OPEN':
      return 'PR ABIERTA';
    case 'BILLING_READY':
      return 'LISTA PARA FACTURAR';
    default:
      return type;
  }
}

function renderInboxContext(item: MobileInboxResponse['items'][number], now: number) {
  if (item.type === 'RUN_IN_PROGRESS') {
    return (
      <View style={styles.runningItemPanel}>
        <Text style={styles.runningItemText}>
          {item.updatedAt
            ? `Codex sigue trabajando desde hace ${formatDurationSince(item.updatedAt, now)}.`
            : 'Codex sigue trabajando en esta sesión.'}
        </Text>
        {item.updatedAt ? (
          <Text style={styles.runningItemMeta}>
            Último movimiento: {formatAbsoluteAndRelativeTime(item.updatedAt, now)}
          </Text>
        ) : null}
      </View>
    );
  }

  if (item.type === 'CLOSE_BLOCKED') {
    return (
      <View style={styles.blockedItemPanel}>
        <Text style={styles.blockedItemTitle}>No se puede cerrar todavía</Text>
        <Text style={styles.blockedItemText}>
          Hace falta resolver este bloqueo antes de reconciliar el repositorio y cerrar la sesión.
        </Text>
      </View>
    );
  }

  if (item.type === 'READY_TO_CLOSE') {
    return (
      <View style={styles.readyItemPanel}>
        <Text style={styles.readyItemTitle}>Lista para rematar</Text>
        <Text style={styles.readyItemText}>
          La pull request ya está mergeada. Falta el cierre fuerte y la reconciliación final del repo.
        </Text>
      </View>
    );
  }

  if (item.type === 'BILLING_READY') {
    return (
      <View style={styles.readyItemPanel}>
        <Text style={styles.readyItemTitle}>Lista para facturar</Text>
        <Text style={styles.readyItemText}>
          El presupuesto aprobado ya puede pasar a facturación sin esperar más trabajo técnico.
        </Text>
      </View>
    );
  }

  return null;
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
  runningSummary: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#fff3cf',
    borderWidth: 1,
    borderColor: '#efd28f',
  },
  runningSummaryText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#6a4914',
  },
  runningSummaryMeta: {
    fontSize: 12,
    color: '#8b6d34',
  },
  responseSummary: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#e2f3e8',
    borderWidth: 1,
    borderColor: '#b7d8c0',
  },
  responseSummaryText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#21573c',
  },
  responseSummaryMeta: {
    fontSize: 12,
    color: '#476d55',
  },
  itemRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 8,
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  runningItemPanel: {
    gap: 4,
  },
  runningItemText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '700',
    color: '#6a4914',
  },
  runningItemMeta: {
    fontSize: 12,
    color: '#8b6d34',
  },
  blockedItemPanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#fde8e3',
    borderWidth: 1,
    borderColor: '#e6b0a4',
  },
  blockedItemTitle: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '800',
    color: '#7b2517',
  },
  blockedItemText: {
    fontSize: 12,
    lineHeight: 17,
    color: '#975042',
  },
  readyItemPanel: {
    gap: 4,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: '#e2f3e8',
    borderWidth: 1,
    borderColor: '#b7d8c0',
  },
  readyItemTitle: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '800',
    color: '#21573c',
  },
  readyItemText: {
    fontSize: 12,
    lineHeight: 17,
    color: '#476d55',
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
