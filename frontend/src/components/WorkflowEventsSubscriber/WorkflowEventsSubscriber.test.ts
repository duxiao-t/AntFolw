import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import {
  consumeEventStream,
  refreshWorkflowQueries,
  TASKS_CHANGED_EVENT,
} from '.';

describe('workflow event stream', () => {
  it('parses fragmented SSE frames', async () => {
    const chunks = ['event: tasks-', 'changed\r\ndata: {"task', 'Id":12}\r\n\r\n'];
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(new TextEncoder().encode(chunk));
        }
        controller.close();
      },
    });
    const messages: Array<{ event: string; data: string }> = [];

    await consumeEventStream(stream, (message) => messages.push(message));

    expect(messages).toEqual([
      { event: 'tasks-changed', data: '{"taskId":12}' },
    ]);
  });

  it('invalidates workflow queries and dispatches the task event', () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(['instance', 11], { status: 'RUNNING' });
    queryClient.setQueryData(['task-comment-presets', 12], ['同意']);
    const listener = vi.fn();
    window.addEventListener(TASKS_CHANGED_EVENT, listener);

    refreshWorkflowQueries(queryClient, '{"taskId":12}');

    expect(queryClient.getQueryState(['instance', 11])?.isInvalidated).toBe(true);
    expect(
      queryClient.getQueryState(['task-comment-presets', 12])?.isInvalidated,
    ).toBe(true);
    expect(listener).toHaveBeenCalledOnce();
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ taskId: 12 });
    window.removeEventListener(TASKS_CHANGED_EVENT, listener);
  });

  it('still dispatches when an event has a non-JSON payload', () => {
    const queryClient = new QueryClient();
    const listener = vi.fn();
    window.addEventListener(TASKS_CHANGED_EVENT, listener);

    expect(() => refreshWorkflowQueries(queryClient, 'not-json')).not.toThrow();
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toBe('not-json');
    window.removeEventListener(TASKS_CHANGED_EVENT, listener);
  });
});
