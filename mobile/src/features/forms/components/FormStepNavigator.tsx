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
            className={`af-step-chip${isCurrent ? ' af-step-chip--current' : ''}${isDone ? ' af-step-chip--done' : ''}${errorCount > 0 ? ' af-step-chip--error' : ''}`}
            aria-current={isCurrent ? 'step' : undefined}
            onClick={() => onSelect(index)}
          >
            <b>{index + 1}</b>
            <span>{group.title}</span>
            {errorCount > 0 ? <small>{errorCount} 项需补充</small> : null}
          </button>
        );
      })}
    </nav>
  );
}

export default FormStepNavigator;
