import { beforeEach, describe, expect, it, vi } from 'vitest';

const controls = vi.hoisted(() => ({ stop: vi.fn(), switchTorch: vi.fn() }));
const decode = vi.hoisted(() => vi.fn());

vi.mock('@zxing/browser', () => ({
  BrowserMultiFormatReader: class {
    decodeFromConstraints(...args: unknown[]) {
      return decode(...args);
    }
  },
}));

import { scanCodeWithCamera } from './nativeMedia';

/** 等某个条件成立（动态 import 要走几个真实的宏任务，微任务让不完）。 */
async function waitFor(condition: () => boolean) {
  for (let attempt = 0; attempt < 100 && !condition(); attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  expect(condition()).toBe(true);
}

/** 取景器只有"关闭"能收尾：调用方中途卸载时必须能从这里 abort 掉，否则摄像头一直亮着。 */
describe('摄像头扫码', () => {
  beforeEach(() => {
    decode.mockReset();
    controls.stop.mockReset();
    decode.mockImplementation(() => Promise.resolve(controls));
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      configurable: true,
    });
  });

  const overlay = () => document.querySelector('[aria-label="扫码取景器"]');

  it('abort 时关掉取景器与解码器，并以 null 结束', async () => {
    const controller = new AbortController();
    const pending = scanCodeWithCamera(controller.signal);
    // 取景器已打开、且 abort 监听已挂上（`decodeFromConstraints` 就在挂完监听之后调）。
    await waitFor(() => overlay() !== null && decode.mock.calls.length > 0);

    controller.abort();

    await expect(pending).resolves.toBeNull();
    expect(controls.stop).toHaveBeenCalled();
    expect(overlay()).toBeNull();
  });

  it('已经 abort 过的信号连取景器都不打开', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(scanCodeWithCamera(controller.signal)).resolves.toBeNull();
    expect(decode).not.toHaveBeenCalled();
  });
});
