import { useMemo, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { fetchJson } from '../api/client';
import {
  CoreCommandResponse,
  MobileSessionSummary,
  SessionDeliverablesView,
} from '../api/types';
import { usePendingActionCenter } from '../actions/PendingActionCenter';
import { confirmAction } from '../actions/confirm';
import {
  buildApproveDeliverableCommand,
  buildCloseSessionCommand,
  buildGenerateDeliverableCommand,
  buildMarkPriceEstimateBilledCommand,
  buildPublishCommand,
  buildSyncPullRequestCommand,
} from '../core/phrases';
import { RunCoreCommandOptions } from '../core/useCoreCommandCenter';
import { ActionButton } from '../components/ActionButton';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function SessionScreen({
  projectId,
  sessionId,
  onOpenConversation,
  onOpenCore,
  onOpenSession,
  onRunCommand,
}: {
  projectId: number | null;
  sessionId: number | null;
  onOpenConversation: () => void;
  onOpenCore: () => void;
  onOpenSession: (sessionId: number) => void;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<CoreCommandResponse>;
}) {
  const { data, error, loading, reload } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<MobileSessionSummary>(`/api/mobile/sessions/${sessionId}/summary`),
    [sessionId],
    { refreshIntervalMs: 5000 }
  );
  const { data: deliverables, reload: reloadDeliverables } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<SessionDeliverablesView>(`/api/mobile/sessions/${sessionId}/deliverables`),
    [sessionId],
    { refreshIntervalMs: 5000 }
  );
  const [billingReference, setBillingReference] = useState('');
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [mutationStatus, setMutationStatus] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const { pendingAction: recoveredPendingAction, startPendingAction, clearPendingAction } = usePendingActionCenter();
  const pendingApprovalIds = useMemo(
    () =>
      new Set(
        (deliverables?.deliverables ?? [])
          .filter((deliverable) => !deliverable.approved)
          .map((deliverable) => deliverable.id)
      ),
    [deliverables]
  );

  const refreshAll = async () => {
    await Promise.all([reload(), reloadDeliverables()]);
  };

  const runCoreMutation = async (input: string) => {
    const response = await onRunCommand({
      input,
      projectId,
      workSessionId: sessionId,
      onSucceeded: (commandResponse) => {
        if (commandResponse.result?.targetId != null) {
          onOpenSession(commandResponse.result.targetId);
        }
      },
    });
    onOpenCore();
    if (response.status === 'NEEDS_CONFIRMATION') {
      setMutationStatus('Atenea Core está esperando confirmación en la pestaña Core.');
      return false;
    }
    if (response.status === 'NEEDS_CLARIFICATION') {
      setMutationStatus('Atenea Core necesita una aclaración en la pestaña Core.');
      return false;
    }
    setMutationStatus('Acción enviada a Atenea Core.');
    return true;
  };

  const runAction = async (label: string, recoveryHint: string, action: () => Promise<boolean | void>) => {
    setPendingAction(label);
    startPendingAction({
      label,
      scope: 'session',
      sessionId: sessionId ?? undefined,
      startedAt: new Date().toISOString(),
      recoveryHint,
    });
    setMutationError(null);
    setMutationStatus(null);
    try {
      const completed = await action();
      if (completed !== false) {
        await refreshAll();
        setMutationStatus(`Operación completada: ${label}.`);
      }
    } catch (actionError) {
      setMutationError(actionError instanceof Error ? actionError.message : `${label} failed`);
    } finally {
      clearPendingAction();
      setPendingAction(null);
    }
  };

  if (sessionId == null) {
    return <Card title="Ninguna sesión seleccionada" subtitle="Elige una sesión desde Inbox o Projects para revisarla aquí." ><Text style={styles.meta}>Esta pantalla usa el contrato de resumen móvil de sesión.</Text></Card>;
  }

  if (loading) {
    return <LoadingBlock label={`Cargando sesión ${sessionId}...`} />;
  }

  if (error || data == null) {
    return <Card title="Sesión no disponible" subtitle={error || 'No se han recibido datos de la sesión.'}><Text style={styles.meta}>Revisa la conectividad con el backend.</Text></Card>;
  }

  const session = data.conversation.view.session;
  const actions = data.actions;
  const insights = data.insights;
  const pending = (name: string) => pendingAction === name;
  const sessionPendingRecovery = recoveredPendingAction?.scope === 'session'
    && recoveredPendingAction.sessionId === sessionId
    ? recoveredPendingAction
    : null;

  const runConfirmedAction = async (
    label: string,
    confirmationTitle: string,
    confirmationMessage: string,
    recoveryHint: string,
    action: () => Promise<void>
  ) => {
    const confirmed = await confirmAction(confirmationTitle, confirmationMessage);
    if (!confirmed) {
      return;
    }
    await runAction(label, recoveryHint, action);
  };

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={styles.container}
      keyboardShouldPersistTaps="handled"
    >
      <Card title={session.title} subtitle={`Session ${session.id}`}>
        <View style={styles.row}>
          <StatePill label={session.status} />
          {session.pullRequestStatus ? <StatePill label={session.pullRequestStatus} /> : null}
        </View>
        <View style={styles.headerRow}>
          <Text style={[styles.meta, styles.headerMeta]}>
            Usa esta vista para revisar estado. Las operaciones de sesión pasan por Atenea Core.
          </Text>
          <View style={styles.headerActions}>
            <Pressable onPress={onOpenConversation} style={styles.headerLinkButton}>
              <Text style={styles.link}>Abrir conversación</Text>
            </Pressable>
            <Pressable onPress={onOpenCore} style={styles.headerLinkButton}>
              <Text style={styles.link}>Abrir Core</Text>
            </Pressable>
          </View>
        </View>
        {session.closeBlockedState ? (
          <>
            <Text style={styles.meta}>Bloqueado: {session.closeBlockedState}</Text>
            {session.closeBlockedReason ? <Text style={styles.meta}>{session.closeBlockedReason}</Text> : null}
            {session.closeBlockedAction ? <Text style={styles.action}>Siguiente: {session.closeBlockedAction}</Text> : null}
            <Text style={styles.meta}>
              Reintento: {session.closeRetryable ? 'seguro tras desbloquear' : 'requiere recuperación manual primero'}
            </Text>
          </>
        ) : null}
        {insights ? (
          <View style={styles.insightsBox}>
            <View style={styles.insightsHeader}>
              <Text style={styles.insightsTitle}>Resumen de sesión</Text>
              <StatePill
                label={blockerLabel(insights.currentBlocker.category)}
                tone={blockerTone(insights.currentBlocker.category)}
              />
            </View>
            <InsightRow label="Último avance" value={insights.latestProgress} />
            <InsightRow label="Bloqueo actual" value={insights.currentBlocker.summary} />
            <InsightRow label="Siguiente paso" value={insights.nextStepRecommended} />
          </View>
        ) : null}
        {data.conversation.view.lastAgentResponse ? (
          <Text style={styles.response}>{data.conversation.view.lastAgentResponse}</Text>
        ) : null}
        {sessionPendingRecovery ? (
          <View style={styles.recoveryBox}>
            <Text style={styles.recoveryTitle}>Acción recuperada: {sessionPendingRecovery.label}</Text>
            <Text style={styles.meta}>{sessionPendingRecovery.recoveryHint}</Text>
            <Pressable onPress={clearPendingAction}>
              <Text style={styles.link}>Ocultar aviso</Text>
            </Pressable>
          </View>
        ) : null}
      </Card>

      <Card title="Operaciones vía Core" subtitle="Estas acciones se envían a Atenea Core para mantener confirmaciones, aclaraciones e historial en un solo sitio.">
        <View style={styles.headerRow}>
          <Text style={[styles.meta, styles.headerMeta]}>La sesión se actualiza automáticamente cada 5s.</Text>
          <Pressable onPress={() => void refreshAll()} style={styles.headerLinkButton}>
            <Text style={styles.link}>Actualizar</Text>
          </Pressable>
        </View>
        <View style={styles.actions}>
          <ActionButton
            label="Abrir conversación"
            disabled={!actions.canCreateTurn && !data.conversation.view.lastAgentResponse}
            onPress={onOpenConversation}
          />
          <ActionButton
            label="Abrir Core"
            onPress={onOpenCore}
          />
          <ActionButton
            label={pending('Sync PR') ? 'Enviando sync...' : 'Sync PR vía Core'}
            disabled={!actions.canSyncPullRequest || pendingAction != null}
            onPress={() =>
              void runAction(
                'Sync PR',
                'Actualiza la sesión antes de reintentar la sincronización de PR para no operar con estado obsoleto.',
                async () => {
                  await runCoreMutation(buildSyncPullRequestCommand());
                }
              )
            }
          />
          <ActionButton
            label={pending('Close') ? 'Enviando cierre...' : 'Cerrar vía Core'}
            disabled={!actions.canClose || pendingAction != null}
            onPress={() =>
              void runConfirmedAction(
                'Close',
                '¿Cerrar sesión?',
                'Usa esto sólo cuando la pull request esté fusionada y el repositorio listo para reconciliar contra la rama base.',
                'Actualiza la sesión antes de reintentar el cierre para ver el último estado de merge y repositorio.',
                async () => {
                  await runCoreMutation(buildCloseSessionCommand());
                }
              )
            }
          />
        </View>
        {pendingAction ? (
          <Text style={styles.pendingNotice}>
            Acción en curso: {pendingAction}. Si cae la conectividad, vuelve a abrir la sesión y actualiza estado antes de reintentar.
          </Text>
        ) : null}
        <ActionButton
          label={pending('Publish') ? 'Enviando publish...' : 'Publicar vía Core'}
          disabled={!actions.canPublish || pendingAction != null}
          onPress={() =>
            void runConfirmedAction(
              'Publish',
              '¿Publicar en pull request?',
              'Esto preparará los cambios, creará commit, empujará la rama de sesión y abrirá o actualizará el flujo de entrega en GitHub.',
              'Actualiza la sesión antes de reintentar publish para confirmar si la rama o la pull request ya se actualizaron.',
              async () => {
                await runCoreMutation(buildPublishCommand());
              }
            )
          }
        />
        {mutationStatus ? <Text style={styles.success}>{mutationStatus}</Text> : null}
        {mutationError ? <Text style={styles.error}>{mutationError}</Text> : null}
      </Card>
      <Card title="Entregables" subtitle="Consulta entregables, aprobaciones y pricing base. La generación se envía vía Core.">
        <View style={styles.actions}>
          {['WORK_TICKET', 'WORK_BREAKDOWN', 'PRICE_ESTIMATE'].map((type) => (
            <ActionButton
              key={type}
              label={pending(`Generate ${type}`) ? 'Enviando...' : `${type} vía Core`}
              disabled={!actions.canGenerateDeliverables || pendingAction != null}
              onPress={() =>
                void runConfirmedAction(
                  `Generate ${type}`,
                  '¿Generar entregable?',
                  `Esto pedirá a Atenea generar el último borrador de ${type} para esta sesión.`,
                  `Actualiza entregables antes de reintentar ${type} para no crear confusión sobre el último borrador.`,
                  async () => {
                    await runCoreMutation(buildGenerateDeliverableCommand(type));
                  }
                )
              }
            />
          ))}
        </View>
        {(deliverables?.deliverables ?? []).map((deliverable) => (
          <View key={deliverable.id} style={styles.deliverable}>
            <View style={styles.deliverableHeader}>
              <Text style={styles.deliverableTitle}>
                {deliverable.type} v{deliverable.version}
              </Text>
              <StatePill
                label={deliverable.approved ? 'APPROVED' : deliverable.status}
                tone={deliverable.approved ? 'good' : 'warning'}
              />
            </View>
            <Text style={styles.meta}>{deliverable.title || deliverable.preview || 'Sin vista previa'}</Text>
            {pendingApprovalIds.has(deliverable.id) && actions.canApproveDeliverables ? (
              <Pressable
                onPress={() =>
                  void runConfirmedAction(
                    `Approve ${deliverable.id}`,
                    '¿Aprobar entregable?',
                    `Esto marcará ${deliverable.type} v${deliverable.version} como baseline aprobado de la sesión.`,
                    'Actualiza entregables antes de reintentar la aprobación para confirmar qué versión es la última baseline aprobada.',
                    async () => {
                      await runCoreMutation(buildApproveDeliverableCommand(deliverable.id));
                    }
                  )
                }
              >
                <Text style={styles.link}>Aprobar entregable</Text>
              </Pressable>
            ) : null}
          </View>
        ))}
        {data.approvedPriceEstimate ? (
          <>
            <View style={styles.priceBox}>
              <Text style={styles.price}>
                {data.approvedPriceEstimate.recommendedPrice} {data.approvedPriceEstimate.currency}
              </Text>
              <StatePill
                label={data.approvedPriceEstimate.billingStatus}
                tone={data.approvedPriceEstimate.billingStatus === 'BILLED' ? 'good' : 'warning'}
              />
            </View>
            {data.approvedPriceEstimate.billingReference ? (
              <Text style={styles.meta}>
                Referencia de facturación: {data.approvedPriceEstimate.billingReference}
              </Text>
            ) : null}
            {actions.canMarkApprovedPriceEstimateBilled ? (
              <>
                <TextInput
                  value={billingReference}
                  onChangeText={setBillingReference}
                  placeholder="Referencia de facturación"
                  placeholderTextColor="#8b7c6b"
                  style={styles.input}
                  autoCapitalize="characters"
                  autoCorrect={false}
                />
                <ActionButton
                  label={pending('Mark billed') ? 'Marcando...' : 'Marcar facturado'}
                  disabled={!billingReference.trim() || pendingAction != null}
                  onPress={() =>
                    void runConfirmedAction(
                      'Mark billed',
                      '¿Marcar como facturado el presupuesto aprobado?',
                      `Esto guardará la referencia ${billingReference.trim()} para el baseline de pricing aprobado.`,
                      'Actualiza la sesión antes de reintentar facturación para verificar si el presupuesto aprobado ya quedó marcado.',
                      async () => {
                        await runCoreMutation(buildMarkPriceEstimateBilledCommand(
                          data.approvedPriceEstimate!.deliverableId,
                          billingReference.trim()
                        ));
                        setBillingReference('');
                      }
                    )
                  }
                />
              </>
            ) : null}
          </>
        ) : (
          <Text style={styles.meta}>No hay baseline de pricing aprobado.</Text>
        )}
      </Card>
    </ScrollView>
  );
}

function InsightRow({
  label,
  value,
}: {
  label: string;
  value: string | null | undefined;
}) {
  if (!value) {
    return null;
  }
  return (
    <View style={styles.insightRow}>
      <Text style={styles.insightLabel}>{label}</Text>
      <Text style={styles.insightValue}>{value}</Text>
    </View>
  );
}

function blockerTone(category: 'NONE' | 'TECHNICAL' | 'BUSINESS') {
  switch (category) {
    case 'NONE':
      return 'good' as const;
    case 'BUSINESS':
      return 'warning' as const;
    case 'TECHNICAL':
    default:
      return 'danger' as const;
  }
}

function blockerLabel(category: 'NONE' | 'TECHNICAL' | 'BUSINESS') {
  switch (category) {
    case 'NONE':
      return 'Sin bloqueo';
    case 'BUSINESS':
      return 'Bloqueo negocio';
    case 'TECHNICAL':
    default:
      return 'Bloqueo técnico';
  }
}

const styles = StyleSheet.create({
  scroll: {
    flex: 1,
  },
  container: {
    gap: 14,
    paddingBottom: 28,
  },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  meta: {
    fontSize: 13,
    lineHeight: 18,
    color: '#705b42',
  },
  action: {
    fontSize: 13,
    fontWeight: '700',
    color: '#2d2218',
  },
  response: {
    fontSize: 14,
    lineHeight: 23,
    color: '#2d2218',
  },
  insightsBox: {
    gap: 10,
    padding: 12,
    borderRadius: 14,
    backgroundColor: '#fff5e7',
    borderWidth: 1,
    borderColor: '#ead7b8',
  },
  insightsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 8,
    flexWrap: 'wrap',
  },
  insightsTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: '#2d2218',
  },
  insightRow: {
    gap: 4,
  },
  insightLabel: {
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 0.3,
    textTransform: 'uppercase',
    color: '#7a6244',
  },
  insightValue: {
    fontSize: 14,
    lineHeight: 21,
    color: '#2d2218',
  },
  recoveryBox: {
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
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
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
  textArea: {
    minHeight: 90,
    textAlignVertical: 'top',
  },
  success: {
    fontSize: 13,
    fontWeight: '700',
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
  turn: {
    gap: 6,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#eee4d4',
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    flexWrap: 'wrap',
    gap: 8,
  },
  headerMeta: {
    flex: 1,
    minWidth: 180,
  },
  headerLinkButton: {
    alignSelf: 'flex-start',
  },
  headerActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
  },
  turnText: {
    fontSize: 14,
    lineHeight: 20,
    color: '#2d2218',
  },
  event: {
    gap: 6,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#eee4d4',
  },
  deliverable: {
    gap: 4,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#eee4d4',
  },
  deliverableHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 8,
  },
  deliverableTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: '#2d2218',
  },
  priceBox: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: 10,
  },
  price: {
    fontSize: 18,
    fontWeight: '800',
    color: '#2e2117',
  },
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
  },
});
