import { request } from '@umijs/max';

export type NativeFile = {
  id: string;
  name: string;
  contentUrl: string;
  contentType: string;
  size: number;
  durationSeconds?: number;
};

export type NativeLocation = {
  latitude: number;
  longitude: number;
  accuracy?: number;
  coordinateSystem?: 'WGS84' | 'GCJ02';
};

export type AudioRecording = {
  stop(): Promise<{ file: File; durationSeconds: number }>;
};

export async function scanCodeWithCamera(): Promise<string | null> {
  requireSecureContext('扫码');
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持摄像头扫码');
  const overlay = scannerOverlay();
  document.body.appendChild(overlay.root);
  let BrowserMultiFormatReader: typeof import('@zxing/browser').BrowserMultiFormatReader;
  try {
    ({ BrowserMultiFormatReader } = await import('@zxing/browser'));
  } catch (error) {
    overlay.root.remove();
    throw error;
  }
  const reader = new BrowserMultiFormatReader();
  let controls: Awaited<ReturnType<typeof reader.decodeFromConstraints>> | undefined;
  return new Promise<string | null>((resolve, reject) => {
    let settled = false;
    const finish = (value: string | null, error?: unknown) => {
      if (settled) return;
      settled = true;
      controls?.stop();
      const stream = overlay.video.srcObject;
      if (typeof MediaStream !== 'undefined' && stream instanceof MediaStream) {
        stream.getTracks().forEach((track) => { track.stop(); });
      }
      overlay.root.remove();
      if (error) reject(cameraError(error)); else resolve(value);
    };
    overlay.close.onclick = () => finish(null);
    reader.decodeFromConstraints(
      { video: { facingMode: { ideal: 'environment' } }, audio: false },
      overlay.video,
      (result) => { if (result) finish(result.getText()); },
    ).then((scannerControls) => {
      controls = scannerControls;
      if (!scannerControls.switchTorch) overlay.torch.hidden = true;
      let torchOn = false;
      overlay.torch.onclick = () => {
        torchOn = !torchOn;
        void scannerControls.switchTorch?.(torchOn).then(() => {
          overlay.torch.textContent = torchOn ? '关闭手电筒' : '打开手电筒';
        }).catch(() => { overlay.torch.hidden = true; });
      };
    }).catch((error) => finish(null, error));
  });
}

export async function beginAudioRecording(): Promise<AudioRecording> {
  requireSecureContext('录音');
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    throw new Error('当前浏览器不支持录音');
  }
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const mimeType = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm']
    .find((type) => MediaRecorder.isTypeSupported(type));
  const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
  const chunks: Blob[] = [];
  const startedAt = Date.now();
  let resolve!: (value: { file: File; durationSeconds: number }) => void;
  let reject!: (error: Error) => void;
  const result = new Promise<{ file: File; durationSeconds: number }>((res, rej) => {
    resolve = res; reject = rej;
  });
  recorder.ondataavailable = (event) => { if (event.data.size > 0) chunks.push(event.data); };
  recorder.onerror = () => {
    stream.getTracks().forEach((track) => { track.stop(); });
    reject(new Error('录音失败，请重试'));
  };
  recorder.onstop = () => {
    stream.getTracks().forEach((track) => { track.stop(); });
    const type = recorder.mimeType || mimeType || 'audio/webm';
    const extension = type.includes('mp4') ? 'm4a' : 'webm';
    resolve({
      file: new File(chunks, `recording-${Date.now()}.${extension}`, { type }),
      durationSeconds: Math.max(1, Math.round((Date.now() - startedAt) / 1000)),
    });
  };
  recorder.start(250);
  return { stop: async () => { if (recorder.state !== 'inactive') recorder.stop(); return result; } };
}

export async function uploadNativeFile(file: File, durationSeconds: number): Promise<NativeFile> {
  const data = new FormData();
  data.set('file', file);
  const uploaded = await request<NativeFile>('/api/mobile/files', { method: 'POST', data });
  return { ...uploaded, durationSeconds };
}

export function getBrowserLocation(): Promise<NativeLocation> {
  requireSecureContext('定位');
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) { reject(new Error('当前浏览器不支持定位')); return; }
    navigator.geolocation.getCurrentPosition((position) => resolve({
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
      accuracy: position.coords.accuracy,
      coordinateSystem: 'WGS84',
    }), (error) => reject(new Error(error.code === error.PERMISSION_DENIED
      ? '定位权限被拒绝，请在浏览器设置中允许定位'
      : error.code === error.TIMEOUT ? '定位超时，请移到开阔位置后重试' : '无法获取位置，请重试')),
    { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 });
  });
}

export function openNativeLocation(location: NativeLocation) {
  const point = `${location.latitude},${location.longitude}`;
  const href = /iPad|iPhone|iPod/i.test(navigator.userAgent)
    ? `https://maps.apple.com/?ll=${point}`
    : `https://www.openstreetmap.org/?mlat=${location.latitude}&mlon=${location.longitude}#map=16/${location.latitude}/${location.longitude}`;
  window.open(href, '_blank', 'noopener,noreferrer');
}

function requireSecureContext(action: string) {
  if (!window.isSecureContext && !['localhost', '127.0.0.1'].includes(window.location.hostname)) {
    throw new Error(`${action}需要 HTTPS 安全连接`);
  }
}

function cameraError(error: unknown) {
  const name = error instanceof DOMException ? error.name : '';
  if (name === 'NotAllowedError') return new Error('摄像头权限被拒绝，请在浏览器设置中允许访问');
  if (name === 'NotFoundError') return new Error('未找到可用摄像头');
  return error instanceof Error ? error : new Error('扫码启动失败');
}

function scannerOverlay() {
  const root = document.createElement('div');
  root.setAttribute('role', 'dialog'); root.setAttribute('aria-label', '扫码取景器');
  root.style.cssText = 'position:fixed;inset:0;z-index:99999;background:#05080c;color:#fff;display:grid;grid-template-rows:auto 1fr auto;padding:20px';
  const header = document.createElement('div');
  header.style.cssText = 'height:64px;display:flex;align-items:center;justify-content:space-between;font:600 17px system-ui';
  header.textContent = '扫描二维码/条码';
  const close = document.createElement('button');
  close.type = 'button'; close.textContent = '关闭';
  close.style.cssText = 'border:0;background:transparent;color:#fff;font:inherit;padding:12px';
  header.appendChild(close);
  const stage = document.createElement('div');
  stage.style.cssText = 'position:relative;overflow:hidden;border-radius:20px;background:#000;min-height:260px';
  const video = document.createElement('video'); video.setAttribute('playsinline', 'true'); video.muted = true;
  video.style.cssText = 'width:100%;height:100%;object-fit:cover';
  const reticle = document.createElement('div');
  reticle.style.cssText = 'position:absolute;left:10%;right:10%;top:50%;height:38%;transform:translateY(-50%);border:2px solid #69f0ae;border-radius:18px;box-shadow:0 0 0 9999px rgba(0,0,0,.42),inset 0 0 24px rgba(105,240,174,.18)';
  stage.append(video, reticle);
  const footer = document.createElement('div');
  footer.style.cssText = 'min-height:100px;display:grid;place-items:center;gap:8px;font:14px system-ui;color:#d8dee9';
  const hint = document.createElement('span'); hint.textContent = '将二维码或条码放入框内';
  const torch = document.createElement('button'); torch.type = 'button'; torch.textContent = '打开手电筒';
  torch.style.cssText = 'border:1px solid rgba(255,255,255,.45);border-radius:999px;background:rgba(255,255,255,.1);color:#fff;padding:9px 18px';
  footer.append(hint, torch); root.append(header, stage, footer);
  return { root, video, close, torch };
}
