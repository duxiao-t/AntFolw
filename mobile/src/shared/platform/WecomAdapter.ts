import type { MobileFile } from '../api/types';
import { apiRequest } from '../api/http';
import { createClientId } from '../clientId';
import type { PlatformAdapter, PlatformAudio, PlatformLocation } from './PlatformAdapter';
import { browserAdapter } from './BrowserAdapter';

type WxResult = Record<string, unknown> & { errMsg?: string };
type Wx = {
  config(config: Record<string, unknown>): void;
  ready(callback: () => void): void;
  error(callback: (error: WxResult) => void): void;
  chooseImage(config: Record<string, unknown>): void;
  uploadImage(config: Record<string, unknown>): void;
  startRecord(config?: Record<string, unknown>): void;
  stopRecord(config: Record<string, unknown>): void;
  onVoiceRecordEnd(config: Record<string, unknown>): void;
  uploadVoice(config: Record<string, unknown>): void;
  scanQRCode(config: Record<string, unknown>): void;
  getLocation(config: Record<string, unknown>): void;
  openLocation(config: Record<string, unknown>): void;
  previewFile(config: Record<string, unknown>): void;
  closeWindow(): void;
};

declare global { interface Window { wx?: Wx } }

const JS_API_LIST = [
  'chooseImage', 'uploadImage', 'startRecord', 'stopRecord', 'onVoiceRecordEnd',
  'uploadVoice', 'scanQRCode', 'getLocation', 'openLocation', 'previewFile', 'closeWindow',
];
let configuredFor = '';
let configuring: Promise<Wx> | null = null;
class WecomUnavailableError extends Error {}
let audioBackend: 'wecom' | 'browser' | null = null;
let lastAutoEndedAudio: PlatformAudio | null = null;
let lastAutoEndedError: Error | null = null;
let voiceRecording: {
  startedAt: number;
  localId?: string;
  upload?: Promise<PlatformAudio>;
} | null = null;

async function wxReady(): Promise<Wx> {
  const pageUrl = window.location.href.split('#')[0] ?? window.location.href;
  if (window.wx && configuredFor === pageUrl) return window.wx;
  if (configuring) return configuring;
  configuring = (async () => {
    await loadScript();
    const sdk = window.wx;
    if (!sdk) throw new Error('企业微信 JS-SDK 加载失败');
    const config = await apiRequest<{ appId: string; timestamp: number; nonceStr: string; signature: string }>(
      `/api/mobile/wecom/js-sdk-config?url=${encodeURIComponent(pageUrl)}`,
    );
    await new Promise<void>((resolve, reject) => {
      sdk.ready(resolve);
      sdk.error((error) => reject(new Error(String(error.errMsg ?? '企业微信 JS-SDK 初始化失败'))));
      sdk.config({ debug: false, beta: true, ...config, jsApiList: JS_API_LIST });
    });
    configuredFor = pageUrl;
    return sdk;
  })().catch((error) => {
    throw new WecomUnavailableError(error instanceof Error ? error.message : '企业微信 JS-SDK 不可用');
  }).finally(() => { configuring = null; });
  return configuring;
}

function loadScript(): Promise<void> {
  if (window.wx) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-antflow-wecom-sdk]');
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error('企业微信 JS-SDK 加载失败')), { once: true });
      return;
    }
    const script = document.createElement('script');
    script.src = 'https://res.wx.qq.com/open/js/jweixin-1.2.0.js';
    script.async = true;
    script.dataset.antflowWecomSdk = 'true';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('企业微信 JS-SDK 加载失败'));
    document.head.appendChild(script);
  });
}

function invoke<T>(run: (sdk: Wx, resolve: (value: T) => void, reject: (error: Error) => void) => void) {
  return wxReady().then((sdk) => new Promise<T>((resolve, reject) => run(sdk, resolve, reject)));
}

function failure(reject: (error: Error) => void) {
  return (result: WxResult) => reject(new Error(String(result.errMsg ?? '企业微信操作失败')));
}

async function importMedia(serverId: string, mediaType: string): Promise<MobileFile> {
  return apiRequest<MobileFile>('/api/mobile/wecom/media/import', {
    method: 'POST',
    headers: { 'Idempotency-Key': createClientId('wecom-media') },
    body: JSON.stringify({ serverId, mediaType }),
  });
}

async function chooseImages(maxCount: number, source: 'camera' | 'album' | 'both' = 'both'): Promise<MobileFile[]> {
  const localIds = await invoke<string[]>((sdk, resolve, reject) => sdk.chooseImage({
    count: Math.max(1, Math.min(9, maxCount)), sizeType: ['original', 'compressed'],
    sourceType: source === 'both' ? ['album', 'camera'] : [source],
    success: (result: WxResult) => resolve(result.localIds as string[]),
    fail: failure(reject), cancel: () => resolve([]),
  }));
  const files: MobileFile[] = [];
  for (const localId of localIds) {
    const serverId = await invoke<string>((sdk, resolve, reject) => sdk.uploadImage({
      localId, isShowProgressTips: 1,
      success: (result: WxResult) => resolve(String(result.serverId ?? '')),
      fail: failure(reject),
    }));
    files.push(await importMedia(serverId, 'image'));
  }
  return files;
}

async function recordAudio(): Promise<PlatformAudio> {
  await startAudioRecording();
  return new Promise((resolve, reject) => window.setTimeout(() => {
    void stopAudioRecording().then(resolve, reject);
  }, 60_000));
}

async function startAudioRecording() {
  if (voiceRecording) throw new Error('已有录音正在进行');
  lastAutoEndedAudio = null;
  lastAutoEndedError = null;
  let sdk: Wx;
  try {
    sdk = await wxReady();
  } catch (error) {
    if (!(error instanceof WecomUnavailableError) || !browserAdapter.startAudioRecording) throw error;
    await browserAdapter.startAudioRecording();
    audioBackend = 'browser';
    return;
  }
  await new Promise<void>((resolve, reject) => sdk.startRecord({ success: resolve, fail: failure(reject) }));
  voiceRecording = { startedAt: Date.now() };
  audioBackend = 'wecom';
  sdk.onVoiceRecordEnd({
    complete: (result: WxResult) => {
      const current = voiceRecording;
      if (!current) return;
      current.localId = String(result.localId ?? '');
      void uploadVoice(current).then((audio) => { lastAutoEndedAudio = audio; })
        .catch((error) => { lastAutoEndedError = error instanceof Error ? error : new Error('录音上传失败'); });
    },
  });
}

async function stopAudioRecording(): Promise<PlatformAudio> {
  if (audioBackend === 'browser' && browserAdapter.stopAudioRecording) {
    audioBackend = null;
    return browserAdapter.stopAudioRecording();
  }
  const current = voiceRecording;
  if (!current) {
    if (lastAutoEndedAudio) {
      const result = lastAutoEndedAudio;
      lastAutoEndedAudio = null;
      return result;
    }
    if (lastAutoEndedError) {
      const error = lastAutoEndedError;
      lastAutoEndedError = null;
      throw error;
    }
    throw new Error('当前没有正在进行的录音');
  }
  if (!current.localId) {
    current.localId = await invoke<string>((sdk, resolve, reject) => sdk.stopRecord({
      success: (result: WxResult) => resolve(String(result.localId ?? '')), fail: failure(reject),
    }));
  }
  return uploadVoice(current);
}

function uploadVoice(current: NonNullable<typeof voiceRecording>): Promise<PlatformAudio> {
  if (current.upload) return current.upload;
  const durationSeconds = Math.max(1, Math.round((Date.now() - current.startedAt) / 1000));
  current.upload = invoke<string>((sdk, resolve, reject) => sdk.uploadVoice({
    localId: current.localId, isShowProgressTips: 1,
    success: (result: WxResult) => resolve(String(result.serverId ?? '')),
    fail: failure(reject),
  })).then((serverId) => importMedia(serverId, 'voice'))
    .then((uploaded) => ({ uploaded: { ...uploaded, durationSeconds }, durationSeconds }))
    .finally(() => {
      if (voiceRecording === current) voiceRecording = null;
      audioBackend = null;
    });
  return current.upload;
}

export const wecomAdapter: PlatformAdapter = {
  kind: 'wecom',
  chooseImages,
  recordAudio,
  startAudioRecording,
  stopAudioRecording,
  scanCode: () => fallbackOnUnavailable(invoke<string | null>((sdk, resolve, reject) => sdk.scanQRCode({
    needResult: 1, scanType: ['qrCode', 'barCode'],
    success: (result: WxResult) => resolve(normalizeScanResult(String(result.resultStr ?? ''))),
    fail: failure(reject), cancel: () => resolve(null),
  })), () => browserAdapter.scanCode?.() ?? Promise.resolve(null)),
  getLocation: () => fallbackOnUnavailable(invoke<PlatformLocation>((sdk, resolve, reject) => sdk.getLocation({
    type: 'gcj02',
    success: (result: WxResult) => resolve({
      latitude: Number(result.latitude), longitude: Number(result.longitude),
      accuracy: Number(result.accuracy) || undefined,
      coordinateSystem: 'GCJ02',
    }),
    fail: failure(reject),
  })), () => browserAdapter.getLocation?.() ?? Promise.reject(new Error('当前浏览器不支持定位'))),
  openLocation: (location) => fallbackOnUnavailable(invoke<void>((sdk, resolve, reject) => sdk.openLocation({
    ...location, scale: 16, success: () => resolve(), fail: failure(reject),
  })), () => browserAdapter.openLocation?.(location) ?? Promise.resolve()),
  async openFile(file) {
    await invoke<void>((sdk, resolve, reject) => sdk.previewFile({
      url: new URL(file.contentUrl || file.url || '', window.location.origin).toString(),
      name: file.name, size: file.sizeBytes ?? file.size,
      success: () => resolve(), fail: failure(reject),
    }));
  },
  closePage() { if (window.wx) window.wx.closeWindow(); else window.history.back(); },
  getEnvironment() { return { standalone: true, userAgent: navigator.userAgent }; },
};

export function normalizeScanResult(value: string) {
  return value.replace(/^(?:CODE_\d+|EAN_\d+|UPC_[A-Z]|CODABAR),/i, '');
}

function fallbackOnUnavailable<T>(primary: Promise<T>, fallback: () => Promise<T>) {
  return primary.catch((error) => {
    if (error instanceof WecomUnavailableError) return fallback();
    throw error;
  });
}
