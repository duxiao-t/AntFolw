import { useEffect, useRef, type RefObject } from 'react';

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function isVisible(el: HTMLElement) {
  if (el.hasAttribute('disabled') || el.getAttribute('aria-hidden') === 'true') {
    return false;
  }
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') {
    return false;
  }
  // Prefer getClientRects over offsetParent — fixed-position ancestors make offsetParent null.
  return el.getClientRects().length > 0;
}

function listFocusables(container: HTMLElement) {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(isVisible);
}

/**
 * Traps keyboard focus inside `containerRef` while `active` is true.
 * Restores previously focused element on deactivate.
 */
export function useFocusTrap(
  active: boolean,
  containerRef: RefObject<HTMLElement | null>,
  onEscape?: () => void,
) {
  const onEscapeRef = useRef(onEscape);
  onEscapeRef.current = onEscape;

  useEffect(() => {
    if (!active) {
      return;
    }

    let cancelled = false;
    let container: HTMLElement | null = null;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    let removeListeners: (() => void) | undefined;

    const attach = () => {
      if (cancelled) {
        return;
      }
      container = containerRef.current;
      if (!container) {
        // Dialog may mount one frame after `active` flips true.
        requestAnimationFrame(attach);
        return;
      }

      const panel = container;

      const focusFirst = () => {
        const items = listFocusables(panel);
        const initial = items[0];
        if (initial) {
          initial.focus();
          return;
        }
        if (!panel.hasAttribute('tabindex')) {
          panel.setAttribute('tabindex', '-1');
        }
        panel.focus();
      };

      // Defer so antd-mobile internal focus settles first.
      requestAnimationFrame(focusFirst);

      const onKeyDown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          onEscapeRef.current?.();
          return;
        }
        if (event.key !== 'Tab') {
          return;
        }
        const items = listFocusables(panel);
        if (items.length === 0) {
          event.preventDefault();
          panel.focus();
          return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        if (!first || !last) {
          return;
        }
        const current = document.activeElement as HTMLElement | null;

        if (event.shiftKey) {
          if (!current || current === first || !panel.contains(current)) {
            event.preventDefault();
            last.focus();
          }
        } else if (!current || current === last || !panel.contains(current)) {
          event.preventDefault();
          first.focus();
        }
      };

      const onFocusIn = (event: FocusEvent) => {
        const target = event.target as Node | null;
        if (target && !panel.contains(target)) {
          const items = listFocusables(panel);
          (items[0] ?? panel).focus();
        }
      };

      document.addEventListener('keydown', onKeyDown, true);
      document.addEventListener('focusin', onFocusIn, true);
      removeListeners = () => {
        document.removeEventListener('keydown', onKeyDown, true);
        document.removeEventListener('focusin', onFocusIn, true);
      };
    };

    attach();

    return () => {
      cancelled = true;
      removeListeners?.();
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        try {
          previouslyFocused.focus();
        } catch {
          // ignore restore failures when node is detached
        }
      }
    };
  }, [active, containerRef]);
}

export default useFocusTrap;
