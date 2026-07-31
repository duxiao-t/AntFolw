import { afterEach, describe, expect, it, vi } from 'vitest';
import { setAuthController } from '../../shared/api/auth';
import { uploadMobileFile } from './files.api';

const noop = async () => {
  /* noop */
};

class MockXMLHttpRequest {
  static latest: MockXMLHttpRequest | null = null;

  readonly upload: {
    onprogress: ((event: ProgressEvent) => void) | null;
  } = { onprogress: null };

  method = '';
  url = '';
  body: Document | XMLHttpRequestBodyInit | null = null;
  withCredentials = false;
  status = 0;
  statusText = '';
  responseText = '';
  onload: (() => void) | null = null;
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
    queueMicrotask(() => {
      this.upload.onprogress?.(new ProgressEvent('progress', {
        lengthComputable: true,
        loaded: 50,
        total: 100,
      }));
      this.status = 200;
      this.responseText = JSON.stringify({
        id: 'file-1',
        name: 'proof.pdf',
        contentUrl: '/api/mobile/files/file-1/content',
        contentType: 'application/pdf',
        size: 10,
      });
      this.onload?.();
    });
  }
}

describe('mobile file api', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    MockXMLHttpRequest.latest = null;
  });

  it('uploads files with XHR progress and shared auth headers', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer mobile-token' }),
      refresh: noop,
      isAuthEndpoint: () => false,
    });
    const progress: number[] = [];

    const result = await uploadMobileFile(
      '/api/mobile/files',
      new File(['%PDF-proof'], 'proof.pdf', { type: 'application/pdf' }),
      (value) => progress.push(value),
    );

    const request = MockXMLHttpRequest.latest;
    expect(result.id).toBe('file-1');
    expect(progress).toEqual([8, 50]);
    expect(request?.method).toBe('POST');
    expect(request?.url).toBe('/api/mobile/files');
    expect(request?.body).toBeInstanceOf(FormData);
    expect(request?.withCredentials).toBe(true);
    expect(request?.getRequestHeader('Accept')).toBe('application/json');
    expect(request?.getRequestHeader('Authorization')).toBe('Bearer mobile-token');
  });
});
