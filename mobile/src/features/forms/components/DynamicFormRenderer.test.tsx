import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DynamicFormRenderer } from './DynamicFormRenderer';
import type { MobileSchemaNode } from '../schema/types';

describe('DynamicFormRenderer', () => {
  it('renders unsupported fields explicitly', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'legacy', type: 'legacy_field', label: '旧字段' },
    ];

    render(<DynamicFormRenderer mode="fill" schema={schema} values={{}} onValueChange={vi.fn()} />);

    expect(screen.getByText('旧字段')).toBeInTheDocument();
    expect(screen.getByText('不支持的字段类型: legacy_field')).toBeInTheDocument();
  });

  it('recursively renders layout children against the full value object', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'layout',
        type: 'span_layout',
        children: [{ id: 'reason', type: 'text', label: '请假事由' }],
      },
    ];
    const onValueChange = vi.fn();

    render(
      <DynamicFormRenderer
        mode="fill"
        schema={schema}
        values={{ reason: '回家探亲' }}
        onValueChange={onValueChange}
      />,
    );

    const input = screen.getByLabelText('请假事由');
    expect(input).toHaveValue('回家探亲');

    fireEvent.change(input, { target: { value: '参加会议' } });

    expect(onValueChange).toHaveBeenCalledWith('reason', '参加会议');
  });

  it('renders readonly leaf summaries without editable controls', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'reason', type: 'text', label: '请假事由' },
    ];

    render(
      <DynamicFormRenderer
        mode="readonly"
        schema={schema}
        values={{ reason: '回家探亲' }}
        onValueChange={vi.fn()}
      />,
    );

    expect(screen.getByText('回家探亲')).toBeInTheDocument();
    expect(screen.queryByLabelText('请假事由')).not.toBeInTheDocument();
  });
});
