import { fetchJson, postJson, uploadMultipartFile } from './client';
import {
  ConfirmCoreCommandRequest,
  CoreCommandEventsResponse,
  CoreCommandListResponse,
  CoreCommandResponse,
  CoreVoiceCommandResponse,
  CoreVoiceTranscriptionResponse,
  CreateCoreCommandRequest,
} from './types';

export function createCoreCommand(
  request: CreateCoreCommandRequest
): Promise<CoreCommandResponse> {
  return postJson<CoreCommandResponse, CreateCoreCommandRequest>('/api/core/commands', request);
}

export function confirmCoreCommand(
  commandId: number,
  request: ConfirmCoreCommandRequest
): Promise<CoreCommandResponse> {
  return postJson<CoreCommandResponse, ConfirmCoreCommandRequest>(
    `/api/core/commands/${commandId}/confirm`,
    request
  );
}

export function fetchCoreCommands(): Promise<CoreCommandListResponse> {
  return fetchJson<CoreCommandListResponse>('/api/core/commands');
}

export function fetchCoreCommandEvents(commandId: number): Promise<CoreCommandEventsResponse> {
  return fetchJson<CoreCommandEventsResponse>(`/api/core/commands/${commandId}/events`);
}

export function createCoreVoiceCommand(params: {
  audio: {
    uri: string;
    name: string;
    type: string;
  };
  projectId?: number | null;
  workSessionId?: number | null;
  operatorKey?: string | null;
  scope?: 'GLOBAL' | 'PROJECT' | 'SESSION' | null;
}): Promise<CoreVoiceCommandResponse> {
  return uploadMultipartFile<CoreVoiceCommandResponse>(
    '/api/core/voice/commands',
    {
      fieldName: 'audio',
      name: params.audio.name,
      type: params.audio.type,
      uri: params.audio.uri,
    },
    {
      operatorKey: params.operatorKey ?? null,
      projectId: params.projectId ?? null,
      workSessionId: params.workSessionId ?? null,
      scope: params.scope ?? null,
    }
  );
}

export function createCoreVoiceTranscription(params: {
  audio: {
    uri: string;
    name: string;
    type: string;
  };
}): Promise<CoreVoiceTranscriptionResponse> {
  return uploadMultipartFile<CoreVoiceTranscriptionResponse>(
    '/api/core/voice/transcriptions',
    {
      fieldName: 'audio',
      name: params.audio.name,
      type: params.audio.type,
      uri: params.audio.uri,
    }
  ).catch((error) => {
    const message = error instanceof Error ? error.message : String(error);
    if (message.includes('/api/core/voice/transcriptions') && message.includes('404')) {
      throw new Error(
        'El backend de Atenea todavía no tiene desplegado el endpoint de transcripción de voz. Hay que actualizar o reiniciar el backend antes de usar el micrófono en Conversation.'
      );
    }
    throw error;
  });
}
