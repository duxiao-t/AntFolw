import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { ConfirmSummaryList } from './ConfirmSummaryList';

describe('form flow components', () => {
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
