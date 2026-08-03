import type { ReactNode } from "react";

export function PageSkeleton({ rows = 4 }: { rows?: number }) {
  const items = Array.from({ length: rows }, (_, idx) => idx);
  return (
    <div className="page page-state" role="status" aria-live="polite" aria-busy="true">
      <div className="skeleton-card" style={{ marginTop: 14 }}>
        <span className="skeleton" style={{ width: "40%", height: 14 }} />
        <span className="skeleton" style={{ width: "72%", height: 24 }} />
      </div>
      {items.map((row) => (
        <span key={`skeleton-row-${row}`} className="skeleton" style={{ height: 56, marginTop: 10, borderRadius: 12 }} />
      ))}
    </div>
  );
}

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
    <div className="empty-hero" role="status" aria-live="polite">
      <div className="empty-hero__art"><EmptyArt /></div>
      <h3>{title}</h3>
      {hint ? <p>{hint}</p> : null}
      {action ? <div className="empty-hero__cta">{action}</div> : null}
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
    <div className="empty-hero" role="alert">
      <div className="empty-hero__art"><ErrorArt /></div>
      <h3>{title}</h3>
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="btn btn--primary btn--block btn--lg" onClick={onRetry}>
          重新加载
        </button>
      ) : null}
    </div>
  );
}

export function OfflineEmpty({ onRetry }: { onRetry?: () => void }) {
  return (
    <main className="page page-state page-state--offline" role="alert">
      <div className="empty-hero">
        <div className="empty-hero__art"><ErrorArt /></div>
        <h3>网络暂时不可用</h3>
        <p>已保留未提交的表单内容<br />网络恢复后可继续操作</p>
        {onRetry ? <div className="empty-hero__cta"><button type="button" className="btn btn--primary btn--block btn--lg" onClick={onRetry}>重新加载</button></div> : null}
      </div>
    </main>
  );
}

function EmptyArt() {
  return <svg className="page-state__art" viewBox="0 0 160 160" fill="none" aria-hidden="true"><circle cx="80" cy="80" r="56" fill="#eef2f7" /><rect x="44" y="48" width="72" height="64" rx="8" fill="#fff" stroke="#cbd5e0" strokeWidth="2" /><path d="M52 64h56M52 76h44M52 88h36M52 100h28" stroke="#cbd5e0" strokeWidth="3" strokeLinecap="round" /><circle cx="120" cy="48" r="14" fill="#0b57d0" /><path d="M114 48l4 4 8-8" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}

function ErrorArt() {
  return <svg className="page-state__art" viewBox="0 0 160 160" fill="none" aria-hidden="true"><circle cx="80" cy="80" r="56" fill="#fdecea" /><path d="M56 64h48M56 80h36M56 96h28" stroke="#c0392b" strokeWidth="3" strokeLinecap="round" /><circle cx="120" cy="48" r="16" fill="#c0392b" /><path d="M114 42l12 12M126 42l-12 12" stroke="#fff" strokeWidth="3" strokeLinecap="round" /></svg>;
}

export default PageSkeleton;
