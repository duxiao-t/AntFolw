import type { PublicBranding } from '../../shared/api/types';

export const FALLBACK_BRANDING: PublicBranding = {
  version: 'builtin-1',
  appName: 'AntFlow 审批',
  companyName: 'AntFlow',
  primaryColor: '#1677ff',
  mobileHeaderTitle: '工作台',
  loginTitle: '登录 AntFlow',
  showLoginFooter: true,
  footerText: '© 2026 AntFlow',
};

/**
 * Allowlist of CSS variables the published branding is allowed to write.
 * Server-provided branding must never write arbitrary CSS, therefore every
 * key here is the only token the branding can reach.
 */
export const ALLOWED_BRAND_TOKENS: readonly string[] = ['--af-color-primary', '--adm-color-primary'];

export const HEX_COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/;

export function isHexColor(value: string): boolean {
  return HEX_COLOR_PATTERN.test(value);
}

export function readDocumentHead(): {
  root: HTMLElement;
  title: string;
  iconHref: string | null;
  manifestHref: string | null;
} {
  const root = document.documentElement;
  const iconLink = document.querySelector<HTMLLinkElement>("link[rel~='icon']");
  const manifestLink = document.querySelector<HTMLLinkElement>("link[rel='manifest']");
  return {
    root,
    title: document.title,
    iconHref: iconLink?.getAttribute('href') ?? null,
    manifestHref: manifestLink?.getAttribute('href') ?? null,
  };
}

/**
 * Apply only the allowlisted branding tokens. Anything else from the server
 * is ignored so an attacker cannot smuggle arbitrary CSS through branding.
 */
export function applyBrandTokens(branding: PublicBranding): void {
  const primary = isHexColor(branding.primaryColor) ? branding.primaryColor : '#1677ff';
  const root = document.documentElement;
  root.style.setProperty('--af-color-primary', primary);
  root.style.setProperty('--adm-color-primary', primary);
  document.title = branding.appName;

  const faviconHref = `/api/public/branding/favicon.svg?v=${encodeURIComponent(branding.version)}`;
  let iconLink = document.querySelector<HTMLLinkElement>("link[rel~='icon']");
  if (!iconLink) {
    iconLink = document.createElement('link');
    iconLink.rel = 'icon';
    document.head.appendChild(iconLink);
  }
  iconLink.setAttribute('href', faviconHref);

  const manifestHref = `/api/public/branding/manifest.webmanifest?v=${encodeURIComponent(branding.version)}`;
  let manifestLink = document.querySelector<HTMLLinkElement>("link[rel='manifest']");
  if (!manifestLink) {
    manifestLink = document.createElement('link');
    manifestLink.rel = 'manifest';
    document.head.appendChild(manifestLink);
  }
  manifestLink.setAttribute('href', manifestHref);
}

export interface BrandingBootOptions {
  fetcher?: () => Promise<PublicBranding | null>;
  apply?: (branding: PublicBranding) => void;
  fallback?: PublicBranding;
}

export async function bootBranding({
  fetcher,
  apply = applyBrandTokens,
  fallback = FALLBACK_BRANDING,
}: BrandingBootOptions = {}): Promise<PublicBranding> {
  const fetchImpl = fetcher ?? defaultFetchBranding;
  const remote = await fetchImpl().catch(() => null);
  if (remote) {
    apply(remote);
    return remote;
  }
  apply(fallback);
  return fallback;
}

async function defaultFetchBranding(): Promise<PublicBranding | null> {
  const { apiRequest } = await import('../../shared/api/http');
  try {
    return await apiRequest<PublicBranding>('/api/public/branding');
  } catch {
    return null;
  }
}

