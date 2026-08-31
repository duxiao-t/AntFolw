import { Popup } from 'antd-mobile';
import { CloseOutline, LeftOutline } from 'antd-mobile-icons';
import {
  type PropsWithChildren,
  type ReactNode,
  useEffect,
  useRef,
} from 'react';
import { createPortal } from 'react-dom';

type MobileSelectionPopupProps = PropsWithChildren<{
  visible: boolean;
  title: string;
  subtitle?: ReactNode;
  headerAction?: ReactNode;
  footer?: ReactNode;
  presentation?: 'fullscreen' | 'sheet';
  onClose: () => void;
}>;

export function MobileSelectionPopup({
  visible,
  title,
  subtitle,
  headerAction,
  footer,
  presentation = 'fullscreen',
  onClose,
  children,
}: MobileSelectionPopupProps) {
  const panelRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!visible || typeof document === 'undefined') {
      return;
    }
    if (presentation === 'fullscreen')
      document.body.classList.add('af-full-picker-open');
    setTimeout(() => {
      const target = panelRef.current?.querySelector<HTMLInputElement>(
        'input[type="search"], input, textarea, button',
      );
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
  }, [onClose, presentation, visible]);

  if (!visible || typeof document === 'undefined') {
    return null;
  }

  const panel = (
    <section
      ref={panelRef}
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className={`af-full-picker__panel${presentation === 'sheet' ? ' af-full-picker__panel--sheet' : ''}`}
      tabIndex={-1}
    >
      {presentation === 'sheet' ? (
        <span className="af-selection-sheet__handle" aria-hidden="true" />
      ) : null}
      <header
        className={`af-full-picker__head${presentation === 'sheet' ? ' af-full-picker__head--sheet' : ''}`}
      >
        {presentation === 'fullscreen' ? (
          <button
            type="button"
            aria-label="返回"
            className="af-full-picker__back"
            onClick={onClose}
          >
            <LeftOutline aria-hidden="true" />
          </button>
        ) : (
          headerAction ?? <span aria-hidden="true" />
        )}
        <div>
          <h3>{title}</h3>
          {subtitle ? <small>{subtitle}</small> : null}
        </div>
        {presentation === 'sheet' ? (
          <button type="button" aria-label="关闭" onClick={onClose}>
            <CloseOutline aria-hidden="true" />
          </button>
        ) : null}
      </header>
      <div className="af-full-picker__body">{children}</div>
      {footer ? (
        <footer className="af-full-picker__footer">{footer}</footer>
      ) : null}
    </section>
  );

  if (presentation === 'sheet') {
    return (
      <Popup
        visible
        position="bottom"
        closeOnMaskClick
        onMaskClick={onClose}
        bodyClassName="af-selection-sheet"
        bodyStyle={{ height: '50dvh' }}
      >
        {panel}
      </Popup>
    );
  }
  return createPortal(
    <div className="af-full-picker">{panel}</div>,
    document.body,
  );
}
