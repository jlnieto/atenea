import { fetchJson, postJson, uploadMultipartFile } from './client';
import {
  ConfirmCoreCommandRequest,
  CoreCommandEventsResponse,
  CoreCommandListResponse,
  CoreCommandResponse,
  CoreVoiceCommandResponse,
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
    }
  );
}
