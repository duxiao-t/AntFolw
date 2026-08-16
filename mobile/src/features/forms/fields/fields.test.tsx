import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { DateField } from './DateField';
import { DateRangeField } from './DateRangeField';
import { DescriptionField } from './DescriptionField';
import { MoneyField } from './MoneyField';
import { MultiSelectField } from './MultiSelectField';
import { NumberField } from './NumberField';
import { RadioField } from './RadioField';
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

  it('renders and validates select field', async () => {
    const user = userEvent.setup();
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
    await user.click(screen.getByRole('button', { name: '选择部门' }));
    await user.click(await screen.findByRole('option', { name: '研发部' }));
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

  it('renders and validates multi select field', async () => {
    const user = userEvent.setup();
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
    await user.click(screen.getByRole('button', { name: '选择抄送人' }));
    expect(screen.getByRole('dialog', { name: '选择抄送人' })).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: '张三' }));
    await user.click(screen.getByRole('checkbox', { name: '李四' }));
    await user.click(screen.getByRole('button', { name: '完成' }));
    expect(onValueChange).toHaveBeenLastCalledWith('cc', ['zhangsan', 'lisi']);
  });

  it('hides unavailable options and submits custom single-select text', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(
      <SelectField
        {...baseProps(
          {
            id: 'machine',
            type: 'select',
            label: '设备',
            props: {
              options: [
                { label: '隐藏设备', value: 'hidden', hidden: true },
                { label: '铁面', value: 'iron' },
                { label: '其他', value: '__antflow_other__', isOther: true },
              ],
            },
          },
          '',
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '选择设备' }));
    expect(screen.queryByRole('option', { name: '隐藏设备' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: '其他' }));
    const input = screen.getAllByLabelText('设备其他内容').at(-1) as HTMLInputElement;
    await user.type(input, '自定义设备');
    expect(onValueChange).toHaveBeenLastCalledWith('machine', '自定义设备');
  });

  it('submits standard and custom multi-select values together', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(
      <MultiSelectField
        {...baseProps(
          {
            id: 'machines',
            type: 'multi_select',
            label: '设备',
            props: {
              options: [
                { label: '铁面', value: 'iron' },
                { label: '其他', value: '__antflow_other__', isOther: true },
              ],
            },
          },
          [],
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '选择设备' }));
    await user.click(screen.getByRole('checkbox', { name: '铁面' }));
    await user.click(screen.getByRole('checkbox', { name: '其他' }));
    await user.click(screen.getByRole('button', { name: '完成' }));
    const input = screen.getAllByLabelText('设备其他内容').at(-1) as HTMLInputElement;
    await user.type(input, '定制机');
    expect(onValueChange).toHaveBeenLastCalledWith('machines', ['iron', '定制机']);
  });

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

  it('opens multi-select options in a full-screen picker', async () => {
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

    await userEvent.click(screen.getByRole('button', { name: '纸张' }));
    expect(screen.getByRole('dialog', { name: '选择用品' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: '笔' }));
    await userEvent.click(screen.getByRole('button', { name: '完成' }));

    expect(onValueChange).toHaveBeenCalledWith('supplies', ['paper', 'pen']);
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

describe('date field time support', () => {
  it('uses datetime-local input when format includes time', () => {
    render(
      <DateField
        {...baseProps(
          { id: 'at', type: 'date', label: '时间', props: { format: 'YYYY-MM-DD HH:mm' } },
          '2026-08-04 10:30',
        )}
      />,
    );
    const input = screen.getByLabelText('时间') as HTMLInputElement;
    expect(input.type).toBe('datetime-local');
    expect(input.value).toBe('2026-08-04T10:30');
  });

  it('uses date input and truncates stored time for plain date format', () => {
    render(
      <DateField
        {...baseProps(
          { id: 'd', type: 'date', label: '日期' },
          '2026-08-04',
        )}
      />,
    );
    const input = screen.getByLabelText('日期') as HTMLInputElement;
    expect(input.type).toBe('date');
    expect(input.value).toBe('2026-08-04');
  });
});
