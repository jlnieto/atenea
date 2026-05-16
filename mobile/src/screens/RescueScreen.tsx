import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Keyboard,
  KeyboardAvoidingView,
  KeyboardEvent,
  NativeScrollEvent,
  NativeSyntheticEvent,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { AteneaApiError, fetchJson, postJson } from '../api/client';
import {
  CreateRescueTurnResponse,
  RescueSessionConversationView,
  ResolveRescueSessionResponse,
} from '../api/types';
import { ActionButton } from '../components/ActionButton';
import { AppIcon } from '../components/AppIcon';
import { IconActionLink } from '../components/IconActionLink';
import { LoadingBlock } from '../components/LoadingBlock';
import { useRemoteResource } from '../hooks/useRemoteResource';
import { formatAteneaVoiceTelemetry, useAteneaVoiceEngine } from '../voice/useAteneaVoiceEngine';
import { RenderedTurnText } from './ConversationScreen';

const MONO_FONT = Platform.select({
  ios: 'Menlo',
  android: 'monospace',
  default: 'monospace',
});

export function RescueScreen({
  projectId,
  onOpenProjects,
}: {
  projectId: number | null;
  onOpenProjects: () => void;
}) {
  const composerMinHeight = 56;
  const composerMaxHeight = 156;
  const voiceRecorder = useAteneaVoiceEngine({ context: 'rescue' });
  const scrollRef = useRef<ScrollView | null>(null);
  const stickToBottomRef = useRef(true);
  const contentHeightRef = useRef(0);
  const [resolvedSessionId, setResolvedSessionId] = useState<number | null>(null);
  const [message, setMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [mutationError, setMutationError] = useState<AteneaApiError | Error | null>(null);
  const [composerInputHeight, setComposerInputHeight] = useState(composerMinHeight);
  const [keyboardInset, setKeyboardInset] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [voiceStatus, setVoiceStatus] = useState<string | null>(null);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const [showVoiceDetails, setShowVoiceDetails] = useState(false);

  const { data, error, loading, refreshing, reload, setData } = useRemoteResource(
    async () => {
      if (projectId == null) {
        return null;
      }
      if (resolvedSessionId == null) {
        const response = await postJson<ResolveRescueSessionResponse>(
          `/api/mobile/projects/${projectId}/rescue-sessions/resolve`,
          { title: 'Rescate operativo' }
        );
        setResolvedSessionId(response.view.session.id);
        return response.view;
      }
      return fetchJson<RescueSessionConversationView>(
        `/api/mobile/rescue-sessions/${resolvedSessionId}/conversation`
      );
    },
    [projectId, resolvedSessionId],
    { refreshIntervalMs: resolvedSessionId == null ? undefined : 4000 }
  );

  const session = data?.session ?? null;
  const turns = data?.turns ?? [];
  const running = session?.status === 'RUNNING';
  const interactionPending = pending || voiceRecorder.busy || voiceRecorder.isRecording;
  const composerHasText = message.trim().length > 0;
  const composerDisabled = interactionPending || session?.canCreateTurn !== true || session.status === 'CLOSED';
  const canSend = composerHasText && !composerDisabled;
  const voiceTelemetrySummary = formatAteneaVoiceTelemetry(voiceRecorder.lastTelemetry);
  const hasVoiceDetails = voiceStatus != null || lastTranscript != null || voiceTelemetrySummary != null;
  const latestOperatorTurn = useMemo(
    () => [...turns].reverse().find((turn) => turn.actor === 'OPERATOR') ?? null,
    [turns]
  );
  const runStartedAt = latestOperatorTurn?.createdAt ?? session?.lastActivityAt ?? null;
  const consoleStatusLabel = running
    ? `Codex trabajando${runStartedAt ? ` · ${formatElapsed(runStartedAt, now)}` : ''}`
    : session?.status === 'CLOSED'
      ? 'Rescate cerrado'
      : null;
  const consoleStatusMeta = running
    ? 'El canal de rescate sigue ejecutando Codex sobre el repositorio actual.'
    : session?.repoPath
      ? `Repo: ${session.repoPath}`
      : 'Canal operativo directo sobre el repositorio del proyecto.';
  const latestOutputSignature = useMemo(
    () => turns.length ? `${turns[turns.length - 1].id}:${turns[turns.length - 1].messageText.length}` : null,
    [turns]
  );
  const composerInputScrollEnabled = composerInputHeight >= composerMaxHeight;
  const voiceWaveHeights = useMemo(
    () => buildVoiceWaveHeights(voiceRecorder.normalizedLevel, voiceRecorder.durationSeconds),
    [voiceRecorder.durationSeconds, voiceRecorder.normalizedLevel]
  );

  const scrollToBottom = () => {
    scrollRef.current?.scrollToEnd({ animated: true });
  };

  useEffect(() => {
    setResolvedSessionId(null);
    setMessage('');
    setMutationError(null);
    setVoiceStatus(null);
    setLastTranscript(null);
    setShowVoiceDetails(false);
    contentHeightRef.current = 0;
    stickToBottomRef.current = true;
  }, [projectId]);

  useEffect(() => {
    if (latestOutputSignature == null || !stickToBottomRef.current) {
      return;
    }
    requestAnimationFrame(scrollToBottom);
  }, [latestOutputSignature]);

  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const intervalId = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => clearInterval(intervalId);
  }, [running]);

  useEffect(() => {
    const handleKeyboardShow = (event: KeyboardEvent) => {
      if (Platform.OS !== 'android') {
        return;
      }
      setKeyboardInset(event.endCoordinates.height);
      stickToBottomRef.current = true;
      requestAnimationFrame(scrollToBottom);
    };

    const handleKeyboardHide = () => {
      if (Platform.OS !== 'android') {
        return;
      }
      setKeyboardInset(0);
    };

    const showSubscription = Keyboard.addListener(
      Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow',
      handleKeyboardShow
    );
    const hideSubscription = Keyboard.addListener(
      Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide',
      handleKeyboardHide
    );

    return () => {
      showSubscription.remove();
      hideSubscription.remove();
    };
  }, []);

  const refreshConversationState = useCallback(async (options?: { silent?: boolean }) => {
    await reload(options);
  }, [reload]);

  const submitMessage = useCallback(async (nextMessage: string) => {
    const normalizedMessage = nextMessage.trim();
    if (!normalizedMessage || session == null || session.canCreateTurn !== true || session.status === 'CLOSED') {
      return;
    }
    setPending(true);
    setMutationError(null);
    Keyboard.dismiss();
    try {
      const response = await postJson<CreateRescueTurnResponse>(
        `/api/mobile/rescue-sessions/${session.id}/turns`,
        { message: normalizedMessage }
      );
      setData(response.view);
      setMessage('');
      stickToBottomRef.current = true;
      await refreshConversationState({ silent: true });
    } catch (sendError) {
      setMutationError(sendError instanceof Error ? sendError : new Error('No se ha podido enviar el turno de rescate'));
    } finally {
      setPending(false);
    }
  }, [refreshConversationState, session, setData]);

  const sendTurn = useCallback(async () => {
    if (!canSend) {
      return;
    }
    await submitMessage(message);
  }, [canSend, message, submitMessage]);

  const handleComposerContentSizeChange = (height: number) => {
    const nextHeight = Math.max(composerMinHeight, Math.min(composerMaxHeight, Math.ceil(height)));
    setComposerInputHeight((currentHeight) => (Math.abs(currentHeight - nextHeight) > 1 ? nextHeight : currentHeight));
  };

  const submitVoiceTurn = async () => {
    if (session == null || !voiceRecorder.isRecording) {
      return;
    }

    setPending(true);
    setMutationError(null);
    setVoiceStatus('Finalizando grabación...');
    try {
      setVoiceStatus('Audio grabado. Transcribiendo...');
      const transcription = await voiceRecorder.stopAndTranscribe('rescue-voice-prompt');
      const transcript = transcription.transcript.trim();
      if (!transcript) {
        setLastTranscript(null);
        setVoiceStatus('La transcripción ha llegado vacía. No he enviado nada a Codex.');
        return;
      }

      setLastTranscript(transcript);
      setVoiceStatus(`Transcripción recibida: "${transcript}"`);
      await submitMessage(transcript);
    } catch (voiceError) {
      const nextMessage = voiceError instanceof Error ? voiceError.message : 'El turno de voz ha fallado';
      setMutationError(new Error(nextMessage));
      setVoiceStatus(nextMessage);
    } finally {
      setPending(false);
    }
  };

  const startVoiceTurn = async () => {
    if (session == null || session.status === 'CLOSED' || session.canCreateTurn !== true) {
      return;
    }

    try {
      setMutationError(null);
      setVoiceStatus('Preparando grabación...');
      await voiceRecorder.start();
      setLastTranscript(null);
      setVoiceStatus('Escuchando... toca otra vez para enviar.');
    } catch (voiceError) {
      const nextMessage = voiceError instanceof Error ? voiceError.message : 'No se pudo iniciar la grabación';
      setMutationError(new Error(nextMessage));
      setVoiceStatus(nextMessage);
    }
  };

  const cancelVoiceTurn = async () => {
    if (!voiceRecorder.isRecording) {
      return;
    }

    setMutationError(null);
    setVoiceStatus('Cancelando grabación...');
    try {
      await voiceRecorder.cancel();
      setLastTranscript(null);
      setVoiceStatus('Grabación cancelada.');
    } catch (voiceError) {
      const nextMessage = voiceError instanceof Error ? voiceError.message : 'No se pudo cancelar la grabación';
      setMutationError(new Error(nextMessage));
      setVoiceStatus(nextMessage);
    }
  };

  if (projectId == null) {
    return (
      <View style={styles.emptyState}>
        <AppIcon name="warning" size={28} color="#855200" />
        <Text style={styles.emptyTitle}>Selecciona un proyecto</Text>
        <Text style={styles.emptyText}>
          El rescate opera sobre el repositorio activo. Elige un proyecto bloqueado desde Proyectos para abrir este canal.
        </Text>
        <ActionButton label="Ir a Proyectos" onPress={onOpenProjects} prominence="high" />
      </View>
    );
  }

  if (loading && data == null) {
    return <LoadingBlock label="Abriendo rescate..." />;
  }

  if (error || data == null || session == null) {
    return (
      <View style={styles.emptyState}>
        <Text style={styles.emptyTitle}>Rescate no disponible</Text>
        <Text style={styles.emptyText}>{error || 'No se han recibido datos del canal de rescate.'}</Text>
        <ActionButton label="Reintentar" onPress={() => void refreshConversationState()} prominence="high" />
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 12 : 8}
    >
      <View style={styles.topBar}>
        <View style={[styles.topBarSlot, styles.topBarSlotLeft]}>
          <IconActionLink label="Proyectos" icon="arrow-left" onPress={onOpenProjects} compact />
        </View>
        <View style={[styles.topBarSlot, styles.topBarSlotCenter]}>
          <Text style={styles.topBarTitle} numberOfLines={1}>Rescate · {session.projectName}</Text>
        </View>
        <View style={[styles.topBarSlot, styles.topBarSlotRight]}>
          <IconActionLink
            label={refreshing ? 'Actualizando...' : 'Actualizar'}
            icon="refresh"
            onPress={() => void refreshConversationState()}
            compact
          />
        </View>
      </View>

      <View style={styles.chatShell}>
        <View style={styles.consoleHeader}>
          <View style={styles.consoleHeaderMain}>
            {consoleStatusLabel ? (
              <Text style={styles.consoleStatusLabel}>{consoleStatusLabel}</Text>
            ) : null}
            {consoleStatusMeta ? (
              <Text style={styles.consoleStatusMeta} numberOfLines={2}>{consoleStatusMeta}</Text>
            ) : null}
          </View>
        </View>

        <ScrollView
          ref={scrollRef}
          style={styles.turnScroll}
          contentContainerStyle={styles.turnList}
          keyboardShouldPersistTaps="handled"
          onContentSizeChange={(_, height) => {
            contentHeightRef.current = height;
            if (stickToBottomRef.current) {
              requestAnimationFrame(scrollToBottom);
            }
          }}
          onScroll={(event: NativeSyntheticEvent<NativeScrollEvent>) => {
            const { contentOffset, contentSize, layoutMeasurement } = event.nativeEvent;
            const distanceFromBottom = contentSize.height - layoutMeasurement.height - contentOffset.y;
            stickToBottomRef.current = distanceFromBottom < 48;
          }}
          scrollEventThrottle={16}
        >
          {turns.map((turn, index) => {
            const operator = turn.actor === 'OPERATOR';
            return (
              <View
                key={turn.id}
                style={[styles.turnRow, index > 0 && styles.turnRowWithDivider]}
              >
                <RenderedTurnText text={turn.messageText} operator={operator} />
                <Text style={styles.turnMeta}>{new Date(turn.createdAt).toLocaleString()}</Text>
              </View>
            );
          })}
        </ScrollView>

        <View
          style={[
            styles.composerPanel,
            Platform.OS === 'android' && keyboardInset > 0
              ? { paddingBottom: keyboardInset + 8 }
              : null,
          ]}
        >
          {voiceRecorder.isRecording ? (
            <View style={styles.composerRecorder}>
              <Pressable
                onPress={() => void cancelVoiceTurn()}
                style={[styles.composerCircleButton, styles.composerRecorderCancelButton]}
              >
                <AppIcon name="close" size={20} color="#dce9e5" />
              </Pressable>
              <View style={styles.composerRecorderCenter}>
                <Text style={styles.composerRecorderTitle}>
                  {voiceRecorder.isListening
                    ? `Escuchando... ${Math.max(1, Math.round(voiceRecorder.durationSeconds))}s`
                    : 'Activando micrófono...'}
                </Text>
                <View style={styles.voiceWaveRow}>
                  {voiceWaveHeights.map((height, index) => (
                    <View
                      key={`wave-${index}`}
                      style={[styles.voiceWaveDot, { height }]}
                    />
                  ))}
                </View>
              </View>
              <Pressable
                onPress={() => void submitVoiceTurn()}
                disabled={!voiceRecorder.isListening}
                style={[
                  styles.composerCircleButton,
                  styles.composerActionButton,
                  styles.composerRecorderActionButton,
                  !voiceRecorder.isListening && styles.composerCircleButtonDisabled,
                ]}
              >
                <AppIcon name="send-up" size={20} color="#dce9e5" />
              </Pressable>
            </View>
          ) : null}
          {mutationError ? (
            <View style={styles.feedbackPanel}>
              <Text style={styles.feedbackTitle}>
                {mutationError instanceof AteneaApiError ? mutationError.title : 'Atención'}
              </Text>
              <ScrollView
                style={styles.feedbackScroll}
                contentContainerStyle={styles.feedbackContent}
                nestedScrollEnabled
                keyboardShouldPersistTaps="handled"
              >
                <Text style={styles.error}>{mutationError.message}</Text>
              </ScrollView>
            </View>
          ) : null}
          {hasVoiceDetails ? (
            <View style={styles.voiceDetailsPanel}>
              <Pressable onPress={() => setShowVoiceDetails((value) => !value)}>
                <Text style={styles.voiceDetailsToggle}>
                  {showVoiceDetails ? 'Ocultar detalles de voz' : 'Ver detalles de voz'}
                </Text>
              </Pressable>
              {showVoiceDetails ? (
                <View style={styles.voiceDetailsContent}>
                  {voiceStatus ? <Text style={styles.voiceStatus}>{voiceStatus}</Text> : null}
                  {lastTranscript ? <Text style={styles.voiceTranscript}>Última transcripción: {lastTranscript}</Text> : null}
                  {voiceTelemetrySummary ? <Text style={styles.voiceStatus}>{voiceTelemetrySummary}</Text> : null}
                </View>
              ) : null}
            </View>
          ) : null}
          {!voiceRecorder.isRecording ? (
            <View style={styles.composerInputWrap}>
              <TextInput
                value={message}
                onChangeText={setMessage}
                placeholder="Escribe o dicta la siguiente instrucción para Codex"
                placeholderTextColor="#6e7b74"
                style={[styles.input, styles.textArea, styles.composerInput, { height: composerInputHeight }]}
                multiline
                editable={!pending && session.status !== 'CLOSED'}
                scrollEnabled={composerInputScrollEnabled}
                onContentSizeChange={(event) => handleComposerContentSizeChange(event.nativeEvent.contentSize.height)}
                onFocus={() => {
                  stickToBottomRef.current = true;
                  requestAnimationFrame(scrollToBottom);
                }}
              />
              <Pressable
                onPress={() => void (composerHasText ? sendTurn() : startVoiceTurn())}
                disabled={composerHasText ? composerDisabled : interactionPending || session.status === 'CLOSED' || session.canCreateTurn !== true}
                style={[
                  styles.composerCircleButton,
                  styles.composerActionButton,
                  styles.composerInlineActionButton,
                  (composerHasText ? composerDisabled : interactionPending || session.status === 'CLOSED' || session.canCreateTurn !== true) && styles.composerCircleButtonDisabled,
                ]}
              >
                {composerHasText ? (
                  <AppIcon
                    name="send-up"
                    size={20}
                    color={composerDisabled ? '#6f8b84' : '#dce9e5'}
                  />
                ) : (
                  <AppIcon
                    name="microphone"
                    size={20}
                    color={interactionPending || session.status === 'CLOSED' || session.canCreateTurn !== true ? '#6f8b84' : '#dce9e5'}
                  />
                )}
              </Pressable>
            </View>
          ) : null}
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

function formatElapsed(startedAt: string | null, now: number) {
  if (!startedAt) {
    return null;
  }
  const diffMs = Math.max(0, now - new Date(startedAt).getTime());
  const totalSeconds = Math.max(1, Math.round(diffMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

function buildVoiceWaveHeights(level: number, durationSeconds: number) {
  const bars = 17;
  const center = (bars - 1) / 2;
  return Array.from({ length: bars }, (_, index) => {
    const distanceFromCenter = Math.abs(index - center) / center;
    const centerBias = 1 - distanceFromCenter * 0.7;
    const ripple = (Math.sin(durationSeconds * 9 + index * 0.82) + 1) / 2;
    const energy = Math.max(0, level + (ripple - 0.5) * level * 0.25);
    return 6 + Math.round((3 + energy * 22) * centerBias);
  });
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    gap: 6,
    backgroundColor: '#3f3f3f',
  },
  emptyState: {
    flex: 1,
    padding: 24,
    justifyContent: 'center',
    alignItems: 'center',
    gap: 12,
    backgroundColor: '#f3efe6',
  },
  emptyTitle: {
    fontSize: 20,
    fontWeight: '900',
    color: '#1f1a14',
  },
  emptyText: {
    maxWidth: 330,
    textAlign: 'center',
    fontSize: 14,
    lineHeight: 20,
    color: '#6f5d45',
  },
  chatShell: {
    flex: 1,
    gap: 6,
    minHeight: 0,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingBottom: 6,
    paddingTop: 10,
    paddingHorizontal: 10,
    backgroundColor: '#3f3f3f',
    borderBottomWidth: 1,
    borderBottomColor: '#5a5a5a',
  },
  topBarSlot: {
    flex: 1,
  },
  topBarSlotLeft: {
    alignItems: 'flex-start',
  },
  topBarSlotCenter: {
    alignItems: 'center',
  },
  topBarSlotRight: {
    alignItems: 'flex-end',
  },
  topBarTitle: {
    maxWidth: 180,
    fontSize: 12,
    fontWeight: '800',
    color: '#d8e4df',
    fontFamily: MONO_FONT,
  },
  consoleHeader: {
    gap: 2,
    paddingHorizontal: 10,
    paddingBottom: 2,
  },
  consoleHeaderMain: {
    gap: 2,
  },
  consoleStatusLabel: {
    fontSize: 11,
    fontWeight: '800',
    color: '#d8e4df',
    fontFamily: MONO_FONT,
  },
  consoleStatusMeta: {
    fontSize: 11,
    lineHeight: 16,
    color: '#97a49f',
    fontFamily: MONO_FONT,
  },
  turnScroll: {
    flex: 1,
    minHeight: 0,
  },
  turnList: {
    gap: 0,
    paddingBottom: 10,
    paddingHorizontal: 10,
  },
  turnRow: {
    paddingHorizontal: 0,
    paddingVertical: 11,
    gap: 6,
  },
  turnRowWithDivider: {
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  turnMeta: {
    fontSize: 10,
    color: '#6f7874',
    fontFamily: MONO_FONT,
  },
  input: {
    borderRadius: 0,
    paddingHorizontal: 10,
    paddingVertical: 12,
    backgroundColor: '#585858',
    fontSize: 13,
    lineHeight: 18,
    color: '#ecf3ef',
    fontFamily: MONO_FONT,
  },
  textArea: {
    minHeight: 56,
    maxHeight: 156,
    textAlignVertical: 'top',
  },
  composerInputWrap: {
    position: 'relative',
    justifyContent: 'flex-end',
  },
  composerInput: {
    paddingRight: 60,
    paddingBottom: 14,
  },
  composerPanel: {
    gap: 8,
    paddingTop: 6,
    paddingBottom: 8,
    paddingHorizontal: 0,
  },
  composerCircleButton: {
    position: 'absolute',
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#4d6763',
    backgroundColor: '#253433',
  },
  composerActionButton: {
    right: 4,
    backgroundColor: '#253433',
    borderColor: '#f1f5f3',
  },
  composerInlineActionButton: {
    bottom: 8,
  },
  composerCircleButtonDisabled: {
    borderColor: '#29413c',
    backgroundColor: '#16211f',
  },
  composerRecorder: {
    position: 'relative',
    minHeight: 92,
    paddingHorizontal: 12,
    paddingTop: 10,
    paddingBottom: 10,
    borderWidth: 1,
    borderColor: '#355c57',
    backgroundColor: '#1a2626',
  },
  composerRecorderCenter: {
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 44,
    paddingBottom: 18,
  },
  composerRecorderTitle: {
    fontSize: 12,
    fontWeight: '800',
    color: '#eef7f4',
    fontFamily: MONO_FONT,
  },
  composerRecorderActionButton: {
    bottom: 8,
  },
  composerRecorderCancelButton: {
    left: 8,
    bottom: 8,
    borderColor: '#f1f5f3',
  },
  voiceWaveRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    minHeight: 30,
    alignSelf: 'stretch',
  },
  voiceWaveDot: {
    width: 6,
    borderRadius: 999,
    backgroundColor: '#79d0bd',
  },
  voiceDetailsPanel: {
    gap: 6,
  },
  voiceDetailsToggle: {
    fontSize: 11,
    fontWeight: '800',
    color: '#73d0bd',
    fontFamily: MONO_FONT,
  },
  voiceDetailsContent: {
    gap: 4,
  },
  voiceStatus: {
    fontSize: 11,
    color: '#9fe1c8',
    fontFamily: MONO_FONT,
  },
  voiceTranscript: {
    fontSize: 11,
    lineHeight: 16,
    color: '#b8c7c0',
    fontFamily: MONO_FONT,
  },
  feedbackPanel: {
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 10,
    borderWidth: 1,
    borderColor: '#633636',
    backgroundColor: '#2e1d1d',
  },
  feedbackTitle: {
    fontSize: 11,
    fontWeight: '800',
    color: '#ffb4b4',
    fontFamily: MONO_FONT,
  },
  feedbackScroll: {
    maxHeight: 108,
  },
  feedbackContent: {
    gap: 8,
  },
  error: {
    fontSize: 13,
    fontWeight: '700',
    color: '#ff7f7f',
    fontFamily: MONO_FONT,
  },
});
