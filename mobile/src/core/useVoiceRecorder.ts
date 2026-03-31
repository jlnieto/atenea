import { useState } from 'react';
import {
  RecordingPresets,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
  useAudioRecorder,
  useAudioRecorderState,
} from 'expo-audio';

export function useVoiceRecorder() {
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);
  const recorderState = useAudioRecorderState(recorder);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
    start,
    stop,
  };
}
