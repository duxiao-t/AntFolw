export type FormStepHeaderProps = {
  title: string;
  description?: string;
  currentIndex: number;
  total: number;
  completedCount: number;
  autosaveLabel?: string;
};

export function FormStepHeader({
  title,
  description,
  currentIndex,
  total,
  completedCount,
  autosaveLabel,
}: FormStepHeaderProps) {
  const progress = total > 0 ? ((currentIndex + 1) / total) * 100 : 0;
  return (
    <section className="af-form-step-head" aria-label="填写进度">
      <div className="af-form-step-head__meta">
        <span>第 {currentIndex + 1} 步 / 共 {total} 步</span>
        <span>{completedCount} 步已完成</span>
      </div>
      <div className="af-form-step-head__title">
        <h2>{title}</h2>
        {autosaveLabel ? <span className="af-tag af-tag--success">{autosaveLabel}</span> : null}
      </div>
      {description ? <p>{description}</p> : null}
      <div className="af-form-step-progress" aria-hidden="true">
        <i style={{ width: `${progress}%` }} />
      </div>
    </section>
  );
}

export default FormStepHeader;
