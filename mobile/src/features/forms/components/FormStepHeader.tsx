export type FormStepHeaderProps = {
  title: string;
  description?: string;
  currentIndex: number;
  total: number;
  completedCount: number;
  fieldCount?: number;
  autosaveLabel?: string;
};

export function FormStepHeader({
  title,
  description,
  currentIndex,
  total,
  completedCount,
  fieldCount = 0,
  autosaveLabel,
}: FormStepHeaderProps) {
  const progress = total > 0 ? ((currentIndex + 1) / total) * 100 : 0;
  const estimatedSeconds = Math.max(20, fieldCount * 20);
  return (
    <section className="af-form-step-head" aria-label="填写进度">
      <div className="af-form-step-head__title">
        <span className="af-form-step-head__kicker">当前分组</span>
        <span className="af-form-step-head__count">{currentIndex + 1} / {total}</span>
      </div>
      <div className="af-form-step-head__main">
        <h2>{title}</h2>
        {autosaveLabel ? <span className="af-tag af-tag--success">{autosaveLabel}</span> : null}
      </div>
      <div className="af-form-step-head__meta">
        <span>本节 {fieldCount} 项，预计 {estimatedSeconds} 秒</span>
        <span>{completedCount} 步已完成</span>
      </div>
      {description ? <p>{description}</p> : null}
      <div className="af-form-step-progress" aria-hidden="true">
        <i style={{ width: `${progress}%` }} />
      </div>
    </section>
  );
}

export default FormStepHeader;
