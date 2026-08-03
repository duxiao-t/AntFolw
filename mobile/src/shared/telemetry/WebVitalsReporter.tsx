import { useEffect } from 'react';
import { onCLS, onFCP, onINP, onLCP } from 'web-vitals';
import { track } from './telemetry';

function report(name: string, value: number, route: string) {
  track({
    name,
    route,
    durationMs: Math.round(value),
  });
}

/**
 * Reports FCP, LCP, CLS and INP via the standard `web-vitals` package.
 * Transport is privacy-safe (name/route/duration only).
 */
export function WebVitalsReporter() {
  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    const route = () => window.location.pathname;
    onFCP((metric) => report('web_vital_fcp', metric.value, route()));
    onLCP((metric) => report('web_vital_lcp', metric.value, route()));
    onCLS((metric) => report('web_vital_cls', metric.value * 1000, route()));
    onINP((metric) => report('web_vital_inp', metric.value, route()));
  }, []);

  return null;
}

export default WebVitalsReporter;