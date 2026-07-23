import type { CSSProperties, ReactNode } from "react";

const skeletonBlockStyle: CSSProperties = {
  minHeight: "100dvh",
  padding: "24px 16px",
  background: "var(--af-color-bg)",
  display: "flex",
  flexDirection: "column",
  gap: 16,
};

const blockBase: CSSProperties = {
  display: "block",
  width: "100%",
  background: "var(--af-color-border)",
  borderRadius: 6,
  animation: "app-pulse 1.4s ease-in-out infinite",
};

export function PageSkeleton({ rows = 4 }: { rows?: number }) {
  const items = Array.from({ length: rows }, (_, idx) => idx);
  return (
    <div role="status" aria-live="polite" aria-busy="true" style={skeletonBlockStyle}>
      <span style={{ ...blockBase, height: 24, width: "40%" }} />
      {items.map((row) => (
        <span key={`skeleton-row-${row}`} style={{ ...blockBase, height: 56 }} />
      ))}
      <style>{`@keyframes app-pulse { 0%,100% { opacity: 0.5 } 50% { opacity: 1 } }`}</style>
    </div>
  );
}

const emptyStyle: CSSProperties = {
  minHeight: 240,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 12,
  padding: 24,
  textAlign: "center",
  color: "var(--af-color-text)",
};

export function PageEmpty({
  title = "暂无数据",
  hint,
  action,
}: {
  title?: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <div role="status" aria-live="polite" style={emptyStyle}>
      <strong style={{ fontSize: "1rem" }}>{title}</strong>
      {hint ? <span style={{ color: "rgba(0,0,0,0.55)" }}>{hint}</span> : null}
      {action}
    </div>
  );
}

export function PageError({
  title = "加载失败",
  message = "请稍后重试或返回工作台。",
  onRetry,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
}) {
  return (
    <div role="alert" style={emptyStyle}>
      <strong style={{ fontSize: "1rem" }}>{title}</strong>
      <span style={{ color: "rgba(0,0,0,0.55)" }}>{message}</span>
      {onRetry ? (
        <button type="button" className="af-btn" onClick={onRetry} style={{ minHeight: 44 }}>
          重试
        </button>
      ) : null}
    </div>
  );
}

export function OfflineBanner({ onRetry }: { onRetry?: () => void }) {
  return (
    <div
      role="status"
      data-testid="offline-banner"
      style={{
        background: "var(--af-color-warning)",
        color: "#fff",
        padding: "9px 14px",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: 12,
        minHeight: 44,
      }}
    >
      <span>网络已断开，正在尝试恢复…</span>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          style={{
            border: "0",
            background: "rgba(255,255,255,0.2)",
            color: "#fff",
            borderRadius: 14,
            padding: "4px 12px",
            minHeight: 28,
            cursor: "pointer",
            font: "inherit",
          }}
        >
          重试
        </button>
      ) : null}
    </div>
  );
}

export function OfflineEmpty({ onRetry }: { onRetry?: () => void }) {
  return (
    <div className="af-empty-state af-fade-in" role="alert">
      <i className="af-empty-state__icon" aria-hidden="true">{"\u2301"}</i>
      <h3 className="af-empty-state__title">网络暂时不可用</h3>
      <p className="af-empty-state__hint">
        已保留未提交的表单内容{"\n"}网络恢复后可继续操作
      </p>
      {onRetry ? (
        <button type="button" className="af-empty-state__button" onClick={onRetry}>
          重新加载
        </button>
      ) : null}
    </div>
  );
}

export default PageSkeleton;
