import { useState } from 'react';
import { confirmCoreCommand, createCoreCommand, createCoreVoiceCommand } from '../api/core';
import {
  CoreChannel,
  CoreClarificationOptionResponse,
  CoreCommandResponse,
  CoreVoiceCommandResponse,
  CreateCoreCommandRequest,
} from '../api/types';
import { humanizeDeliverableType } from './phrases';

export type RunCoreCommandOptions = {
  input: string;
  channel?: CoreChannel;
  projectId?: number | null;
  workSessionId?: number | null;
  onSucceeded?: (response: CoreCommandResponse) => void;
  openCoreOnAttention?: boolean;
};

export type RunCoreVoiceCommandOptions = {
  audio: {
    uri: string;
    name: string;
    type: string;
  };
  projectId?: number | null;
  workSessionId?: number | null;
  onSucceeded?: (response: CoreCommandResponse) => void;
  openCoreOnAttention?: boolean;
};

type UseCoreCommandCenterOptions = {
  operatorKey: string;
  selectedProjectId: number | null;
  selectedSessionId: number | null;
  onSelectProject: (projectId: number | null) => void;
  onSelectSession: (sessionId: number | null) => void;
  onAttentionRequired: () => void;
};

export function useCoreCommandCenter({
  operatorKey,
  selectedProjectId,
  selectedSessionId,
  onSelectProject,
  onSelectSession,
  onAttentionRequired,
}: UseCoreCommandCenterOptions) {
  const [activeCommand, setActiveCommand] = useState<CoreCommandResponse | null>(null);
  const [lastRequest, setLastRequest] = useState<CreateCoreCommandRequest | null>(null);
  const [historyVersion, setHistoryVersion] = useState(0);

  const runCommand = async ({
    input,
    channel = 'TEXT',
    projectId = selectedProjectId,
    workSessionId = selectedSessionId,
    onSucceeded,
    openCoreOnAttention = true,
  }: RunCoreCommandOptions) => {
    const request: CreateCoreCommandRequest = {
      input: input.trim(),
      channel,
      context: {
        projectId,
        workSessionId,
        operatorKey,
      },
      confirmation: {
        confirmed: false,
        confirmationToken: null,
      },
    };
    const response = await createCoreCommand(request);
    setLastRequest(request);
    applyResponseState(response, request.context ?? null);
    setActiveCommand(response);
    setHistoryVersion((current) => current + 1);
    if (
      openCoreOnAttention
      && (response.status === 'NEEDS_CONFIRMATION' || response.status === 'NEEDS_CLARIFICATION')
    ) {
      onAttentionRequired();
    }
    if (response.status === 'SUCCEEDED') {
      onSucceeded?.(response);
    }
    return response;
  };

  const confirmActiveCommand = async () => {
    if (activeCommand?.commandId == null || activeCommand.confirmation?.confirmationToken == null) {
      throw new Error('No confirmation is pending in Atenea Core.');
    }
    const response = await confirmCoreCommand(activeCommand.commandId, {
      confirmationToken: activeCommand.confirmation.confirmationToken,
    });
    applyResponseState(response, lastRequest?.context ?? null);
    setActiveCommand(response);
    setHistoryVersion((current) => current + 1);
    return response;
  };

  const resolveClarification = async (option: CoreClarificationOptionResponse) => {
    if (lastRequest == null) {
      throw new Error('No pending clarification context is available.');
    }

    const nextContext = {
      projectId: lastRequest.context?.projectId ?? null,
      workSessionId: lastRequest.context?.workSessionId ?? null,
      operatorKey,
    };
    let nextInput = lastRequest.input;

    if (option.type === 'PROJECT' && option.targetId != null) {
      nextContext.projectId = option.targetId;
      nextContext.workSessionId = null;
    }
    if (option.type === 'WORK_SESSION' && option.targetId != null) {
      nextContext.workSessionId = option.targetId;
    }
    if (option.type === 'DELIVERABLE_TYPE') {
      nextInput = `genera ${humanizeDeliverableType(option.label)}`;
    }

    return runCommand({
      input: nextInput,
      channel: lastRequest.channel,
      projectId: nextContext.projectId,
      workSessionId: nextContext.workSessionId,
    });
  };

  const clearActiveCommand = () => {
    setActiveCommand(null);
  };

  const runVoiceCommand = async ({
    audio,
    projectId = selectedProjectId,
    workSessionId = selectedSessionId,
    onSucceeded,
    openCoreOnAttention = true,
  }: RunCoreVoiceCommandOptions) => {
    const response = await createCoreVoiceCommand({
      audio,
      projectId,
      workSessionId,
      operatorKey,
    });
    const requestContext = {
      projectId,
      workSessionId,
      operatorKey,
    };
    setLastRequest({
      input: response.transcript,
      channel: 'VOICE',
      context: requestContext,
      confirmation: {
        confirmed: false,
        confirmationToken: null,
      },
    });
    applyResponseState(response.command, requestContext);
    setActiveCommand(response.command);
    setHistoryVersion((current) => current + 1);
    if (
      openCoreOnAttention
      && (response.command.status === 'NEEDS_CONFIRMATION' || response.command.status === 'NEEDS_CLARIFICATION')
    ) {
      onAttentionRequired();
    }
    if (response.command.status === 'SUCCEEDED') {
      onSucceeded?.(response.command);
    }
    return response;
  };

  const applyResponseState = (
    response: CoreCommandResponse,
    requestContext: CreateCoreCommandRequest['context']
  ) => {
    if (requestContext?.projectId != null) {
      onSelectProject(requestContext.projectId);
    }
    if (requestContext?.workSessionId != null) {
      onSelectSession(requestContext.workSessionId);
    }
    if (response.result?.targetType === 'PROJECT' && response.result.targetId != null) {
      onSelectProject(response.result.targetId);
      if (response.intent?.capability === 'activate_project_context') {
        onSelectSession(null);
      }
    }
    if (response.result?.targetType === 'WORK_SESSION' && response.result.targetId != null) {
      onSelectSession(response.result.targetId);
    }
  };

  return {
    activeCommand,
    clearActiveCommand,
    confirmActiveCommand,
    historyVersion,
    lastRequest,
    resolveClarification,
    runCommand,
    runVoiceCommand,
  };
}
