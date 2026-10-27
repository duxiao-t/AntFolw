import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { formRegistry } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';
import { Inspector } from './Inspector';
import { useFormDesignerStore } from './useFormDesignerStore';

beforeEach(() => {
  useFormDesignerStore.setState({
    schema: [],
    selectedId: null,
    history: { past: [], future: [] },
  });
});

function selectNode(type: string, props: Record<string, any>) {
  useFormDesignerStore.setState({
    schema: [{ id: 'field', type, label: '验收字段', props }],
    selectedId: 'field',
  });
}

function control(label: string) {
  const field = screen.getByText(label).parentElement;
  if (!field) throw new Error('配置项缺少容器');
  return within(field);
}

function node(): SchemaNode {
  return useFormDesignerStore.getState().schema[0];
}

function expectNode(type: string, props: Record<string, any>) {
  expect(node()).toEqual({ id: 'field', type, label: '验收字段', props });
}

describe('registered field configuration panels', () => {
  it.each([
    ['number', '最小值', 'min', '10', 10],
    ['money', '前缀', 'prefix', '$', '$'],
    ['date', '最早可选日期', 'minDate', '2026-01-01', '2026-01-01'],
    ['date_range', '最晚可选日期', 'maxDate', '2026-12-31', '2026-12-31'],
    ['time', '占位提示', 'placeholder', '选择时间', '选择时间'],
    ['user_picker', '最多选择人数', 'maxCount', '8', 8],
    ['dept_picker', '最多选择数', 'maxCount', '8', 8],
    ['file_upload', '最大上传数量', 'maxCount', '5', 5],
    ['image_upload', '最多上传数量', 'maxCount', '6', 6],
    ['video_upload', '最长时长(秒)', 'maxDuration', '90', 90],
    ['audio_upload', '最多录音段数', 'maxCount', '4', 4],
    ['span_layout', '列数', 'columns', '3', 3],
    ['table_list', '最大行数', 'maxRows', '80', 80],
    ['matrix_fill', '最大字符数', 'maxLength', '3000', 3000],
  ])('persists registered %s settings without losing unknown props', (type, label, key, input, expected) => {
    const initial = { multiple: true, customProperty: { untouched: true } };
    selectNode(String(type), initial);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<QueryClientProvider client={client}><Inspector /></QueryClientProvider>);
    const field = control(String(label));
    const element = field.queryByRole('spinbutton') ?? field.getByRole('textbox');
    fireEvent.change(element, { target: { value: String(input) } });
    expectNode(String(type), { ...initial, [String(key)]: expected });
    view.unmount();
    render(<QueryClientProvider client={client}><Inspector /></QueryClientProvider>);
    const reopened = control(String(label));
    expect(reopened.queryByRole('spinbutton') ?? reopened.getByRole('textbox')).toHaveValue(String(input));
    client.clear();
  });

  it('registers every existing component settings panel and leaves configuration-free fields optional', () => {
    const withoutPanels = ['description', 'scan_code', 'location'];
    expect(Object.entries(formRegistry).filter(([, field]) => !field.ConfigPanel).map(([type]) => type).sort())
      .toEqual(withoutPanels.sort());
  });

  it('keeps checklist result configuration and unknown properties after reopening', () => {
    const initial = { customProperty: 42, items: [{ id: 'one', label: '项目', required: true }] };
    selectNode('checklist', initial);
    const view = render(<Inspector />);
    fireEvent.change(screen.getByPlaceholderText('结果1'), { target: { value: '合格' } });
    expect(node().props?.results[0]).toMatchObject({ id: 'pass', label: '合格' });
    expect(node().props?.items).toEqual(initial.items);
    expect(node().props?.customProperty).toBe(42);
    view.unmount();
    render(<Inspector />);
    expect(screen.getByPlaceholderText('结果1')).toHaveValue('合格');
  });
  it('edits all text settings without losing other props and reads them after reopening', () => {
    const initial = { inputType: 'text', maxLength: 120, trim: false, placeholder: '保留', required: true };
    selectNode('text', initial);
    const { unmount } = render(<Inspector />);

    fireEvent.mouseDown(control('输入类型').getByRole('combobox'));
    fireEvent.click(screen.getByText('数字'));
    expectNode('text', { ...initial, inputType: 'number' });

    fireEvent.change(control('最大长度').getByRole('spinbutton'), { target: { value: '300' } });
    expectNode('text', { ...initial, inputType: 'number', maxLength: 300 });

    fireEvent.click(screen.getByRole('checkbox', { name: '自动去除首尾空格' }));
    expectNode('text', { ...initial, inputType: 'number', maxLength: 300, trim: true });

    unmount();
    render(<Inspector />);
    expect(control('输入类型').getByText('数字')).toBeInTheDocument();
    expect(control('最大长度').getByRole('spinbutton')).toHaveValue('300');
    expect(screen.getByRole('checkbox', { name: '自动去除首尾空格' })).toBeChecked();
  });

  it('edits all textarea settings without losing other props and reads them after reopening', () => {
    const initial = { rows: 3, maxLength: 1000, showCount: false, placeholder: '保留', required: true };
    selectNode('textarea', initial);
    const { unmount } = render(<Inspector />);

    fireEvent.change(control('行数').getByRole('spinbutton'), { target: { value: '6' } });
    expectNode('textarea', { ...initial, rows: 6 });

    fireEvent.change(control('最大长度').getByRole('spinbutton'), { target: { value: '2500' } });
    expectNode('textarea', { ...initial, rows: 6, maxLength: 2500 });

    fireEvent.click(screen.getByRole('checkbox', { name: '显示字数统计' }));
    expectNode('textarea', { ...initial, rows: 6, maxLength: 2500, showCount: true });

    unmount();
    render(<Inspector />);
    expect(control('行数').getByRole('spinbutton')).toHaveValue('6');
    expect(control('最大长度').getByRole('spinbutton')).toHaveValue('2500');
    expect(screen.getByRole('checkbox', { name: '显示字数统计' })).toBeChecked();
  });

  it.each([
    { type: 'text', maxLength: 255 },
    { type: 'textarea', maxLength: 2000 },
  ])('preserves $type defaults and numeric clear fallbacks', ({ type, maxLength }) => {
    selectNode(type, {});
    render(<Inspector />);
    expect(control('最大长度').getByRole('spinbutton')).toHaveValue(String(maxLength));
    if (type === 'text') {
      expect(control('输入类型').getByText('文本')).toBeInTheDocument();
    } else {
      expect(control('行数').getByRole('spinbutton')).toHaveValue('4');
      fireEvent.change(control('行数').getByRole('spinbutton'), { target: { value: '' } });
      expect(node().props?.rows).toBe(4);
    }
    fireEvent.change(control('最大长度').getByRole('spinbutton'), { target: { value: '' } });
    expect(node().props?.maxLength).toBe(maxLength);
  });

  it('edits number settings through its registered panel', () => {
    const initial = { min: 0, max: 100, precision: 2, step: 5, required: true };
    selectNode('number', initial);
    render(<Inspector />);
    fireEvent.change(control('最小值').getByRole('spinbutton'), { target: { value: '10' } });
    expectNode('number', { ...initial, min: 10 });
  });
});
