import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  RecordingPresets,
  getRecordingPermissionsAsync,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
  useAudioRecorder,
  useAudioRecorderState,
} from 'expo-audio';
import * as FileSystem from 'expo-file-system';
import { Platform } from 'react-native';
import { createCoreVoiceTranscription } from '../api/core';

const recorderPollMs = 80;
const minimumReadyMs = 420;
const readyTimeoutMs = 1800;
const restartReadyTimeoutMs = 2200;
const minimumUsefulDurationSeconds = 0.25;

export type AteneaVoiceContext = 'core' | 'conversation' | 'rescue';

export type AteneaVoiceStatus =
  | 'idle'
  | 'requesting_permission'
  | 'preparing'
  | 'ready'
  | 'warming_up'
  | 'listening'
  | 'finalizing'
  | 'transcribing'
  | 'failed';

export type AteneaVoiceAudioUpload = {
  uri: string;
  name: string;
  type: string;
};

export type AteneaVoiceTelemetry = {
  attemptId: string;
  context: AteneaVoiceContext;
  platform: string;
  startedAt: number;
  permissionMs: number | null;
  prepareMs: number | null;
  readyMs: number | null;
  firstDurationMs: number | null;
  firstMeteringMs: number | null;
  watchdogRestarted: boolean;
  durationSeconds: number;
  peakLevel: number;
  averageLevel: number;
  levelSamples: number;
  fileSizeBytes: number | null;
  completedAt: number | null;
  error: string | null;
};

export type AteneaVoiceCapture = {
  audio: AteneaVoiceAudioUpload;
  durationSeconds: number;
  telemetry: AteneaVoiceTelemetry;
};

export type AteneaVoiceTranscription = AteneaVoiceCapture & {
  transcript: string;
};

type UseAteneaVoiceEngineOptions = {
  context: AteneaVoiceContext;
  prewarm?: boolean;
};

type RecorderSnapshot = {
  durationMillis: number;
  isRecording: boolean;
  metering: number | null;
  observedAt: number;
};

type MutableTelemetry = AteneaVoiceTelemetry & {
  permissionStartedAt: number | null;
  prepareStartedAt: number | null;
  recordCalledAt: number | null;
};

export function useAteneaVoiceEngine({
  context,
  prewarm = true,
}: UseAteneaVoiceEngineOptions) {
  const recorderOptions = useMemo(
    () => ({
      ...RecordingPresets.HIGH_QUALITY,
      isMeteringEnabled: true,
    }),
    []
  );
  const recorder = useAudioRecorder(recorderOptions);
  const recorderState = useAudioRecorderState(recorder, recorderPollMs);

  const [status, setStatus] = useState<AteneaVoiceStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [normalizedLevel, setNormalizedLevel] = useState(0);
  const [lastTelemetry, setLastTelemetry] = useState<AteneaVoiceTelemetry | null>(null);

  const preparedRef = useRef(false);
  const prewarmingRef = useRef(false);
  const mountedRef = useRef(true);
  const snapshotRef = useRef<RecorderSnapshot>({
    durationMillis: 0,
    isRecording: false,
    metering: null,
    observedAt: Date.now(),
  });
  const telemetryRef = useRef<MutableTelemetry | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      void deactivateAudioMode();
    };
  }, []);

  useEffect(() => {
    const now = Date.now();
    const metering = isFiniteNumber(recorderState.metering) ? recorderState.metering : null;
    snapshotRef.current = {
      durationMillis: recorderState.durationMillis,
      isRecording: recorderState.isRecording,
      metering,
      observedAt: now,
    };

    const telemetry = telemetryRef.current;
    if (telemetry?.recordCalledAt != null && recorderState.isRecording) {
      if (telemetry.firstDurationMs == null && recorderState.durationMillis > 0) {
        telemetry.firstDurationMs = now - telemetry.recordCalledAt;
      }
      if (telemetry.firstMeteringMs == null && metering != null) {
        telemetry.firstMeteringMs = now - telemetry.recordCalledAt;
      }
    }

    if (!recorderState.isRecording) {
      setNormalizedLevel(0);
      return;
    }

    const targetLevel = normalizeMetering(metering);
    setNormalizedLevel((currentLevel) => {
      const smoothing = targetLevel > currentLevel ? 0.65 : 0.22;
      const nextLevel = currentLevel + (targetLevel - currentLevel) * smoothing;
      if (telemetry != null) {
        telemetry.peakLevel = Math.max(telemetry.peakLevel, nextLevel);
        telemetry.averageLevel =
          (telemetry.averageLevel * telemetry.levelSamples + nextLevel) / (telemetry.levelSamples + 1);
        telemetry.levelSamples += 1;
      }
      return nextLevel;
    });
  }, [
    recorderState.durationMillis,
    recorderState.isRecording,
    recorderState.metering,
  ]);

  const prewarmRecorder = useCallback(async () => {
    if (
      prewarmingRef.current
      || preparedRef.current
      || snapshotRef.current.isRecording
      || status === 'preparing'
      || status === 'ready'
      || status === 'warming_up'
      || status === 'listening'
    ) {
      return;
    }

    try {
      prewarmingRef.current = true;
      const permission = await getRecordingPermissionsAsync();
      if (!permission.granted) {
        return;
      }
      setStatus('preparing');
      await setAudioModeAsync({
        allowsRecording: true,
        playsInSilentMode: true,
      });
      await recorder.prepareToRecordAsync();
      preparedRef.current = true;
      if (mountedRef.current) {
        setStatus('ready');
      }
    } catch {
      preparedRef.current = false;
      if (mountedRef.current) {
        setStatus('idle');
      }
    } finally {
      prewarmingRef.current = false;
    }
  }, [recorder, status]);

  useEffect(() => {
    if (!prewarm) {
      return;
    }
    void prewarmRecorder();
  }, [prewarm, prewarmRecorder]);

  const start = useCallback(async () => {
    setError(null);
    const telemetry = createTelemetry(context);
    telemetryRef.current = telemetry;
    setLastTelemetry(toPublicTelemetry(telemetry));

    try {
      setStatus('requesting_permission');
      telemetry.permissionStartedAt = Date.now();
      const permission = await requestRecordingPermissionsAsync();
      telemetry.permissionMs = Date.now() - telemetry.permissionStartedAt;
      if (!permission.granted) {
        throw new Error('No tengo permiso para usar el micrófono.');
      }

      await startNativeRecording(telemetry);
      let ready = await waitForRecorderReady(telemetry, readyTimeoutMs);

      if (!ready) {
        telemetry.watchdogRestarted = true;
        await stopNativeRecordingQuietly();
        await delay(160);
        await startNativeRecording(telemetry);
        ready = await waitForRecorderReady(telemetry, restartReadyTimeoutMs);
      }

      if (!ready) {
        throw new Error('El micrófono no ha entregado audio estable al iniciar. Vuelve a intentarlo.');
      }

      telemetry.readyMs = Date.now() - (telemetry.recordCalledAt ?? telemetry.startedAt);
      setStatus('listening');
      setLastTelemetry(toPublicTelemetry(telemetry));
    } catch (startError) {
      preparedRef.current = false;
      await deactivateAudioMode();
      const message = startError instanceof Error ? startError.message : 'No se pudo iniciar el micrófono.';
      telemetry.error = message;
      setError(message);
      setStatus('failed');
      setLastTelemetry(toPublicTelemetry(telemetry));
      throw startError;
    }
  }, [context, recorder]);

  const stop = useCallback(async (fileBaseName = `${context}-voice-command`): Promise<AteneaVoiceCapture> => {
    const telemetry = telemetryRef.current ?? createTelemetry(context);
    telemetryRef.current = telemetry;
    setStatus('finalizing');
    setError(null);

    try {
      const durationSeconds = Math.max(snapshotRef.current.durationMillis / 1000, recorder.currentTime ?? 0);
      await recorder.stop();
      preparedRef.current = false;
      await deactivateAudioMode();

      const uri = recorder.uri;
      if (!uri) {
        throw new Error('El grabador no ha producido ningún archivo de audio.');
      }

      const fileInfo = await FileSystem.getInfoAsync(uri);
      const fileSizeBytes = fileInfo.exists ? fileInfo.size : null;
      if (!fileInfo.exists || fileSizeBytes == null || fileSizeBytes <= 0) {
        throw new Error('El archivo de audio generado está vacío.');
      }
      if (durationSeconds < minimumUsefulDurationSeconds) {
        throw new Error('La grabación es demasiado corta para transcribirla.');
      }

      telemetry.durationSeconds = durationSeconds;
      telemetry.fileSizeBytes = fileSizeBytes;
      telemetry.completedAt = Date.now();
      telemetry.error = null;
      const publicTelemetry = toPublicTelemetry(telemetry);
      setLastTelemetry(publicTelemetry);
      setStatus('idle');

      return {
        audio: buildAudioUpload(uri, fileBaseName),
        durationSeconds,
        telemetry: publicTelemetry,
      };
    } catch (stopError) {
      preparedRef.current = false;
      await deactivateAudioMode();
      const message = stopError instanceof Error ? stopError.message : 'No se pudo finalizar la grabación.';
      telemetry.error = message;
      setError(message);
      setStatus('failed');
      setLastTelemetry(toPublicTelemetry(telemetry));
      throw stopError;
    }
  }, [context, recorder]);

  const stopAndTranscribe = useCallback(async (fileBaseName: string): Promise<AteneaVoiceTranscription> => {
    const capture = await stop(fileBaseName);
    setStatus('transcribing');
    try {
      const transcription = await createCoreVoiceTranscription({
        audio: capture.audio,
      });
      setStatus('idle');
      return {
        ...capture,
        transcript: transcription.transcript.trim(),
      };
    } catch (transcriptionError) {
      const telemetry = telemetryRef.current;
      const message = transcriptionError instanceof Error ? transcriptionError.message : 'No se pudo transcribir el audio.';
      if (telemetry != null) {
        telemetry.error = message;
        setLastTelemetry(toPublicTelemetry(telemetry));
      }
      setError(message);
      setStatus('failed');
      throw transcriptionError;
    }
  }, [stop]);

  const cancel = useCallback(async () => {
    setError(null);
    try {
      if (snapshotRef.current.isRecording || recorder.isRecording) {
        await recorder.stop();
      }
    } finally {
      preparedRef.current = false;
      telemetryRef.current = null;
      setNormalizedLevel(0);
      await deactivateAudioMode();
      setStatus('idle');
    }
  }, [recorder]);

  const startNativeRecording = async (telemetry: MutableTelemetry) => {
    setStatus('preparing');
    await setAudioModeAsync({
      allowsRecording: true,
      playsInSilentMode: true,
    });

    telemetry.prepareStartedAt = Date.now();
    if (!preparedRef.current) {
      await recorder.prepareToRecordAsync();
      preparedRef.current = true;
    }
    telemetry.prepareMs = Date.now() - telemetry.prepareStartedAt;

    setStatus('warming_up');
    telemetry.recordCalledAt = Date.now();
    recorder.record();
    preparedRef.current = false;
  };

  const stopNativeRecordingQuietly = async () => {
    try {
      if (snapshotRef.current.isRecording || recorder.isRecording) {
        await recorder.stop();
      }
    } catch {
      // Best effort watchdog recovery.
    } finally {
      preparedRef.current = false;
      await deactivateAudioMode();
    }
  };

  const waitForRecorderReady = async (telemetry: MutableTelemetry, timeoutMs: number) => {
    const recordCalledAt = telemetry.recordCalledAt ?? Date.now();
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeoutMs) {
      const statusSnapshot = readRecorderSnapshot();
      observeReadinessTelemetry(telemetry, statusSnapshot, recordCalledAt);
      const elapsedMs = Date.now() - recordCalledAt;
      if (
        elapsedMs >= minimumReadyMs
        && statusSnapshot.isRecording
        && (statusSnapshot.durationMillis > 0 || statusSnapshot.metering != null)
      ) {
        return true;
      }
      await delay(recorderPollMs);
    }
    return false;
  };

  const readRecorderSnapshot = (): RecorderSnapshot => {
    try {
      const nativeStatus = recorder.getStatus();
      const metering = isFiniteNumber(nativeStatus.metering) ? nativeStatus.metering : null;
      const snapshot = {
        durationMillis: nativeStatus.durationMillis,
        isRecording: nativeStatus.isRecording,
        metering,
        observedAt: Date.now(),
      };
      snapshotRef.current = snapshot;
      return snapshot;
    } catch {
      return snapshotRef.current;
    }
  };

  return {
    busy: status === 'requesting_permission'
      || status === 'preparing'
      || status === 'warming_up'
      || status === 'finalizing'
      || status === 'transcribing',
    cancel,
    durationSeconds: snapshotRef.current.durationMillis / 1000,
    error,
    isListening: status === 'listening',
    isRecording: status === 'listening' || status === 'warming_up',
    lastTelemetry,
    metering: snapshotRef.current.metering,
    normalizedLevel,
    prewarm: prewarmRecorder,
    start,
    status,
    stop,
    stopAndTranscribe,
  };
}

export function formatAteneaVoiceTelemetry(telemetry: AteneaVoiceTelemetry | null) {
  if (telemetry == null) {
    return null;
  }

  const parts = [
    telemetry.readyMs == null ? null : `micro listo en ${telemetry.readyMs} ms`,
    telemetry.firstDurationMs == null ? null : `audio en ${telemetry.firstDurationMs} ms`,
    telemetry.fileSizeBytes == null ? null : `${Math.round(telemetry.fileSizeBytes / 1024)} KB`,
    telemetry.watchdogRestarted ? 'watchdog reinició captura' : null,
  ].filter(isNonEmptyString);

  if (parts.length === 0) {
    return null;
  }
  return `Diagnóstico de audio: ${parts.join(' · ')}.`;
}

function createTelemetry(context: AteneaVoiceContext): MutableTelemetry {
  return {
    attemptId: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    averageLevel: 0,
    completedAt: null,
    context,
    durationSeconds: 0,
    error: null,
    fileSizeBytes: null,
    firstDurationMs: null,
    firstMeteringMs: null,
    permissionMs: null,
    permissionStartedAt: null,
    platform: Platform.OS,
    prepareMs: null,
    prepareStartedAt: null,
    readyMs: null,
    recordCalledAt: null,
    startedAt: Date.now(),
    levelSamples: 0,
    peakLevel: 0,
    watchdogRestarted: false,
  };
}

function toPublicTelemetry(telemetry: MutableTelemetry): AteneaVoiceTelemetry {
  return {
    attemptId: telemetry.attemptId,
    averageLevel: telemetry.averageLevel,
    completedAt: telemetry.completedAt,
    context: telemetry.context,
    durationSeconds: telemetry.durationSeconds,
    error: telemetry.error,
    fileSizeBytes: telemetry.fileSizeBytes,
    firstDurationMs: telemetry.firstDurationMs,
    firstMeteringMs: telemetry.firstMeteringMs,
    levelSamples: telemetry.levelSamples,
    peakLevel: telemetry.peakLevel,
    permissionMs: telemetry.permissionMs,
    platform: telemetry.platform,
    prepareMs: telemetry.prepareMs,
    readyMs: telemetry.readyMs,
    startedAt: telemetry.startedAt,
    watchdogRestarted: telemetry.watchdogRestarted,
  };
}

function observeReadinessTelemetry(
  telemetry: MutableTelemetry,
  snapshot: RecorderSnapshot,
  recordCalledAt: number
) {
  if (!snapshot.isRecording) {
    return;
  }
  if (telemetry.firstDurationMs == null && snapshot.durationMillis > 0) {
    telemetry.firstDurationMs = snapshot.observedAt - recordCalledAt;
  }
  if (telemetry.firstMeteringMs == null && snapshot.metering != null) {
    telemetry.firstMeteringMs = snapshot.observedAt - recordCalledAt;
  }
}

function buildAudioUpload(uri: string, fileBaseName: string): AteneaVoiceAudioUpload {
  const extension = extensionFromUri(uri) ?? 'm4a';
  return {
    uri,
    name: `${fileBaseName}.${extension}`,
    type: mimeTypeForExtension(extension),
  };
}

function extensionFromUri(uri: string) {
  const cleanUri = uri.split('?')[0] ?? uri;
  const match = /\.([a-zA-Z0-9]+)$/.exec(cleanUri);
  return match?.[1]?.toLowerCase() ?? null;
}

function mimeTypeForExtension(extension: string) {
  switch (extension) {
    case 'aac':
      return 'audio/aac';
    case '3gp':
      return 'audio/3gpp';
    case 'wav':
      return 'audio/wav';
    case 'webm':
      return 'audio/webm';
    case 'mp3':
      return 'audio/mpeg';
    case 'mp4':
    case 'm4a':
    default:
      return 'audio/mp4';
  }
}

async function deactivateAudioMode() {
  try {
    await setAudioModeAsync({
      allowsRecording: false,
      playsInSilentMode: true,
    });
  } catch {
    // Audio mode cleanup should never mask the original voice error.
  }
}

function normalizeMetering(metering: number | null | undefined) {
  if (!isFiniteNumber(metering)) {
    return 0;
  }

  const floorDb = -60;
  const clamped = Math.max(floorDb, Math.min(0, metering));
  const linear = (clamped - floorDb) / Math.abs(floorDb);
  return Math.pow(linear, 0.75);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isNonEmptyString(value: string | null): value is string {
  return value != null && value.length > 0;
}

function delay(ms: number) {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });
}
