import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { DateField } from './DateField';
import { DateRangeField } from './DateRangeField';
import { DescriptionField } from './DescriptionField';
import { MoneyField } from './MoneyField';
import { MultiSelectField } from './MultiSelectField';
import { NumberField } from './NumberField';
import { SelectField } from './SelectField';
import { TextField } from './TextField';
import { TextareaField } from './TextareaField';

function baseProps(node: MobileSchemaNode, value: unknown, onValueChange = vi.fn()) {
  return {
    node,
    value,
    values: { [node.id]: value },
    mode: 'fill' as const,
    error: undefined,
    onValueChange,
  };
}

describe('leaf mobile fields', () => {
  it('renders and validates text field', () => {
    const onValueChange = vi.fn();
    render(<TextField {...baseProps({ id: 'reason', type: 'text', label: '请假事由', props: { required: true } }, '', onValueChange)} />);

    expect(screen.getByText('请填写请假事由')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('请假事由'), { target: { value: '回家探亲' } });
    expect(onValueChange).toHaveBeenCalledWith('reason', '回家探亲');

    render(
      <TextField
        {...baseProps(
          { id: 'reason', type: 'text', label: '请假事由' },
          '回家探亲',
        )}
        mode="readonly"
      />,
    );
    expect(screen.getByText('回家探亲')).toBeInTheDocument();
  });

  it('renders and validates textarea field', () => {
    const onValueChange = vi.fn();
    render(<TextareaField {...baseProps({ id: 'detail', type: 'textarea', label: '说明', props: { required: true } }, '', onValueChange)} />);

    expect(screen.getByText('请填写说明')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('说明'), { target: { value: '补充说明' } });
    expect(onValueChange).toHaveBeenCalledWith('detail', '补充说明');
  });

  it('renders and validates number field', () => {
    const onValueChange = vi.fn();
    render(<NumberField {...baseProps({ id: 'days', type: 'number', label: '天数', props: { required: true } }, '', onValueChange)} />);

    expect(screen.getByText('请填写天数')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('天数'), { target: { value: '3' } });
    expect(onValueChange).toHaveBeenCalledWith('days', '3');
  });

  it('renders and validates money field', () => {
    const onValueChange = vi.fn();
    render(<MoneyField {...baseProps({ id: 'amount', type: 'money', label: '金额', props: { required: true } }, '', onValueChange)} />);

    expect(screen.getByText('请填写金额')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('金额'), { target: { value: '128.50' } });
    expect(onValueChange).toHaveBeenCalledWith('amount', '128.50');
  });

  it('renders and validates date field', () => {
    const onValueChange = vi.fn();
    render(<DateField {...baseProps({ id: 'applyDate', type: 'date', label: '申请日期', props: { required: true } }, '', onValueChange)} />);

    expect(screen.getByText('请填写申请日期')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('申请日期'), { target: { value: '2026-07-20' } });
    expect(onValueChange).toHaveBeenCalledWith('applyDate', '2026-07-20');
  });

  it('renders and validates date range field', () => {
    const onValueChange = vi.fn();
    render(
      <DateRangeField
        {...baseProps(
          { id: 'travel', type: 'date_range', label: '出差时间', props: { required: true } },
          ['', ''],
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写出差时间')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('出差开始'), { target: { value: '2026-07-20' } });
    fireEvent.change(screen.getByLabelText('出差结束'), { target: { value: '2026-07-22' } });
    expect(onValueChange).toHaveBeenCalledWith('travel', ['2026-07-20', '2026-07-22']);
  });

  it('renders and validates select field', () => {
    const onValueChange = vi.fn();
    const { unmount } = render(
      <SelectField
        {...baseProps(
          {
            id: 'dept',
            type: 'select',
            label: '部门',
            props: {
              required: true,
              options: [
                { label: '研发部', value: 'dev' },
                { label: '财务部', value: 'finance' },
              ],
            },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写部门')).toBeInTheDocument();
    fireEvent.click(screen.getByText('研发部'));
    expect(onValueChange).toHaveBeenCalledWith('dept', 'dev');
    unmount();
    render(
      <SelectField
        {...baseProps(
          {
            id: 'dept',
            type: 'select',
            label: '部门',
            props: {
              options: [
                { label: '研发部', value: 'dev' },
                { label: '财务部', value: 'finance' },
              ],
            },
          },
          'dev',
        )}
        mode="readonly"
      />,
    );
    expect(screen.getByText('研发部')).toBeInTheDocument();
  });

  it('renders and validates multi select field', () => {
    const onValueChange = vi.fn();
    render(
      <MultiSelectField
        {...baseProps(
          {
            id: 'cc',
            type: 'multi_select',
            label: '抄送人',
            props: {
              required: true,
              options: [
                { label: '张三', value: 'zhangsan' },
                { label: '李四', value: 'lisi' },
              ],
            },
          },
          [],
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写抄送人')).toBeInTheDocument();
    fireEvent.click(screen.getByText('张三'));
    fireEvent.click(screen.getByText('李四'));
    expect(onValueChange).toHaveBeenLastCalledWith('cc', ['zhangsan', 'lisi']);
  });

  it('renders description field as read-only content', () => {
    render(
      <DescriptionField
        {...baseProps({ id: 'desc', type: 'description', label: '说明', props: { text: '请核对后提交' } }, null)}
        mode="readonly"
      />,
    );

    expect(screen.getByText('请核对后提交')).toBeInTheDocument();
    expect(screen.queryByLabelText('说明')).not.toBeInTheDocument();
  });
});
