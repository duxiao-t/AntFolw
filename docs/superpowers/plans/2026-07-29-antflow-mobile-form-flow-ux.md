# AntFlow Mobile Form Flow UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the mobile form submission chain so dynamic forms are filled step-by-step, visually lighter, and consistent with the blue/green AntFlow mobile direction.

**Architecture:** Add a pure schema-to-step grouping layer, then wire `FormFillPage` to render one group at a time without changing submitted values. Reuse the existing field registry, draft recovery, self-select, confirm, submit, and process APIs; the work is mobile frontend only.

**Tech Stack:** Vite 8, React 19, React Router 7, TanStack Query 5, Ant Design Mobile 5, Vitest, Testing Library, Biome.

## Global Constraints

- Work only inside `mobile/` and docs unless a task explicitly says otherwise.
- Do not change backend API contracts or the recursive workflow tree model.
- Form values must continue to use `MobileSchemaNode.id` as the submitted key.
- Hidden fields are still controlled by existing `isVisibleNode(node, values)`.
- Final submitted data must still pass through `collectVisibleValues`.
- Keep draft save, local recovery, attachment upload, self-select, idempotent submit, and process detail behavior intact.
- Use blue `#1769e0` for brand/current/primary actions and green `#0f8a5f` for success/save/completed states.
- Use low-glare page background `#f5f8fc`.
- Do not use viewport-width font scaling.
- Touch targets must remain at least 44px high.
- Run mobile verification from `mobile/`, not repo root.

---

## File Map

- Create `mobile/src/features/forms/schema/stepGroups.ts`: pure form step grouping functions.
- Create `mobile/src/features/forms/schema/stepGroups.test.ts`: TDD coverage for grouping behavior.
- Create `mobile/src/features/forms/components/FormStepHeader.tsx`: current step title, progress bar, status summary.
- Create `mobile/src/features/forms/components/FormStepNavigator.tsx`: compact step chips with error and completed state.
- Create `mobile/src/features/forms/components/ConfirmSummaryList.tsx`: compact key/value summary shared by confirm and process pages.
- Modify `mobile/src/features/forms/FormFillPage.tsx`: current-step state, per-step validation, full validation before next route.
- Modify `mobile/src/features/forms/FormFillPage.test.tsx`: grouping, navigation, error routing, draft regression tests.
- Modify `mobile/src/features/forms/SubmitConfirmPage.tsx`: use compact summary and stronger confirm layout.
- Modify `mobile/src/features/forms/submit-flow.test.tsx`: confirm flow regression coverage.
- Modify `mobile/src/features/forms/SelfSelectPage.tsx`: blue/green self-select visual structure, error placement.
- Modify `mobile/src/features/forms/SubmitSuccessPage.tsx`: align result page with first-stage visual language.
- Modify `mobile/src/features/processes/ProcessDetailPage.tsx`: use compact summary and blue/green process status.
- Modify `mobile/src/features/processes/ProcessDetailPage.test.tsx`: process detail summary and withdraw visibility coverage.
- Modify field components under `mobile/src/features/forms/fields/`: align comfort field shell and option controls.
- Modify `mobile/src/features/forms/fields/fieldShared.tsx`: shared field shell classes and option helpers if needed.
- Modify `mobile/src/styles/tokens.css`: first-stage blue/green tokens.
- Modify `mobile/src/styles/global.css`: form-flow, field, summary, step, and action-bar styles.
- Modify `mobile/src/features/workbench/AppCatalogPage.tsx`: fix existing mobile lint error for semantic search element.
- Modify `mobile/src/features/workbench/WorkbenchPage.tsx`: fix existing mobile lint error for unsupported ARIA.

---

### Task 1: Schema Step Grouping

**Files:**
- Create: `mobile/src/features/forms/schema/stepGroups.ts`
- Create: `mobile/src/features/forms/schema/stepGroups.test.ts`

**Interfaces:**
- Consumes: `MobileSchemaNode`, `MobileFormValues`, `isVisibleNode(node, values)`
- Produces:
  - `type FormStepGroup`
  - `function buildFormStepGroups(schema: MobileSchemaNode[], values: MobileFormValues): FormStepGroup[]`
  - `function fieldIdsInStep(group: FormStepGroup): string[]`

- [ ] **Step 1: Write failing tests for grouped schema**

Create `mobile/src/features/forms/schema/stepGroups.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { MobileSchemaNode } from './types';
import { buildFormStepGroups, fieldIdsInStep } from './stepGroups';

describe('buildFormStepGroups', () => {
  it('uses span_layout nodes as named mobile steps', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'leave-time',
        type: 'span_layout',
        label: '请假时间',
        children: [
          { id: 'start', type: 'date', label: '开始时间' },
          { id: 'end', type: 'date', label: '结束时间' },
        ],
      },
      { id: 'reason', type: 'textarea', label: '请假事由' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(2);
    expect(groups[0]).toMatchObject({ id: 'leave-time', title: '请假时间' });
    expect(fieldIdsInStep(groups[0])).toEqual(['start', 'end']);
    expect(groups[1]).toMatchObject({ id: 'auto-2', title: '补充信息' });
    expect(fieldIdsInStep(groups[1])).toEqual(['reason']);
  });

  it('uses a description before fields as step description', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'desc-1', type: 'description', label: '说明', props: { text: '请填写真实金额' } },
      { id: 'amount', type: 'money', label: '报销金额' },
      { id: 'invoice', type: 'file_upload', label: '发票' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(1);
    expect(groups[0].title).toBe('报销金额');
    expect(groups[0].description).toBe('请填写真实金额');
    expect(fieldIdsInStep(groups[0])).toEqual(['amount', 'invoice']);
  });

  it('keeps table_list as its own step', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'title', type: 'text', label: '标题' },
      {
        id: 'items',
        type: 'table_list',
        label: '费用明细',
        children: [{ id: 'name', type: 'text', label: '名称' }],
      },
      { id: 'remark', type: 'textarea', label: '备注' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups.map((group) => group.title)).toEqual(['基础信息', '费用明细', '补充信息']);
    expect(fieldIdsInStep(groups[1])).toEqual(['items']);
  });

  it('chunks ungrouped visible fields into groups of at most six', () => {
    const schema: MobileSchemaNode[] = Array.from({ length: 7 }, (_, index) => ({
      id: `field-${index + 1}`,
      type: 'text',
      label: `字段${index + 1}`,
    }));

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(2);
    expect(fieldIdsInStep(groups[0])).toEqual(['field-1', 'field-2', 'field-3', 'field-4', 'field-5', 'field-6']);
    expect(fieldIdsInStep(groups[1])).toEqual(['field-7']);
  });

  it('excludes fields hidden by visibleWhen rules', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'needBudget', type: 'switch', label: '需要预算' },
      {
        id: 'budget',
        type: 'money',
        label: '预算金额',
        props: { displayCondition: { field: 'needBudget', operator: 'eq', value: true } },
      },
    ];

    const groups = buildFormStepGroups(schema, { needBudget: false });

    expect(fieldIdsInStep(groups[0])).toEqual(['needBudget']);
  });
});
```

- [ ] **Step 2: Run the focused failing test**

Run:

```powershell
npm.cmd test -- src/features/forms/schema/stepGroups.test.ts
```

Expected: fail because `stepGroups.ts` does not exist.

- [ ] **Step 3: Implement the pure grouping module**

Create `mobile/src/features/forms/schema/stepGroups.ts`:

```ts
import { isVisibleNode } from './validators';
import type { MobileFormValues, MobileSchemaNode } from './types';

const AUTO_GROUP_SIZE = 6;

export type FormStepGroup = {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
  fieldIds: string[];
};

export function buildFormStepGroups(schema: MobileSchemaNode[], values: MobileFormValues): FormStepGroup[] {
  const groups: FormStepGroup[] = [];
  let pendingDescription: string | undefined;
  let looseNodes: MobileSchemaNode[] = [];

  function flushLoose(title = groups.length === 0 ? '基础信息' : '补充信息') {
    if (looseNodes.length === 0) return;
    for (let index = 0; index < looseNodes.length; index += AUTO_GROUP_SIZE) {
      const chunk = looseNodes.slice(index, index + AUTO_GROUP_SIZE);
      const sequence = groups.length + 1;
      groups.push(toGroup({
        id: `auto-${sequence}`,
        title: index === 0 ? title : `${title}${Math.floor(index / AUTO_GROUP_SIZE) + 1}`,
        description: index === 0 ? pendingDescription : undefined,
        nodes: chunk,
      }));
    }
    looseNodes = [];
    pendingDescription = undefined;
  }

  for (const node of schema) {
    if (!isVisibleNode(node, values)) continue;
    if (node.type === 'description') {
      const text = descriptionText(node);
      if (text) pendingDescription = text;
      continue;
    }
    if (node.type === 'span_layout') {
      flushLoose();
      groups.push(toGroup({
        id: node.id,
        title: node.label ?? '表单分组',
        description: pendingDescription,
        nodes: node.children ?? [],
      }));
      pendingDescription = undefined;
      continue;
    }
    if (node.type === 'table_list') {
      flushLoose();
      groups.push(toGroup({
        id: node.id,
        title: node.label ?? '明细',
        description: pendingDescription,
        nodes: [node],
      }));
      pendingDescription = undefined;
      continue;
    }
    looseNodes.push(node);
  }
  flushLoose();

  return groups.length > 0 ? groups : [toGroup({ id: 'empty', title: '表单内容', nodes: [] })];
}

export function fieldIdsInStep(group: FormStepGroup): string[] {
  return group.fieldIds;
}

function toGroup(input: {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
}): FormStepGroup {
  return {
    id: input.id,
    title: input.title,
    description: input.description,
    nodes: input.nodes,
    fieldIds: input.nodes.flatMap(collectFieldIds),
  };
}

function collectFieldIds(node: MobileSchemaNode): string[] {
  if (node.type === 'description') return [];
  if (node.children && node.type !== 'table_list') {
    return node.children.flatMap(collectFieldIds);
  }
  return [node.id];
}

function descriptionText(node: MobileSchemaNode): string {
  const text = node.props?.text;
  if (typeof text === 'string' && text.trim()) return text.trim();
  return node.label ?? '';
}
```

- [ ] **Step 4: Run the focused test**

Run:

```powershell
npm.cmd test -- src/features/forms/schema/stepGroups.test.ts
```

Expected: pass.

- [ ] **Step 5: Run related schema tests**

Run:

```powershell
npm.cmd test -- src/features/forms/schema/fieldRegistry.test.ts src/features/forms/schema/stepGroups.test.ts
```

Expected: pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add mobile/src/features/forms/schema/stepGroups.ts mobile/src/features/forms/schema/stepGroups.test.ts
git commit -m "功能(移动端): 增加表单分步分组规则"
```

---

### Task 2: Step Header And Summary Components

**Files:**
- Create: `mobile/src/features/forms/components/FormStepHeader.tsx`
- Create: `mobile/src/features/forms/components/FormStepNavigator.tsx`
- Create: `mobile/src/features/forms/components/ConfirmSummaryList.tsx`
- Create: `mobile/src/features/forms/components/formFlowComponents.test.tsx`
- Modify: `mobile/src/styles/global.css`

**Interfaces:**
- Consumes: `FormStepGroup` from Task 1 and field summaries from `getFieldDefinition(type).summarize`
- Produces:
  - `FormStepHeader(props: FormStepHeaderProps)`
  - `FormStepNavigator(props: FormStepNavigatorProps)`
  - `ConfirmSummaryList(props: ConfirmSummaryListProps)`

- [ ] **Step 1: Write failing component tests**

Create `mobile/src/features/forms/components/formFlowComponents.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { FormStepGroup } from '../schema/stepGroups';
import type { MobileSchemaNode } from '../schema/types';
import { ConfirmSummaryList } from './ConfirmSummaryList';
import { FormStepHeader } from './FormStepHeader';
import { FormStepNavigator } from './FormStepNavigator';

const groups: FormStepGroup[] = [
  { id: 'a', title: '请假时间', nodes: [], fieldIds: ['start', 'end'] },
  { id: 'b', title: '请假事由', nodes: [], fieldIds: ['reason'] },
];

describe('form flow components', () => {
  it('renders current step progress and description', () => {
    render(
      <FormStepHeader
        title="请假时间"
        description="先确认时间"
        currentIndex={0}
        total={2}
        completedCount={0}
        autosaveLabel="已自动保存"
      />,
    );

    expect(screen.getByRole('heading', { name: '请假时间' })).toBeInTheDocument();
    expect(screen.getByText('第 1 步 / 共 2 步')).toBeInTheDocument();
    expect(screen.getByText('先确认时间')).toBeInTheDocument();
    expect(screen.getByText('已自动保存')).toBeInTheDocument();
  });

  it('lets the user switch to a step and exposes error counts', async () => {
    const onSelect = vi.fn();
    render(
      <FormStepNavigator
        groups={groups}
        currentIndex={0}
        completedStepIds={new Set(['a'])}
        errorCounts={{ b: 2 }}
        onSelect={onSelect}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: /请假事由/ }));

    expect(onSelect).toHaveBeenCalledWith(1);
    expect(screen.getByText('2 项需补充')).toBeInTheDocument();
  });

  it('renders compact summary rows without description nodes', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'desc', type: 'description', label: '说明', props: { text: '请核对' } },
      { id: 'reason', type: 'text', label: '请假事由' },
      { id: 'days', type: 'number', label: '请假天数' },
    ];

    render(<ConfirmSummaryList schema={schema} values={{ reason: '回家探亲', days: 2 }} />);

    expect(screen.queryByText('说明')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('summary-reason')).getByText('回家探亲')).toBeInTheDocument();
    expect(within(screen.getByTestId('summary-days')).getByText('2')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the failing component tests**

Run:

```powershell
npm.cmd test -- src/features/forms/components/formFlowComponents.test.tsx
```

Expected: fail because components do not exist.

- [ ] **Step 3: Implement `FormStepHeader`**

Create `mobile/src/features/forms/components/FormStepHeader.tsx`:

```tsx
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
```

- [ ] **Step 4: Implement `FormStepNavigator`**

Create `mobile/src/features/forms/components/FormStepNavigator.tsx`:

```tsx
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
```

- [ ] **Step 5: Implement `ConfirmSummaryList`**

Create `mobile/src/features/forms/components/ConfirmSummaryList.tsx`:

```tsx
import { getFieldDefinition } from '../schema/fieldRegistry';
import type { MobileFormValues, MobileSchemaNode } from '../schema/types';
import { isVisibleNode } from '../schema/validators';

export type SummaryRow = {
  id: string;
  label: string;
  value: string;
};

export type ConfirmSummaryListProps = {
  schema: MobileSchemaNode[];
  values: MobileFormValues;
  emptyText?: string;
};

export function ConfirmSummaryList({
  schema,
  values,
  emptyText = '暂无表单字段',
}: ConfirmSummaryListProps) {
  const rows = summarizeSchemaRows(schema, values);
  if (rows.length === 0) {
    return <p className="af-empty-text">{emptyText}</p>;
  }
  return (
    <dl className="af-summary-list">
      {rows.map((row) => (
        <div key={row.id} className="af-summary-row" data-testid={`summary-${row.id}`}>
          <dt>{row.label}</dt>
          <dd>{row.value || '未填写'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function summarizeSchemaRows(schema: MobileSchemaNode[], values: MobileFormValues): SummaryRow[] {
  return schema.flatMap((node) => summarizeNode(node, values));
}

function summarizeNode(node: MobileSchemaNode, values: MobileFormValues): SummaryRow[] {
  if (!isVisibleNode(node, values) || node.type === 'description') return [];
  if (node.children && node.type !== 'table_list') {
    return node.children.flatMap((child) => summarizeNode(child, values));
  }
  return [{
    id: node.id,
    label: node.label ?? node.id,
    value: getFieldDefinition(node.type).summarize(node, values[node.id]),
  }];
}

export default ConfirmSummaryList;
```

- [ ] **Step 6: Add component CSS**

Append to `mobile/src/styles/global.css`:

```css
.af-form-step-head {
  background: var(--af-color-surface);
  border: 1px solid var(--af-color-border);
  border-radius: 12px;
  padding: 12px;
  display: grid;
  gap: 8px;
}
.af-form-step-head__meta,
.af-form-step-head__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.af-form-step-head__meta {
  color: var(--af-color-muted);
  font-size: 11px;
}
.af-form-step-head__title h2 {
  margin: 0;
  color: var(--af-color-text);
  font-size: 17px;
  font-weight: 800;
}
.af-form-step-head p {
  margin: 0;
  color: var(--af-color-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.af-form-step-progress {
  height: 6px;
  overflow: hidden;
  border-radius: var(--af-radius-pill);
  background: var(--af-color-primary-soft);
}
.af-form-step-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--af-color-primary);
}
.af-form-step-nav {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}
.af-step-chip {
  min-width: 92px;
  min-height: 44px;
  border: 1px solid var(--af-color-border);
  border-radius: 12px;
  background: var(--af-color-surface);
  color: var(--af-color-text-secondary);
  display: grid;
  grid-template-columns: 20px 1fr;
  gap: 4px;
  align-items: center;
  padding: 7px 8px;
  text-align: left;
}
.af-step-chip b {
  width: 20px;
  height: 20px;
  border-radius: var(--af-radius-pill);
  display: grid;
  place-items: center;
  background: var(--af-color-primary-soft);
  color: var(--af-color-primary);
  font-size: 11px;
}
.af-step-chip span {
  font-size: 12px;
  font-weight: 700;
}
.af-step-chip small {
  grid-column: 2;
  color: var(--af-color-danger);
  font-size: 10px;
}
.af-step-chip--current {
  border-color: var(--af-color-primary);
  background: #fbfdff;
}
.af-step-chip--done b {
  background: var(--af-color-success-soft);
  color: var(--af-color-success);
}
.af-step-chip--error {
  border-color: var(--af-color-danger);
}
.af-summary-list {
  margin: 0;
  display: grid;
  gap: 0;
}
.af-summary-row {
  display: grid;
  grid-template-columns: minmax(72px, 32%) 1fr;
  gap: 10px;
  min-height: 42px;
  align-items: center;
  border-top: 1px solid var(--af-color-line);
}
.af-summary-row:first-child {
  border-top: 0;
}
.af-summary-row dt {
  color: var(--af-color-muted);
  font-size: 12px;
}
.af-summary-row dd {
  margin: 0;
  color: var(--af-color-text);
  font-size: 13px;
  font-weight: 650;
  text-align: right;
  overflow-wrap: anywhere;
}
.af-empty-text {
  margin: 0;
  color: var(--af-color-muted);
  font-size: 12px;
}
```

- [ ] **Step 7: Run component tests**

Run:

```powershell
npm.cmd test -- src/features/forms/components/formFlowComponents.test.tsx
```

Expected: pass.

- [ ] **Step 8: Commit Task 2**

```powershell
git add mobile/src/features/forms/components/FormStepHeader.tsx mobile/src/features/forms/components/FormStepNavigator.tsx mobile/src/features/forms/components/ConfirmSummaryList.tsx mobile/src/features/forms/components/formFlowComponents.test.tsx mobile/src/styles/global.css
git commit -m "功能(移动端): 增加填报步骤组件"
```

---

### Task 3: Step-By-Step FormFillPage

**Files:**
- Modify: `mobile/src/features/forms/FormFillPage.tsx`
- Modify: `mobile/src/features/forms/FormFillPage.test.tsx`

**Interfaces:**
- Consumes:
  - `buildFormStepGroups(schema, values)`
  - `FormStepHeader`
  - `FormStepNavigator`
  - `DynamicFormRenderer`
- Produces:
  - `FormFillPage` renders only current step nodes
  - per-step validation before step advance
  - full-schema validation before route advance

- [ ] **Step 1: Replace test fixture with grouped schema**

In `mobile/src/features/forms/FormFillPage.test.tsx`, change `FORM_RESPONSE.schema` to:

```ts
schema: [
  {
    id: 'time',
    type: 'span_layout',
    label: '请假时间',
    children: [
      { id: 'start', type: 'date', label: '开始时间', props: { required: true } },
      { id: 'end', type: 'date', label: '结束时间', props: { required: true } },
    ],
  },
  {
    id: 'reason-group',
    type: 'span_layout',
    label: '请假事由',
    children: [
      { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
    ],
  },
],
```

- [ ] **Step 2: Write failing tests for step rendering and validation**

Append tests to `describe('FormFillPage', ...)`:

```tsx
it('renders one form group at a time and advances after current step is valid', async () => {
  renderForm();

  expect(await screen.findByRole('heading', { name: '请假时间' })).toBeInTheDocument();
  expect(screen.getByText('第 1 步 / 共 2 步')).toBeInTheDocument();
  expect(screen.queryByLabelText('请假事由')).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: '下一步' }));

  expect(await screen.findByText('请填写开始时间')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('开始时间'), '2026-07-30');
  await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
  await userEvent.click(screen.getByRole('button', { name: '下一步' }));

  expect(await screen.findByRole('heading', { name: '请假事由' })).toBeInTheDocument();
  expect(screen.getByText('第 2 步 / 共 2 步')).toBeInTheDocument();
});

it('jumps back to the first step with errors during final validation', async () => {
  renderForm();

  await userEvent.type(await screen.findByLabelText('开始时间'), '2026-07-30');
  await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
  await userEvent.click(screen.getByRole('button', { name: '下一步' }));
  await userEvent.click(screen.getByRole('button', { name: '下一步' }));

  expect(await screen.findByText('请填写请假事由')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '请假事由' })).toBeInTheDocument();
});
```

- [ ] **Step 3: Run failing page tests**

Run:

```powershell
npm.cmd test -- src/features/forms/FormFillPage.test.tsx
```

Expected: fail because `FormFillPage` still renders all fields at once.

- [ ] **Step 4: Add step state and derived groups**

In `FormFillPage.tsx`, add imports:

```ts
import { FormStepHeader } from './components/FormStepHeader';
import { FormStepNavigator } from './components/FormStepNavigator';
import { buildFormStepGroups } from './schema/stepGroups';
```

Add state near existing `useState` calls:

```ts
const [currentStepIndex, setCurrentStepIndex] = useState(0);
const [completedStepIds, setCompletedStepIds] = useState<Set<string>>(() => new Set());
```

After `formSchema`:

```ts
const stepGroups = buildFormStepGroups(formSchema, values);
const currentStep = stepGroups[Math.min(currentStepIndex, Math.max(stepGroups.length - 1, 0))];
const currentStepErrors = currentStep ? pickErrors(errors, currentStep.fieldIds) : {};
const stepErrorCounts = errorCountsByStep(stepGroups, errors);
```

Add helpers at bottom:

```ts
function pickErrors(errors: FieldValidationErrors, fieldIds: string[]): FieldValidationErrors {
  return fieldIds.reduce<FieldValidationErrors>((next, fieldId) => {
    if (errors[fieldId]) next[fieldId] = errors[fieldId];
    return next;
  }, {});
}

function errorCountsByStep(
  groups: Array<{ id: string; fieldIds: string[] }>,
  errors: FieldValidationErrors,
): Record<string, number> {
  return groups.reduce<Record<string, number>>((next, group) => {
    const count = group.fieldIds.filter((fieldId) => Boolean(errors[fieldId])).length;
    if (count > 0) next[group.id] = count;
    return next;
  }, {});
}
```

- [ ] **Step 5: Render current step only**

Replace the existing `af-form-head` and `af-card--form` block with:

```tsx
<FormStepHeader
  title={currentStep?.title ?? title}
  description={currentStep?.description ?? description}
  currentIndex={currentStepIndex}
  total={stepGroups.length}
  completedCount={completedStepIds.size}
  autosaveLabel={status || undefined}
/>
<FormStepNavigator
  groups={stepGroups}
  currentIndex={currentStepIndex}
  completedStepIds={completedStepIds}
  errorCounts={stepErrorCounts}
  onSelect={setCurrentStepIndex}
/>
<section className="af-card--form">
  <DynamicFormRenderer
    schema={currentStep?.nodes ?? []}
    values={values}
    mode={draftQuery.data?.readOnly ? 'readonly' : 'fill'}
    errors={currentStepErrors}
    onValueChange={(fieldId, value) => {
      setValues((current) => ({ ...current, [fieldId]: value }));
      setErrors((current) => {
        const next = { ...current };
        delete next[fieldId];
        return next;
      });
      setStatus('');
    }}
  />
</section>
```

- [ ] **Step 6: Change `goNext` to validate current step first**

Replace `goNext()` with:

```ts
function goNext() {
  const currentErrors = validateSchemaValues(currentStep?.nodes ?? [], values);
  if (Object.keys(currentErrors).length > 0) {
    setErrors((existing) => ({ ...existing, ...currentErrors }));
    showToast({ icon: 'fail', content: '请先完善当前步骤' });
    scrollToFirstError(currentErrors);
    return;
  }

  if (currentStep) {
    setCompletedStepIds((existing) => new Set(existing).add(currentStep.id));
  }

  if (currentStepIndex < stepGroups.length - 1) {
    setCurrentStepIndex((index) => index + 1);
    return;
  }

  const nextErrors = validateSchemaValues(formSchema, values);
  setErrors(nextErrors);
  if (Object.keys(nextErrors).length > 0) {
    const firstErrorStepIndex = stepGroups.findIndex((group) =>
      group.fieldIds.some((fieldId) => Boolean(nextErrors[fieldId])),
    );
    if (firstErrorStepIndex >= 0) {
      setCurrentStepIndex(firstErrorStepIndex);
    }
    showToast({ icon: 'fail', content: '请完善必填或格式错误字段' });
    scrollToFirstError(nextErrors);
    return;
  }

  const submitValues = collectVisibleValues(formSchema, values);
  recoveryWriterRef.current?.flush();
  beginSubmitFlow({ formCode: code, draftId, values: submitValues });
  const nextPath =
    findSelfSelectRules(process).length > 0
      ? `/forms/${encodeURIComponent(code)}/self-select`
      : `/forms/${encodeURIComponent(code)}/confirm`;
  setSubmitNavigationAllowed(true);
  setPendingSubmitPath(nextPath);
}
```

- [ ] **Step 7: Run focused page tests**

Run:

```powershell
npm.cmd test -- src/features/forms/FormFillPage.test.tsx
```

Expected: pass.

- [ ] **Step 8: Run submit-flow regression tests**

Run:

```powershell
npm.cmd test -- src/features/forms/submit-flow.test.tsx
```

Expected: pass. Update any one-screen form-flow test in the same commit so it fills the first visible step, clicks `下一步`, fills the second visible step, then asserts confirm or self-select navigation.

- [ ] **Step 9: Commit Task 3**

```powershell
git add mobile/src/features/forms/FormFillPage.tsx mobile/src/features/forms/FormFillPage.test.tsx mobile/src/features/forms/submit-flow.test.tsx
git commit -m "功能(移动端): 表单填写改为分步推进"
```

---

### Task 4: Comfort Field Styling

**Files:**
- Modify: `mobile/src/styles/tokens.css`
- Modify: `mobile/src/styles/global.css`
- Modify: `mobile/src/features/forms/fields/fieldShared.tsx`
- Modify: `mobile/src/features/forms/fields/SelectField.tsx`
- Modify: `mobile/src/features/forms/fields/RadioField.tsx`
- Modify: `mobile/src/features/forms/fields/MultiSelectField.tsx`
- Modify: `mobile/src/features/forms/fields/CheckboxField.tsx`
- Modify: `mobile/src/features/forms/fields/fields.test.tsx`

**Interfaces:**
- Consumes: `FieldShell`, `fieldOptions`, field components.
- Produces: consistent blue/green tokens and button-like option controls.

- [ ] **Step 1: Write failing tests for option fields**

In `mobile/src/features/forms/fields/fields.test.tsx`, add:

```tsx
it('renders radio options as large touch choices', async () => {
  const onValueChange = vi.fn();
  render(
    <RadioField
      {...baseProps({
        id: 'leaveType',
        type: 'radio',
        label: '请假类型',
        props: { options: [{ label: '年假', value: 'annual' }, { label: '调休', value: 'adjust' }] },
      }, 'annual', onValueChange)}
    />,
  );

  const annual = screen.getByRole('radio', { name: '年假' });
  const adjust = screen.getByRole('radio', { name: '调休' });

  expect(annual).toHaveClass('af-choice-tile');
  expect(annual).toHaveAttribute('aria-checked', 'true');

  await userEvent.click(adjust);

  expect(onValueChange).toHaveBeenCalledWith('leaveType', 'adjust');
});

it('renders multi-select options as large touch choices', async () => {
  const onValueChange = vi.fn();
  render(
    <MultiSelectField
      {...baseProps({
        id: 'supplies',
        type: 'multi_select',
        label: '用品',
        props: { options: [{ label: '纸张', value: 'paper' }, { label: '笔', value: 'pen' }] },
      }, ['paper'], onValueChange)}
    />,
  );

  const pen = screen.getByRole('checkbox', { name: '笔' });

  expect(pen).toHaveClass('af-choice-tile');

  await userEvent.click(pen);

  expect(onValueChange).toHaveBeenCalledWith('supplies', ['paper', 'pen']);
});
```

- [ ] **Step 2: Run failing field tests**

Run:

```powershell
npm.cmd test -- src/features/forms/fields/fields.test.tsx
```

Expected: fail until option controls use `af-choice-tile`.

- [ ] **Step 3: Update tokens**

In `mobile/src/styles/tokens.css`, set these exact values in `:root`:

```css
--af-color-primary: #1769e0;
--af-color-success: #0f8a5f;
--af-color-success-soft: #e7f6ef;
--af-color-danger: #b42318;
--af-color-danger-soft: #fff1f0;
--af-color-text: #102033;
--af-color-text-secondary: #4d5c6d;
--af-color-muted: #65758a;
--af-color-bg: #f5f8fc;
--af-color-surface: #ffffff;
--af-color-border: #dfe8f4;
--af-color-line: #e9eff6;
--af-color-primary-soft: #eaf2ff;
--af-radius-control: 12px;
```

- [ ] **Step 4: Add comfort field CSS**

Update field styles in `mobile/src/styles/global.css` so `.af-field`, `.af-field__label`, inputs, textareas, selects, and `.af-choice-tile` use this pattern:

```css
.af-field {
  display: grid;
  gap: 7px;
  padding: 12px 0;
  border-top: 1px solid var(--af-color-line);
}
.af-field:first-child {
  border-top: 0;
  padding-top: 0;
}
.af-field__head {
  display: flex;
  align-items: baseline;
  gap: 4px;
  min-width: 0;
}
.af-field__label {
  color: var(--af-color-muted);
  font-size: 12px;
  font-weight: 700;
}
.af-field input,
.af-field textarea,
.af-field select {
  min-height: 44px;
  border: 1px solid var(--af-color-border);
  border-radius: var(--af-radius-control);
  background: #fbfdff;
  color: var(--af-color-text);
  font-size: 14px;
}
.af-choice-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.af-choice-tile {
  min-height: 44px;
  border: 1px solid var(--af-color-border);
  border-radius: var(--af-radius-control);
  background: #fbfdff;
  color: var(--af-color-text);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
}
.af-choice-tile[aria-checked="true"],
.af-choice-tile--selected {
  border-color: var(--af-color-primary);
  background: var(--af-color-primary-soft);
  color: var(--af-color-primary);
}
```

- [ ] **Step 5: Update radio/select-like fields to use choice tiles**

In `RadioField.tsx`, render options as buttons or labels with `role="radio"`:

```tsx
<div className="af-choice-grid" role="radiogroup" aria-label={label}>
  {fieldOptions(node).map((option) => {
    const selected = option.value === value;
    return (
      <button
        key={option.value}
        type="button"
        role="radio"
        aria-checked={selected}
        className="af-choice-tile"
        disabled={option.disabled}
        onClick={() => onValueChange(node.id, option.value)}
      >
        {option.label}
      </button>
    );
  })}
</div>
```

Apply the same `af-choice-grid` and `af-choice-tile` class approach to `SelectField.tsx`, `MultiSelectField.tsx`, and `CheckboxField.tsx`. For multi-select and checkbox, use `role="checkbox"` and `aria-checked`.

- [ ] **Step 6: Run focused field tests**

Run:

```powershell
npm.cmd test -- src/features/forms/fields/fields.test.tsx
```

Expected: pass.

- [ ] **Step 7: Run advanced field regressions**

Run:

```powershell
npm.cmd test -- src/features/forms/fields/advanced-fields.test.tsx
```

Expected: pass.

- [ ] **Step 8: Commit Task 4**

```powershell
git add mobile/src/styles/tokens.css mobile/src/styles/global.css mobile/src/features/forms/fields/fieldShared.tsx mobile/src/features/forms/fields/SelectField.tsx mobile/src/features/forms/fields/RadioField.tsx mobile/src/features/forms/fields/MultiSelectField.tsx mobile/src/features/forms/fields/CheckboxField.tsx mobile/src/features/forms/fields/fields.test.tsx
git commit -m "修复(移动端): 优化表单字段填报手感"
```

---

### Task 5: Self-Select Approval UX

**Files:**
- Modify: `mobile/src/features/forms/SelfSelectPage.tsx`
- Modify: `mobile/src/features/forms/submit-flow.test.tsx`
- Modify: `mobile/src/styles/global.css`

**Interfaces:**
- Consumes: `findSelfSelectRules`, `useSubmitFlowStore`, existing user search APIs.
- Produces: self-select page with one node card per SELF_SELECT rule and blue/green states.

- [ ] **Step 1: Add failing self-select UI expectations**

In `mobile/src/features/forms/submit-flow.test.tsx`, add assertions in the self-select test path:

```tsx
expect(await screen.findByRole('heading', { name: '选择审批人' })).toBeInTheDocument();
expect(screen.getByText('请选择本次流程需要你指定的审批人')).toBeInTheDocument();
expect(screen.getByRole('button', { name: /确认审批人/ })).toHaveClass('af-btn');
```

- [ ] **Step 2: Run failing self-select flow test**

Run:

```powershell
npm.cmd test -- src/features/forms/submit-flow.test.tsx
```

Expected: fail until page copy and class names are aligned.

- [ ] **Step 3: Update `SelfSelectPage` layout**

Change the page body to this structure while preserving existing selection state:

```tsx
<AppPage
  title="选择审批人"
  action={
    <button type="button" className="af-link-button" onClick={confirmSelection}>
      确定
    </button>
  }
>
  <div className="af-stack">
    <section className="af-form-step-head">
      <div className="af-form-step-head__title">
        <h2>选择审批人</h2>
        <span className="af-tag">流程必填</span>
      </div>
      <p>请选择本次流程需要你指定的审批人。</p>
    </section>
    {rules.map((rule) => (
      <section key={rule.nodeId} className="af-card af-self-select-card">
        <div className="af-card__title">
          <span>{rule.name}</span>
          <small>{rule.multiple ? '可多选' : '单选'}</small>
        </div>
        {/* keep existing candidate/search rendering here, but use af-choice-tile for candidate rows */}
      </section>
    ))}
  </div>
  <div className="af-bottom-bar">
    <button type="button" className="af-btn af-btn--block" onClick={confirmSelection}>
      确认审批人
    </button>
  </div>
</AppPage>
```

- [ ] **Step 4: Add self-select card CSS**

Append:

```css
.af-self-select-card {
  display: grid;
  gap: 10px;
}
.af-self-select-card .af-choice-tile {
  justify-content: flex-start;
  text-align: left;
}
```

- [ ] **Step 5: Run self-select tests**

Run:

```powershell
npm.cmd test -- src/features/forms/submit-flow.test.tsx
```

Expected: pass.

- [ ] **Step 6: Commit Task 5**

```powershell
git add mobile/src/features/forms/SelfSelectPage.tsx mobile/src/features/forms/submit-flow.test.tsx mobile/src/styles/global.css
git commit -m "修复(移动端): 优化自选审批人体验"
```

---

### Task 6: Compact Confirm And Success Pages

**Files:**
- Modify: `mobile/src/features/forms/SubmitConfirmPage.tsx`
- Modify: `mobile/src/features/forms/SubmitSuccessPage.tsx`
- Modify: `mobile/src/features/forms/submit-flow.test.tsx`
- Modify: `mobile/src/styles/global.css`

**Interfaces:**
- Consumes: `ConfirmSummaryList`, `selectedAssigneeNames`, submit mutations.
- Produces: compact confirm page and result page aligned to first-stage UX.

- [ ] **Step 1: Write failing confirm summary assertions**

In `mobile/src/features/forms/submit-flow.test.tsx`, add after navigating to confirm:

```tsx
expect(await screen.findByRole('heading', { name: '确认提交' })).toBeInTheDocument();
expect(screen.getByText('申请摘要')).toBeInTheDocument();
expect(screen.getByRole('button', { name: '返回修改' })).toBeInTheDocument();
expect(screen.getByRole('button', { name: '确认提交' })).toHaveClass('af-btn');
```

- [ ] **Step 2: Run failing confirm tests**

Run:

```powershell
npm.cmd test -- src/features/forms/submit-flow.test.tsx
```

Expected: fail until confirm layout is changed.

- [ ] **Step 3: Replace local summary code with `ConfirmSummaryList`**

In `SubmitConfirmPage.tsx`, import:

```ts
import { ConfirmSummaryList } from './components/ConfirmSummaryList';
```

Remove local `summarizeRows` and `summarizeNode` helper functions from the bottom of the file. Replace the application summary section with:

```tsx
<section className="af-card">
  <div className="af-card__title">
    <span>申请摘要</span>
    <button
      type="button"
      className="af-link-button"
      onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}
    >
      返回修改
    </button>
  </div>
  <ConfirmSummaryList
    schema={formSchemaWithoutSelfSelectRules(formQuery.data?.schema ?? [])}
    values={flow.values}
  />
</section>
```

- [ ] **Step 4: Align confirm bottom actions**

Replace confirm bottom bar with:

```tsx
<div className="af-bottom-bar af-bottom-bar--split">
  <button
    type="button"
    className="af-btn af-btn--ghost"
    onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}
  >
    返回修改
  </button>
  <button
    type="button"
    className="af-btn"
    disabled={submitMutation.isPending}
    onClick={() => submitMutation.mutate()}
  >
    {error ? '重试提交' : submitMutation.isPending ? '提交中...' : '确认提交'}
  </button>
</div>
```

- [ ] **Step 5: Update success page copy and layout**

In `SubmitSuccessPage.tsx`, keep existing route params and navigation, but render:

```tsx
<AppPage title="提交成功" variant="blank" back={false}>
  <section className="af-success-page">
    <div className="af-success-mark" aria-hidden="true">✓</div>
    <h1>提交成功</h1>
    <p>{mode === 'direct' ? '表单已提交完成。' : '申请已进入审批流程，请等待审批人处理。'}</p>
    <div className="af-success-page__buttons">
      <button type="button" className="af-btn af-btn--ghost" onClick={() => navigate('/workbench')}>
        返回工作台
      </button>
      <button type="button" className="af-btn" onClick={() => navigate(`/processes/${instanceId}`)}>
        查看进度
      </button>
    </div>
  </section>
</AppPage>
```

`AppPage` supports `variant="blank"` and `back={false}`; use those exact props for this page.

- [ ] **Step 6: Add bottom split CSS if absent**

Append:

```css
.af-bottom-bar--split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.af-success-mark {
  width: 56px;
  height: 56px;
  border-radius: var(--af-radius-pill);
  display: grid;
  place-items: center;
  background: var(--af-color-success-soft);
  color: var(--af-color-success);
  font-size: 30px;
  font-weight: 900;
}
```

- [ ] **Step 7: Run submit flow tests**

Run:

```powershell
npm.cmd test -- src/features/forms/submit-flow.test.tsx
```

Expected: pass.

- [ ] **Step 8: Commit Task 6**

```powershell
git add mobile/src/features/forms/SubmitConfirmPage.tsx mobile/src/features/forms/SubmitSuccessPage.tsx mobile/src/features/forms/submit-flow.test.tsx mobile/src/styles/global.css
git commit -m "修复(移动端): 优化提交确认与成功页"
```

---

### Task 7: Process Detail Form Summary

**Files:**
- Modify: `mobile/src/features/processes/ProcessDetailPage.tsx`
- Modify: `mobile/src/features/processes/ProcessDetailPage.test.tsx`
- Modify: `mobile/src/styles/global.css`

**Interfaces:**
- Consumes: `ConfirmSummaryList`, instance detail API data.
- Produces: process detail page using compact summary and blue/green status semantics.

- [ ] **Step 1: Write failing process detail summary assertion**

In `mobile/src/features/processes/ProcessDetailPage.test.tsx`, add:

```tsx
expect(await screen.findByText('申请摘要')).toBeInTheDocument();
expect(screen.getByTestId('summary-item')).toHaveTextContent('显示器');
```

The current fixture field id is `item`, so the summary row test id is `summary-item`.

- [ ] **Step 2: Run failing process detail test**

Run:

```powershell
npm.cmd test -- src/features/processes/ProcessDetailPage.test.tsx
```

Expected: fail because process detail still uses readonly `DynamicFormRenderer`.

- [ ] **Step 3: Replace readonly form renderer with summary list**

In `ProcessDetailPage.tsx`, replace:

```ts
import { DynamicFormRenderer } from "../forms/components/DynamicFormRenderer";
```

with:

```ts
import { ConfirmSummaryList } from '../forms/components/ConfirmSummaryList';
```

Replace the `申请摘要` section body with:

```tsx
<ConfirmSummaryList schema={schema} values={values} />
```

- [ ] **Step 4: Align status tag classes**

Use status-specific class names:

```tsx
<span className={`af-tag af-tag--instance-${instance.status.toLowerCase()}`}>
  {instanceStatusLabel(instance.status)}
</span>
```

Add CSS:

```css
.af-tag--instance-running {
  background: var(--af-color-primary-soft);
  color: var(--af-color-primary);
}
.af-tag--instance-approved {
  background: var(--af-color-success-soft);
  color: var(--af-color-success);
}
.af-tag--instance-rejected,
.af-tag--instance-withdrawn {
  background: var(--af-color-danger-soft);
  color: var(--af-color-danger);
}
```

- [ ] **Step 5: Run process detail tests**

Run:

```powershell
npm.cmd test -- src/features/processes/ProcessDetailPage.test.tsx
```

Expected: pass.

- [ ] **Step 6: Commit Task 7**

```powershell
git add mobile/src/features/processes/ProcessDetailPage.tsx mobile/src/features/processes/ProcessDetailPage.test.tsx mobile/src/styles/global.css
git commit -m "修复(移动端): 优化流程进度申请摘要"
```

---

### Task 8: Mobile Lint Blockers And Final Verification

**Files:**
- Modify: `mobile/src/features/workbench/AppCatalogPage.tsx`
- Modify: `mobile/src/features/workbench/WorkbenchPage.tsx`
- Test: `mobile/src/features/workbench/AppCatalogPage.test.tsx`
- Test: `mobile/src/features/workbench/WorkbenchPage.test.tsx`

**Interfaces:**
- Consumes: existing workbench/catalog components.
- Produces: mobile lint unblocked and first-stage verification evidence.

- [ ] **Step 1: Fix semantic search lint error**

In `AppCatalogPage.tsx`, replace:

```tsx
<div role="search" className="af-search">{"\u2315 搜索应用"}</div>
```

with:

```tsx
<search className="af-search">{"\u2315 搜索应用"}</search>
```

- [ ] **Step 2: Fix unsupported ARIA lint error**

In `WorkbenchPage.tsx`, replace:

```tsx
<div className="af-workbench__metrics" aria-label="快捷统计">
```

with:

```tsx
<section className="af-workbench__metrics" aria-label="快捷统计">
```

and replace the matching closing `</div>` with `</section>`.

- [ ] **Step 3: Run lint**

Run:

```powershell
npm.cmd run lint
```

Expected: exit 0. Warnings about `document.cookie`, Biome config deprecation, or `Object.hasOwn` may remain only if Biome reports them as warnings; lint errors must be 0.

- [ ] **Step 4: Run unit tests**

Run:

```powershell
npm.cmd test
```

Expected: all mobile Vitest tests pass.

- [ ] **Step 5: Run production build**

Run:

```powershell
npm.cmd run build
```

Expected: exit 0 and `dist/` generated.

- [ ] **Step 6: Run bundle budget**

Run:

```powershell
npm.cmd run check:bundle
```

Expected: entry gzip total stays under 250 KiB.

- [ ] **Step 7: Run targeted e2e visual flow if Playwright browsers are installed**

Run:

```powershell
npm.cmd run test:e2e -- full-approval-flow.spec.ts
```

Expected: pass. When the command reports missing Playwright browsers, run `npx playwright install chromium` from `mobile/`, then rerun this exact command.

- [ ] **Step 8: Commit Task 8**

```powershell
git add mobile/src/features/workbench/AppCatalogPage.tsx mobile/src/features/workbench/WorkbenchPage.tsx
git commit -m "修复(移动端): 清理填报体验重做门禁"
```

---

## Completion Gate

Before claiming the implementation complete, run from `mobile/`:

```powershell
npm.cmd run lint
npm.cmd test
npm.cmd run build
npm.cmd run check:bundle
```

Expected:

- `npm.cmd run lint`: exit 0.
- `npm.cmd test`: all Vitest tests pass.
- `npm.cmd run build`: exit 0.
- `npm.cmd run check:bundle`: entry gzip total under 250 KiB.

Run the focused e2e after unit/build gates:

```powershell
npm.cmd run test:e2e -- full-approval-flow.spec.ts
```

---

## Self-Review Notes

Spec coverage:

- Form filling step-by-step: Task 1 and Task 3.
- Grouping by schema structure: Task 1.
- Blue/green design system: Task 4.
- Comfortable field controls: Task 4.
- Self-select approval UX: Task 5.
- Compact confirm page and success page: Task 6.
- Process progress summary: Task 7.
- Lint and verification gates: Task 8.
- Second-stage visual consistency is intentionally outside this plan and remains in the approved spec as the next separate plan.

Type consistency:

- `FormStepGroup`, `buildFormStepGroups`, and `fieldIdsInStep` are introduced in Task 1 and consumed by Tasks 2 and 3.
- `ConfirmSummaryList` is introduced in Task 2 and consumed by Tasks 6 and 7.
- `FormStepHeader` and `FormStepNavigator` are introduced in Task 2 and consumed by Task 3.

Execution rule:

- Implement tasks sequentially. Each task has its own failing test, passing test, and commit gate.
