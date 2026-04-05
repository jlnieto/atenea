import { useEffect, useRef, useState } from 'react';
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
  MobileSessionSummary,
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
import { playCommandSpeech, playVoicePreview, speakSpanish } from '../core/speech';
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
  const [speechStatus, setSpeechStatus] = useState<string | null>(null);
  const [speechError, setSpeechError] = useState<string | null>(null);
  const [voiceStatus, setVoiceStatus] = useState<string | null>(null);
  const [showProjectSection, setShowProjectSection] = useState(true);
  const [showPortfolioSection, setShowPortfolioSection] = useState(true);
  const [showRecentCommands, setShowRecentCommands] = useState(true);
  const [showProjectShortcuts, setShowProjectShortcuts] = useState(true);
  const [previewVoice, setPreviewVoice] = useState('nova');
  const [previewSpeed, setPreviewSpeed] = useState(1.2);
  const [previewText, setPreviewText] = useState(
    'Punto actual: el formulario principal ya está conectado. No hay bloqueo activo. Siguiente paso recomendado: probar el envío con datos reales.'
  );
  const [previewStatus, setPreviewStatus] = useState<string | null>(null);
  const [previewingVoice, setPreviewingVoice] = useState(false);
  const lastSpokenRef = useRef<string | null>(null);
  const activeCommandId = activeCommand?.commandId ?? null;
  const activeSessionSummary = extractSessionSummary(activeCommand);
  const activeSessionInsights = activeSessionSummary?.insights ?? null;
  const showStructuredSessionSummary = activeSessionInsights != null;

  const { data: commandHistory, loading: historyLoading, reload: reloadHistory } = useRemoteResource(
    () => fetchCoreCommands(),
    [operatorKey, historyVersion]
  );
  const { data: commandEvents, reload: reloadEvents } = useRemoteResource(
    () =>
      activeCommandId == null
        ? Promise.resolve<CoreCommandEventsResponse | null>(null)
        : fetchCoreCommandEvents(activeCommandId),
    [activeCommandId, historyVersion]
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
    void speakCommand(activeCommand.commandId, speakableMessage);
  }, [activeCommand, autoSpeak, authSession]);

  useEffect(() => {
    if (selectedSessionId != null) {
      setShowProjectSection(false);
      setShowPortfolioSection(false);
      setShowRecentCommands(false);
      setShowProjectShortcuts(false);
      return;
    }
    if (selectedProjectId != null) {
      setShowProjectSection(true);
      setShowPortfolioSection(false);
      setShowRecentCommands(true);
      setShowProjectShortcuts(true);
      return;
    }
    setShowProjectSection(false);
    setShowPortfolioSection(true);
    setShowRecentCommands(true);
    setShowProjectShortcuts(true);
  }, [selectedProjectId, selectedSessionId]);

  const applySpeechResult = (result: { mode: 'remote' | 'fallback-local' | 'local' | 'failed'; detail: string }) => {
    setSpeechStatus(result.detail);
    setSpeechError(result.mode === 'failed' ? result.detail : null);
  };

  const speakCommand = async (commandId: number, fallbackText: string) => {
    const result = authSession?.accessToken
      ? await playCommandSpeech(commandId, authSession.accessToken, fallbackText)
      : await speakSpanish(fallbackText);
    applySpeechResult(result);
  };

  const runSpeechProbe = async () => {
    const result = await speakSpanish('Prueba de voz de Atenea. Si oyes esto, el TTS local del dispositivo funciona.');
    applySpeechResult(result);
  };

  const runVoicePreview = async () => {
    if (!authSession?.accessToken || !previewText.trim()) {
      setPreviewStatus('La vista previa remota necesita sesión activa y un texto de prueba.');
      return;
    }
    setPreviewingVoice(true);
    const result = await playVoicePreview(previewText.trim(), previewVoice, previewSpeed, authSession.accessToken);
    setPreviewStatus(result.detail);
    setPreviewingVoice(false);
  };

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
      setVoiceStatus('Finalizando grabación...');
      try {
        const uri = await voiceRecorder.stop();
        setVoiceStatus('Audio grabado. Enviando a Atenea Core...');
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
        setVoiceStatus(`Transcripción recibida: "${response.transcript}"`);
      } catch (voiceError) {
        setError(voiceError instanceof Error ? voiceError.message : 'Voice command failed');
        setVoiceStatus(voiceError instanceof Error ? voiceError.message : 'El comando de voz ha fallado.');
      } finally {
        setSending(false);
      }
      return;
    }

    try {
      setLastTranscript(null);
      setError(null);
      setVoiceStatus('Preparando grabación...');
      await voiceRecorder.start();
      setVoiceStatus('Grabando. Pulsa `Enviar voz` para mandar el audio.');
    } catch (voiceError) {
      setError(voiceError instanceof Error ? voiceError.message : 'Voice recording failed');
      setVoiceStatus(voiceError instanceof Error ? voiceError.message : 'No se pudo iniciar la grabación de voz.');
    }
  };

  const portfolioActions = [
    {
      label: 'Estado proyectos',
      action: () => executeCommand({ input: buildPortfolioStatusCommand(), workSessionId: null }),
    },
  ];

  const projectActions = selectedProjectId == null
    ? []
    : [
        {
          label: 'Estado proyecto',
          action: () => executeCommand({
            input: buildProjectStatusCommand(),
            projectId: selectedProjectId,
            workSessionId: null,
          }),
        },
        {
          label: 'Abrir sesión',
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
      ];

  const sessionActions = selectedSessionId == null
    ? []
    : [
        {
          label: 'Estado sesión',
          action: () => executeCommand({
            input: buildSessionSummaryCommand(),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Publish',
          action: () => executeCommand({
            input: buildPublishCommand(),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Sync PR',
          action: () => executeCommand({
            input: buildSyncPullRequestCommand(),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Close',
          action: () => executeCommand({
            input: buildCloseSessionCommand(),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
      ];

  const deliverableActions = selectedSessionId == null
    ? []
    : [
        {
          label: 'Deliverables',
          action: () => executeCommand({
            input: buildSessionDeliverablesCommand(),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Gen ticket',
          action: () => executeCommand({
            input: buildGenerateDeliverableCommand('WORK_TICKET'),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Gen breakdown',
          action: () => executeCommand({
            input: buildGenerateDeliverableCommand('WORK_BREAKDOWN'),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
        {
          label: 'Gen price',
          action: () => executeCommand({
            input: buildGenerateDeliverableCommand('PRICE_ESTIMATE'),
            projectId: selectedProjectId,
            workSessionId: selectedSessionId,
          }),
        },
      ];

  if (historyLoading && commandHistory == null) {
    return <LoadingBlock label="Cargando Atenea Core..." />;
  }

  return (
    <View style={styles.container}>
      <Card
        title="Atenea Core"
        subtitle="Superficie conversacional para operar proyectos y sesiones."
      >
        <View style={styles.contextRow}>
          <StatePill label={`Proyecto ${selectedProjectId ?? 'ninguno'}`} tone={selectedProjectId ? 'good' : 'default'} />
          <StatePill label={`Sesión ${selectedSessionId ?? 'ninguna'}`} tone={selectedSessionId ? 'good' : 'default'} />
          <Pressable onPress={() => setAutoSpeak((current) => !current)}>
            <Text style={styles.link}>{autoSpeak ? 'Silenciar respuestas' : 'Leer respuestas'}</Text>
          </Pressable>
        </View>
        <Text style={styles.meta}>{coreInputHint(selectedProjectId, selectedSessionId, voiceRecorder.isRecording)}</Text>
        <TextInput
          value={draftInput}
          onChangeText={setDraftInput}
          placeholder="Escribe una orden o usa voz. Ejemplo: dime en qué punto estamos."
          placeholderTextColor="#8b7c6b"
          style={styles.input}
          multiline
        />
        <View style={styles.actions}>
          <ActionButton
            label={sending ? 'Enviando...' : 'Enviar a Core'}
            onPress={() => void submitDraft()}
            disabled={sending || !draftInput.trim()}
          />
          <ActionButton
            label={
              sending
                ? 'Enviando voz...'
                : voiceRecorder.isRecording
                ? `Enviar voz (${Math.max(1, Math.round(voiceRecorder.durationSeconds))}s)`
                : 'Grabar voz'
            }
            onPress={() => void toggleVoiceCapture()}
            disabled={sending || voiceRecorder.busy}
          />
          {activeCommand?.speakableMessage ? (
            <ActionButton
              label="Repetir voz"
              onPress={() => void speakCommand(activeCommand.commandId ?? 0, activeCommand.speakableMessage ?? '')}
            />
          ) : null}
          {activeCommand ? (
            <ActionButton label="Quitar foco" onPress={onClearActiveCommand} />
          ) : null}
        </View>
        {voiceRecorder.isRecording ? (
          <Text style={styles.meta}>
            Grabando ahora. Pulsa `Enviar voz` para parar la grabación y mandar el audio a Atenea Core.
          </Text>
        ) : null}
        {voiceRecorder.error ? (
          <Text style={styles.error}>{voiceRecorder.error}</Text>
        ) : null}
        {hasVoiceDiagnostics(lastTranscript, voiceStatus, speechStatus) ? (
          <View style={styles.voiceDiagnostics}>
            <View style={styles.voiceDiagnosticsHeader}>
              <Text style={styles.voiceDiagnosticsTitle}>Detalles de voz</Text>
              <Pressable onPress={() => void runSpeechProbe()}>
                <Text style={styles.link}>Probar voz</Text>
              </Pressable>
            </View>
            {lastTranscript ? (
              <Text style={styles.meta}>Transcripción: "{lastTranscript}"</Text>
            ) : null}
            {voiceStatus ? (
              <Text style={styles.meta}>Estado de voz: {voiceStatus}</Text>
            ) : null}
            {speechStatus ? (
              <Text style={speechError ? styles.error : styles.meta}>Estado de audio: {speechStatus}</Text>
            ) : null}
          </View>
        ) : null}
      </Card>

      <Card
        title="Banco de pruebas de voz"
        subtitle="Temporal. Compara voces y velocidades remotas antes de dejar una fija."
      >
        <Text style={styles.meta}>Voces sugeridas</Text>
        <View style={styles.optionRow}>
          {VOICE_PREVIEW_OPTIONS.map((option) => (
            <Pressable
              key={option.value}
              onPress={() => setPreviewVoice(option.value)}
              style={[styles.optionChip, previewVoice === option.value && styles.optionChipActive]}
            >
              <Text style={[styles.optionChipLabel, previewVoice === option.value && styles.optionChipLabelActive]}>
                {option.label}
              </Text>
            </Pressable>
          ))}
        </View>
        <TextInput
          value={previewVoice}
          onChangeText={setPreviewVoice}
          placeholder="ID de voz remota, por ejemplo coral"
          placeholderTextColor="#8b7c6b"
          style={styles.previewVoiceInput}
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Text style={styles.meta}>Velocidad</Text>
        <View style={styles.optionRow}>
          {VOICE_SPEED_OPTIONS.map((option) => (
            <Pressable
              key={option.toFixed(2)}
              onPress={() => setPreviewSpeed(option)}
              style={[styles.optionChip, previewSpeed === option && styles.optionChipActive]}
            >
              <Text style={[styles.optionChipLabel, previewSpeed === option && styles.optionChipLabelActive]}>
                {option.toFixed(2)}x
              </Text>
            </Pressable>
          ))}
        </View>
        <TextInput
          value={previewText}
          onChangeText={setPreviewText}
          placeholder="Texto de prueba para la voz remota"
          placeholderTextColor="#8b7c6b"
          style={styles.previewInput}
          multiline
        />
        <View style={styles.actions}>
          <ActionButton
            label={previewingVoice ? 'Probando voz...' : 'Probar voz remota'}
            onPress={() => void runVoicePreview()}
            disabled={previewingVoice || !previewText.trim() || authSession == null}
          />
        </View>
        {previewStatus ? (
          <Text style={previewStatus.includes('fall') || previewStatus.includes('Error') ? styles.error : styles.meta}>
            {previewStatus}
          </Text>
        ) : null}
      </Card>

      {selectedSessionId == null ? (
        <Card title="Portfolio" subtitle="Atajos globales que no necesitan proyecto ni sesión activa.">
          <View style={styles.quickGrid}>
            {portfolioActions.map((action) => (
              <ActionButton
                key={action.label}
                label={action.label}
                onPress={() => void action.action()}
                disabled={sending}
              />
            ))}
          </View>
        </Card>
      ) : (
        <Card title="Portfolio" subtitle="Secundario mientras haya una sesión activa.">
          <SectionToggle
            expanded={showPortfolioSection}
            onPress={() => setShowPortfolioSection((current) => !current)}
            expandedLabel="Ocultar atajos de portfolio"
            collapsedLabel="Mostrar atajos de portfolio"
          />
          {showPortfolioSection ? (
            <View style={styles.quickGrid}>
              {portfolioActions.map((action) => (
                <ActionButton
                  key={action.label}
                  label={action.label}
                  onPress={() => void action.action()}
                  disabled={sending}
                />
              ))}
            </View>
          ) : null}
        </Card>
      )}

      {selectedProjectId != null ? (
        selectedSessionId == null ? (
          <Card
            title="Proyecto"
            subtitle={`Proyecto activo ${selectedProjectId}.`}
          >
            <View style={styles.quickGrid}>
              {projectActions.map((action) => (
                <ActionButton
                  key={action.label}
                  label={action.label}
                  onPress={() => void action.action()}
                  disabled={sending}
                />
              ))}
            </View>
          </Card>
        ) : (
          <Card
            title="Proyecto"
            subtitle={`Secundario mientras la sesión ${selectedSessionId} esté activa.`}
          >
            <SectionToggle
              expanded={showProjectSection}
              onPress={() => setShowProjectSection((current) => !current)}
              expandedLabel="Ocultar atajos de proyecto"
              collapsedLabel="Mostrar atajos de proyecto"
            />
            {showProjectSection ? (
              <View style={styles.quickGrid}>
                {projectActions.map((action) => (
                  <ActionButton
                    key={action.label}
                    label={action.label}
                    onPress={() => void action.action()}
                    disabled={sending}
                  />
                ))}
              </View>
            ) : null}
          </Card>
        )
      ) : null}

      {selectedSessionId != null ? (
        <Card
          title="Sesión"
          subtitle={`Acciones principales para la sesión ${selectedSessionId}.`}
        >
          <View style={styles.quickGrid}>
            {sessionActions.map((action) => (
              <ActionButton
                key={action.label}
                label={action.label}
                onPress={() => void action.action()}
                disabled={sending}
              />
            ))}
          </View>
        </Card>
      ) : null}

      {selectedSessionId != null ? (
        <Card
          title="Entregables"
          subtitle="Genera o consulta entregables de la sesión activa."
        >
          <View style={styles.quickGrid}>
            {deliverableActions.map((action) => (
              <ActionButton
                key={action.label}
                label={action.label}
                onPress={() => void action.action()}
                disabled={sending}
              />
            ))}
          </View>
        </Card>
      ) : null}

      {activeCommand ? (
        <Card
          title={`Comando ${activeCommand.commandId}`}
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
          {showStructuredSessionSummary && activeSessionSummary ? (
            <View style={styles.contextRow}>
              <StatePill label={activeSessionSummary.conversation.view.session.status} />
              <StatePill
                label={activeSessionSummary.conversation.view.runInProgress ? 'EN CURSO' : 'EN ESPERA'}
                tone={activeSessionSummary.conversation.view.runInProgress ? 'warning' : 'good'}
              />
              {activeSessionSummary.conversation.view.session.pullRequestStatus ? (
                <StatePill label={activeSessionSummary.conversation.view.session.pullRequestStatus} />
              ) : null}
            </View>
          ) : null}
          {!showStructuredSessionSummary && activeCommand.operatorMessage ? (
            <Text style={styles.operatorMessage}>{activeCommand.operatorMessage}</Text>
          ) : null}
          {showStructuredSessionSummary && activeSessionSummary ? (
            <View style={styles.insightsBox}>
              <View style={styles.insightsHeader}>
                <Text style={styles.insightsTitle}>
                  Resumen de sesión: {activeSessionSummary.conversation.view.session.title}
                </Text>
                <StatePill
                  label={blockerLabel(activeSessionInsights.currentBlocker.category)}
                  tone={blockerTone(activeSessionInsights.currentBlocker.category)}
                />
              </View>
              <InsightRow label="Último avance" value={activeSessionInsights.latestProgress} />
              <InsightRow label="Bloqueo actual" value={activeSessionInsights.currentBlocker.summary} />
              <InsightRow label="Siguiente paso" value={activeSessionInsights.nextStepRecommended} />
            </View>
          ) : null}
          {activeCommand.result?.targetType === 'WORK_SESSION' && activeCommand.result.targetId != null ? (
            <Pressable onPress={() => onOpenSession(activeCommand.result!.targetId!)}>
              <Text style={styles.link}>Abrir sesión {activeCommand.result.targetId}</Text>
            </Pressable>
          ) : null}
          {activeCommand.status === 'NEEDS_CONFIRMATION' && activeCommand.confirmation ? (
            <>
              <Text style={styles.meta}>{activeCommand.confirmation.message}</Text>
              <ActionButton
                label={sending ? 'Confirmando...' : 'Confirmar comando'}
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
        <Card title="Error de Core" subtitle={error}>
          <Text style={styles.meta}>Revisa el contexto actual e inténtalo de nuevo.</Text>
        </Card>
      ) : null}

      <Card
        title="Comandos recientes"
        subtitle={selectedSessionId != null ? 'Secundario mientras haya una sesión activa.' : 'Últimos comandos registrados en este operador.'}
      >
        <View style={styles.contextRow}>
          <Pressable onPress={() => void reloadHistory()}>
            <Text style={styles.link}>Actualizar comandos</Text>
          </Pressable>
          {activeCommandId != null ? (
            <Pressable onPress={() => void reloadEvents()}>
              <Text style={styles.link}>Actualizar eventos</Text>
            </Pressable>
          ) : null}
          {selectedSessionId != null ? (
            <Pressable onPress={() => setShowRecentCommands((current) => !current)}>
              <Text style={styles.link}>{showRecentCommands ? 'Ocultar lista' : 'Mostrar lista'}</Text>
            </Pressable>
          ) : null}
        </View>
        {showRecentCommands ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false}>
            <View style={styles.historyList}>
              {(commandHistory?.items ?? []).slice(0, 8).map((item) => (
                <HistoryCard key={item.commandId} item={item} />
              ))}
            </View>
          </ScrollView>
        ) : null}
      </Card>

      <Card
        title="Atajos de proyecto"
        subtitle={selectedSessionId != null ? 'Secundario mientras haya una sesión activa.' : 'Usa un proyecto reciente para fijar contexto antes del siguiente comando.'}
      >
        {selectedSessionId != null ? (
          <SectionToggle
            expanded={showProjectShortcuts}
            onPress={() => setShowProjectShortcuts((current) => !current)}
            expandedLabel="Ocultar atajos"
            collapsedLabel="Mostrar atajos"
          />
        ) : null}
        {showProjectShortcuts ? (
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
        ) : null}
      </Card>
    </View>
  );
}

function extractSessionSummary(command: CoreCommandResponse | null): MobileSessionSummary | null {
  if (command?.result?.type !== 'WORK_SESSION_SUMMARY' || !command.result.payload) {
    return null;
  }
  return command.result.payload as MobileSessionSummary;
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

function coreInputHint(
  selectedProjectId: number | null,
  selectedSessionId: number | null,
  isRecording: boolean
) {
  if (isRecording) {
    return 'Grabando ahora. Pulsa otra vez para enviar el audio a Core.';
  }
  if (selectedSessionId != null) {
    return 'Sesión activa. Pide resumen de desarrollo, publicar, sincronizar PR o entregables.';
  }
  if (selectedProjectId != null) {
    return 'Proyecto activo. Puedes pedir estado del proyecto o abrir una sesión.';
  }
  return 'Sin contexto activo. Empieza por portfolio o selecciona proyecto y sesión.';
}

function hasVoiceDiagnostics(
  lastTranscript: string | null,
  voiceStatus: string | null,
  speechStatus: string | null
) {
  return lastTranscript != null || voiceStatus != null || speechStatus != null;
}

const VOICE_PREVIEW_OPTIONS = [
  { value: 'coral', label: 'Coral' },
  { value: 'nova', label: 'Nova' },
  { value: 'shimmer', label: 'Shimmer' },
  { value: 'fable', label: 'Fable' },
  { value: 'sage', label: 'Sage' },
  { value: 'alloy', label: 'Alloy' },
  { value: 'ash', label: 'Ash' },
  { value: 'ballad', label: 'Ballad' },
  { value: 'verse', label: 'Verse' },
  { value: 'echo', label: 'Echo' },
  { value: 'onyx', label: 'Onyx' },
];

const VOICE_SPEED_OPTIONS = [1.15, 1.2, 1.25, 1.3, 1.35];

function HistoryCard({
  item,
}: {
  item: CoreCommandSummaryResponse;
}) {
  const summary = compactHistorySummary(item);
  return (
    <View style={styles.historyCard}>
      <View style={styles.historyPills}>
        <StatePill label={item.status} tone={historyStatusTone(item.status)} />
        {item.intent?.capability ? <StatePill label={capabilityLabel(item.intent.capability)} /> : null}
        {item.interpretation?.source ? <StatePill label={item.interpretation.source} /> : null}
      </View>
      <Text style={styles.historyInput} numberOfLines={2}>
        {item.rawInput}
      </Text>
      {summary ? (
        <Text style={styles.historyMessage} numberOfLines={3}>
          {summary}
        </Text>
      ) : null}
      <Text style={styles.historyMeta}>{new Date(item.createdAt).toLocaleString()}</Text>
    </View>
  );
}

function compactHistorySummary(item: CoreCommandSummaryResponse) {
  if (item.errorMessage) {
    return item.errorMessage;
  }
  if (item.resultSummary) {
    return item.resultSummary;
  }
  if (item.status === 'NEEDS_CONFIRMATION') {
    return 'Esperando confirmación en Core.';
  }
  if (item.status === 'NEEDS_CLARIFICATION') {
    return 'Esperando aclaración en Core.';
  }
  return item.operatorMessage;
}

function SectionToggle({
  expanded,
  onPress,
  expandedLabel,
  collapsedLabel,
}: {
  expanded: boolean;
  onPress: () => void;
  expandedLabel: string;
  collapsedLabel: string;
}) {
  return (
    <Pressable onPress={onPress}>
      <Text style={styles.link}>{expanded ? expandedLabel : collapsedLabel}</Text>
    </Pressable>
  );
}

function capabilityLabel(value: string) {
  return value.replaceAll('_', ' ');
}

function historyStatusTone(status: CoreCommandSummaryResponse['status']) {
  switch (status) {
    case 'SUCCEEDED':
      return 'good' as const;
    case 'FAILED':
    case 'REJECTED':
      return 'danger' as const;
    case 'NEEDS_CONFIRMATION':
    case 'NEEDS_CLARIFICATION':
      return 'warning' as const;
    default:
      return 'default' as const;
  }
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
    lineHeight: 19,
    color: '#705b42',
  },
  input: {
    minHeight: 110,
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 13,
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
  optionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  optionChip: {
    paddingHorizontal: 12,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: '#efe5d6',
    borderWidth: 1,
    borderColor: '#e0d0b9',
  },
  optionChipActive: {
    backgroundColor: '#2e6a57',
    borderColor: '#2e6a57',
  },
  optionChipLabel: {
    fontSize: 12,
    fontWeight: '800',
    color: '#6c5437',
  },
  optionChipLabelActive: {
    color: '#f6f1e8',
  },
  operatorMessage: {
    fontSize: 14,
    lineHeight: 20,
    color: '#2d2218',
  },
  previewInput: {
    minHeight: 88,
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 12,
    backgroundColor: '#fffdf8',
    fontSize: 14,
    color: '#2d2218',
    textAlignVertical: 'top',
  },
  previewVoiceInput: {
    minHeight: 46,
    borderWidth: 1,
    borderColor: '#dccfb8',
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#fffdf8',
    fontSize: 14,
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
  voiceDiagnostics: {
    gap: 8,
    padding: 12,
    borderRadius: 14,
    backgroundColor: '#fff5e7',
    borderWidth: 1,
    borderColor: '#ead7b8',
  },
  voiceDiagnosticsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 8,
    flexWrap: 'wrap',
  },
  voiceDiagnosticsTitle: {
    fontSize: 14,
    fontWeight: '800',
    color: '#2d2218',
  },
  insightsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 8,
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
    padding: 13,
    borderRadius: 14,
    backgroundColor: '#f7f0e4',
    borderWidth: 1,
    borderColor: '#e3d6c0',
  },
  historyPills: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
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
