import { fireEvent, render, screen, within } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it } from 'vitest';
import type { SchemaNode } from '../../../registry/types';
import { DisplayRulesEditor } from './DisplayRulesEditor';
import { useFormDesignerStore } from './useFormDesignerStore';

const source: SchemaNode = {
  id: 'source',
  type: 'select',
  label: '故障类型',
  props: {
    options: [
      { label: '甲', value: 'a' },
      { label: '乙', value: 'b' },
    ],
  },
};

function resetStore(schema: SchemaNode[]) {
  useFormDesignerStore.setState({
    schema,
    selectedId: source.id,
    history: { past: [], future: [] },
  });
}

function renderEditor(schema: SchemaNode[]) {
  return render(
    <App>
      <DisplayRulesEditor source={source} schema={schema} />
    </App>,
  );
}

beforeEach(() => resetStore([]));

describe('DisplayRulesEditor', () => {
  it('opens from the switch and discards an unfinished draft on cancel', () => {
    const schema = [source, { id: 'target', type: 'text', label: '处理说明', props: {} }];
    resetStore(schema);
    renderEditor(schema);

    expect(screen.getByText('0条逻辑规则')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: '启用逻辑规则' }));
    const dialog = screen.getByRole('dialog', { name: '设置字段显示规则' });
    fireEvent.click(within(dialog).getByRole('button', { name: /添加新规则/ }));
    expect(within(dialog).getByLabelText('规则1选项')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }));

    expect(useFormDesignerStore.getState().schema).toEqual(schema);
    expect(useFormDesignerStore.getState().history.past).toHaveLength(0);
  });

  it('summarizes stored rules and saves target changes atomically', () => {
    const schema: SchemaNode[] = [
      source,
      {
        id: 'controlled',
        type: 'text',
        label: '处理说明',
        props: { displayCondition: { fieldId: source.id, operator: 'eq', value: 'a' } },
      },
      { id: 'visible', type: 'number', label: '返工数量', props: {} },
    ];
    resetStore(schema);
    renderEditor(schema);

    expect(screen.getByText('1条逻辑规则')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '设置' }));
    const targetSelect = screen.getByLabelText('规则1显示字段');
    fireEvent.mouseDown(targetSelect);

    expect(screen.getByText('单行文本')).toBeInTheDocument();
    expect(screen.getByText('数字')).toBeInTheDocument();
    expect(screen.getByText('已隐藏')).toBeInTheDocument();
    expect(screen.getByText('已显示')).toBeInTheDocument();
    fireEvent.click(screen.getByText('返工数量'));
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(useFormDesignerStore.getState().schema[2]?.props?.displayCondition).toEqual({
      fieldId: source.id,
      operator: 'eq',
      value: 'a',
    });
    expect(useFormDesignerStore.getState().history.past).toHaveLength(1);
  });

  it('clears existing rules only after confirming the switch-off action', async () => {
    const schema: SchemaNode[] = [
      source,
      {
        id: 'target',
        type: 'text',
        label: '处理说明',
        props: { displayCondition: { fieldId: source.id, operator: 'eq', value: 'a' } },
      },
    ];
    resetStore(schema);
    renderEditor(schema);

    fireEvent.click(screen.getByRole('switch', { name: '启用逻辑规则' }));
    const confirm = await screen.findByRole('dialog', { name: '关闭逻辑规则' });
    fireEvent.click(within(confirm).getByRole('button', { name: /确认关闭/ }));

    expect(useFormDesignerStore.getState().schema[1]?.props?.displayCondition).toBeUndefined();
    expect(useFormDesignerStore.getState().history.past).toHaveLength(1);
  });
});
