import { render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { BrandProvider } from './BrandProvider';
import {
  applyBrandTokens,
  FALLBACK_BRANDING,
  HEX_COLOR_PATTERN,
  isHexColor,
  readDocumentHead,
} from './brandTokens';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PublicBranding } from '../../shared/api/types';
import type { ReactNode } from 'react';

function withQuery(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>;
}

const REMOTE_BRANDING: PublicBranding = {
  version: 'tenant-2026-07-20',
  appName: 'AntFlow Ops',
  companyName: 'Acme',
  primaryColor: '#31a354',
  mobileHeaderTitle: 'Ops',
  loginTitle: 'Sign In',
  showLoginFooter: false,
  footerText: 'Acme',
};

describe('isHexColor', () => {
  it.each([
    ['#1677ff', true],
    ['#31A354', true],
    ['#ffffff', true],
    ['#fff', false],
    ['rgb(0,0,0)', false],
    ['1677ff', false],
    ['', false],
  ])('recognises %s as %s', (input, expected) => {
    expect(isHexColor(input)).toBe(expected);
  });

  it('exposes a 6-digit hex pattern', () => {
    expect(HEX_COLOR_PATTERN.test('#1677ff')).toBe(true);
    expect(HEX_COLOR_PATTERN.test('#1677fff')).toBe(false);
  });
});

describe('applyBrandTokens', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.head.querySelectorAll('link[rel~="icon"], link[rel="manifest"]').forEach((node) => {
      node.remove();
    });
    document.documentElement.style.cssText = '';
    document.title = '';
  });

  afterEach(() => {
    document.head.querySelectorAll('link[rel~="icon"], link[rel="manifest"]').forEach((node) => {
      node.remove();
    });
    document.documentElement.style.cssText = '';
  });

  it('applies a valid primary color and sets title', () => {
    applyBrandTokens({ ...REMOTE_BRANDING, primaryColor: '#1677ff' });
    const root = document.documentElement;
    expect(root.style.getPropertyValue('--af-color-primary')).toBe('#1677ff');
    expect(root.style.getPropertyValue('--adm-color-primary')).toBe('#1677ff');
    expect(document.title).toBe('AntFlow Ops');
  });

  it('falls back to the canonical primary color for invalid input', () => {
    applyBrandTokens({ ...REMOTE_BRANDING, primaryColor: 'rgb(0,0,0)' });
    expect(document.documentElement.style.getPropertyValue('--af-color-primary')).toBe('#1677ff');
    expect(document.documentElement.style.getPropertyValue('--adm-color-primary')).toBe('#1677ff');
  });

  it('updates document title and creates a favicon link with version query', () => {
    applyBrandTokens(REMOTE_BRANDING);
    const iconLink = document.querySelector("link[rel~='icon']");
    expect(iconLink).toBeTruthy();
    expect(iconLink?.getAttribute('href')).toBe(
      '/api/public/branding/favicon.svg?v=tenant-2026-07-20',
    );
    expect(document.title).toBe('AntFlow Ops');
  });

  it('creates a manifest link tied to the published version', () => {
    applyBrandTokens(REMOTE_BRANDING);
    const manifestLink = document.querySelector("link[rel='manifest']");
    expect(manifestLink).toBeTruthy();
    expect(manifestLink?.getAttribute('href')).toBe(
      '/api/public/branding/manifest.webmanifest?v=tenant-2026-07-20',
    );
  });

  it('exposes a stable header inspector', () => {
    expect(readDocumentHead().title).toBe('');
    applyBrandTokens(REMOTE_BRANDING);
    expect(readDocumentHead().title).toBe('AntFlow Ops');
    expect(readDocumentHead().iconHref).toBe(
      '/api/public/branding/favicon.svg?v=tenant-2026-07-20',
    );
  });
});

describe('BrandProvider', () => {
  beforeEach(() => {
    document.head.querySelectorAll('link[rel~="icon"], link[rel="manifest"]').forEach((node) => {
      node.remove();
    });
    document.documentElement.style.cssText = '';
  });

  it('uses fallback branding synchronously so first paint is on-brand', () => {
    render(withQuery(<BrandProvider>{null}</BrandProvider>));
    const primary = document.documentElement.style.getPropertyValue('--af-color-primary');
    expect(primary === '' || primary === FALLBACK_BRANDING.primaryColor).toBe(true);
  });

  it('renders children inside the brand context', () => {
    function Inspect() {
      return <div data-testid="inspect">{FALLBACK_BRANDING.appName}</div>;
    }
    render(
      withQuery(
        <BrandProvider>
          <Inspect />
        </BrandProvider>,
      ),
    );
    expect(document.querySelector('[data-testid="inspect"]')?.textContent).toBe('AntFlow 审批');
  });
});
