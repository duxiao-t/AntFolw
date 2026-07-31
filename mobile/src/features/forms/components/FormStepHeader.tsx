import type { ReactNode } from "react";

export type FormStepHeaderProps = {
  title: string;
  description?: string;
  currentIndex: number;
  total: number;
  completedCount: number;
  fieldCount?: number;
  autosaveLabel?: string;
  sectionLabel?: string;
  children?: ReactNode;
};

export function FormStepHeader({
  title,
  description,
  currentIndex,
  total,
  completedCount,
  fieldCount = 0,
  autosaveLabel,
  sectionLabel,
  children,
}: FormStepHeaderProps) {
  const progress = total > 0 ? ((currentIndex + 1) / total) * 100 : 0;
  return (
    <section className="step-head" aria-label="填写进度">
      <div className="step-head__kicker"><span>步骤 {currentIndex + 1} / {total} · {sectionLabel || `${fieldCount} 项内容`}</span><span className="step-head__count">第 {currentIndex + 1} 步</span></div>
      <h2>{title}</h2>
      <p>{description || `请完成本步骤内容，带 * 为必填项。`}{autosaveLabel ? ` · ${autosaveLabel}` : ""}</p>
      <div className="step-progress" aria-hidden="true">
        <i style={{ width: `${progress}%` }} />
      </div>
      {children}
      <span className="visually-hidden">已完成 {completedCount} 步</span>
    </section>
  );
}

export default FormStepHeader;
