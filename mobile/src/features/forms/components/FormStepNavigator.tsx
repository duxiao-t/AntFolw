import type { FormStepGroup } from '../schema/stepGroups';

export type FormStepNavigatorProps = {
  groups: FormStepGroup[];
  currentIndex: number;
  completedStepIds: Set<string>;
  errorCounts: Record<string, number>;
  onSelect: (index: number) => void;
};

export function FormStepNavigator({
  groups,
  currentIndex,
  completedStepIds,
  errorCounts,
  onSelect,
}: FormStepNavigatorProps) {
  return (
    <nav className="step-nav" aria-label="表单步骤">
      {groups.map((group, index) => {
        const errorCount = errorCounts[group.id] ?? 0;
        const isCurrent = index === currentIndex;
        const isDone = completedStepIds.has(group.id);
        return (
          <button
            key={group.id}
            type="button"
            className={`step-dot${isCurrent ? ' step-dot--current' : ''}${isDone ? ' step-dot--done' : ''}${errorCount > 0 ? ' step-dot--error' : ''}`}
            aria-current={isCurrent ? 'step' : undefined}
            aria-label={`${index + 1}. ${group.title}${errorCount > 0 ? `，${errorCount} 项需补充` : ''}`}
            onClick={() => onSelect(index)}
          >
            {isDone ? '✓' : index + 1}
          </button>
        );
      })}
    </nav>
  );
}

export function FormNextStepHint({
  groups,
  currentIndex,
  errorCounts,
  finalTitle = '最后一步：提交确认',
  finalHint = '完成后进入提交确认',
}: Pick<FormStepNavigatorProps, 'groups' | 'currentIndex' | 'errorCounts'> & { finalTitle?: string; finalHint?: string }) {
  const nextGroup = groups[currentIndex + 1] ?? null;
  const nextErrorCount = nextGroup ? errorCounts[nextGroup.id] ?? 0 : 0;

  return (
    <div className={`form-next-hint${nextGroup ? '' : ' form-next-hint--done'}`}>
      {nextGroup ? (
        <>
          <strong>下一步：{nextGroup.title}</strong>
          <span>{nextErrorCount > 0 ? `${nextErrorCount} 项需补充` : '完成本节后继续填写'}</span>
        </>
      ) : (
        <>
          <strong>{finalTitle}</strong>
          <span>{finalHint}</span>
        </>
      )}
    </div>
  );
}

export default FormStepNavigator;
