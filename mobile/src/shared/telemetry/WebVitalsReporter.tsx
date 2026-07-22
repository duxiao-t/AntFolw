import { useEffect } from 'react';
import { track } from './telemetry';

/**
 * Reports FCP, LCP, CLS and INP without requiring the web-vitals package.
 * Transport is privacy-safe (name/route/duration only).
 */
export function WebVitalsReporter() {
  useEffect(() => {
    if (typeof window === 'undefined' || typeof PerformanceObserver === 'undefined') {
      return;
    }

    const cleanups: Array<() => void> = [];
    let clsValue = 0;
    let inpValue = 0;

    const safeObserve = (
      type: string,
      callback: (list: PerformanceObserverEntryList) => void,
      options?: PerformanceObserverInit,
    ) => {
      try {
        const observer = new PerformanceObserver(callback);
        observer.observe(options ?? { type, buffered: true });
        cleanups.push(() => observer.disconnect());
      } catch {
        // entry type unsupported in this environment
      }
    };

    safeObserve('paint', (list) => {
      for (const entry of list.getEntries()) {
        if (entry.name === 'first-contentful-paint') {
          track({
            name: 'web_vital_fcp',
            route: window.location.pathname,
            durationMs: Math.round(entry.startTime),
          });
        }
      }
    });

    safeObserve('largest-contentful-paint', (list) => {
      const entries = list.getEntries();
      const last = entries[entries.length - 1];
      if (last) {
        track({
          name: 'web_vital_lcp',
          route: window.location.pathname,
          durationMs: Math.round(last.startTime),
        });
      }
    });

    safeObserve('layout-shift', (list) => {
      for (const entry of list.getEntries()) {
        const layout = entry as PerformanceEntry & {
          hadRecentInput?: boolean;
          value?: number;
        };
        if (!layout.hadRecentInput && typeof layout.value === 'number') {
          clsValue += layout.value;
          track({
            name: 'web_vital_cls',
            route: window.location.pathname,
            durationMs: Math.round(clsValue * 1000),
          });
        }
      }
    });

    safeObserve(
      'event',
      (list) => {
        for (const entry of list.getEntries()) {
          if (entry.duration > inpValue) {
            inpValue = entry.duration;
            track({
              name: 'web_vital_inp',
              route: window.location.pathname,
              durationMs: Math.round(inpValue),
            });
          }
        }
      },
      { type: 'event', buffered: true, durationThreshold: 16 } as PerformanceObserverInit,
    );

    return () => {
      for (const dispose of cleanups) {
        dispose();
      }
    };
  }, []);

  return null;
}

export default WebVitalsReporter;
