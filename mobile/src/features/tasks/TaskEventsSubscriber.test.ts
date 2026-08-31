import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { queryKeys } from '../../shared/api/queryKeys';
import { consumeEventStream, refreshTaskQueries } from './TaskEventsSubscriber';

describe('task event stream', () => {
  it('parses fragmented SSE frames and invalidates task caches', async () => {
    const chunks = ['event: tasks-', 'changed\ndata: {"task', 'Id":12}\n\n'];
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks)
          controller.enqueue(new TextEncoder().encode(chunk));
        controller.close();
      },
    });
    const messages: Array<{ event: string; data: string }> = [];

    await consumeEventStream(stream, (message) => messages.push(message));

    expect(messages).toEqual([
      { event: 'tasks-changed', data: '{"taskId":12}' },
    ]);

    const queryClient = new QueryClient();
    queryClient.setQueryData(queryKeys.tasks({ view: 'pending' }), [
      'old task',
    ]);
    queryClient.setQueryData(queryKeys.bootstrap, { pendingCount: 0 });
    queryClient.setQueryData(queryKeys.notifications, { items: [] });
    refreshTaskQueries(queryClient);

    expect(
      queryClient.getQueryState(queryKeys.tasks({ view: 'pending' }))
        ?.isInvalidated,
    ).toBe(true);
    expect(queryClient.getQueryState(queryKeys.bootstrap)?.isInvalidated).toBe(
      true,
    );
    expect(queryClient.getQueryState(queryKeys.notifications)?.isInvalidated).toBe(true);
  });
});
