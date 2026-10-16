import { App } from 'antd';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ComponentType } from 'react';
import type { FieldComponentProps, SchemaNode } from '../../registry/types';
import { CheckboxField } from './CheckboxField';
import { RadioField } from './RadioField';
import { SearchField } from './SearchField';
import { SwitchField } from './SwitchField';
import { TimeField } from './TimeField';

function renderField(
  Component: ComponentType<FieldComponentProps>,
  node: SchemaNode,
  value: unknown,
  onChange: (value: unknown) => void = () => {},
  mode: FieldComponentProps['mode'] = 'runtime-fill',
) {
  return render(
    <App>
      <Component node={node} mode={mode} value={value} onChange={onChange} />
    </App>,
  );
}

describe('desktop radio/checkbox/search/time/switch', () => {
  /**
   * 这条钉的是审计指出的两端漂移：桌面 `normalizeSelectOptions` 会把 `isOther` 选项的 value
   * 改写成 `__antflow_other__`，移动端保留原值。导入自移动端的表单里存的是 `other`，
   * 一旦改写成哨兵值，已提交的数据就匹配不上选项了——所以 radio/checkbox 自己读选项。
   */
  it('keeps an imported isOther option at its original value', () => {
    const onChange = vi.fn();
    renderField(
      RadioField.Component,
      {
        id: 'pick',
        type: 'radio',
        label: '单选',
        props: { options: [{ value: 'other', label: '其他', isOther: true }] },
      },
      'other',
      onChange,
    );

    const radio = screen.getByRole('radio') as HTMLInputElement;
    expect(radio.value).toBe('other');
    expect(radio.checked).toBe(true);
  });

  it('writes a scalar for radio and an array for checkbox', () => {
    const onRadio = vi.fn();
    const onCheckbox = vi.fn();
    const props = { options: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }] };

    renderField(RadioField.Component, { id: 'r', type: 'radio', label: 'R', props }, undefined, onRadio);
    fireEvent.click(screen.getAllByRole('radio')[1]);
    expect(onRadio).toHaveBeenLastCalledWith('b');

    renderField(CheckboxField.Component, { id: 'c', type: 'checkbox', label: 'C', props }, ['a'], onCheckbox);
    fireEvent.click(screen.getAllByRole('checkbox')[1]);
    expect(onCheckbox).toHaveBeenLastCalledWith(['a', 'b']);
  });

  /** 数字 0 是合法值，不能被当成"空"或被字符串化。 */
  it('treats option value 0 as a real selection', () => {
    const onChange = vi.fn();
    renderField(
      RadioField.Component,
      { id: 'n', type: 'radio', label: 'N', props: { options: [{ value: 0, label: '零' }] } },
      0,
      onChange,
    );
    expect((screen.getByRole('radio') as HTMLInputElement).checked).toBe(true);
  });

  it('echoes "00:00" instead of treating it as empty', () => {
    const onChange = vi.fn();
    const node: SchemaNode = { id: 't', type: 'time', label: '时间' };
    const { container } = renderField(TimeField.Component, node, '00:00', onChange);

    // 回显：输入框里就是 00:00。`Number('00') === 0` 是真的 0 点，不是"没填"。
    expect((container.querySelector('input') as HTMLInputElement).value).toBe('00:00');
  });

  it('clears back to undefined', () => {
    const onChange = vi.fn();
    const { container } = renderField(
      TimeField.Component,
      { id: 't', type: 'time', label: '时间' },
      '09:30',
      onChange,
    );

    const clear = container.querySelector('.ant-picker-clear') as HTMLElement | null;
    expect(clear).not.toBeNull();
    fireEvent.click(clear as HTMLElement);
    expect(onChange).toHaveBeenLastCalledWith(undefined);
  });

  it('does not write anything while readonly', () => {
    const onRadio = vi.fn();
    renderField(
      RadioField.Component,
      { id: 'r', type: 'radio', label: 'R', props: { options: [{ value: 'a', label: '甲' }] } },
      'a',
      onRadio,
      'readonly',
    );
    expect(screen.queryByRole('radio')).toBeNull();
    expect(screen.getByText('甲')).toBeInTheDocument();
    expect(onRadio).not.toHaveBeenCalled();
  });

  it('renders switch state from a strict boolean and its flanking labels', () => {
    const onChange = vi.fn();
    renderField(
      SwitchField.Component,
      { id: 's', type: 'switch', label: '开关', props: { checkedText: '开', uncheckedText: '关' } },
      'true',
      onChange,
    );
    // 字符串 "true" 不是 boolean true —— 移动端也是这个判据。
    expect(screen.getByRole('switch').getAttribute('aria-checked')).toBe('false');
    expect(screen.getByText('关')).toBeInTheDocument();
  });

  it('search field writes plain strings', () => {
    const onChange = vi.fn();
    renderField(SearchField.Component, { id: 'q', type: 'search', label: '搜索' }, '', onChange);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'abc' } });
    expect(onChange).toHaveBeenLastCalledWith('abc');
  });
});
