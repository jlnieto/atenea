import { useEffect, useMemo, useState } from 'react';
import {
  Keyboard,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { fetchCoreCommands } from '../api/core';
import {
  CoreClarificationOptionResponse,
  CoreCommandResponse,
  CoreCommandSummaryResponse,
  CoreRequestContext,
} from '../api/types';
import { AppIcon } from '../components/AppIcon';
import { LoadingBlock } from '../components/LoadingBlock';
import { StatePill } from '../components/StatePill';
import {
  labelCoreCapability,
  labelCoreCommandStatus,
  labelCoreRiskLevel,
  toneCoreCommandStatus,
  toneCoreRiskLevel,
} from '../core/presentation';
import { RunCoreCommandOptions, RunCoreVoiceCommandOptions } from '../core/useCoreCommandCenter';
import { useRemoteResource } from '../hooks/useRemoteResource';
import { useAteneaVoiceEngine } from '../voice/useAteneaVoiceEngine';

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

type CoreScope = NonNullable<CoreRequestContext['scope']>;

const quickPrompts = [
  { label: 'Apache', input: 'comprueba apache en el dedicado' },
  { label: 'Recovery', input: 'recupera apache en el dedicado' },
  { label: 'Incidencias', input: 'lista incidencias de operaciones' },
];

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
  const voiceRecorder = useAteneaVoiceEngine({ context: 'core' });
  const [draftInput, setDraftInput] = useState('');
  const [scope, setScope] = useState<CoreScope>('GLOBAL');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);

  const { data: commandHistory, loading: historyLoading, reload: reloadHistory } = useRemoteResource(
    () => fetchCoreCommands(),
    [operatorKey, historyVersion]
  );

  const scopeOptions = useMemo(
    () => [
      { value: 'GLOBAL' as const, label: 'Global', disabled: false },
      { value: 'PROJECT' as const, label: 'Proyecto', disabled: selectedProjectId == null },
      { value: 'SESSION' as const, label: 'Sesión', disabled: selectedSessionId == null },
    ],
    [selectedProjectId, selectedSessionId]
  );

  useEffect(() => {
    if ((scope === 'PROJECT' && selectedProjectId == null) || (scope === 'SESSION' && selectedSessionId == null)) {
      setScope('GLOBAL');
    }
  }, [scope, selectedProjectId, selectedSessionId]);

  const scopeContext = (nextScope: CoreScope = scope) => {
    if (nextScope === 'PROJECT') {
      return { projectId: selectedProjectId, workSessionId: null, scope: nextScope };
    }
    if (nextScope === 'SESSION') {
      return { projectId: selectedProjectId, workSessionId: selectedSessionId, scope: nextScope };
    }
    return { projectId: null, workSessionId: null, scope: nextScope };
  };

  const executeCommand = async (options: RunCoreCommandOptions) => {
    setSending(true);
    setError(null);
    try {
      const response = await onRunCommand(options);
      await reloadHistory({ silent: true });
      return response;
    } catch (commandError) {
      setError(commandError instanceof Error ? commandError.message : 'No se pudo ejecutar el comando.');
      throw commandError;
    } finally {
      setSending(false);
    }
  };

  const submitDraft = async () => {
    const input = draftInput.trim();
    if (!input || sending) {
      return;
    }
    Keyboard.dismiss();
    const context = scopeContext();
    try {
      await executeCommand({
        input,
        channel: 'TEXT',
        projectId: context.projectId,
        workSessionId: context.workSessionId,
        scope: context.scope,
      });
      setDraftInput('');
    } catch {
      // The inline error is already shown above the history.
    }
  };

  const runQuickPrompt = async (input: string) => {
    if (sending) {
      return;
    }
    const context = scopeContext('GLOBAL');
    try {
      await executeCommand({
        input,
        channel: 'TEXT',
        projectId: context.projectId,
        workSessionId: context.workSessionId,
        scope: context.scope,
      });
    } catch {
      // The inline error is already shown above the history.
    }
  };

  const toggleVoiceCapture = async () => {
    if (sending) {
      return;
    }
    if (voiceRecorder.isRecording) {
      if (!voiceRecorder.isListening) {
        return;
      }
      setSending(true);
      setError(null);
      try {
        const capture = await voiceRecorder.stop('core-voice-command');
        const context = scopeContext();
        const response = await onRunVoiceCommand({
          audio: capture.audio,
          projectId: context.projectId,
          workSessionId: context.workSessionId,
          scope: context.scope,
        });
        setLastTranscript(response.transcript);
        await reloadHistory({ silent: true });
      } catch (voiceError) {
        setError(voiceError instanceof Error ? voiceError.message : 'No se pudo procesar el audio.');
      } finally {
        setSending(false);
      }
      return;
    }

    setError(null);
    setLastTranscript(null);
    try {
      await voiceRecorder.start();
    } catch (voiceError) {
      setError(voiceError instanceof Error ? voiceError.message : 'No se pudo iniciar el micrófono.');
    }
  };

  const confirmActiveCommand = async () => {
    setSending(true);
    setError(null);
    try {
      await onConfirmActiveCommand();
      await reloadHistory({ silent: true });
    } catch (confirmError) {
      setError(confirmError instanceof Error ? confirmError.message : 'No se pudo confirmar la acción.');
    } finally {
      setSending(false);
    }
  };

  const resolveClarification = async (option: CoreClarificationOptionResponse) => {
    setSending(true);
    setError(null);
    try {
      await onResolveClarification(option);
      await reloadHistory({ silent: true });
    } catch (clarificationError) {
      setError(clarificationError instanceof Error ? clarificationError.message : 'No se pudo aplicar la selección.');
    } finally {
      setSending(false);
    }
  };

  const recentCommands = commandHistory?.items.slice(0, 20) ?? [];
  const activeMessage = activeCommand == null ? null : commandMessage(activeCommand);
  const activeWorkSessionId =
    activeCommand?.result?.targetType === 'WORK_SESSION' && activeCommand.result.targetId != null
      ? activeCommand.result.targetId
      : null;

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 84 : 0}
    >
      <View style={styles.composerSurface}>
        <View style={styles.scopeRow}>
          {scopeOptions.map((option) => (
            <Pressable
              key={option.value}
              disabled={option.disabled}
              onPress={() => setScope(option.value)}
              style={[
                styles.scopeButton,
                scope === option.value && styles.scopeButtonActive,
                option.disabled && styles.scopeButtonDisabled,
              ]}
            >
              <Text
                style={[
                  styles.scopeButtonText,
                  scope === option.value && styles.scopeButtonTextActive,
                  option.disabled && styles.scopeButtonTextDisabled,
                ]}
              >
                {option.label}
              </Text>
            </Pressable>
          ))}
        </View>

        <View style={styles.inputRow}>
          <TextInput
            value={draftInput}
            onChangeText={setDraftInput}
            placeholder="Escribe a Atenea"
            placeholderTextColor="#82766b"
            multiline
            style={styles.input}
            editable={!sending && !voiceRecorder.isRecording}
            returnKeyType="send"
            onSubmitEditing={submitDraft}
          />
          <Pressable
            onPress={toggleVoiceCapture}
            disabled={sending}
            style={[
              styles.iconButton,
              voiceRecorder.isRecording && styles.iconButtonRecording,
              sending && styles.iconButtonDisabled,
            ]}
          >
            <AppIcon name="microphone" color={voiceRecorder.isRecording ? '#ffffff' : '#24463d'} />
          </Pressable>
          <Pressable
            onPress={submitDraft}
            disabled={sending || draftInput.trim().length === 0}
            style={[
              styles.iconButton,
              styles.sendButton,
              (sending || draftInput.trim().length === 0) && styles.iconButtonDisabled,
            ]}
          >
            <AppIcon name="send-up" color="#ffffff" />
          </Pressable>
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.quickRow}>
          {quickPrompts.map((prompt) => (
            <Pressable
              key={prompt.label}
              onPress={() => void runQuickPrompt(prompt.input)}
              disabled={sending}
              style={[styles.quickChip, sending && styles.quickChipDisabled]}
            >
              <Text style={styles.quickChipText}>{prompt.label}</Text>
            </Pressable>
          ))}
        </ScrollView>
      </View>

      <ScrollView
        style={styles.thread}
        contentContainerStyle={styles.threadContent}
        keyboardShouldPersistTaps="handled"
      >
        {error ? <Text style={styles.errorText}>{error}</Text> : null}
        {voiceRecorder.isRecording ? (
          <Text style={styles.recordingText}>
            {voiceRecorder.isListening ? 'Grabando...' : 'Activando micrófono...'}
          </Text>
        ) : null}
        {lastTranscript ? <Text style={styles.transcriptText}>Voz: {lastTranscript}</Text> : null}

        {activeCommand ? (
          <View style={styles.responseBlock}>
            <View style={styles.responseHeader}>
              <View style={styles.pillRow}>
                <StatePill
                  label={labelCoreCommandStatus(activeCommand.status)}
                  tone={toneCoreCommandStatus(activeCommand.status)}
                />
                {activeCommand.intent ? (
                  <>
                    <StatePill label={labelCoreCapability(activeCommand.intent.capability)} tone="info" />
                    <StatePill
                      label={labelCoreRiskLevel(activeCommand.intent.riskLevel)}
                      tone={toneCoreRiskLevel(activeCommand.intent.riskLevel)}
                    />
                  </>
                ) : null}
              </View>
              <Pressable onPress={onClearActiveCommand} style={styles.closeButton}>
                <AppIcon name="close" size={18} color="#6c6258" />
              </Pressable>
            </View>

            {activeMessage ? <Text style={styles.responseText}>{activeMessage}</Text> : null}

            {activeCommand.confirmation ? (
              <View style={styles.actionRow}>
                <Pressable
                  onPress={confirmActiveCommand}
                  disabled={sending}
                  style={[styles.primaryAction, sending && styles.actionDisabled]}
                >
                  <Text style={styles.primaryActionText}>Confirmar</Text>
                </Pressable>
              </View>
            ) : null}

            {activeCommand.clarification ? (
              <View style={styles.optionList}>
                {activeCommand.clarification.options.map((option) => (
                  <Pressable
                    key={`${option.type}:${option.targetId ?? option.label}`}
                    onPress={() => void resolveClarification(option)}
                    disabled={sending}
                    style={[styles.optionButton, sending && styles.actionDisabled]}
                  >
                    <Text style={styles.optionButtonText}>{option.label}</Text>
                  </Pressable>
                ))}
              </View>
            ) : null}

            {activeWorkSessionId != null ? (
              <Pressable onPress={() => onOpenSession(activeWorkSessionId)} style={styles.secondaryAction}>
                <Text style={styles.secondaryActionText}>Abrir sesión</Text>
              </Pressable>
            ) : null}
          </View>
        ) : null}

        <Text style={styles.sectionTitle}>Historial</Text>
        {historyLoading ? <LoadingBlock label="Cargando historial" /> : null}
        {!historyLoading && recentCommands.length === 0 ? (
          <Text style={styles.emptyText}>No hay comandos todavía.</Text>
        ) : null}
        {recentCommands.map((command) => (
          <HistoryItem key={command.commandId} command={command} />
        ))}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

function HistoryItem({ command }: { command: CoreCommandSummaryResponse }) {
  const message = summaryMessage(command);
  return (
    <View style={styles.historyItem}>
      <View style={styles.historyMetaRow}>
        <Text style={styles.historyTime}>{formatTime(command.createdAt)}</Text>
        <StatePill label={labelCoreCommandStatus(command.status)} tone={toneCoreCommandStatus(command.status)} />
      </View>
      <Text style={styles.userText}>{command.rawInput}</Text>
      {message ? <Text style={styles.historyResponseText}>{message}</Text> : null}
    </View>
  );
}

function commandMessage(command: CoreCommandResponse) {
  return (
    command.operatorMessage?.trim()
    || command.speakableMessage?.trim()
    || command.confirmation?.message?.trim()
    || command.clarification?.message?.trim()
    || null
  );
}

function summaryMessage(command: CoreCommandSummaryResponse) {
  return (
    command.operatorMessage?.trim()
    || command.speakableMessage?.trim()
    || command.resultSummary?.trim()
    || command.errorMessage?.trim()
    || null
  );
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f6f3ee',
  },
  composerSurface: {
    borderBottomWidth: 1,
    borderBottomColor: '#ded7cd',
    backgroundColor: '#fffdf8',
    paddingHorizontal: 14,
    paddingTop: 12,
    paddingBottom: 10,
    gap: 10,
  },
  scopeRow: {
    flexDirection: 'row',
    gap: 8,
  },
  scopeButton: {
    flex: 1,
    minHeight: 34,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#cfc5b7',
    backgroundColor: '#f5efe6',
  },
  scopeButtonActive: {
    borderColor: '#24463d',
    backgroundColor: '#24463d',
  },
  scopeButtonDisabled: {
    opacity: 0.45,
  },
  scopeButtonText: {
    fontSize: 13,
    fontWeight: '800',
    color: '#4d443c',
  },
  scopeButtonTextActive: {
    color: '#ffffff',
  },
  scopeButtonTextDisabled: {
    color: '#877b70',
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 8,
  },
  input: {
    flex: 1,
    minHeight: 48,
    maxHeight: 118,
    borderWidth: 1,
    borderColor: '#cfc5b7',
    borderRadius: 10,
    backgroundColor: '#ffffff',
    paddingHorizontal: 12,
    paddingTop: 12,
    paddingBottom: 10,
    fontSize: 16,
    color: '#201c18',
  },
  iconButton: {
    width: 48,
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#cfc5b7',
    backgroundColor: '#eef6f2',
  },
  iconButtonRecording: {
    borderColor: '#8e2c24',
    backgroundColor: '#8e2c24',
  },
  iconButtonDisabled: {
    opacity: 0.45,
  },
  sendButton: {
    borderColor: '#24463d',
    backgroundColor: '#24463d',
  },
  quickRow: {
    gap: 8,
    paddingRight: 10,
  },
  quickChip: {
    minHeight: 32,
    justifyContent: 'center',
    borderRadius: 999,
    borderWidth: 1,
    borderColor: '#b8cfc6',
    backgroundColor: '#e9f3ef',
    paddingHorizontal: 12,
  },
  quickChipDisabled: {
    opacity: 0.45,
  },
  quickChipText: {
    fontSize: 13,
    fontWeight: '800',
    color: '#24463d',
  },
  thread: {
    flex: 1,
  },
  threadContent: {
    padding: 14,
    paddingBottom: 36,
    gap: 12,
  },
  errorText: {
    borderRadius: 8,
    backgroundColor: '#f0d1cb',
    padding: 12,
    color: '#7b2517',
    fontSize: 13,
    fontWeight: '700',
  },
  recordingText: {
    color: '#8e2c24',
    fontSize: 13,
    fontWeight: '800',
  },
  transcriptText: {
    color: '#50463f',
    fontSize: 13,
  },
  responseBlock: {
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#d7cec2',
    backgroundColor: '#ffffff',
    padding: 12,
    gap: 12,
  },
  responseHeader: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 8,
  },
  pillRow: {
    flex: 1,
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  closeButton: {
    width: 32,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  responseText: {
    fontSize: 15,
    lineHeight: 22,
    color: '#201c18',
  },
  actionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  primaryAction: {
    minHeight: 42,
    justifyContent: 'center',
    borderRadius: 8,
    backgroundColor: '#164b3f',
    paddingHorizontal: 14,
  },
  primaryActionText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '800',
  },
  secondaryAction: {
    alignSelf: 'flex-start',
    minHeight: 38,
    justifyContent: 'center',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#8b765a',
    paddingHorizontal: 12,
  },
  secondaryActionText: {
    color: '#2f2419',
    fontSize: 13,
    fontWeight: '800',
  },
  actionDisabled: {
    opacity: 0.55,
  },
  optionList: {
    gap: 8,
  },
  optionButton: {
    minHeight: 42,
    justifyContent: 'center',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#cfc5b7',
    backgroundColor: '#f7f2eb',
    paddingHorizontal: 12,
  },
  optionButtonText: {
    color: '#2f2419',
    fontSize: 14,
    fontWeight: '800',
  },
  sectionTitle: {
    marginTop: 4,
    fontSize: 12,
    fontWeight: '900',
    color: '#786c60',
    textTransform: 'uppercase',
  },
  emptyText: {
    color: '#705b42',
    fontSize: 14,
  },
  historyItem: {
    borderBottomWidth: 1,
    borderBottomColor: '#ded7cd',
    paddingBottom: 12,
    gap: 8,
  },
  historyMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  historyTime: {
    minWidth: 42,
    color: '#80756b',
    fontSize: 12,
    fontWeight: '800',
  },
  userText: {
    fontSize: 15,
    lineHeight: 21,
    color: '#201c18',
    fontWeight: '700',
  },
  historyResponseText: {
    fontSize: 14,
    lineHeight: 20,
    color: '#4f473f',
  },
});
