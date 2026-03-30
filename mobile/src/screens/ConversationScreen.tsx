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
import { fetchJson, postJson } from '../api/client';
import {
  CreateSessionTurnConversationViewResponse,
  CreateSessionTurnRequest,
  MobileSessionEventsResponse,
  WorkSessionConversationView,
} from '../api/types';
import { ActionButton } from '../components/ActionButton';
import { useAuth } from '../auth/AuthContext';
import { Card } from '../components/Card';
import { LoadingBlock } from '../components/LoadingBlock';
import { useRemoteResource } from '../hooks/useRemoteResource';

const MONO_FONT = Platform.select({
  ios: 'Menlo',
  android: 'monospace',
  default: 'monospace',
});

export function ConversationScreen({
  sessionId,
  onBackToSession,
}: {
  sessionId: number | null;
  onBackToSession: () => void;
}) {
  const { session: authSession } = useAuth();
  const { data, error, loading, refreshing, reload, setData } = useRemoteResource(
    () =>
      sessionId == null
        ? Promise.resolve(null)
        : fetchJson<WorkSessionConversationView>(`/api/mobile/sessions/${sessionId}/conversation`),
    [sessionId],
    { refreshIntervalMs: 15000 }
  );
  const [turnMessage, setTurnMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const scrollRef = useRef<ScrollView | null>(null);
  const stickToBottomRef = useRef(true);
  const lastStreamEventAtRef = useRef<string | null>(null);

  const session = data?.view.session ?? null;
  const turns = data?.recentTurns ?? [];
  const composerDisabled = pending || sessionId == null || !data?.view.canCreateTurn;

  const scrollToBottom = () => {
    scrollRef.current?.scrollToEnd({ animated: true });
  };

  useEffect(() => {
    if (!loading && stickToBottomRef.current) {
      requestAnimationFrame(scrollToBottom);
    }
  }, [loading, turns.length]);

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
        void reload({ silent: true });
      } catch {
        // Ignore malformed stream payloads and keep the polling fallback.
      }
    };

    stream.addEventListener('session-events', onSessionEvents);

    return () => {
      stream.removeAllEventListeners();
      stream.close();
    };
  }, [sessionId, authSession, reload]);

  const sendTurn = async () => {
    if (sessionId == null || !turnMessage.trim()) {
      return;
    }

    setPending(true);
    setMutationError(null);
    try {
      const response = await postJson<CreateSessionTurnConversationViewResponse, CreateSessionTurnRequest>(
        `/api/mobile/sessions/${sessionId}/turns`,
        { message: turnMessage.trim() }
      );
      setData(response.view);
      setTurnMessage('');
    } catch (actionError) {
      setMutationError(actionError instanceof Error ? actionError.message : 'Turn failed');
    } finally {
      setPending(false);
    }
  };

  if (sessionId == null) {
    return (
      <Card title="No session selected" subtitle="Choose a session first, then open the conversation workspace.">
        <ActionButton label="Back to session" onPress={onBackToSession} />
      </Card>
    );
  }

  if (loading) {
    return <LoadingBlock label={`Loading conversation for session ${sessionId}...`} />;
  }

  if (error || data == null || session == null) {
    return (
      <Card title="Conversation unavailable" subtitle={error || 'No conversation data returned.'}>
        <View style={styles.headerActions}>
          <ActionButton label="Retry" onPress={() => void reload()} />
          <ActionButton label="Back to session" onPress={onBackToSession} />
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
        <Pressable onPress={onBackToSession}>
          <Text style={styles.backLink}>Back to session</Text>
        </Pressable>
        <Pressable onPress={() => void reload()}>
          <Text style={styles.refreshLink}>{refreshing ? 'Refreshing...' : 'Refresh'}</Text>
        </Pressable>
      </View>

      <View style={styles.chatShell}>
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
          <TextInput
            value={turnMessage}
            onChangeText={setTurnMessage}
            placeholder="write your next prompt to codex"
            placeholderTextColor="#6e7b74"
            style={[styles.input, styles.textArea]}
            multiline
          />
          <View style={styles.composerRow}>
            <Text style={styles.composerMeta}>
              {data.view.runInProgress
                ? 'A run is still in progress. You can refresh and wait for the latest reply.'
                : 'The prompt is sent to the current WorkSession thread.'}
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
                {pending ? 'Sending...' : 'Send prompt'}
              </Text>
            </Pressable>
          </View>
        </View>
        {mutationError ? <Text style={styles.error}>{mutationError}</Text> : null}
        {data.view.lastError ? <Text style={styles.error}>{data.view.lastError}</Text> : null}
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
    paddingBottom: 4,
    paddingTop: 8,
    paddingHorizontal: 10,
    backgroundColor: '#3f3f3f',
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
  turnList: {
    gap: 0,
    paddingBottom: 10,
    paddingHorizontal: 10,
  },
  turnRow: {
    paddingHorizontal: 0,
    paddingVertical: 10,
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
    paddingTop: 8,
    paddingBottom: 12,
    paddingHorizontal: 0,
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
