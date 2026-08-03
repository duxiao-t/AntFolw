export type TelemetryEvent = {
  name: string;
  route: string;
  durationMs?: number;
  status?: number;
  code?: string;
  traceId?: string;
};

type TelemetryTransport = (event: TelemetryEvent) => void | Promise<void>;

const FORBIDDEN_PAYLOAD_KEYS =
  /token|password|passwd|secret|authorization|cookie|comment|filename|fileName|contentUrl|signed|formData|formValue|values/i;

let transport: TelemetryTransport | null = null;

function currentRoute(): string {
  if (typeof window === 'undefined') {
    return '/';
  }
  return `${window.location.pathname}${window.location.search}`;
}

export function isTelemetryEnabled(): boolean {
  const disabled = import.meta.env.VITE_TELEMETRY_DISABLED;
  if (disabled === 'true' || disabled === '1') {
    return false;
  }
  return true;
}

export function getTelemetryEndpoint(): string | undefined {
  const endpoint = import.meta.env.VITE_TELEMETRY_ENDPOINT;
  if (typeof endpoint === 'string' && endpoint.trim()) {
    return endpoint.trim();
  }
  return undefined;
}

/** Strip accidental sensitive fields if callers spread extra props. */
export function sanitizeTelemetryEvent(event: TelemetryEvent): TelemetryEvent {
  const safe: TelemetryEvent = {
    name: String(event.name ?? ''),
    route: String(event.route ?? currentRoute()),
  };
  if (typeof event.durationMs === 'number' && Number.isFinite(event.durationMs)) {
    safe.durationMs = event.durationMs;
  }
  if (typeof event.status === 'number' && Number.isFinite(event.status)) {
    safe.status = event.status;
  }
  if (typeof event.code === 'string' && event.code && !FORBIDDEN_PAYLOAD_KEYS.test(event.code)) {
    safe.code = event.code;
  }
  if (typeof event.traceId === 'string' && event.traceId) {
    safe.traceId = event.traceId;
  }
  return safe;
}

export function setTelemetryTransport(next: TelemetryTransport | null): void {
  transport = next;
}

function defaultTransport(event: TelemetryEvent): void {
  if (!isTelemetryEnabled()) {
    return;
  }
  const endpoint = getTelemetryEndpoint();
  if (endpoint && typeof fetch === 'function') {
    void fetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(event),
      keepalive: true,
      credentials: 'omit',
    }).catch(() => {
      // Telemetry must never break the app.
    });
    return;
  }
  if (import.meta.env.DEV && typeof console !== 'undefined' && typeof console.debug === 'function') {
    console.debug('[telemetry]', event);
  }
}

export function track(event: TelemetryEvent): void {
  if (!isTelemetryEnabled()) {
    return;
  }
  const safe = sanitizeTelemetryEvent(event);
  try {
    const runner = transport ?? defaultTransport;
    void runner(safe);
  } catch {
    // ignore transport failures
  }
}

export function trackRouteEvent(
  name: string,
  extra: Omit<TelemetryEvent, 'name' | 'route'> = {},
): void {
  track({ name, route: currentRoute(), ...extra });
}

export default track;
