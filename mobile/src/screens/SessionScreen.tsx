import { useMemo, useState } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { fetchJson, postJson } from '../api/client';
import {
  MarkPriceEstimateBilledRequest,
  MobileSessionSummary,
  SessionDeliverablesView,
} from '../api/types';
import { ActionButton } from '../components/ActionButton';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

export function SessionScreen({
  sessionId,
  onOpenConversation,
}: {
  sessionId: number | null;
  onOpenConversation: () => void;
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

  const runAction = async (label: string, action: () => Promise<void>) => {
    setPendingAction(label);
    setMutationError(null);
    setMutationStatus(null);
    try {
      await action();
      await refreshAll();
      setMutationStatus(`${label} completed.`);
    } catch (actionError) {
      setMutationError(actionError instanceof Error ? actionError.message : `${label} failed`);
    } finally {
      setPendingAction(null);
    }
  };

  if (sessionId == null) {
    return <Card title="No session selected" subtitle="Choose a session from Inbox or Projects to operate it from mobile." ><Text style={styles.meta}>This screen is wired to the mobile session summary contract.</Text></Card>;
  }

  if (loading) {
    return <LoadingBlock label={`Loading session ${sessionId}...`} />;
  }

  if (error || data == null) {
    return <Card title="Session unavailable" subtitle={error || 'No session data returned.'}><Text style={styles.meta}>Check backend connectivity.</Text></Card>;
  }

  const session = data.conversation.view.session;
  const actions = data.actions;
  const pending = (name: string) => pendingAction === name;

  return (
    <View style={styles.container}>
      <Card title={session.title} subtitle={`Session ${session.id}`}>
        <View style={styles.row}>
          <StatePill label={session.status} />
          {session.pullRequestStatus ? <StatePill label={session.pullRequestStatus} /> : null}
        </View>
        <View style={styles.headerRow}>
          <Text style={[styles.meta, styles.headerMeta]}>
            Conversation, turns and Codex replies now live in a dedicated workspace.
          </Text>
          <Pressable onPress={onOpenConversation} style={styles.headerLinkButton}>
            <Text style={styles.link}>Open conversation</Text>
          </Pressable>
        </View>
        {session.closeBlockedState ? (
          <>
            <Text style={styles.meta}>Blocked: {session.closeBlockedState}</Text>
            {session.closeBlockedReason ? <Text style={styles.meta}>{session.closeBlockedReason}</Text> : null}
            {session.closeBlockedAction ? <Text style={styles.action}>Next: {session.closeBlockedAction}</Text> : null}
          </>
        ) : null}
        {data.conversation.view.lastAgentResponse ? (
          <Text style={styles.response}>{data.conversation.view.lastAgentResponse}</Text>
        ) : null}
      </Card>

      <Card title="Delivery Actions" subtitle="Operate publish, sync and close from the session control view.">
        <View style={styles.headerRow}>
          <Text style={[styles.meta, styles.headerMeta]}>Session refreshes automatically every 5s.</Text>
          <Pressable onPress={() => void refreshAll()} style={styles.headerLinkButton}>
            <Text style={styles.link}>Refresh</Text>
          </Pressable>
        </View>
        <View style={styles.actions}>
          <ActionButton
            label="Open conversation"
            disabled={!actions.canCreateTurn && !data.conversation.view.lastAgentResponse}
            onPress={onOpenConversation}
          />
          <ActionButton
            label={pending('Sync PR') ? 'Syncing...' : 'Sync PR'}
            disabled={!actions.canSyncPullRequest || pendingAction != null}
            onPress={() =>
              void runAction('Sync PR', async () => {
                await postJson(`/api/mobile/sessions/${sessionId}/pull-request/sync`);
              })
            }
          />
          <ActionButton
            label={pending('Close') ? 'Closing...' : 'Close'}
            disabled={!actions.canClose || pendingAction != null}
            onPress={() =>
              void runAction('Close', async () => {
                await postJson(`/api/mobile/sessions/${sessionId}/close`);
              })
            }
          />
        </View>
        <ActionButton
          label={pending('Publish') ? 'Publishing...' : 'Publish'}
          disabled={!actions.canPublish || pendingAction != null}
          onPress={() =>
            void runAction('Publish', async () => {
              await postJson(`/api/mobile/sessions/${sessionId}/publish`);
            })
          }
        />
        {mutationStatus ? <Text style={styles.success}>{mutationStatus}</Text> : null}
        {mutationError ? <Text style={styles.error}>{mutationError}</Text> : null}
      </Card>
      <Card title="Deliverables" subtitle="Latest deliverables, approvals and pricing baseline">
        <View style={styles.actions}>
          {['WORK_TICKET', 'WORK_BREAKDOWN', 'PRICE_ESTIMATE'].map((type) => (
            <ActionButton
              key={type}
              label={pending(`Generate ${type}`) ? 'Generating...' : type}
              disabled={!actions.canGenerateDeliverables || pendingAction != null}
              onPress={() =>
                void runAction(`Generate ${type}`, async () => {
                  await postJson(`/api/mobile/sessions/${sessionId}/deliverables/${type}/generate`);
                })
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
            <Text style={styles.meta}>{deliverable.title || deliverable.preview || 'No preview'}</Text>
            {pendingApprovalIds.has(deliverable.id) && actions.canApproveDeliverables ? (
              <Pressable
                onPress={() =>
                  void runAction(`Approve ${deliverable.id}`, async () => {
                    await postJson(`/api/mobile/sessions/${sessionId}/deliverables/${deliverable.id}/approve`);
                  })
                }
              >
                <Text style={styles.link}>Approve deliverable</Text>
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
                Billing reference: {data.approvedPriceEstimate.billingReference}
              </Text>
            ) : null}
            {actions.canMarkApprovedPriceEstimateBilled ? (
              <>
                <TextInput
                  value={billingReference}
                  onChangeText={setBillingReference}
                  placeholder="Billing reference"
                  placeholderTextColor="#8b7c6b"
                  style={styles.input}
                  autoCapitalize="characters"
                  autoCorrect={false}
                />
                <ActionButton
                  label={pending('Mark billed') ? 'Marking...' : 'Mark billed'}
                  disabled={!billingReference.trim() || pendingAction != null}
                  onPress={() =>
                    void runAction('Mark billed', async () => {
                      await postJson<unknown, MarkPriceEstimateBilledRequest>(
                        `/api/mobile/sessions/${sessionId}/deliverables/${data.approvedPriceEstimate!.deliverableId}/billing/mark-billed`,
                        { billingReference: billingReference.trim() }
                      );
                      setBillingReference('');
                    })
                  }
                />
              </>
            ) : null}
          </>
        ) : (
          <Text style={styles.meta}>No approved pricing baseline.</Text>
        )}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
  },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  meta: {
    fontSize: 13,
    color: '#705b42',
  },
  action: {
    fontSize: 13,
    fontWeight: '700',
    color: '#2d2218',
  },
  response: {
    fontSize: 14,
    lineHeight: 22,
    color: '#2d2218',
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
    paddingVertical: 10,
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
