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
    <nav className="af-form-step-nav" aria-label="表单步骤">
      {groups.map((group, index) => {
        const errorCount = errorCounts[group.id] ?? 0;
        const isCurrent = index === currentIndex;
        const isDone = completedStepIds.has(group.id);
        return (
          <button
            key={group.id}
            type="button"
            className={`af-step-dot${isCurrent ? ' af-step-dot--current' : ''}${isDone ? ' af-step-dot--done' : ''}${errorCount > 0 ? ' af-step-dot--error' : ''}`}
            aria-current={isCurrent ? 'step' : undefined}
            aria-label={`${index + 1}. ${group.title}${errorCount > 0 ? `，${errorCount} 项需补充` : ''}`}
            onClick={() => onSelect(index)}
          >
            {index + 1}
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
}: Pick<FormStepNavigatorProps, 'groups' | 'currentIndex' | 'errorCounts'>) {
  const nextGroup = groups[currentIndex + 1] ?? null;
  const nextErrorCount = nextGroup ? errorCounts[nextGroup.id] ?? 0 : 0;

  return (
    <div className={`af-next-step-card${nextGroup ? '' : ' af-next-step-card--done'}`}>
      {nextGroup ? (
        <>
          <strong>接下来：{nextGroup.title}</strong>
          <span>{nextErrorCount > 0 ? `${nextErrorCount} 项需补充` : '完成本节后继续填写'}</span>
        </>
      ) : (
        <>
          <strong>最后一步</strong>
          <span>完成后进入提交确认</span>
        </>
      )}
    </div>
  );
}

export default FormStepNavigator;
