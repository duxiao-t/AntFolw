import { LeftOutline } from 'antd-mobile-icons';
import { useEffect, useRef, type PropsWithChildren, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

type MobileSelectionPopupProps = PropsWithChildren<{
  visible: boolean;
  title: string;
  subtitle?: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
}>;

export function MobileSelectionPopup({
  visible,
  title,
  subtitle,
  footer,
  onClose,
  children,
}: MobileSelectionPopupProps) {
  const panelRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!visible || typeof document === 'undefined') {
      return;
    }
    document.body.classList.add('af-full-picker-open');
    setTimeout(() => {
      const target = panelRef.current?.querySelector<HTMLInputElement>('input[type="search"], input, textarea, button');
      target?.focus();
    }, 0);
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.classList.remove('af-full-picker-open');
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose, visible]);

  if (!visible || typeof document === 'undefined') {
    return null;
  }

  return createPortal(
    <div className="af-full-picker">
      <section
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="af-full-picker__panel"
        tabIndex={-1}
      >
        <header className="af-full-picker__head">
          <button type="button" aria-label="返回" className="af-full-picker__back" onClick={onClose}>
            <LeftOutline aria-hidden="true" />
          </button>
          <div>
            <h3>{title}</h3>
            {subtitle ? <small>{subtitle}</small> : null}
          </div>
        </header>
        <div className="af-full-picker__body">{children}</div>
        {footer ? <footer className="af-full-picker__footer">{footer}</footer> : null}
      </section>
    </div>,
    document.body,
  );
}
