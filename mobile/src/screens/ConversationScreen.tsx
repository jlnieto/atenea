import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import EventSource from 'react-native-sse';
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
import { API_BASE_URL } from '../api/config';
import { fetchJson } from '../api/client';
import {
  CoreCommandResponse,
  MobileSessionEventsResponse,
  WorkSessionConversationView,
} from '../api/types';
import { RunCoreCommandOptions } from '../core/useCoreCommandCenter';
import { ActionButton } from '../components/ActionButton';
import { AppIcon } from '../components/AppIcon';
import { IconActionLink } from '../components/IconActionLink';
import { useAuth } from '../auth/AuthContext';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { useRemoteResource } from '../hooks/useRemoteResource';
import { formatAteneaVoiceTelemetry, useAteneaVoiceEngine } from '../voice/useAteneaVoiceEngine';

const MONO_FONT = Platform.select({
  ios: 'Menlo',
  android: 'monospace',
  default: 'monospace',
});

export function ConversationScreen({
  projectId,
  sessionId,
  autoReadRequest: _autoReadRequest,
  readReceiptToken,
  onBackToSession,
  onOpenCore,
  onRunCommand,
}: {
  projectId: number | null;
  sessionId: number | null;
  autoReadRequest?: { token: number; mode: 'brief' | 'full' } | null;
  readReceiptToken?: number | null;
  onBackToSession: () => void;
  onOpenCore: () => void;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<CoreCommandResponse>;
}) {
  const composerMinHeight = 56;
  const composerMaxHeight = 156;
  const { session: authSession } = useAuth();
  const voiceRecorder = useAteneaVoiceEngine({ context: 'conversation' });
  const [streamHealthy, setStreamHealthy] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const fallbackRefreshIntervalMs = streamHealthy ? undefined : 5000;
  const { data, error, loading, refreshing, reload } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<WorkSessionConversationView>(`/api/mobile/sessions/${sessionId}/conversation`),
    [sessionId],
    { refreshIntervalMs: fallbackRefreshIntervalMs }
  );
  const {
    data: eventsData,
    reload: reloadEvents,
    setData: setEventsData,
  } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<MobileSessionEventsResponse>(`/api/mobile/sessions/${sessionId}/events?limit=10`),
    [sessionId],
    { refreshIntervalMs: fallbackRefreshIntervalMs }
  );
  const [turnMessage, setTurnMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [voiceStatus, setVoiceStatus] = useState<string | null>(null);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const [showVoiceDetails, setShowVoiceDetails] = useState(false);
  const [composerInputHeight, setComposerInputHeight] = useState(composerMinHeight);
  const [keyboardInset, setKeyboardInset] = useState(0);
  const scrollRef = useRef<ScrollView | null>(null);
  const stickToBottomRef = useRef(true);
  const contentHeightRef = useRef(0);
  const lastStreamEventAtRef = useRef<string | null>(null);
  const lastSeenLatestTurnIdRef = useRef<number | null>(null);
  const lastSeenOutputSignatureRef = useRef<string | null>(null);

  const session = data?.view.session ?? null;
  const turns = data?.recentTurns ?? [];
  const latestSessionEvent = useMemo(() => eventsData?.events.at(0) ?? null, [eventsData]);
  const latestOperatorTurn = useMemo(
    () => [...turns].reverse().find((turn) => turn.actor === 'OPERATOR') ?? null,
    [turns]
  );
  const latestAgentTurn = useMemo(
    () => [...turns].reverse().find((turn) => turn.actor === 'CODEX' || turn.actor === 'ATENEA') ?? null,
    [turns]
  );
  const currentRun = data?.view.latestRun ?? null;
  const runStartedAt = currentRun?.startedAt
    ?? latestOperatorTurn?.createdAt
    ?? session?.lastActivityAt
    ?? null;
  const runElapsed = data?.view.runInProgress ? formatElapsed(runStartedAt, now) : null;
  const latestEventLabel = latestSessionEvent ? describeSessionEvent(latestSessionEvent) : null;
  const liveStatusLabel = streamHealthy ? 'En vivo' : 'Fallback cada 5s';
  const liveStatusDetail = streamHealthy
    ? 'La sesión está recibiendo eventos en directo.'
    : 'Sin SSE activo; la conversación se refresca por polling.';
  const interactionPending = pending || voiceRecorder.busy || voiceRecorder.isRecording;
  const composerDisabled = interactionPending || sessionId == null || !data?.view.canCreateTurn;
  const composerHasText = turnMessage.trim().length > 0;
  const composerInputScrollEnabled = composerInputHeight >= composerMaxHeight;
  const voiceWaveHeights = useMemo(
    () => buildVoiceWaveHeights(voiceRecorder.normalizedLevel, voiceRecorder.durationSeconds),
    [voiceRecorder.durationSeconds, voiceRecorder.normalizedLevel]
  );
  const consoleStatusLabel = data?.view.runInProgress
    ? runElapsed
      ? `Codex trabajando · ${runElapsed}`
      : 'Codex trabajando'
    : null;
  const consoleStatusMeta = data?.view.runInProgress
    ? latestEventLabel ?? 'La sesión sigue en curso.'
    : null;
  const voiceTelemetrySummary = formatAteneaVoiceTelemetry(voiceRecorder.lastTelemetry);
  const hasVoiceDetails = voiceStatus != null || lastTranscript != null || voiceTelemetrySummary != null;
  const latestOutputSignature = latestAgentTurn
    ? `${latestAgentTurn.id}:${latestAgentTurn.messageText.length}:${data?.view.lastAgentResponse?.length ?? 0}`
    : data?.view.lastAgentResponse?.length
      ? `response:${data.view.lastAgentResponse.length}`
      : null;

  const refreshConversationState = useCallback(async (options?: { silent?: boolean }) => {
    await Promise.all([
      reload(options),
      reloadEvents(options),
    ]);
  }, [reload, reloadEvents]);

  const scrollToBottom = () => {
    scrollRef.current?.scrollToEnd({ animated: true });
  };

  useEffect(() => {
    if (loading) {
      return;
    }
    if (stickToBottomRef.current) {
      requestAnimationFrame(scrollToBottom);
    }
  }, [loading, turns.length]);

  useEffect(() => {
    if (!latestAgentTurn) {
      return;
    }
    if (lastSeenLatestTurnIdRef.current == null) {
      lastSeenLatestTurnIdRef.current = latestAgentTurn.id;
      return;
    }
    if (lastSeenLatestTurnIdRef.current === latestAgentTurn.id) {
      return;
    }
    lastSeenLatestTurnIdRef.current = latestAgentTurn.id;
    if (stickToBottomRef.current) {
      requestAnimationFrame(scrollToBottom);
    }
  }, [latestAgentTurn]);

  useEffect(() => {
    if (latestOutputSignature == null) {
      return;
    }
    if (lastSeenOutputSignatureRef.current == null) {
      lastSeenOutputSignatureRef.current = latestOutputSignature;
      return;
    }
    if (lastSeenOutputSignatureRef.current === latestOutputSignature) {
      return;
    }
    lastSeenOutputSignatureRef.current = latestOutputSignature;
    if (stickToBottomRef.current) {
      requestAnimationFrame(scrollToBottom);
    }
  }, [latestOutputSignature]);

  useEffect(() => {
    setStreamHealthy(false);
    lastStreamEventAtRef.current = null;
    setNow(Date.now());
  }, [sessionId, authSession]);

  useEffect(() => {
    if (!data?.view.runInProgress) {
      return undefined;
    }

    const intervalId = setInterval(() => {
      setNow(Date.now());
    }, 1000);

    return () => clearInterval(intervalId);
  }, [data?.view.runInProgress]);

  useEffect(() => {
    contentHeightRef.current = 0;
    lastSeenLatestTurnIdRef.current = null;
    lastSeenOutputSignatureRef.current = null;
  }, [sessionId]);

  useEffect(() => {
    const handleKeyboardShow = (event: KeyboardEvent) => {
      if (Platform.OS !== 'android') {
        return;
      }
      setKeyboardInset(event.endCoordinates.height);
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

  useEffect(() => {
    if (!stickToBottomRef.current) {
      return;
    }
    requestAnimationFrame(scrollToBottom);
  }, [composerInputHeight, voiceRecorder.isRecording]);

  useEffect(() => {
    if (sessionId == null || authSession == null) {
      return undefined;
    }

    const stream = new EventSource<'session-events'>(
      `${API_BASE_URL}/api/mobile/sessions/${sessionId}/events/stream`,
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

    const onOpen = () => {
      setStreamHealthy(true);
    };

    const onError = () => {
      setStreamHealthy(false);
    };

    const onSessionEvents = (event: { data: string | null }) => {
      if (!event.data) {
        return;
      }

      try {
        const payload = JSON.parse(event.data) as MobileSessionEventsResponse;
        const latestEventAt = payload.events[0]?.at ?? null;
        if (latestEventAt == null || latestEventAt === lastStreamEventAtRef.current) {
          return;
        }
        lastStreamEventAtRef.current = latestEventAt;
        setStreamHealthy(true);
        setNow(Date.now());
        setEventsData((current) => mergeSessionEvents(current, payload));
        void refreshConversationState({ silent: true });
      } catch {
        void refreshConversationState({ silent: true });
      }
    };

    stream.addEventListener('open', onOpen);
    stream.addEventListener('error', onError);
    stream.addEventListener('session-events', onSessionEvents);

    return () => {
      setStreamHealthy(false);
      stream.removeAllEventListeners();
      stream.close();
    };
  }, [authSession, refreshConversationState, sessionId, setEventsData]);

  const sendTurn = async () => {
    if (sessionId == null || !turnMessage.trim()) {
      return;
    }

    setPending(true);
    setMutationError(null);
    setVoiceStatus(null);
    try {
      const response = await onRunCommand({
        input: turnMessage.trim(),
        projectId,
        workSessionId: sessionId,
        openCoreOnAttention: true,
      });
      if (response.status === 'SUCCEEDED') {
        await refreshConversationState({ silent: true });
        setTurnMessage('');
        return;
      }
      if (response.status === 'NEEDS_CONFIRMATION') {
        setMutationError('Atenea Core necesita confirmación en la pestaña Core antes de continuar.');
        return;
      }
      if (response.status === 'NEEDS_CLARIFICATION') {
        setMutationError('Atenea Core necesita una aclaración en la pestaña Core antes de continuar.');
      }
    } catch (actionError) {
      setMutationError(actionError instanceof Error ? actionError.message : 'El turno ha fallado');
    } finally {
      setPending(false);
    }
  };

  const handleComposerContentSizeChange = (height: number) => {
    const nextHeight = Math.max(composerMinHeight, Math.min(composerMaxHeight, Math.ceil(height)));
    setComposerInputHeight((currentHeight) => (Math.abs(currentHeight - nextHeight) > 1 ? nextHeight : currentHeight));
  };

  const submitVoiceTurn = async () => {
    if (sessionId == null) {
      return;
    }

    setMutationError(null);

    if (!voiceRecorder.isRecording) {
      return;
    }

    setPending(true);
    setVoiceStatus('Finalizando grabación...');
    try {
      setVoiceStatus('Audio grabado. Transcribiendo...');
      const transcription = await voiceRecorder.stopAndTranscribe('conversation-voice-prompt');
      const transcript = transcription.transcript.trim();
      if (!transcript) {
        setLastTranscript(null);
        setVoiceStatus('La transcripción ha llegado vacía. No he enviado nada a Codex.');
        return;
      }

      setLastTranscript(transcript);
      setVoiceStatus(`Transcripción recibida: "${transcript}"`);
      const response = await onRunCommand({
        input: transcript,
        channel: 'VOICE',
        projectId,
        workSessionId: sessionId,
        openCoreOnAttention: true,
      });
      if (response.status === 'SUCCEEDED') {
        await refreshConversationState({ silent: true });
        return;
      }
      if (response.status === 'NEEDS_CONFIRMATION') {
        setMutationError('Atenea Core necesita confirmación en la pestaña Core antes de continuar.');
        return;
      }
      if (response.status === 'NEEDS_CLARIFICATION') {
        setMutationError('Atenea Core necesita una aclaración en la pestaña Core antes de continuar.');
      }
    } catch (voiceError) {
      const message = voiceError instanceof Error ? voiceError.message : 'El turno de voz ha fallado';
      setMutationError(message);
      setVoiceStatus(message);
    } finally {
      setPending(false);
    }
  };

  const startVoiceTurn = async () => {
    if (sessionId == null) {
      return;
    }

    try {
      setVoiceStatus('Preparando grabación...');
      await voiceRecorder.start();
      setLastTranscript(null);
      setVoiceStatus('Escuchando... toca otra vez para enviar.');
    } catch (voiceError) {
      const message = voiceError instanceof Error ? voiceError.message : 'No se pudo iniciar la grabación';
      setMutationError(message);
      setVoiceStatus(message);
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
      const message = voiceError instanceof Error ? voiceError.message : 'No se pudo cancelar la grabación';
      setMutationError(message);
      setVoiceStatus(message);
    }
  };

  if (sessionId == null) {
    return (
      <Card title="Ninguna sesión seleccionada" subtitle="Elige una sesión antes de abrir el espacio de conversación.">
        <ActionButton label="Volver a sesión" onPress={onBackToSession} />
      </Card>
    );
  }

  if (loading) {
    return <LoadingBlock label={`Cargando conversación de la sesión ${sessionId}...`} />;
  }

  if (error || data == null || session == null) {
    return (
      <Card title="Conversación no disponible" subtitle={error || 'No se han recibido datos de conversación.'}>
        <View style={styles.headerActions}>
          <ActionButton label="Reintentar" onPress={() => void refreshConversationState()} />
          <ActionButton label="Volver a sesión" onPress={onBackToSession} />
        </View>
      </Card>
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
          <IconActionLink label="Volver a sesión" icon="arrow-left" onPress={onBackToSession} compact />
        </View>
        <View style={[styles.topBarSlot, styles.topBarSlotCenter]}>
          <IconActionLink label="Abrir Core" icon="spark" onPress={onOpenCore} compact />
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
        {consoleStatusLabel || consoleStatusMeta ? (
          <View style={styles.consoleHeader}>
            <View style={styles.consoleHeaderMain}>
              {consoleStatusLabel ? (
                <Text style={styles.consoleStatusLabel}>{consoleStatusLabel}</Text>
              ) : null}
              {consoleStatusMeta ? (
                <Text style={styles.consoleStatusMeta}>{consoleStatusMeta}</Text>
              ) : null}
            </View>
          </View>
        ) : null}
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
            const nearBottom = distanceFromBottom < 48;
            stickToBottomRef.current = nearBottom;
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
          {mutationError || data.view.lastError ? (
            <View style={styles.feedbackPanel}>
              <Text style={styles.feedbackTitle}>Atención</Text>
              <ScrollView
                style={styles.feedbackScroll}
                contentContainerStyle={styles.feedbackContent}
                nestedScrollEnabled
                keyboardShouldPersistTaps="handled"
              >
                {mutationError ? <Text style={styles.error}>{mutationError}</Text> : null}
                {data.view.lastError ? <Text style={styles.error}>{data.view.lastError}</Text> : null}
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
                value={turnMessage}
                onChangeText={setTurnMessage}
                placeholder="Escribe o dicta la siguiente instrucción para Codex"
              placeholderTextColor="#6e7b74"
              style={[styles.input, styles.textArea, styles.composerInput, { height: composerInputHeight }]}
              multiline
              scrollEnabled={composerInputScrollEnabled}
              onContentSizeChange={(event) => handleComposerContentSizeChange(event.nativeEvent.contentSize.height)}
              onFocus={() => {
                stickToBottomRef.current = true;
                requestAnimationFrame(scrollToBottom);
              }}
            />
              <Pressable
                onPress={() => void (composerHasText ? sendTurn() : startVoiceTurn())}
                disabled={composerHasText ? composerDisabled : interactionPending}
                style={[
                  styles.composerCircleButton,
                  styles.composerActionButton,
                  styles.composerInlineActionButton,
                  (composerHasText ? composerDisabled : interactionPending) && styles.composerCircleButtonDisabled,
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
                    color={interactionPending ? '#6f8b84' : '#dce9e5'}
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

export function RenderedTurnText({ text, operator }: { text: string; operator: boolean }) {
  const normalizedText = useMemo(() => normalizeTurnText(text), [text]);
  const blocks = useMemo(() => parseTurnBlocks(normalizedText), [normalizedText]);

  return (
    <View style={styles.messageBlocks}>
      {blocks.map((block, index) =>
        block.type === 'code' ? (
          <ScrollView
            key={`${block.type}-${index}`}
            horizontal
            style={styles.codeScroll}
            contentContainerStyle={styles.codeScrollContent}
          >
            <Text style={styles.codeText}>{block.content}</Text>
          </ScrollView>
        ) : (
          <RenderedParagraph
            key={`${block.type}-${index}`}
            text={block.content}
            operator={operator}
          />
        )
      )}
    </View>
  );
}

function normalizeTurnText(text: string) {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/```(bash|text|json|js|ts|tsx|html|css|sh|sql|xml|yaml|yml)(?!\n)/g, '```$1\n')
    .replace(/(^|\n)(#{1,3}\s+[^\n#]*?)([a-záéíóúñ])([A-ZÁÉÍÓÚÑ])/g, '$1$2$3\n$4')
    .replace(/([A-Za-zÁÉÍÓÚÑáéíóúñ])(\d+[.)]\s+)/g, '$1\n$2')
    .replace(/(:\s+)(\d+[.)]\s+)/g, '$1\n$2')
    .replace(/([^\n])\s*(\d+[.)]\s+)(?=[A-Za-zÁÉÍÓÚÑáéíóúñ])/g, '$1\n$2')
    .replace(/([^\n*])\s*([-*]\s+)(?=[`A-Za-zÁÉÍÓÚÑáéíóúñ])/g, '$1\n$2')
    .replace(/([a-záéíóúñ])((?:Si|El|La|Los|Las|Un|Una|Este|Esta|Esto|Estado|Ahora|Además|También|Pero|Como|Cuando|Puedo|Puedes|Recomiendo|Confirmo|Confirmó|Comando|Resultado)\s+)/g, '$1\n$2')
    .replace(/([^\n])(-\s+)/g, '$1\n$2');
}

function mergeSessionEvents(
  current: MobileSessionEventsResponse | null,
  incoming: MobileSessionEventsResponse
) {
  const merged = [...(incoming.events ?? []), ...(current?.events ?? [])];
  const deduped = new Map<string, MobileSessionEventsResponse['events'][number]>();

  for (const event of merged) {
    const key = [
      event.type,
      event.at ?? '',
      event.runId ?? '',
      event.turnId ?? '',
      event.deliverableId ?? '',
    ].join('|');
    if (!deduped.has(key)) {
      deduped.set(key, event);
    }
  }

  return {
    sessionId: incoming.sessionId,
    generatedAt: incoming.generatedAt,
    events: [...deduped.values()]
      .sort((left, right) => new Date(right.at).getTime() - new Date(left.at).getTime())
      .slice(0, 10),
  };
}

function describeSessionEvent(event: MobileSessionEventsResponse['events'][number]) {
  switch (event.type) {
    case 'RUN_STARTED':
      return 'Codex ha empezado a trabajar en este turno';
    case 'RUN_SUCCEEDED':
      return 'Codex terminó y dejó una respuesta nueva';
    case 'RUN_FAILED':
      return 'La ejecución terminó con error';
    case 'TURN_OPERATOR':
      return 'Has enviado una nueva instrucción';
    case 'TURN_CODEX':
      return 'Codex ha añadido un turno visible';
    case 'TURN_ATENEA':
      return 'Atenea ha añadido un turno visible';
    case 'SESSION_PUBLISHED':
      return 'La sesión se ha publicado en pull request';
    case 'SESSION_CLOSE_BLOCKED':
      return 'El cierre de la sesión sigue bloqueado';
    case 'SESSION_CLOSED':
      return 'La sesión ya está cerrada';
    case 'SESSION_OPENED':
      return 'La sesión quedó abierta y operativa';
    case 'DELIVERABLE_GENERATED':
      return 'Se ha generado un entregable';
    case 'DELIVERABLE_APPROVED':
      return 'Se ha aprobado un entregable';
    case 'DELIVERABLE_BILLED':
      return 'Se ha marcado un entregable como facturado';
    default:
      return event.title;
  }
}

function sanitizeEventDetail(value: string | null) {
  if (!value?.trim()) {
    return null;
  }
  return value.replace(/\s+/g, ' ').trim();
}

function formatElapsed(startedAt: string | null, now: number) {
  if (!startedAt) {
    return null;
  }
  const diffMs = Math.max(0, now - new Date(startedAt).getTime());
  return formatDuration(diffMs);
}

function formatRelativeTime(value: string, now: number) {
  const diffMs = Math.max(0, now - new Date(value).getTime());
  if (diffMs < 5000) {
    return 'hace unos segundos';
  }
  return `hace ${formatDuration(diffMs)}`;
}

function formatAbsoluteAndRelativeTime(value: string, now: number) {
  const timestamp = new Date(value);
  return `${timestamp.toLocaleTimeString()} · ${formatRelativeTime(value, now)}`;
}

function formatDuration(diffMs: number) {
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

function RenderedParagraph({ text, operator }: { text: string; operator: boolean }) {
  const lines = text.split('\n');

  return (
    <View style={styles.paragraphBlock}>
      {lines.map((line, index) => {
        if (line.trim() === '') {
          return <View key={`spacer-${index}`} style={styles.blankLine} />;
        }

        const trimmed = line.trimStart();
        const headingLevel = trimmed.startsWith('### ')
          ? 3
          : trimmed.startsWith('## ')
            ? 2
            : trimmed.startsWith('# ')
              ? 1
              : 0;
        const quote = trimmed.startsWith('> ');
        const bullet = /^[-*]\s+/.test(trimmed);
        const numbered = /^\d+\.\s+/.test(trimmed);
        const content = headingLevel > 0
          ? trimmed.replace(/^#{1,3}\s+/, '')
          : quote
            ? trimmed.replace(/^>\s+/, '')
            : bullet
              ? trimmed.replace(/^[-*]\s+/, '')
              : numbered
                ? trimmed.replace(/^\d+\.\s+/, '')
                : line;
        const prefix = quote
          ? '> '
          : bullet
            ? '- '
            : numbered
              ? `${trimmed.match(/^(\d+)\./)?.[1] ?? ''}. `
              : '';

        return (
          <View
            key={`${index}-${content.length}`}
            style={[
              styles.lineRow,
              headingLevel > 0 && styles.headingRow,
              quote && styles.quoteRow,
              (bullet || numbered) && styles.listRow,
            ]}
          >
            {prefix ? (
              <Text
                style={[
                  styles.turnText,
                  styles.linePrefix,
                  quote && styles.quotePrefix,
                  (bullet || numbered) && styles.listPrefix,
                ]}
              >
                {prefix}
              </Text>
            ) : null}
            <Text
              style={[
                styles.turnText,
                operator ? styles.operatorText : styles.codexText,
                headingLevel > 0 && styles.headingText,
                quote && styles.quoteText,
              ]}
            >
              {renderInlineTokens(content)}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

function renderInlineTokens(text: string) {
  const tokens = tokenizeInline(text);
  return tokens.map((token, index) => (
    <Text
      key={`${token.type}-${index}-${token.content.length}`}
      style={[
        token.type === 'bold' && styles.boldText,
        token.type === 'inlineCode' && styles.inlineCodeText,
      ]}
    >
      {token.content}
    </Text>
  ));
}

function tokenizeInline(text: string): Array<{ type: 'text' | 'bold' | 'inlineCode'; content: string }> {
  const tokens: Array<{ type: 'text' | 'bold' | 'inlineCode'; content: string }> = [];
  const regex = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let cursor = 0;

  for (const match of text.matchAll(regex)) {
    const index = match.index ?? 0;
    if (index > cursor) {
      tokens.push({ type: 'text', content: text.slice(cursor, index) });
    }

    const value = match[0];
    if (value.startsWith('**') && value.endsWith('**')) {
      tokens.push({ type: 'bold', content: value.slice(2, -2) });
    } else if (value.startsWith('`') && value.endsWith('`')) {
      tokens.push({ type: 'inlineCode', content: value.slice(1, -1) });
    }
    cursor = index + value.length;
  }

  if (cursor < text.length) {
    tokens.push({ type: 'text', content: text.slice(cursor) });
  }

  if (tokens.length === 0) {
    return [{ type: 'text', content: text }];
  }

  return tokens;
}

function parseTurnBlocks(text: string): Array<{ type: 'text' | 'code'; content: string }> {
  const normalized = text.replace(/\r\n/g, '\n');
  const blocks: Array<{ type: 'text' | 'code'; content: string }> = [];
  const regex = /```(?:[^\n`]*)\n?([\s\S]*?)```/g;
  let cursor = 0;

  for (const match of normalized.matchAll(regex)) {
    const index = match.index ?? 0;
    if (index > cursor) {
      const plain = normalized.slice(cursor, index);
      if (plain.trim() !== '') {
        blocks.push({ type: 'text', content: plain.trimEnd() });
      }
    }
    const code = match[1] ?? '';
    blocks.push({ type: 'code', content: code.trimEnd() });
    cursor = index + match[0].length;
  }

  if (cursor < normalized.length) {
    const plain = normalized.slice(cursor);
    if (plain.trim() !== '') {
      blocks.push({ type: 'text', content: plain.trimEnd() });
    }
  }

  if (blocks.length === 0) {
    return [{ type: 'text', content: normalized.trimEnd() }];
  }

  return blocks;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    gap: 6,
    backgroundColor: '#3f3f3f',
  },
  chatShell: {
    flex: 1,
    gap: 6,
    minHeight: 0,
  },
  consoleHeader: {
    gap: 2,
    paddingHorizontal: 10,
    paddingBottom: 2,
  },
  consoleHeaderMain: {
    gap: 2,
  },
  consoleTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#e8f0ec',
    fontFamily: MONO_FONT,
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
  statusScroll: {
    flexShrink: 1,
    minHeight: 0,
  },
  statusScrollContent: {
    paddingBottom: 2,
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
  backLink: {
    fontSize: 14,
    fontWeight: '800',
    color: '#179489',
    fontFamily: MONO_FONT,
  },
  refreshLink: {
    fontSize: 14,
    fontWeight: '800',
    color: '#179489',
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
  messageBlocks: {
    gap: 5,
  },
  paragraphBlock: {
    gap: 1,
  },
  lineRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  headingRow: {
    marginBottom: 4,
  },
  quoteRow: {
    marginVertical: 1,
  },
  listRow: {
    marginVertical: 1,
  },
  blankLine: {
    height: 8,
  },
  turnText: {
    fontSize: 13,
    lineHeight: 18,
    fontFamily: MONO_FONT,
    flexShrink: 1,
  },
  operatorText: {
    color: '#e7ece9',
  },
  codexText: {
    color: '#e7ece9',
  },
  turnMeta: {
    fontSize: 10,
    color: '#6f7874',
    fontFamily: MONO_FONT,
  },
  codeScroll: {
    marginVertical: 4,
  },
  codeScrollContent: {
    paddingHorizontal: 0,
    paddingVertical: 0,
  },
  codeText: {
    fontSize: 12,
    lineHeight: 18,
    color: '#179489',
    fontFamily: MONO_FONT,
  },
  headingText: {
    color: '#179489',
    fontWeight: '700',
  },
  boldText: {
    color: '#179489',
    fontWeight: '700',
  },
  inlineCodeText: {
    color: '#179489',
    fontFamily: MONO_FONT,
  },
  linePrefix: {
    flexShrink: 0,
  },
  quotePrefix: {
    color: '#179489',
  },
  listPrefix: {
    color: '#179489',
  },
  quoteText: {
    color: '#a9d8bc',
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
  voiceActions: {
    gap: 8,
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
  composerRecorderActionButton: {
    bottom: 8,
  },
  composerCancelButton: {
    left: 8,
    bottom: 8,
  },
  composerRecorderCancelButton: {
    left: 8,
    bottom: 8,
    borderColor: '#f1f5f3',
  },
  composerCircleButtonDisabled: {
    borderColor: '#29413c',
    backgroundColor: '#16211f',
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
  voiceUtilityRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  voiceButtonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  voiceMeta: {
    fontSize: 11,
    lineHeight: 16,
    color: '#8a948f',
    fontFamily: MONO_FONT,
  },
  voiceStatus: {
    fontSize: 11,
    color: '#9fe1c8',
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
  voiceTranscript: {
    fontSize: 11,
    lineHeight: 16,
    color: '#b8c7c0',
    fontFamily: MONO_FONT,
  },
  composerMeta: {
    fontSize: 11,
    color: '#78827e',
    fontFamily: MONO_FONT,
  },
  error: {
    fontSize: 13,
    fontWeight: '700',
    color: '#ff7f7f',
    fontFamily: MONO_FONT,
  },
  headerActions: {
    gap: 10,
  },
});
