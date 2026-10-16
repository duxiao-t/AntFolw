import { afterEach, describe, expect, it, vi } from 'vitest';
import { FileStillProcessingError, waitForNativeFileReady, type NativeFile } from './nativeMedia';

const http = vi.hoisted(() => ({ request: vi.fn() }));
// 只替掉网络：这个文件测的就是轮询本身，`@umijs/max` 的 `request` 是它唯一的出口。
vi.mock('@umijs/max', () => ({ request: http.request }));

function processing(overrides: Partial<NativeFile> = {}): NativeFile {
  return {
    id: 'file-1', name: 'clip.mp4', contentUrl: '/api/mobile/files/file-1/content',
    contentType: 'video/mp4', size: 10, status: 'PROCESSING', ...overrides,
  };
}

describe('桌面端媒体轮询', () => {
  afterEach(() => {
    vi.useRealTimers();
    http.request.mockReset();
  });

  it('轮询窗口用完但服务端仍停在 PROCESSING：抛 FileStillProcessingError 并带上服务端文件', async () => {
    vi.useFakeTimers();
    http.request.mockResolvedValue(processing());

    const pending = waitForNativeFileReady(processing());
    const rejection = expect(pending).rejects.toBeInstanceOf(FileStillProcessingError);
    await vi.advanceTimersByTimeAsync(1800 * 1000 + 100);

    await rejection;
    // 带着文件才能"继续等待"；普通 Error 会让调用方判失败、用户重传第二份视频。
    await expect(pending.catch((error: unknown) => (error as FileStillProcessingError).file))
      .resolves.toMatchObject({ id: 'file-1', status: 'PROCESSING' });
  });

  it('服务端明确 FAILED 时仍然透出可读原因', async () => {
    vi.useFakeTimers();
    http.request.mockResolvedValue(processing({ status: 'FAILED', failureReason: '转码队列忙' }));

    const pending = waitForNativeFileReady(processing());
    const rejection = expect(pending).rejects.toThrow('转码队列忙');
    await vi.advanceTimersByTimeAsync(1100);

    await rejection;
  });
});
