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
  CoreCommandResponse,
  MarkPriceEstimateBilledRequest,
  MobileSessionSummary,
  SessionDeliverablesView,
} from '../api/types';
import { usePendingActionCenter } from '../actions/PendingActionCenter';
import { confirmAction } from '../actions/confirm';
import {
  buildCloseSessionCommand,
  buildGenerateDeliverableCommand,
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
  onOpenSession,
  onRunCommand,
}: {
  projectId: number | null;
  sessionId: number | null;
  onOpenConversation: () => void;
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
    if (response.status === 'NEEDS_CONFIRMATION') {
      setMutationStatus('Atenea Core is waiting for confirmation in the Core tab.');
      return false;
    }
    if (response.status === 'NEEDS_CLARIFICATION') {
      setMutationStatus('Atenea Core needs clarification in the Core tab.');
      return false;
    }
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
        setMutationStatus(`${label} completed.`);
      }
    } catch (actionError) {
      setMutationError(actionError instanceof Error ? actionError.message : `${label} failed`);
    } finally {
      clearPendingAction();
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
            <Text style={styles.meta}>
              Retry guidance: {session.closeRetryable ? 'safe to retry after unblocking' : 'manual recovery required first'}
            </Text>
          </>
        ) : null}
        {data.conversation.view.lastAgentResponse ? (
          <Text style={styles.response}>{data.conversation.view.lastAgentResponse}</Text>
        ) : null}
        {sessionPendingRecovery ? (
          <View style={styles.recoveryBox}>
            <Text style={styles.recoveryTitle}>Recovered pending action: {sessionPendingRecovery.label}</Text>
            <Text style={styles.meta}>{sessionPendingRecovery.recoveryHint}</Text>
            <Pressable onPress={clearPendingAction}>
              <Text style={styles.link}>Dismiss recovery notice</Text>
            </Pressable>
          </View>
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
              void runAction(
                'Sync PR',
                'Refresh the session before retrying pull request synchronization so you do not act on stale PR state.',
                async () => {
                  await runCoreMutation(buildSyncPullRequestCommand());
                }
              )
            }
          />
          <ActionButton
            label={pending('Close') ? 'Closing...' : 'Close'}
            disabled={!actions.canClose || pendingAction != null}
            onPress={() =>
              void runConfirmedAction(
                'Close',
                'Close session?',
                'Use this only after the pull request is merged and the repository is ready to reconcile back to the base branch.',
                'Refresh the session before retrying close so the latest merge and repository state are visible.',
                async () => {
                  await runCoreMutation(buildCloseSessionCommand());
                }
              )
            }
          />
        </View>
        {pendingAction ? (
          <Text style={styles.pendingNotice}>
            Action in progress: {pendingAction}. If connectivity drops, reopen the session and refresh state before retrying.
          </Text>
        ) : null}
        <ActionButton
          label={pending('Publish') ? 'Publishing...' : 'Publish'}
          disabled={!actions.canPublish || pendingAction != null}
          onPress={() =>
            void runConfirmedAction(
              'Publish',
              'Publish to pull request?',
              'This will stage the current workspace changes, create a commit, push the session branch and open or update the delivery flow in GitHub.',
              'Refresh the session before retrying publish so you can confirm whether the branch or pull request was already updated.',
              async () => {
                await runCoreMutation(buildPublishCommand());
              }
            )
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
                void runConfirmedAction(
                  `Generate ${type}`,
                  'Generate deliverable?',
                  `This will ask Atenea to generate the latest ${type} draft for this session.`,
                  `Refresh deliverables before retrying ${type} generation so you do not create confusion about the latest draft.`,
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
            <Text style={styles.meta}>{deliverable.title || deliverable.preview || 'No preview'}</Text>
            {pendingApprovalIds.has(deliverable.id) && actions.canApproveDeliverables ? (
              <Pressable
                onPress={() =>
                  void runConfirmedAction(
                    `Approve ${deliverable.id}`,
                    'Approve deliverable?',
                    `This will mark ${deliverable.type} v${deliverable.version} as the approved baseline for the session.`,
                    'Refresh deliverables before retrying approval so you can confirm which version is currently the latest approved baseline.',
                    async () => {
                      await postJson(`/api/mobile/sessions/${sessionId}/deliverables/${deliverable.id}/approve`);
                    }
                  )
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
                    void runConfirmedAction(
                      'Mark billed',
                      'Mark approved price estimate as billed?',
                      `This will persist billing reference ${billingReference.trim()} for the approved pricing baseline.`,
                      'Refresh the session before retrying billing so you can verify whether the approved price estimate is already marked billed.',
                      async () => {
                        await postJson<unknown, MarkPriceEstimateBilledRequest>(
                          `/api/mobile/sessions/${sessionId}/deliverables/${data.approvedPriceEstimate!.deliverableId}/billing/mark-billed`,
                          { billingReference: billingReference.trim() }
                        );
                        setBillingReference('');
                      }
                    )
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
