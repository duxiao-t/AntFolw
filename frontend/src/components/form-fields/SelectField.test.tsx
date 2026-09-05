import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MultiSelectField } from './MultiSelectField';
import { SelectField } from './SelectField';

describe('desktop select fields', () => {
  it('keeps historical fields on the dropdown display', () => {
    render(
      <SelectField.Component
        node={{
          id: 'machine',
          type: 'select',
          label: '设备',
          props: { options: [{ label: '铁面', value: 'iron' }] },
        }}
        mode="runtime-fill"
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByRole('combobox')).toBeInTheDocument();
  });

  it('selects and clears a list-style single choice', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <SelectField.Component
        node={{
          id: 'machine',
          type: 'select',
          label: '设备',
          props: {
            displayStyle: 'list',
            options: [{ label: '铁面', value: 'iron' }],
          },
        }}
        mode="runtime-fill"
        value="iron"
        onChange={onChange}
      />,
    );

    const option = screen.getByRole('radio', { name: '设备：铁面' });
    expect(option).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(option);
    expect(onChange).toHaveBeenLastCalledWith(undefined);

    rerender(
      <SelectField.Component
        node={{
          id: 'machine',
          type: 'select',
          label: '设备',
          props: {
            displayStyle: 'list',
            allowClear: false,
            options: [{ label: '铁面', value: 'iron' }],
          },
        }}
        mode="runtime-fill"
        value="iron"
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '设备：铁面' }));
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('uses a two-column block multi-select and enforces maxCount', () => {
    const onChange = vi.fn();
    render(
      <MultiSelectField.Component
        node={{
          id: 'machines',
          type: 'multi_select',
          label: '设备',
          props: {
            displayStyle: 'block_double',
            maxCount: 1,
            options: [
              { label: '铁面', value: 'iron' },
              { label: '冲床', value: 'press' },
            ],
          },
        }}
        mode="runtime-fill"
        value={['iron']}
        onChange={onChange}
      />,
    );

    expect(document.querySelector('.form-select-choices--block_double')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '设备：冲床' })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: '设备：铁面' }));
    expect(onChange).toHaveBeenCalledWith([]);
  });

  it('edits a custom single-select value through the other input', () => {
    const onChange = vi.fn();
    render(
      <SelectField.Component
        node={{
          id: 'machine',
          type: 'select',
          label: '设备',
          props: {
            options: [
              { label: '铁面', value: 'iron' },
              { label: '其他', value: '__antflow_other__', isOther: true },
            ],
          },
        }}
        mode="runtime-fill"
        value="自定义设备"
        onChange={onChange}
      />,
    );

    const input = screen.getByLabelText('设备自定义内容');
    expect(input).toHaveValue('自定义设备');
    fireEvent.change(input, { target: { value: '新设备' } });
    expect(onChange).toHaveBeenCalledWith('新设备');
  });

  it('keeps standard and custom values in a multi-select array', () => {
    const onChange = vi.fn();
    render(
      <MultiSelectField.Component
        node={{
          id: 'machines',
          type: 'multi_select',
          label: '设备',
          props: {
            options: [
              { label: '铁面', value: 'iron' },
              { label: '其他', value: '__antflow_other__', isOther: true },
            ],
          },
        }}
        mode="runtime-fill"
        value={['iron', '自定义设备']}
        onChange={onChange}
      />,
    );

    const input = screen.getByLabelText('设备自定义内容');
    fireEvent.change(input, { target: { value: '新设备' } });
    expect(onChange).toHaveBeenCalledWith(['iron', '新设备']);
  });

  it('does not reinterpret a hidden option value as custom other text', () => {
    render(
      <SelectField.Component
        node={{
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
        }}
        mode="runtime-fill"
        value="hidden"
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByLabelText('设备自定义内容')).not.toBeInTheDocument();
  });
});
