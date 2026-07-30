import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { FormStepGroup } from '../schema/stepGroups';
import type { MobileSchemaNode } from '../schema/types';
import { ConfirmSummaryList } from './ConfirmSummaryList';
import { FormStepHeader } from './FormStepHeader';
import { FormNextStepHint, FormStepNavigator } from './FormStepNavigator';

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
        fieldCount={2}
        autosaveLabel="已自动保存"
      />,
    );

    expect(screen.getByRole('heading', { name: '请假时间' })).toBeInTheDocument();
    expect(screen.getByText('1 / 2')).toBeInTheDocument();
    expect(screen.getByText('本节 2 项，预计 40 秒')).toBeInTheDocument();
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
    expect(screen.getByRole('button', { name: '2. 请假事由，2 项需补充' })).toBeInTheDocument();
  });

  it('renders the next step hint as a separate card', () => {
    render(<FormNextStepHint groups={groups} currentIndex={0} errorCounts={{ b: 2 }} />);

    expect(screen.getByText('接下来：请假事由')).toBeInTheDocument();
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
