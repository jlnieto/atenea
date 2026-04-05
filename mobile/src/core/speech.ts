import * as Speech from 'expo-speech';
import { createAudioPlayer, setAudioModeAsync, setIsAudioActiveAsync } from 'expo-audio';
import * as FileSystem from 'expo-file-system/legacy';
import { API_BASE_URL } from '../api/config';

const DEFAULT_LANGUAGE = 'es-ES';
const DEFAULT_SPEECH_OPTIONS = {
  language: DEFAULT_LANGUAGE,
  pitch: 1.0,
  rate: 1.02,
};

let preferredVoiceIdentifierPromise: Promise<string | undefined> | null = null;
let remoteSpeechPlayer: ReturnType<typeof createAudioPlayer> | null = null;
let lastRemoteSpeechFileUri: string | null = null;
let playbackGeneration = 0;

export type SpeechPlaybackResult = {
  mode: 'remote' | 'fallback-local' | 'local' | 'failed';
  detail: string;
};

async function resolvePreferredVoiceIdentifier() {
  const availableVoices = await Speech.getAvailableVoicesAsync();
  const spanishVoices = availableVoices.filter((voice) => voice.language?.toLowerCase().startsWith('es'));
  const exactVoice = spanishVoices.find((voice) => voice.language?.toLowerCase() === DEFAULT_LANGUAGE.toLowerCase());
  if (exactVoice) {
    return exactVoice.identifier;
  }
  const spainVoice = spanishVoices.find((voice) => voice.language?.toLowerCase().startsWith('es-es'));
  if (spainVoice) {
    return spainVoice.identifier;
  }
  return spanishVoices[0]?.identifier;
}

function getPreferredVoiceIdentifier() {
  if (preferredVoiceIdentifierPromise == null) {
    preferredVoiceIdentifierPromise = resolvePreferredVoiceIdentifier().catch(() => undefined);
  }
  return preferredVoiceIdentifierPromise;
}

function normalizeSpeakableText(text: string) {
  return text
    .replace(/WorkSession/g, 'sesión de trabajo')
    .replace(/pull request/gi, 'pul requést')
    .replace(/Codex/gi, 'Códex');
}

function waitForPlaybackStatus(
  player: NonNullable<typeof remoteSpeechPlayer>,
  predicate: (status: typeof player.currentStatus) => boolean,
  timeoutMs: number,
  timeoutMessage: string
) {
  return new Promise<typeof player.currentStatus>((resolve, reject) => {
    const currentStatus = player.currentStatus;
    if (predicate(currentStatus)) {
      resolve(currentStatus);
      return;
    }

    const timeout = setTimeout(() => {
      subscription.remove();
      reject(new Error(`${timeoutMessage} (${JSON.stringify(player.currentStatus)}).`));
    }, timeoutMs);

    const subscription = player.addListener('playbackStatusUpdate', (status) => {
      if (!predicate(status)) {
        return;
      }
      clearTimeout(timeout);
      subscription.remove();
      resolve(status);
    });
  });
}

function startPlaybackGeneration() {
  playbackGeneration += 1;
  return playbackGeneration;
}

async function clearPlaybackResources() {
  await Speech.stop().catch(() => undefined);
  if (remoteSpeechPlayer != null) {
    try {
      remoteSpeechPlayer.pause();
    } catch {
      // Ignore player pause errors and continue cleanup.
    }
    remoteSpeechPlayer.remove();
    remoteSpeechPlayer = null;
  }
  await setIsAudioActiveAsync(false).catch(() => undefined);
}

function isPlaybackStale(generation: number) {
  return playbackGeneration !== generation;
}

export async function speakSpanish(text: string) {
  const trimmed = text.trim();
  if (!trimmed) {
    return {
      mode: 'failed',
      detail: 'No había texto para reproducir.',
    } satisfies SpeechPlaybackResult;
  }
  const generation = startPlaybackGeneration();
  try {
    await clearPlaybackResources();
    const voiceIdentifier = await getPreferredVoiceIdentifier();
    const speakableText = normalizeSpeakableText(trimmed);
    await new Promise<void>((resolve, reject) => {
      Speech.speak(speakableText, {
        ...DEFAULT_SPEECH_OPTIONS,
        ...(voiceIdentifier ? { voice: voiceIdentifier } : {}),
        onDone: () => {
          if (isPlaybackStale(generation)) {
            reject(new Error('La reproducción local fue reemplazada por otra operación.'));
            return;
          }
          resolve();
        },
        onStopped: () => {
          reject(new Error('La reproducción local se detuvo antes de terminar.'));
        },
        onError: (error) => {
          reject(new Error(error instanceof Error ? error.message : 'El TTS local falló.'));
        },
      });
    });
    return {
      mode: 'local',
      detail: voiceIdentifier
        ? `TTS local completado con voz ${voiceIdentifier}.`
        : 'TTS local completado con la voz por defecto del dispositivo.',
    } satisfies SpeechPlaybackResult;
  } catch (error) {
    return {
      mode: 'failed',
      detail: error instanceof Error ? error.message : 'El TTS local falló.',
    } satisfies SpeechPlaybackResult;
  } finally {
    if (!isPlaybackStale(generation)) {
      await setIsAudioActiveAsync(false).catch(() => undefined);
    }
  }
}

export async function playCommandSpeech(commandId: number, accessToken: string, fallbackText: string) {
  try {
    return await playRemoteSpeech(
      `${API_BASE_URL}/api/core/commands/${commandId}/speech`,
      `Audio remoto reproducido para el comando ${commandId}.`,
      accessToken
    );
  } catch (error) {
    const remoteErrorDetail = error instanceof Error ? error.message : 'Error desconocido al reproducir audio remoto.';
    const fallbackResult = await speakSpanish(fallbackText);
    if (fallbackResult.mode === 'failed') {
      return {
        mode: 'failed',
        detail: `Falló el audio remoto (${remoteErrorDetail}) y también el fallback local: ${fallbackResult.detail}`,
      } satisfies SpeechPlaybackResult;
    }
    return {
      mode: 'fallback-local',
      detail: `Falló el audio remoto (${remoteErrorDetail}) y se usó fallback local. ${fallbackResult.detail}`,
    } satisfies SpeechPlaybackResult;
  }
}

export async function playSessionLatestResponseSpeech(
  sessionId: number,
  accessToken: string,
  fallbackText: string,
  mode: 'brief' | 'full' = 'brief'
) {
  try {
    return await playRemoteSpeech(
      `${API_BASE_URL}/api/core/work-sessions/${sessionId}/latest-response/speech?mode=${mode}`,
      `Audio remoto reproducido para la última respuesta de la sesión ${sessionId} (${mode}).`,
      accessToken
    );
  } catch (error) {
    const remoteErrorDetail = error instanceof Error ? error.message : 'Error desconocido al reproducir audio remoto.';
    const fallbackResult = await speakSpanish(fallbackText);
    if (fallbackResult.mode === 'failed') {
      return {
        mode: 'failed',
        detail: `Falló el audio remoto (${remoteErrorDetail}) y también el fallback local: ${fallbackResult.detail}`,
      } satisfies SpeechPlaybackResult;
    }
    return {
      mode: 'fallback-local',
      detail: `Falló el audio remoto (${remoteErrorDetail}) y se usó fallback local. ${fallbackResult.detail}`,
    } satisfies SpeechPlaybackResult;
  }
}

export async function playVoicePreview(
  text: string,
  voice: string,
  speed: number,
  accessToken: string
) {
  const params = new URLSearchParams({
    text,
    voice,
    speed: speed.toFixed(2),
  });
  try {
    return await playRemoteSpeech(
      `${API_BASE_URL}/api/core/voice/preview?${params.toString()}`,
      `Vista previa remota reproducida con ${voice} a ${speed.toFixed(2)}x.`,
      accessToken
    );
  } catch (error) {
    return {
      mode: 'failed',
      detail: error instanceof Error ? error.message : 'La vista previa de voz remota ha fallado.',
    } satisfies SpeechPlaybackResult;
  }
}

export async function stopSpeechPlayback() {
  startPlaybackGeneration();
  await clearPlaybackResources();
}

async function playRemoteSpeech(url: string, successDetail: string, accessToken?: string) {
  const generation = startPlaybackGeneration();
  try {
    await clearPlaybackResources();
    await setAudioModeAsync({
      allowsRecording: false,
      playsInSilentMode: true,
      shouldPlayInBackground: false,
      shouldRouteThroughEarpiece: false,
      interruptionMode: 'duckOthers',
    });
    await setIsAudioActiveAsync(true);

    const downloadedAudioUri = await downloadRemoteSpeech(url, accessToken);
    if (isPlaybackStale(generation)) {
      throw new Error('La reproducción remota fue reemplazada por otra operación.');
    }
    if (lastRemoteSpeechFileUri != null && lastRemoteSpeechFileUri !== downloadedAudioUri) {
      await FileSystem.deleteAsync(lastRemoteSpeechFileUri, { idempotent: true }).catch(() => undefined);
    }
    lastRemoteSpeechFileUri = downloadedAudioUri;

    remoteSpeechPlayer = createAudioPlayer(
      downloadedAudioUri,
      {
        downloadFirst: false,
        keepAudioSessionActive: true,
        updateInterval: 100,
      }
    );
    remoteSpeechPlayer.volume = 1.0;
    await waitForPlaybackStatus(
      remoteSpeechPlayer,
      (status) => status.isLoaded,
      30000,
      'El audio remoto no terminó de cargarse'
    );
    remoteSpeechPlayer.play();
    await waitForPlaybackStatus(
      remoteSpeechPlayer,
      (status) => status.playing || status.currentTime > 0 || status.didJustFinish,
      30000,
      'El audio remoto no empezó a reproducirse'
    );
    if (isPlaybackStale(generation)) {
      throw new Error('La reproducción remota fue reemplazada por otra operación.');
    }
    await waitForPlaybackStatus(
      remoteSpeechPlayer,
      (status) => status.didJustFinish,
      300000,
      'El audio remoto no terminó de reproducirse'
    );

    return {
      mode: 'remote',
      detail: successDetail,
    } satisfies SpeechPlaybackResult;
  } finally {
    if (!isPlaybackStale(generation)) {
      await clearPlaybackResources();
    }
  }
}

async function downloadRemoteSpeech(url: string, accessToken?: string) {
  const baseDirectory = FileSystem.cacheDirectory ?? FileSystem.documentDirectory;
  if (!baseDirectory) {
    throw new Error('No hay un directorio temporal disponible para descargar audio remoto.');
  }

  const targetUri = `${baseDirectory}atenea-speech-${Date.now()}.mp3`;
  const downloadResult = await FileSystem.downloadAsync(url, targetUri, {
    headers: accessToken
      ? { Authorization: `Bearer ${accessToken}` }
      : undefined,
  });

  if (downloadResult.status !== 200) {
    throw new Error(`La descarga del audio remoto devolvió HTTP ${downloadResult.status}.`);
  }

  return downloadResult.uri;
}
