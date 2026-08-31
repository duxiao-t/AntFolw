import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { apiFetch } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import { useAuthStore } from '../auth/auth.store';

type StreamEvent = { event: string; data: string };

export function TaskEventsSubscriber() {
  const status = useAuthStore((state) => state.status);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (
      status !== 'authenticated' ||
      window.location.pathname.endsWith('/form-preview')
    )
      return;
    let active = true;
    let retryMs = 1_000;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let request: AbortController | undefined;

    const connect = async () => {
      request = new AbortController();
      try {
        const response = await apiFetch(
          '/api/mobile/events',
          { headers: { Accept: 'text/event-stream' } },
          { signal: request.signal },
        );
        if (!response.body) throw new Error('Event stream unavailable');
        refreshTaskQueries(queryClient);
        retryMs = 1_000;
        await consumeEventStream(response.body, (message) => {
          if (message.event === 'tasks-changed')
            refreshTaskQueries(queryClient);
        });
      } catch {
        if (!active || request.signal.aborted) return;
      }
      if (!active) return;
      retryTimer = setTimeout(() => {
        void connect();
      }, retryMs);
      retryMs = Math.min(retryMs * 2, 15_000);
    };

    void connect();
    return () => {
      active = false;
      request?.abort();
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [queryClient, status]);

  return null;
}

export function refreshTaskQueries(queryClient: QueryClient) {
  void queryClient.invalidateQueries({ queryKey: queryKeys.taskRoot });
  void queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap });
  void queryClient.invalidateQueries({ queryKey: queryKeys.notifications });
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
      const separator =
        buffer.slice(boundary).match(/^\r?\n\r?\n/)?.[0] ?? '\n\n';
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
