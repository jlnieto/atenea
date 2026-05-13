import { useEffect, useMemo, useState } from 'react';
import {
  RecordingPresets,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
  useAudioRecorder,
  useAudioRecorderState,
} from 'expo-audio';

export function useVoiceRecorder() {
  const recorderOptions = useMemo(
    () => ({
      ...RecordingPresets.HIGH_QUALITY,
      isMeteringEnabled: true,
    }),
    []
  );
  const recorder = useAudioRecorder(recorderOptions);
  const recorderState = useAudioRecorderState(recorder, 80);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [normalizedLevel, setNormalizedLevel] = useState(0);

  useEffect(() => {
    if (!recorderState.isRecording) {
      setNormalizedLevel(0);
      return;
    }

    const targetLevel = normalizeMetering(recorderState.metering);
    setNormalizedLevel((currentLevel) => {
      const smoothing = targetLevel > currentLevel ? 0.65 : 0.22;
      return currentLevel + (targetLevel - currentLevel) * smoothing;
    });
  }, [recorderState.isRecording, recorderState.metering]);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const permission = await requestRecordingPermissionsAsync();
      if (!permission.granted) {
        throw new Error('Microphone permission was not granted.');
      }
      await setAudioModeAsync({
        allowsRecording: true,
        playsInSilentMode: true,
      });
      await recorder.prepareToRecordAsync();
      recorder.record();
    } catch (recordingError) {
      setError(recordingError instanceof Error ? recordingError.message : 'Voice recording failed');
      throw recordingError;
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    setBusy(true);
    setError(null);
    try {
      await recorder.stop();
      await setAudioModeAsync({
        allowsRecording: false,
        playsInSilentMode: true,
      });
      if (!recorder.uri) {
        throw new Error('No audio file was produced by the recorder.');
      }
      return recorder.uri;
    } catch (recordingError) {
      setError(recordingError instanceof Error ? recordingError.message : 'Voice recording could not be finalized');
      throw recordingError;
    } finally {
      setBusy(false);
    }
  };

  return {
    busy,
    durationSeconds: recorderState.durationMillis / 1000,
    error,
    isRecording: recorderState.isRecording,
    metering: recorderState.metering ?? null,
    normalizedLevel,
    start,
    stop,
  };
}

function normalizeMetering(metering: number | null | undefined) {
  if (metering == null || Number.isNaN(metering)) {
    return 0;
  }

  const floorDb = -60;
  const clamped = Math.max(floorDb, Math.min(0, metering));
  const linear = (clamped - floorDb) / Math.abs(floorDb);
  return Math.pow(linear, 0.75);
}
