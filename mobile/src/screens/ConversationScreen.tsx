import { useEffect, useMemo, useRef, useState } from 'react';
import EventSource from 'react-native-sse';
import {
  KeyboardAvoidingView,
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
  MobileSessionSummary,
  MobileSessionEventsResponse,
  WorkSessionConversationView,
} from '../api/types';
import { RunCoreCommandOptions, RunCoreVoiceCommandOptions } from '../core/useCoreCommandCenter';
import { ActionButton } from '../components/ActionButton';
import { useAuth } from '../auth/AuthContext';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { playSessionLatestResponseSpeech, stopSpeechPlayback } from '../core/speech';
import { useVoiceRecorder } from '../core/useVoiceRecorder';
import { useRemoteResource } from '../hooks/useRemoteResource';

const MONO_FONT = Platform.select({
  ios: 'Menlo',
  android: 'monospace',
  default: 'monospace',
});

export function ConversationScreen({
  projectId,
  sessionId,
  autoReadRequest,
  onBackToSession,
  onOpenCore,
  onRunCommand,
  onRunVoiceCommand,
}: {
  projectId: number | null;
  sessionId: number | null;
  autoReadRequest?: { token: number; mode: 'brief' | 'full' } | null;
  onBackToSession: () => void;
  onOpenCore: () => void;
  onRunCommand: (options: RunCoreCommandOptions) => Promise<CoreCommandResponse>;
  onRunVoiceCommand: (options: RunCoreVoiceCommandOptions) => Promise<{ transcript: string; command: CoreCommandResponse }>;
}) {
  const { session: authSession } = useAuth();
  const voiceRecorder = useVoiceRecorder();
  const [streamHealthy, setStreamHealthy] = useState(false);
  const { data, error, loading, refreshing, reload } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<WorkSessionConversationView>(`/api/mobile/sessions/${sessionId}/conversation`),
    [sessionId],
    { refreshIntervalMs: streamHealthy ? undefined : 15000 }
  );
  const { data: summaryData, reload: reloadSummary } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<MobileSessionSummary>(`/api/mobile/sessions/${sessionId}/summary`),
    [sessionId],
    { refreshIntervalMs: streamHealthy ? undefined : 15000 }
  );
  const [turnMessage, setTurnMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [voiceStatus, setVoiceStatus] = useState<string | null>(null);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const [speechStatus, setSpeechStatus] = useState<string | null>(null);
  const [autoReadEnabled, setAutoReadEnabled] = useState(true);
  const [speechActive, setSpeechActive] = useState(false);
  const [speechPending, setSpeechPending] = useState(false);
  const scrollRef = useRef<ScrollView | null>(null);
  const stickToBottomRef = useRef(true);
  const lastStreamEventAtRef = useRef<string | null>(null);
  const latestSpokenTurnIdRef = useRef<number | null>(null);
  const consumedAutoReadTokenRef = useRef<number | null>(null);
  const speechRequestIdRef = useRef(0);

  const session = data?.view.session ?? null;
  const turns = data?.recentTurns ?? [];
  const latestAgentTurn = useMemo(
    () => [...turns].reverse().find((turn) => turn.actor === 'CODEX' || turn.actor === 'ATENEA') ?? null,
    [turns]
  );
  const quickSummary = useMemo(
    () => buildQuickSummary(summaryData, data, latestAgentTurn?.messageText ?? null),
    [summaryData, data, latestAgentTurn]
  );
  const speechBusy = speechPending || speechActive;
  const interactionPending = pending || voiceRecorder.busy || voiceRecorder.isRecording;
  const composerDisabled = interactionPending || sessionId == null || !data?.view.canCreateTurn;

  const scrollToBottom = () => {
    scrollRef.current?.scrollToEnd({ animated: true });
  };

  useEffect(() => {
    if (!loading && stickToBottomRef.current) {
      requestAnimationFrame(scrollToBottom);
    }
  }, [loading, turns.length]);

  useEffect(() => {
    setStreamHealthy(false);
  }, [sessionId, authSession]);

  useEffect(() => {
    latestSpokenTurnIdRef.current = null;
    consumedAutoReadTokenRef.current = null;
    setSpeechStatus(null);
    setSpeechActive(false);
    setSpeechPending(false);
    speechRequestIdRef.current += 1;
  }, [sessionId]);

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
        const latestEventAt = payload.events.at(-1)?.at ?? null;
        if (latestEventAt == null || latestEventAt === lastStreamEventAtRef.current) {
          return;
        }
        lastStreamEventAtRef.current = latestEventAt;
        setStreamHealthy(true);
        void Promise.all([
          reload({ silent: true }),
          reloadSummary({ silent: true }),
        ]);
      } catch {
        void Promise.all([
          reload({ silent: true }),
          reloadSummary({ silent: true }),
        ]);
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
  }, [sessionId, authSession, reload, reloadSummary]);

  useEffect(() => {
    if (!latestAgentTurn) {
      return;
    }
    if (latestSpokenTurnIdRef.current == null) {
      latestSpokenTurnIdRef.current = latestAgentTurn.id;
      return;
    }
    if (latestSpokenTurnIdRef.current === latestAgentTurn.id) {
      return;
    }
    latestSpokenTurnIdRef.current = latestAgentTurn.id;
    if (!autoReadEnabled || voiceRecorder.isRecording || pending || speechBusy) {
      return;
    }
    void playLatestResponse('brief', { automatic: true });
  }, [latestAgentTurn, autoReadEnabled, voiceRecorder.isRecording, pending, speechBusy]);

  useEffect(() => {
    if (!autoReadRequest || autoReadRequest.token === consumedAutoReadTokenRef.current) {
      return;
    }
    if (
      sessionId == null
      || data?.view.lastAgentResponse == null
      || voiceRecorder.isRecording
      || pending
      || speechBusy
    ) {
      return;
    }
    consumedAutoReadTokenRef.current = autoReadRequest.token;
    void playLatestResponse(autoReadRequest.mode, { automatic: true });
  }, [autoReadRequest, data?.view.lastAgentResponse, pending, sessionId, voiceRecorder.isRecording, speechBusy]);

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
        await Promise.all([
          reload({ silent: true }),
          reloadSummary({ silent: true }),
        ]);
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

  const playLatestResponse = async (
    mode: 'brief' | 'full',
    options?: { automatic?: boolean }
  ) => {
    const responseText = data?.view.lastAgentResponse?.trim();
    if (!sessionId || !responseText || speechPending || speechActive) {
      return;
    }
    const requestId = speechRequestIdRef.current + 1;
    speechRequestIdRef.current = requestId;
    const fallbackText = mode === 'brief'
      ? buildBriefSpeechFallback(summaryData, data)
      : buildFullSpeechFallback(data);
    setSpeechPending(true);
    setSpeechActive(true);
    setSpeechStatus(
      options?.automatic
        ? 'Nueva respuesta recibida. Leyendo resumen...'
        : mode === 'full'
          ? 'Preparando lectura completa...'
          : 'Preparando resumen hablado...'
    );
    try {
      const result = await playSessionLatestResponseSpeech(
        sessionId,
        authSession?.accessToken ?? '',
        fallbackText,
        mode
      );
      if (speechRequestIdRef.current !== requestId) {
        return;
      }
      setSpeechStatus(result.detail);
    } finally {
      if (speechRequestIdRef.current === requestId) {
        setSpeechPending(false);
        setSpeechActive(false);
      }
    }
  };

  const stopAudio = async () => {
    speechRequestIdRef.current += 1;
    await stopSpeechPlayback();
    setSpeechPending(false);
    setSpeechActive(false);
    setSpeechStatus('Lectura detenida.');
  };

  const toggleVoiceTurn = async () => {
    if (sessionId == null) {
      return;
    }

    setMutationError(null);

    if (voiceRecorder.isRecording) {
      setPending(true);
      setVoiceStatus('Finalizando grabación...');
      try {
        const uri = await voiceRecorder.stop();
        setVoiceStatus('Audio grabado. Enviando a Atenea Core...');
        const response = await onRunVoiceCommand({
          audio: {
            uri,
            name: 'conversation-voice-command.m4a',
            type: 'audio/mp4',
          },
          projectId,
          workSessionId: sessionId,
          openCoreOnAttention: true,
        });
        setLastTranscript(response.transcript);
        setVoiceStatus(`Transcripción recibida: "${response.transcript}"`);
        if (response.command.status === 'SUCCEEDED') {
          await Promise.all([
            reload({ silent: true }),
            reloadSummary({ silent: true }),
          ]);
          return;
        }
        if (response.command.status === 'NEEDS_CONFIRMATION') {
          setMutationError('Atenea Core necesita confirmación en la pestaña Core antes de continuar.');
          return;
        }
        if (response.command.status === 'NEEDS_CLARIFICATION') {
          setMutationError('Atenea Core necesita una aclaración en la pestaña Core antes de continuar.');
        }
      } catch (voiceError) {
        const message = voiceError instanceof Error ? voiceError.message : 'El turno de voz ha fallado';
        setMutationError(message);
        setVoiceStatus(message);
      } finally {
        setPending(false);
      }
      return;
    }

    try {
      await stopSpeechPlayback();
      speechRequestIdRef.current += 1;
      setSpeechPending(false);
      setSpeechActive(false);
      setSpeechStatus(null);
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
          <ActionButton label="Reintentar" onPress={() => void reload()} />
          <ActionButton label="Volver a sesión" onPress={onBackToSession} />
        </View>
      </Card>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 12 : 0}
    >
      <View style={styles.topBar}>
        <View style={styles.headerActionsRow}>
          <Pressable onPress={onBackToSession}>
            <Text style={styles.backLink}>Volver a sesión</Text>
          </Pressable>
          <Pressable onPress={onOpenCore}>
            <Text style={styles.refreshLink}>Abrir Core</Text>
          </Pressable>
        </View>
        <Pressable onPress={() => void reload()}>
          <Text style={styles.refreshLink}>{refreshing ? 'Actualizando...' : 'Actualizar'}</Text>
        </Pressable>
      </View>

      <View style={styles.chatShell}>
        {quickSummary ? (
          <View style={styles.summaryCard}>
            <View style={styles.summaryHeader}>
              <View style={styles.summaryHeaderCopy}>
                <Text style={styles.summaryTitle}>Resumen rápido</Text>
                <Text style={styles.summarySubtitle}>
                  {data.view.runInProgress
                    ? 'Codex sigue trabajando. Puedes oír el último resumen y seguir con otro proyecto.'
                    : 'Usa este bloque para decidir rápido si leer más, probar cambios o seguir pidiendo trabajo.'}
                </Text>
              </View>
              <ActionButton
                label={autoReadEnabled ? 'Autolectura: sí' : 'Autolectura: no'}
                onPress={() => setAutoReadEnabled((value) => !value)}
              />
            </View>
            <View style={styles.summaryGrid}>
              <SummaryItem label="Punto actual" value={quickSummary.latestProgress} />
              <SummaryItem label="Qué ha hecho" value={quickSummary.whatDone} />
              {quickSummary.touchedFiles ? (
                <SummaryItem label="Archivos tocados" value={quickSummary.touchedFiles} />
              ) : null}
              <SummaryItem label="Bloqueo" value={quickSummary.blocker} />
              <SummaryItem label="Siguiente paso" value={quickSummary.nextStep} />
              <SummaryItem label="Verificación" value={quickSummary.verification} />
            </View>
            <View style={styles.summaryActions}>
              <ActionButton
                label="Leer resumen"
                disabled={interactionPending || speechBusy}
                onPress={() => void playLatestResponse('brief')}
              />
              <ActionButton
                label="Leer completa"
                disabled={interactionPending || speechBusy}
                onPress={() => void playLatestResponse('full')}
              />
              {speechActive ? (
                <ActionButton
                  label="Parar audio"
                  onPress={() => void stopAudio()}
                />
              ) : null}
            </View>
          </View>
        ) : null}
        <ScrollView
          ref={scrollRef}
          style={styles.turnScroll}
          contentContainerStyle={styles.turnList}
          keyboardShouldPersistTaps="handled"
          onContentSizeChange={() => {
            if (stickToBottomRef.current) {
              requestAnimationFrame(scrollToBottom);
            }
          }}
          onScroll={(event: NativeSyntheticEvent<NativeScrollEvent>) => {
            const { contentOffset, contentSize, layoutMeasurement } = event.nativeEvent;
            const distanceFromBottom = contentSize.height - layoutMeasurement.height - contentOffset.y;
            stickToBottomRef.current = distanceFromBottom < 80;
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

        <View style={styles.composerPanel}>
          <Text style={styles.composerHint}>
            Esta conversación continúa la sesión activa. Lo normal es hablar, escuchar el resumen, revisar el bloque rápido y decidir si pides más detalle.
          </Text>
          <View style={styles.voiceDeck}>
            <Pressable
              onPress={() => void toggleVoiceTurn()}
              disabled={!voiceRecorder.isRecording && interactionPending}
              style={[
                styles.touchToTalkButton,
                voiceRecorder.isRecording && styles.touchToTalkButtonActive,
                !voiceRecorder.isRecording && interactionPending && styles.touchToTalkButtonDisabled,
              ]}
            >
              <Text style={styles.touchToTalkLabel}>
                {voiceRecorder.isRecording
                  ? `Enviar voz (${Math.max(1, Math.round(voiceRecorder.durationSeconds))}s)`
                  : 'Tocar para hablar'}
              </Text>
              <Text style={styles.touchToTalkMeta}>
                {voiceRecorder.isRecording
                  ? 'Toca otra vez para enviar la instrucción a Codex.'
                  : 'Un toque empieza a escuchar. Otro toque envía la orden en esta sesión.'}
              </Text>
            </Pressable>
            <View style={styles.voiceUtilityRow}>
              <ActionButton
                label={speechActive ? 'Parar audio' : 'Repetir resumen'}
                disabled={interactionPending || speechPending}
                onPress={() => void (speechActive ? stopAudio() : playLatestResponse('brief'))}
              />
              <ActionButton
                label="Abrir Core"
                onPress={onOpenCore}
              />
            </View>
          </View>
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
          {voiceStatus ? <Text style={styles.voiceStatus}>{voiceStatus}</Text> : null}
          {lastTranscript ? <Text style={styles.voiceTranscript}>Última transcripción: {lastTranscript}</Text> : null}
          {speechStatus ? <Text style={styles.voiceStatus}>{speechStatus}</Text> : null}
          <TextInput
            value={turnMessage}
            onChangeText={setTurnMessage}
            placeholder="Si prefieres, escribe la siguiente instrucción para Codex"
            placeholderTextColor="#6e7b74"
            style={[styles.input, styles.textArea]}
            multiline
          />
          <View style={styles.composerRow}>
            <Text style={styles.composerMeta}>
              {data.view.runInProgress
                ? 'Hay una ejecución en curso. Puedes actualizar y esperar la última respuesta.'
                : 'El prompt se enviará al hilo actual de la WorkSession.'}
            </Text>
            <Pressable
              onPress={() => void sendTurn()}
              disabled={composerDisabled || !turnMessage.trim()}
              style={[
                styles.sendButton,
                (composerDisabled || !turnMessage.trim()) && styles.sendButtonDisabled,
              ]}
            >
              <Text
                style={[
                  styles.sendButtonLabel,
                  (composerDisabled || !turnMessage.trim()) && styles.sendButtonLabelDisabled,
                ]}
              >
                {pending && !voiceRecorder.isRecording ? 'Enviando...' : 'Enviar prompt'}
              </Text>
            </Pressable>
          </View>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

function RenderedTurnText({ text, operator }: { text: string; operator: boolean }) {
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
    .replace(/([a-záéíóúñ])(\d)/g, '$1 $2')
    .replace(/(\d)([A-Za-zÁÉÍÓÚÑáéíóúñ])/g, '$1 $2')
    .replace(/([^\n])(-\s+)/g, '$1\n$2');
}

function buildBriefSpeechFallback(
  summary: MobileSessionSummary | null,
  conversation: WorkSessionConversationView | null
) {
  const insights = summary?.insights;
  const clauses: string[] = [];

  if (insights?.latestProgress?.trim()) {
    clauses.push(`Último avance: ${trimTrailingPunctuation(insights.latestProgress)}.`);
  } else {
    const point = extractSection(conversation?.view.lastAgentResponse ?? '', 'Punto actual');
    if (point) {
      clauses.push(`Punto actual: ${trimTrailingPunctuation(point)}.`);
    }
  }

  if (summary?.conversation.view.runInProgress) {
    clauses.push('Codex sigue trabajando en esta sesión.');
  }

  const blocker = insights?.currentBlocker;
  if (blocker?.summary?.trim()) {
    if (blocker.category === 'NONE') {
      clauses.push('Bloqueo actual: Sin bloqueo activo.');
    } else {
      clauses.push(`Bloqueo actual: ${trimTrailingPunctuation(blocker.summary)}.`);
    }
  }

  if (insights?.nextStepRecommended?.trim()) {
    clauses.push(`Siguiente paso: ${trimTrailingPunctuation(insights.nextStepRecommended)}.`);
  } else {
    const nextStep = extractSection(conversation?.view.lastAgentResponse ?? '', 'Siguiente paso recomendado');
    if (nextStep) {
      clauses.push(`Siguiente paso: ${trimTrailingPunctuation(nextStep)}.`);
    }
  }

  const touchedFiles = summarizeTouchedFiles(conversation?.recentTurns ?? [], conversation?.view.lastAgentResponse ?? '');
  if (touchedFiles) {
    clauses.push(`Archivos tocados: ${trimTrailingPunctuation(touchedFiles)}.`);
  }

  const verification = trimTrailingPunctuation(
    extractSection(conversation?.view.lastAgentResponse ?? '', 'Verificación')
      ?? 'Sin bloque de verificación explícito'
  );
  if (verification && verification !== 'Sin bloque de verificación explícito') {
    clauses.push(`Verificación: ${verification}.`);
  }

  if (clauses.length > 0) {
    return clauses.join(' ');
  }

  return conversation?.view.lastAgentResponse?.trim() ?? '';
}

function buildFullSpeechFallback(conversation: WorkSessionConversationView | null) {
  const latestAgentTurn = [...(conversation?.recentTurns ?? [])]
    .reverse()
    .find((turn) => turn.actor === 'CODEX' || turn.actor === 'ATENEA');
  const source = latestAgentTurn?.messageText?.trim() || conversation?.view.lastAgentResponse?.trim() || '';
  if (!source) {
    return '';
  }

  const sections = [
    buildSectionSentence(source, 'Punto actual'),
    buildSectionSentence(source, 'Qué he encontrado'),
    buildSectionSentence(source, 'Qué he hecho'),
    buildSectionSentence(source, 'Bloqueo actual'),
    buildSectionSentence(source, 'Siguiente paso recomendado'),
    buildSectionSentence(source, 'Verificación'),
  ].filter(Boolean);

  if (sections.length > 0) {
    return sections.join(' ');
  }

  return sanitizeSpeechBody(source) ?? source;
}

function extractSection(text: string, title: string) {
  if (!text.trim()) {
    return null;
  }
  const pattern = new RegExp(`##\\s+${title}\\s+([\\s\\S]*?)(?=\\n##\\s+|$)`, 'i');
  const match = text.match(pattern);
  if (!match?.[1]) {
    return null;
  }
  return match[1]
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/\[[^\]]+\]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

function trimTrailingPunctuation(text: string) {
  return text.replace(/\s+/g, ' ').replace(/[.。!！?？:：]+$/g, '').trim();
}

function buildSectionSentence(text: string, heading: string) {
  const extracted = extractSection(text, heading);
  if (!extracted) {
    return null;
  }
  const sanitized = sanitizeSpeechBody(extracted);
  if (!sanitized) {
    return null;
  }
  if (sanitized.toLowerCase().startsWith('ruta actual del proyecto')) {
    return null;
  }
  return `${heading}: ${trimTrailingPunctuation(sanitized)}.`;
}

function sanitizeSpeechBody(text: string) {
  const sanitized = text
    .replace(/```[\s\S]*?```/g, ' He preparado cambios en código y el detalle está disponible en pantalla. ')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^.*\/workspace\/.*$/gm, ' ')
    .replace(/^.*python3 -m http\.server.*$/gm, ' ')
    .replace(/^.*http:\/\/localhost:8000\/.*$/gm, ' ')
    .replace(/^.*Abrir Core.*$/gm, ' ')
    .replace(/^.*Archivos relevantes.*$/gim, ' ')
    .replace(/^.*Comandos útiles.*$/gim, ' ')
    .replace(/^[-*]\s+/gm, '')
    .replace(/^\d+\.\s+/gm, '')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\b\/[^\s)]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  if (!sanitized) {
    return null;
  }
  return sanitized;
}

function buildQuickSummary(
  summary: MobileSessionSummary | null,
  conversation: WorkSessionConversationView | null,
  latestAgentText: string | null
) {
  const source = latestAgentText ?? conversation?.view.lastAgentResponse ?? '';
  const latestProgress = trimTrailingPunctuation(
    summary?.insights?.latestProgress
      ?? extractSection(source, 'Punto actual')
      ?? 'No hay un punto actual resumido disponible'
  );
  const whatDone = trimTrailingPunctuation(
    extractSection(source, 'Qué he hecho')
      ?? 'No hay una lista breve de cambios en la última respuesta'
  );
  const blocker = summary?.insights?.currentBlocker?.category === 'NONE'
    ? 'Sin bloqueo activo'
    : trimTrailingPunctuation(
      summary?.insights?.currentBlocker?.summary
        ?? extractSection(source, 'Bloqueo actual')
        ?? 'No se ha detectado un bloqueo explícito'
    );
  const nextStep = trimTrailingPunctuation(
    summary?.insights?.nextStepRecommended
      ?? extractSection(source, 'Siguiente paso recomendado')
      ?? 'No se ha detectado un siguiente paso claro'
  );
  const verification = trimTrailingPunctuation(
    extractSection(source, 'Verificación')
      ?? 'Sin bloque de verificación explícito en la última respuesta'
  );
  const touchedFiles = summarizeTouchedFiles(conversation?.recentTurns ?? [], source);

  return {
    latestProgress,
    whatDone,
    touchedFiles,
    blocker,
    nextStep,
    verification,
  };
}

function SummaryItem({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.summaryItem}>
      <Text style={styles.summaryLabel}>{label}</Text>
      <Text style={styles.summaryValue}>{value}</Text>
    </View>
  );
}

function summarizeTouchedFiles(
  turns: WorkSessionConversationView['recentTurns'],
  source: string
) {
  const latestAgentTurn = [...turns].reverse().find((turn) => turn.actor === 'CODEX' || turn.actor === 'ATENEA');
  const candidateSource = latestAgentTurn?.messageText ?? source;
  const sections = [
    extractSection(candidateSource, 'Qué he hecho'),
    extractSection(candidateSource, 'Archivos relevantes'),
    extractSection(candidateSource, 'Qué he encontrado'),
  ].filter(Boolean).join('\n');

  if (!sections.trim()) {
    return null;
  }

  const labels = new Set<string>();
  collectFileLabels(labels, sections, /\[([^\]]+)]\([^)]+\)/g);
  collectFileLabels(labels, sections, /`([^`]+\.[A-Za-z0-9]+)`/g);
  collectFileLabels(labels, sections, /([A-Za-z0-9._/-]+\.[A-Za-z0-9]+)/g);

  const compact = [...labels]
    .map(compactFileLabel)
    .filter((value): value is string => Boolean(value))
    .slice(0, 3);

  if (compact.length === 0) {
    return null;
  }
  if (compact.length === 1) {
    return compact[0];
  }
  if (compact.length === 2) {
    return `${compact[0]} y ${compact[1]}`;
  }
  return `${compact[0]}, ${compact[1]} y ${compact[2]}`;
}

function collectFileLabels(target: Set<string>, source: string, pattern: RegExp) {
  for (const match of source.matchAll(pattern)) {
    const value = match[1]?.trim();
    if (value) {
      target.add(value);
    }
  }
}

function compactFileLabel(raw: string | undefined) {
  if (!raw) {
    return null;
  }
  const normalized = raw
    .replace(/`/g, '')
    .replace(/#L\d+.*$/i, '')
    .replace(/\?.*$/, '')
    .trim();
  const lastSlash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
  const label = lastSlash >= 0 ? normalized.slice(lastSlash + 1) : normalized;
  if (!label.includes('.')) {
    return null;
  }
  return label;
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
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
    paddingBottom: 6,
    paddingTop: 10,
    paddingHorizontal: 10,
    backgroundColor: '#3f3f3f',
  },
  headerActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    flexWrap: 'wrap',
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
  summaryCard: {
    gap: 10,
    marginHorizontal: 10,
    marginBottom: 6,
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderWidth: 1,
    borderColor: '#274842',
    backgroundColor: '#213130',
  },
  summaryHeader: {
    gap: 10,
  },
  summaryHeaderCopy: {
    gap: 4,
  },
  summaryTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: '#eaf8f2',
    fontFamily: MONO_FONT,
  },
  summarySubtitle: {
    fontSize: 11,
    lineHeight: 16,
    color: '#9eb5ae',
    fontFamily: MONO_FONT,
  },
  summaryGrid: {
    gap: 8,
  },
  summaryItem: {
    gap: 2,
  },
  summaryLabel: {
    fontSize: 11,
    color: '#73d0bd',
    fontWeight: '800',
    fontFamily: MONO_FONT,
  },
  summaryValue: {
    fontSize: 12,
    lineHeight: 18,
    color: '#e7ece9',
    fontFamily: MONO_FONT,
  },
  summaryActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
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
    paddingVertical: 8,
    backgroundColor: '#585858',
    fontSize: 13,
    color: '#ecf3ef',
    fontFamily: MONO_FONT,
  },
  textArea: {
    minHeight: 84,
    maxHeight: 150,
    textAlignVertical: 'top',
  },
  composerPanel: {
    gap: 8,
    paddingTop: 10,
    paddingBottom: 14,
    paddingHorizontal: 0,
  },
  composerHint: {
    fontSize: 11,
    lineHeight: 16,
    color: '#8a948f',
    fontFamily: MONO_FONT,
  },
  voiceActions: {
    gap: 8,
  },
  voiceDeck: {
    gap: 10,
  },
  touchToTalkButton: {
    gap: 4,
    paddingHorizontal: 14,
    paddingVertical: 14,
    borderWidth: 1,
    borderColor: '#179489',
    backgroundColor: '#1d3c38',
  },
  touchToTalkButtonActive: {
    borderColor: '#8be5cf',
    backgroundColor: '#22514a',
  },
  touchToTalkButtonDisabled: {
    borderColor: '#29413c',
    backgroundColor: '#16211f',
  },
  touchToTalkLabel: {
    fontSize: 16,
    fontWeight: '800',
    color: '#f0fbf7',
    fontFamily: MONO_FONT,
  },
  touchToTalkMeta: {
    fontSize: 11,
    lineHeight: 16,
    color: '#b8c7c0',
    fontFamily: MONO_FONT,
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
  voiceTranscript: {
    fontSize: 11,
    lineHeight: 16,
    color: '#b8c7c0',
    fontFamily: MONO_FONT,
  },
  composerRow: {
    gap: 8,
    alignItems: 'flex-end',
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
  sendButton: {
    alignSelf: 'flex-end',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#2c4046',
    borderWidth: 1,
    borderColor: '#179489',
  },
  sendButtonDisabled: {
    backgroundColor: '#0c1511',
    borderColor: '#1a2420',
  },
  sendButtonLabel: {
    fontSize: 13,
    fontWeight: '800',
    color: '#179489',
    fontFamily: MONO_FONT,
  },
  sendButtonLabelDisabled: {
    color: '#4f675c',
  },
  headerActions: {
    gap: 10,
  },
});
