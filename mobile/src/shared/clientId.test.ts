import { afterEach, describe, expect, it, vi } from 'vitest';
import { createClientId } from './clientId';

afterEach(() => vi.unstubAllGlobals());

describe('createClientId', () => {
  it('uses randomUUID when available', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'native-uuid' });
    expect(createClientId()).toBe('native-uuid');
  });

  it('creates a UUID with getRandomValues in older webviews', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        bytes.set(Array.from({ length: 16 }, (_, index) => index));
        return bytes;
      },
    });
    expect(createClientId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('falls back when Web Crypto is unavailable', () => {
    vi.stubGlobal('crypto', undefined);
    expect(createClientId('upload')).toMatch(/^upload-\d+-[0-9a-f]+$/);
  });
});
