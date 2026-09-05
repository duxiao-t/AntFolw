import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { ChecklistField } from './ChecklistField';
import { DateField } from './DateField';
import { DateRangeField } from './DateRangeField';
import { DescriptionField } from './DescriptionField';
import { MoneyField } from './MoneyField';
import { MultiSelectField } from './MultiSelectField';
import { NumberField } from './NumberField';
import { RadioField } from './RadioField';
import { SelectField } from './SelectField';
import { TextareaField } from './TextareaField';
import { TextField } from './TextField';

function baseProps(
  node: MobileSchemaNode,
  value: unknown,
  onValueChange = vi.fn(),
) {
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
  it('shows checklist question descriptions only when enabled', () => {
    const node: MobileSchemaNode = {
      id: 'inspection',
      type: 'checklist',
      label: '检查项',
      props: {
        questionDescription: '这是题干',
        showDescription: true,
        items: [{ id: 'item-1', label: '检查项1', required: true }],
      },
    };
    const { rerender } = render(<ChecklistField {...baseProps(node, [])} />);

    expect(screen.getByText('这是题干')).toHaveClass('af-checklist__description');

    rerender(
      <ChecklistField
        {...baseProps({ ...node, props: { ...node.props, showDescription: false } }, [])}
      />,
    );
    expect(screen.queryByText('这是题干')).not.toBeInTheDocument();

    rerender(<ChecklistField {...baseProps(node, [])} mode="readonly" />);
    expect(screen.getByText('这是题干')).toBeInTheDocument();
  });

  it('uses configured checklist result labels and selected color', async () => {
    const onValueChange = vi.fn();
    const node: MobileSchemaNode = {
      id: 'inspection',
      type: 'checklist',
      label: '检查项',
      props: {
        items: [{ id: 'item-1', label: '设备外观', required: true }],
        results: [
          { id: 'ok', label: '合格', color: '#123456' },
          { id: 'bad', label: '需整改', color: '#D93025' },
        ],
      },
    };
    const { container } = render(
      <ChecklistField {...baseProps(node, [], onValueChange)} />,
    );

    await userEvent.click(screen.getByRole('button', { name: '合格' }));

    expect(onValueChange).toHaveBeenCalledWith(
      'inspection',
      [expect.objectContaining({ id: 'item-1', status: 'ok' })],
    );
    expect(screen.getByRole('button', { name: '合格，点击取消选择' })).toBeInTheDocument();
    expect(container.querySelector('.af-check__card--selected')).toHaveStyle(
      '--af-check-color: #123456',
    );
  });

  it('renders and validates text field', () => {
    const onValueChange = vi.fn();
    render(
      <TextField
        {...baseProps(
          {
            id: 'reason',
            type: 'text',
            label: '请假事由',
            props: { required: true },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写请假事由')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('请假事由'), {
      target: { value: '回家探亲' },
    });
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
    render(
      <TextareaField
        {...baseProps(
          {
            id: 'detail',
            type: 'textarea',
            label: '说明',
            props: { required: true },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写说明')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('说明'), {
      target: { value: '补充说明' },
    });
    expect(onValueChange).toHaveBeenCalledWith('detail', '补充说明');
  });

  it('renders and validates number field', () => {
    const onValueChange = vi.fn();
    render(
      <NumberField
        {...baseProps(
          {
            id: 'days',
            type: 'number',
            label: '天数',
            props: { required: true },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写天数')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('天数'), { target: { value: '3' } });
    expect(onValueChange).toHaveBeenCalledWith('days', '3');
  });

  it('renders and validates money field', () => {
    const onValueChange = vi.fn();
    render(
      <MoneyField
        {...baseProps(
          {
            id: 'amount',
            type: 'money',
            label: '金额',
            props: { required: true },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写金额')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('金额'), {
      target: { value: '128.50' },
    });
    expect(onValueChange).toHaveBeenCalledWith('amount', '128.50');
  });

  it('renders and validates date field', () => {
    const onValueChange = vi.fn();
    render(
      <DateField
        {...baseProps(
          {
            id: 'applyDate',
            type: 'date',
            label: '申请日期',
            props: { required: true },
          },
          '',
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写申请日期')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('申请日期'), {
      target: { value: '2026-07-20' },
    });
    expect(onValueChange).toHaveBeenCalledWith('applyDate', '2026-07-20');
  });

  it('renders and validates date range field', () => {
    const onValueChange = vi.fn();
    render(
      <DateRangeField
        {...baseProps(
          {
            id: 'travel',
            type: 'date_range',
            label: '出差时间',
            props: { required: true },
          },
          ['', ''],
          onValueChange,
        )}
      />,
    );

    expect(screen.getByText('请填写出差时间')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('出差开始'), {
      target: { value: '2026-07-20' },
    });
    fireEvent.change(screen.getByLabelText('出差结束'), {
      target: { value: '2026-07-22' },
    });
    expect(onValueChange).toHaveBeenCalledWith('travel', [
      '2026-07-20',
      '2026-07-22',
    ]);
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

  it.each([
    ['list', 'af-select-choices--list'],
    ['block_single', 'af-select-choices--block_single'],
    ['block_double', 'af-select-choices--block_double'],
  ])('renders %s select choices inline', async (displayStyle, className) => {
    const onValueChange = vi.fn();
    render(
      <SelectField
        {...baseProps(
          {
            id: 'dept',
            type: 'select',
            label: '部门',
            props: {
              displayStyle,
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

    expect(document.querySelector(`.${className}`)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: '部门：研发部' }));
    expect(onValueChange).toHaveBeenCalledWith('dept', 'dev');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
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
    expect(
      screen.getByRole('dialog', { name: '选择抄送人' }),
    ).toBeInTheDocument();
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
    expect(
      screen.queryByRole('option', { name: '隐藏设备' }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: '其他' }));
    const input = screen
      .getAllByLabelText('设备其他内容')
      .at(-1) as HTMLInputElement;
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
    const input = screen
      .getAllByLabelText('设备其他内容')
      .at(-1) as HTMLInputElement;
    await user.type(input, '定制机');
    expect(onValueChange).toHaveBeenLastCalledWith('machines', [
      'iron',
      '定制机',
    ]);
  });

  it('searches and clears a single select when enabled', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(
      <SelectField
        {...baseProps(
          {
            id: 'supply',
            type: 'select',
            label: '用品',
            props: {
              showSearch: true,
              options: [
                { label: '纸张', value: 'paper' },
                { label: '签字笔', value: 'pen' },
              ],
            },
          },
          'paper',
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '纸张' }));
    const search = screen.getByRole('searchbox', { name: '搜索用品' });
    await user.type(search, '签字');
    await waitFor(() => expect(search).toHaveFocus());
    expect(search).toHaveValue('签字');
    expect(screen.queryByRole('option', { name: '纸张' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: '签字笔' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '清空' }));

    expect(onValueChange).toHaveBeenLastCalledWith('supply', undefined);
    expect(screen.queryByRole('dialog', { name: '选择用品' })).not.toBeInTheDocument();
  });

  it('keeps clear hidden when allowClear is disabled', async () => {
    render(
      <SelectField
        {...baseProps(
          {
            id: 'supply',
            type: 'select',
            label: '用品',
            props: {
              allowClear: false,
              options: [{ label: '纸张', value: 'paper' }],
            },
          },
          'paper',
        )}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '纸张' }));
    expect(screen.queryByRole('button', { name: '清空' })).not.toBeInTheDocument();
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
  });

  it('searches and clears the multi-select draft before confirmation', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(
      <MultiSelectField
        {...baseProps(
          {
            id: 'supplies',
            type: 'multi_select',
            label: '用品',
            props: {
              showSearch: true,
              options: [
                { label: '纸张', value: 'paper' },
                { label: '签字笔', value: 'pen' },
              ],
            },
          },
          ['paper'],
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '纸张' }));
    const search = screen.getByRole('searchbox', { name: '搜索用品' });
    await user.type(search, '不存在');
    await waitFor(() => expect(search).toHaveFocus());
    expect(search).toHaveValue('不存在');
    expect(screen.getByText('没有匹配的选项')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '清空' }));
    await user.click(screen.getByRole('button', { name: '完成' }));

    expect(onValueChange).toHaveBeenLastCalledWith('supplies', []);
  });

  it('renders radio options as large touch choices', async () => {
    const onValueChange = vi.fn();
    render(
      <RadioField
        {...baseProps(
          {
            id: 'leaveType',
            type: 'radio',
            label: '请假类型',
            props: {
              options: [
                { label: '年假', value: 'annual' },
                { label: '调休', value: 'adjust' },
              ],
            },
          },
          'annual',
          onValueChange,
        )}
      />,
    );

    const annual = screen.getByRole('radio', { name: '年假' });
    const adjust = screen.getByRole('radio', { name: '调休' });

    expect(annual).toHaveClass('af-choice-tile');
    expect(annual).toHaveAttribute('aria-checked', 'true');

    await userEvent.click(adjust);

    expect(onValueChange).toHaveBeenCalledWith('leaveType', 'adjust');
  });

  it('opens multi-select options in a half-height bottom sheet', async () => {
    const onValueChange = vi.fn();
    render(
      <MultiSelectField
        {...baseProps(
          {
            id: 'supplies',
            type: 'multi_select',
            label: '用品',
            props: {
              options: [
                { label: '纸张', value: 'paper' },
                { label: '笔', value: 'pen' },
              ],
            },
          },
          ['paper'],
          onValueChange,
        )}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '纸张' }));
    const dialog = screen.getByRole('dialog', { name: '选择用品' });
    expect(dialog).toHaveClass('af-full-picker__panel--sheet');
    const sheet = dialog.closest('.af-selection-sheet') as HTMLElement;
    expect(sheet).toHaveClass('adm-popup-body-position-bottom');
    await userEvent.click(screen.getByRole('checkbox', { name: '笔' }));
    await userEvent.click(screen.getByRole('button', { name: '完成' }));

    expect(onValueChange).toHaveBeenCalledWith('supplies', ['paper', 'pen']);
  });

  it('renders description field as read-only content', () => {
    render(
      <DescriptionField
        {...baseProps(
          {
            id: 'desc',
            type: 'description',
            label: '说明',
            props: { text: '请核对后提交' },
          },
          null,
        )}
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
          {
            id: 'at',
            type: 'date',
            label: '时间',
            props: { format: 'YYYY-MM-DD HH:mm' },
          },
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
        {...baseProps({ id: 'd', type: 'date', label: '日期' }, '2026-08-04')}
      />,
    );
    const input = screen.getByLabelText('日期') as HTMLInputElement;
    expect(input.type).toBe('date');
    expect(input.value).toBe('2026-08-04');
  });
});
