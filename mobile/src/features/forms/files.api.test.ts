import { afterEach, describe, expect, it, vi } from 'vitest';
import { setAuthController } from '../../shared/api/auth';
import { fetchMobileFileBlob, uploadMobileFile, type UploadProgressEvent } from './files.api';

const noop = async () => {
  /* noop */
};

class MockXMLHttpRequest {
  static latest: MockXMLHttpRequest | null = null;
  static requests: MockXMLHttpRequest[] = [];
  static completionMode: 'load' | 'readystatechange' = 'load';
  static loaded = 50;
  static statuses = [200];

  readonly upload: {
    onprogress: ((event: ProgressEvent) => void) | null;
    onload: ((event: ProgressEvent) => void) | null;
  } = { onprogress: null, onload: null };

  method = '';
  url = '';
  body: Document | XMLHttpRequestBodyInit | null = null;
  withCredentials = false;
  status = 0;
  statusText = '';
  responseText = '';
  readyState = 0;
  onload: (() => void) | null = null;
  onreadystatechange: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;

  private readonly headers = new Map<string, string>();

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers.set(name.toLowerCase(), value);
  }

  getRequestHeader(name: string) {
    return this.headers.get(name.toLowerCase()) ?? null;
  }

  getResponseHeader() {
    return null;
  }

  send(body: Document | XMLHttpRequestBodyInit | null) {
    this.body = body;
    MockXMLHttpRequest.latest = this;
    MockXMLHttpRequest.requests.push(this);
    const requestIndex = MockXMLHttpRequest.requests.length - 1;
    const status = MockXMLHttpRequest.statuses[Math.min(requestIndex, MockXMLHttpRequest.statuses.length - 1)] ?? 200;
    queueMicrotask(() => {
      this.upload.onprogress?.(new ProgressEvent('progress', {
        lengthComputable: true,
        loaded: MockXMLHttpRequest.loaded,
        total: 100,
      }));
      this.upload.onload?.(new ProgressEvent('load'));
      this.status = status;
      this.responseText = status >= 200 && status < 300
        ? JSON.stringify({
          id: 'file-1',
          name: 'proof.pdf',
          contentUrl: '/api/mobile/files/file-1/content',
          contentType: 'application/pdf',
          size: 10,
        })
        : JSON.stringify({ code: 'TOKEN_EXPIRED', message: 'expired' });
      this.readyState = 4;
      if (MockXMLHttpRequest.completionMode === 'readystatechange') {
        this.onreadystatechange?.();
      } else {
        this.onload?.();
      }
    });
  }
}

describe('mobile file api', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    MockXMLHttpRequest.latest = null;
    MockXMLHttpRequest.requests = [];
    MockXMLHttpRequest.completionMode = 'load';
    MockXMLHttpRequest.loaded = 50;
    MockXMLHttpRequest.statuses = [200];
  });

  it('uploads files with XHR progress and shared auth headers', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer mobile-token' }),
      refresh: noop,
      isAuthEndpoint: () => false,
    });
    const progress: UploadProgressEvent[] = [];

    const result = await uploadMobileFile(
      '/api/mobile/files',
      new File(['%PDF-proof'], 'proof.pdf', { type: 'application/pdf' }),
      (event) => progress.push(event),
    );

    const request = MockXMLHttpRequest.latest;
    expect(result.id).toBe('file-1');
    expect(progress).toEqual([
      { phase: 'uploading', progress: 0 },
      { phase: 'uploading', progress: 50 },
      { phase: 'processing', progress: 96 },
      { phase: 'done', progress: 100 },
    ]);
    expect(request?.method).toBe('POST');
    expect(request?.url).toBe('/api/mobile/files');
    expect(request?.body).toBeInstanceOf(FormData);
    expect(request?.withCredentials).toBe(true);
    expect(request?.getRequestHeader('Accept')).toBe('application/json');
    expect(request?.getRequestHeader('Authorization')).toBe('Bearer mobile-token');
  });

  it('settles uploads that only report completion through readyState changes', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest);
    MockXMLHttpRequest.completionMode = 'readystatechange';
    MockXMLHttpRequest.loaded = 100;
    setAuthController({
      authorizationHeader: () => ({}),
      refresh: noop,
      isAuthEndpoint: () => false,
    });
    const progress: UploadProgressEvent[] = [];

    const result = await uploadMobileFile(
      '/api/mobile/files',
      new File(['%PDF-proof'], 'proof.pdf', { type: 'application/pdf' }),
      (event) => progress.push(event),
    );

    expect(result.id).toBe('file-1');
    expect(progress).toEqual([
      { phase: 'uploading', progress: 0 },
      { phase: 'uploading', progress: 95 },
      { phase: 'processing', progress: 96 },
      { phase: 'done', progress: 100 },
    ]);
  });

  it('keeps progress monotonic when auth refresh retries the upload', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest);
    MockXMLHttpRequest.loaded = 100;
    MockXMLHttpRequest.statuses = [401, 200];
    const refresh = vi.fn(noop);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer rotated-token' }),
      refresh,
      isAuthEndpoint: () => false,
    });
    const progress: UploadProgressEvent[] = [];

    const result = await uploadMobileFile(
      '/api/mobile/files',
      new File(['%PDF-proof'], 'proof.pdf', { type: 'application/pdf' }),
      (event) => progress.push(event),
    );

    expect(result.id).toBe('file-1');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(MockXMLHttpRequest.requests).toHaveLength(2);
    expect(MockXMLHttpRequest.requests[1]?.getRequestHeader('X-AF-Retry')).toBe('1');
    expect(progress).toEqual([
      { phase: 'uploading', progress: 0 },
      { phase: 'uploading', progress: 95 },
      { phase: 'processing', progress: 96 },
      { phase: 'done', progress: 100 },
    ]);
  });

  it('fetches protected file content as a blob with auth refresh retry', async () => {
    let token = 'expired-token';
    const refresh = vi.fn(async () => {
      token = 'rotated-token';
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        const headers = new Headers(init?.headers);
        if (headers.get('Authorization') === 'Bearer expired-token') {
          return new Response(JSON.stringify({ code: 'TOKEN_EXPIRED', message: 'expired' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        expect(headers.get('Authorization')).toBe('Bearer rotated-token');
        expect(headers.get('X-AF-Retry')).toBe('1');
        expect(init?.credentials).toBe('include');
        return new Response(new Blob(['image-bytes'], { type: 'image/png' }), {
          status: 200,
          headers: { 'Content-Type': 'image/png' },
        });
      }),
    );
    setAuthController({
      authorizationHeader: () => ({ Authorization: `Bearer ${token}` }),
      refresh,
      isAuthEndpoint: () => false,
    });

    const blob = await fetchMobileFileBlob('/api/mobile/files/file-1/content');

    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(blob.type).toBe('image/png');
    expect(await blob.text()).toBe('image-bytes');
  });
});
