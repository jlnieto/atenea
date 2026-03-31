import { useEffect, useRef, useState } from 'react';
import * as Speech from 'expo-speech';
import EventSource from 'react-native-sse';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { API_BASE_URL } from '../api/config';
import { fetchCoreCommandEvents, fetchCoreCommands } from '../api/core';
import {
  CoreClarificationOptionResponse,
  CoreCommandEventsResponse,
  CoreCommandResponse,
  CoreCommandSummaryResponse,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  buildCloseSessionCommand,
  buildGenerateDeliverableCommand,
  buildOpenSessionCommand,
  buildPortfolioStatusCommand,
  buildProjectStatusCommand,
  buildPublishCommand,
  buildSessionDeliverablesCommand,
  buildSessionSummaryCommand,
  buildSyncPullRequestCommand,
} from '../core/phrases';
import { RunCoreCommandOptions, RunCoreVoiceCommandOptions } from '../core/useCoreCommandCenter';
import { useVoiceRecorder } from '../core/useVoiceRecorder';
import { ActionButton } from '../components/ActionButton';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import { useRemoteResource } from '../hooks/useRemoteResource';

type CoreConsoleScreenProps = {
  activeCommand: CoreCommandResponse | null;
  historyVersion: number;
  operatorKey: string;
  selectedProjectId: number | null;
  selectedSessionId: number | null;
  onClearActiveCommand: () => void;
  onConfirmActiveCommand: () => Promise<CoreCommandResponse>;
  onOpenSession: (sessionId: number) => void;
  onResolveClarification: (option: CoreClarificationOptionResponse) => Promise<CoreCommandResponse>;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<CoreCommandResponse>;
  onRunVoiceCommand: (options: RunCoreVoiceCommandOptions) => Promise<{ transcript: string; command: CoreCommandResponse }>;
};

export function CoreConsoleScreen({
  activeCommand,
  historyVersion,
  operatorKey,
  selectedProjectId,
  selectedSessionId,
  onClearActiveCommand,
  onConfirmActiveCommand,
  onOpenSession,
  onResolveClarification,
  onRunCommand,
  onRunVoiceCommand,
}: CoreConsoleScreenProps) {
  const { session: authSession } = useAuth();
  const voiceRecorder = useVoiceRecorder();
  const [draftInput, setDraftInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autoSpeak, setAutoSpeak] = useState(true);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const lastSpokenRef = useRef<string | null>(null);
  const activeCommandId = activeCommand?.commandId ?? null;

  const { data: commandHistory, loading: historyLoading, reload: reloadHistory } = useRemoteResource(
    () => fetchCoreCommands(),
    [operatorKey, historyVersion],
    { refreshIntervalMs: 10000 }
  );
  const { data: commandEvents, reload: reloadEvents } = useRemoteResource(
    () =>
      activeCommandId == null
        ? Promise.resolve<CoreCommandEventsResponse | null>(null)
        : fetchCoreCommandEvents(activeCommandId),
    [activeCommandId, historyVersion],
    { refreshIntervalMs: 3000 }
  );

  useEffect(() => {
    if (activeCommandId == null || authSession == null) {
      return undefined;
    }

    const stream = new EventSource<'core-command-events'>(
      `${API_BASE_URL}/api/core/commands/${activeCommandId}/events/stream`,
      {
        headers: {
          Authorization: {
            toString() {
              return `Bearer ${authSession.accessToken}`;
            },
          },
        },
        timeout: 0,
        pollingInterval: 5000,
      }
    );

    const onCoreEvents = (event: { data: string | null }) => {
      if (!event.data) {
        return;
      }
      try {
        const payload = JSON.parse(event.data) as CoreCommandEventsResponse;
        if (payload.commandId !== activeCommandId) {
          return;
        }
        void reloadEvents({ silent: true });
        void reloadHistory({ silent: true });
      } catch {
        // Keep polling fallback when the stream payload is malformed.
      }
    };

    stream.addEventListener('core-command-events', onCoreEvents);
    return () => {
      stream.removeAllEventListeners();
      stream.close();
    };
  }, [activeCommandId, authSession, reloadEvents, reloadHistory]);

  useEffect(() => {
    const speakableMessage = activeCommand?.speakableMessage?.trim();
    if (!autoSpeak || !speakableMessage || activeCommand?.commandId == null) {
      return;
    }
    const speakKey = `${activeCommand.commandId}:${speakableMessage}`;
    if (lastSpokenRef.current === speakKey) {
      return;
    }
    lastSpokenRef.current = speakKey;
    Speech.stop();
    Speech.speak(speakableMessage);
  }, [activeCommand, autoSpeak]);

  const executeCommand = async (options: RunCoreCommandOptions) => {
    setSending(true);
    setError(null);
    try {
      const response = await onRunCommand(options);
      if (response.status === 'SUCCEEDED' && options.input === draftInput.trim()) {
        setDraftInput('');
      }
      return response;
    } catch (commandError) {
      setError(commandError instanceof Error ? commandError.message : 'Core command failed');
      throw commandError;
    } finally {
      setSending(false);
    }
  };

  const submitDraft = async () => {
    if (!draftInput.trim()) {
      return;
    }
    await executeCommand({
      input: draftInput,
      projectId: selectedProjectId,
      workSessionId: selectedSessionId,
    });
  };

  const toggleVoiceCapture = async () => {
    if (voiceRecorder.isRecording) {
      setSending(true);
      setError(null);
      try {
        const uri = await voiceRecorder.stop();
        const response = await onRunVoiceCommand({
          audio: {
            uri,
            name: 'voice-command.m4a',
            type: 'audio/mp4',
          },
          projectId: selectedProjectId,
          workSessionId: selectedSessionId,
        });
        setLastTranscript(response.transcript);
      } catch (voiceError) {
        setError(voiceError instanceof Error ? voiceError.message : 'Voice command failed');
      } finally {
        setSending(false);
      }
      return;
    }

    try {
      await voiceRecorder.start();
    } catch (voiceError) {
      setError(voiceError instanceof Error ? voiceError.message : 'Voice recording failed');
    }
  };

  const quickActions = [
    {
      label: 'Estado proyectos',
      disabled: false,
      action: () => executeCommand({ input: buildPortfolioStatusCommand(), workSessionId: null }),
    },
    {
      label: 'Estado proyecto',
      disabled: selectedProjectId == null,
      action: () => executeCommand({
        input: buildProjectStatusCommand(),
        projectId: selectedProjectId,
        workSessionId: null,
      }),
    },
    {
      label: 'Abrir sesión',
      disabled: selectedProjectId == null,
      action: () => executeCommand({
        input: buildOpenSessionCommand(null),
        projectId: selectedProjectId,
        workSessionId: null,
        onSucceeded: (response) => {
          if (response.result?.targetId != null) {
            onOpenSession(response.result.targetId);
          }
        },
      }),
    },
    {
      label: 'Estado sesión',
      disabled: selectedSessionId == null,
      action: () => executeCommand({
        input: buildSessionSummaryCommand(),
        projectId: selectedProjectId,
        workSessionId: selectedSessionId,
      }),
    },
    {
      label: 'Publish',
      disabled: selectedSessionId == null,
      action: () => executeCommand({
        input: buildPublishCommand(),
        projectId: selectedProjectId,
        workSessionId: selectedSessionId,
      }),
    },
    {
      label: 'Sync PR',
      disabled: selectedSessionId == null,
      action: () => executeCommand({
        input: buildSyncPullRequestCommand(),
        projectId: selectedProjectId,
        workSessionId: selectedSessionId,
      }),
    },
    {
      label: 'Deliverables',
      disabled: selectedSessionId == null,
      action: () => executeCommand({
        input: buildSessionDeliverablesCommand(),
        projectId: selectedProjectId,
        workSessionId: selectedSessionId,
      }),
    },
    {
      label: 'Close',
      disabled: selectedSessionId == null,
      action: () => executeCommand({
        input: buildCloseSessionCommand(),
        projectId: selectedProjectId,
        workSessionId: selectedSessionId,
      }),
    },
  ];

  if (historyLoading && commandHistory == null) {
    return <LoadingBlock label="Loading Atenea Core..." />;
  }

  return (
    <View style={styles.container}>
      <Card
        title="Atenea Core"
        subtitle="Single conversational surface for project context, WorkSession operation and guided follow-up."
      >
        <View style={styles.contextRow}>
          <StatePill label={`Project ${selectedProjectId ?? 'none'}`} tone={selectedProjectId ? 'good' : 'default'} />
          <StatePill label={`Session ${selectedSessionId ?? 'none'}`} tone={selectedSessionId ? 'good' : 'default'} />
          <Pressable onPress={() => setAutoSpeak((current) => !current)}>
            <Text style={styles.link}>{autoSpeak ? 'Mute replies' : 'Speak replies'}</Text>
          </Pressable>
        </View>
        <Text style={styles.meta}>
          Voice output is active through `speakableMessage`. Voice input still needs STT wiring.
        </Text>
        <TextInput
          value={draftInput}
          onChangeText={setDraftInput}
          placeholder="Pídele a Atenea el estado de un proyecto, que abra sesión, que publique o que continúe trabajando."
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          multiline
        />
        <View style={styles.actions}>
          <ActionButton
            label={sending ? 'Sending...' : 'Send to Core'}
            onPress={() => void submitDraft()}
            disabled={sending || !draftInput.trim()}
          />
          <ActionButton
            label={
              voiceRecorder.isRecording
                ? `Stop voice (${Math.max(1, Math.round(voiceRecorder.durationSeconds))}s)`
                : 'Voice command'
            }
            onPress={() => void toggleVoiceCapture()}
            disabled={sending || voiceRecorder.busy}
          />
          {activeCommand?.speakableMessage ? (
            <ActionButton
              label="Speak again"
              onPress={() => Speech.speak(activeCommand.speakableMessage ?? '')}
            />
          ) : null}
          {activeCommand ? (
            <ActionButton label="Clear focus" onPress={onClearActiveCommand} />
          ) : null}
        </View>
        {lastTranscript ? (
          <Text style={styles.meta}>Last voice transcript: "{lastTranscript}"</Text>
        ) : null}
        {voiceRecorder.error ? (
          <Text style={styles.error}>{voiceRecorder.error}</Text>
        ) : null}
      </Card>

      <Card title="Quick actions" subtitle="Fast path for the most common development capabilities over Core.">
        <View style={styles.quickGrid}>
          {quickActions.map((action) => (
            <ActionButton
              key={action.label}
              label={action.label}
              onPress={() => void action.action()}
              disabled={sending || action.disabled}
            />
          ))}
          <ActionButton
            label="Gen ticket"
            onPress={() =>
              void executeCommand({
                input: buildGenerateDeliverableCommand('WORK_TICKET'),
                projectId: selectedProjectId,
                workSessionId: selectedSessionId,
              })
            }
            disabled={sending || selectedSessionId == null}
          />
          <ActionButton
            label="Gen breakdown"
            onPress={() =>
              void executeCommand({
                input: buildGenerateDeliverableCommand('WORK_BREAKDOWN'),
                projectId: selectedProjectId,
                workSessionId: selectedSessionId,
              })
            }
            disabled={sending || selectedSessionId == null}
          />
          <ActionButton
            label="Gen price"
            onPress={() =>
              void executeCommand({
                input: buildGenerateDeliverableCommand('PRICE_ESTIMATE'),
                projectId: selectedProjectId,
                workSessionId: selectedSessionId,
              })
            }
            disabled={sending || selectedSessionId == null}
          />
        </View>
      </Card>

      {activeCommand ? (
        <Card
          title={`Command ${activeCommand.commandId}`}
          subtitle={activeCommand.intent?.capability ?? activeCommand.status}
        >
          <View style={styles.contextRow}>
            <StatePill label={activeCommand.status} tone={activeCommand.status === 'SUCCEEDED' ? 'good' : 'warning'} />
            {activeCommand.intent?.riskLevel ? (
              <StatePill label={activeCommand.intent.riskLevel} tone={activeCommand.intent.requiresConfirmation ? 'warning' : 'default'} />
            ) : null}
            {activeCommand.interpretation?.source ? (
              <StatePill label={activeCommand.interpretation.source} />
            ) : null}
          </View>
          {activeCommand.operatorMessage ? (
            <Text style={styles.operatorMessage}>{activeCommand.operatorMessage}</Text>
          ) : null}
          {activeCommand.result?.targetType === 'WORK_SESSION' && activeCommand.result.targetId != null ? (
            <Pressable onPress={() => onOpenSession(activeCommand.result!.targetId!)}>
              <Text style={styles.link}>Open session {activeCommand.result.targetId}</Text>
            </Pressable>
          ) : null}
          {activeCommand.status === 'NEEDS_CONFIRMATION' && activeCommand.confirmation ? (
            <>
              <Text style={styles.meta}>{activeCommand.confirmation.message}</Text>
              <ActionButton
                label={sending ? 'Confirming...' : 'Confirm command'}
                onPress={() => void executeCommandConfirmation(onConfirmActiveCommand, setSending, setError)}
                disabled={sending}
              />
            </>
          ) : null}
          {activeCommand.status === 'NEEDS_CLARIFICATION' && activeCommand.clarification ? (
            <>
              <Text style={styles.meta}>{activeCommand.clarification.message}</Text>
              <View style={styles.quickGrid}>
                {activeCommand.clarification.options.map((option) => (
                  <ActionButton
                    key={`${option.type}-${option.targetId ?? option.label}`}
                    label={option.label}
                    onPress={() =>
                      void executeCommandClarification(option, onResolveClarification, setSending, setError)
                    }
                    disabled={sending}
                  />
                ))}
              </View>
            </>
          ) : null}
          {commandEvents?.events.length ? (
            <View style={styles.eventList}>
              {commandEvents.events.slice(-5).map((event) => (
                <View key={event.id} style={styles.eventRow}>
                  <Text style={styles.eventPhase}>{event.phase}</Text>
                  <Text style={styles.eventMessage}>{event.message}</Text>
                </View>
              ))}
            </View>
          ) : null}
        </Card>
      ) : null}

      {error ? (
        <Card title="Core error" subtitle={error}>
          <Text style={styles.meta}>Review the current context and try the command again.</Text>
        </Card>
      ) : null}

      <Card title="Recent commands" subtitle="Latest Core commands recorded for this operator shell.">
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          <View style={styles.historyList}>
            {(commandHistory?.items ?? []).slice(0, 8).map((item) => (
              <HistoryCard key={item.commandId} item={item} />
            ))}
          </View>
        </ScrollView>
      </Card>

      <Card title="Project shortcuts" subtitle="Use a project name directly to set context before the next command.">
        <View style={styles.quickGrid}>
          {(commandHistory?.items ?? [])
            .filter((item) => item.intent?.capability === 'activate_project_context')
            .slice(0, 3)
            .map((item) => (
              <ActionButton
                key={item.commandId}
                label={item.rawInput}
                onPress={() => void executeCommand({ input: item.rawInput, workSessionId: null })}
              />
            ))}
        </View>
      </Card>
    </View>
  );
}

function HistoryCard({
  item,
}: {
  item: CoreCommandSummaryResponse;
}) {
  return (
    <View style={styles.historyCard}>
      <Text style={styles.historyStatus}>{item.status}</Text>
      <Text style={styles.historyInput}>{item.rawInput}</Text>
      {item.operatorMessage ? <Text style={styles.historyMessage}>{item.operatorMessage}</Text> : null}
      <Text style={styles.historyMeta}>{new Date(item.createdAt).toLocaleString()}</Text>
    </View>
  );
}

async function executeCommandConfirmation(
  onConfirmActiveCommand: () => Promise<CoreCommandResponse>,
  setSending: (sending: boolean) => void,
  setError: (error: string | null) => void
) {
  setSending(true);
  setError(null);
  try {
    await onConfirmActiveCommand();
  } catch (error) {
    setError(error instanceof Error ? error.message : 'Core confirmation failed');
  } finally {
    setSending(false);
  }
}

async function executeCommandClarification(
  option: CoreClarificationOptionResponse,
  onResolveClarification: (option: CoreClarificationOptionResponse) => Promise<CoreCommandResponse>,
  setSending: (sending: boolean) => void,
  setError: (error: string | null) => void
) {
  setSending(true);
  setError(null);
  try {
    await onResolveClarification(option);
  } catch (error) {
    setError(error instanceof Error ? error.message : 'Core clarification follow-up failed');
  } finally {
    setSending(false);
  }
}

const styles = StyleSheet.create({
  container: {
    gap: 14,
  },
  contextRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
  },
  meta: {
    fontSize: 13,
    lineHeight: 18,
    color: '#705b42',
  },
  input: {
    minHeight: 110,
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 12,
    backgroundColor: '#fffdf8',
    fontSize: 15,
    color: '#2d2218',
    textAlignVertical: 'top',
  },
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  quickGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  operatorMessage: {
    fontSize: 14,
    lineHeight: 20,
    color: '#2d2218',
  },
  eventList: {
    gap: 6,
  },
  eventRow: {
    gap: 2,
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: '#efe4d2',
  },
  eventPhase: {
    fontSize: 11,
    fontWeight: '800',
    color: '#7b684f',
  },
  eventMessage: {
    fontSize: 13,
    color: '#2d2218',
  },
  historyList: {
    flexDirection: 'row',
    gap: 10,
  },
  historyCard: {
    width: 230,
    gap: 6,
    padding: 12,
    borderRadius: 14,
    backgroundColor: '#f7f0e4',
    borderWidth: 1,
    borderColor: '#e3d6c0',
  },
  historyStatus: {
    fontSize: 11,
    fontWeight: '800',
    color: '#7b684f',
  },
  historyInput: {
    fontSize: 14,
    fontWeight: '700',
    color: '#2d2218',
  },
  historyMessage: {
    fontSize: 13,
    color: '#5d4a34',
  },
  historyMeta: {
    fontSize: 12,
    color: '#7b684f',
  },
  link: {
    fontSize: 13,
    fontWeight: '800',
    color: '#2e6a57',
  },
  error: {
    fontSize: 13,
    fontWeight: '700',
    color: '#9f3024',
  },
});
