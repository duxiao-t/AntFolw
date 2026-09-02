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

  it('renders span-layout child validation errors on the child field', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'layout',
        type: 'span_layout',
        children: [{ id: 'reason', type: 'text', label: '请假事由', props: { required: true } }],
      },
    ];

    render(
      <DynamicFormRenderer
        mode="fill"
        schema={schema}
        values={{ reason: '' }}
        errors={{ reason: '请填写请假事由' }}
        onValueChange={vi.fn()}
      />,
    );

    expect(screen.getByText('请填写请假事由')).toBeInTheDocument();
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

  it('applies per-field mode override and hides hidden fields', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'editable', type: 'text', label: '可编辑' },
      { id: 'readonly', type: 'text', label: '只读' },
      { id: 'secret', type: 'text', label: '隐藏' },
    ];

    render(
      <DynamicFormRenderer
        mode="readonly"
        modeOverride={{ editable: 'fill', secret: 'hidden' }}
        schema={schema}
        values={{ editable: 'a', readonly: 'b', secret: 'c' }}
        onValueChange={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('可编辑')).toHaveValue('a');
    expect(screen.queryByLabelText('只读')).not.toBeInTheDocument();
    expect(screen.getByText('b')).toBeInTheDocument();
    expect(screen.queryByText('隐藏')).not.toBeInTheDocument();
    expect(screen.queryByText('c')).not.toBeInTheDocument();
  });

  it('applies per-field modes inside table rows', () => {
    const schema: MobileSchemaNode[] = [{
      id: 'lines',
      type: 'table_list',
      label: '采购明细',
      children: [
        { id: 'item', type: 'text', label: '物品' },
        { id: 'price', type: 'number', label: '核定价' },
        { id: 'secret', type: 'text', label: '内部备注' },
      ],
    }];

    render(
      <DynamicFormRenderer
        mode="fill"
        modeOverride={{ price: 'readonly', secret: 'hidden' }}
        schema={schema}
        values={{ lines: [{ item: '显示器', price: 2600, secret: '内部信息' }] }}
        onValueChange={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '展开 第1行' }));
    expect(screen.getByLabelText('物品')).toHaveValue('显示器');
    expect(screen.getByText('2600')).toBeInTheDocument();
    expect(screen.queryByLabelText('核定价')).not.toBeInTheDocument();
    expect(screen.queryByText('内部备注')).not.toBeInTheDocument();
    expect(screen.queryByText('内部信息')).not.toBeInTheDocument();
  });
});
