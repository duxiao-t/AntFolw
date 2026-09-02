import type { QueryClient } from '@tanstack/react-query';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

const TOKEN_KEY = 'antflow-token';
export const TASKS_CHANGED_EVENT = 'antflow:tasks-changed';

type StreamEvent = { event: string; data: string };

export function WorkflowEventsSubscriber({ enabled }: { enabled: boolean }) {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!enabled) return;
    let active = true;
    let retryMs = 1_000;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let request: AbortController | undefined;

    const connect = async () => {
      request = new AbortController();
      try {
        const token = localStorage.getItem(TOKEN_KEY);
        const response = await fetch('/api/workflow/events', {
          credentials: 'include',
          headers: {
            Accept: 'text/event-stream',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          signal: request.signal,
        });
        if (!response.ok || !response.body) {
          throw new Error(`Event stream unavailable: ${response.status}`);
        }
        refreshWorkflowQueries(queryClient);
        retryMs = 1_000;
        await consumeEventStream(response.body, (message) => {
          if (message.event === 'tasks-changed') {
            refreshWorkflowQueries(queryClient, message.data);
          }
        });
      } catch {
        if (!active || request.signal.aborted) return;
      }
      if (!active) return;
      retryTimer = setTimeout(() => void connect(), retryMs);
      retryMs = Math.min(retryMs * 2, 15_000);
    };

    void connect();
    return () => {
      active = false;
      request?.abort();
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [enabled, queryClient]);

  return null;
}

export function refreshWorkflowQueries(queryClient: QueryClient, data?: string) {
  void queryClient.invalidateQueries({ queryKey: ['instance'] });
  void queryClient.invalidateQueries({ queryKey: ['task-comment-presets'] });
  window.dispatchEvent(
    new CustomEvent(TASKS_CHANGED_EVENT, { detail: parseEventData(data) }),
  );
}

function parseEventData(data?: string): unknown {
  if (!data) return undefined;
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}

export async function consumeEventStream(
  stream: ReadableStream<Uint8Array>,
  onMessage: (message: StreamEvent) => void,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    let boundary = buffer.search(/\r?\n\r?\n/);
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary);
      const separator = buffer.slice(boundary).match(/^\r?\n\r?\n/)?.[0] ?? '\n\n';
      buffer = buffer.slice(boundary + separator.length);
      const message = parseFrame(frame);
      if (message) onMessage(message);
      boundary = buffer.search(/\r?\n\r?\n/);
    }
    if (done) break;
  }
}

function parseFrame(frame: string): StreamEvent | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trimStart();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  return data.length ? { event, data: data.join('\n') } : null;
}

export default WorkflowEventsSubscriber;
