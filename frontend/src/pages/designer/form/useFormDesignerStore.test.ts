import { beforeEach, describe, expect, it } from 'vitest';
import { useFormDesignerStore } from './useFormDesignerStore';
import type { SchemaNode } from '../../../registry/types';

function resetStore() {
  useFormDesignerStore.setState({
    schema: [],
    selectedId: null,
    history: { past: [], future: [] },
  });
}

function node(id: string, type = 'text'): SchemaNode {
  return { id, type, label: id, props: {} };
}

beforeEach(resetStore);

describe('useFormDesignerStore', () => {
  it('adds nodes to the top level and selects the new node', () => {
    const id = useFormDesignerStore.getState().addNode(null, 'text', { placeholder: 'x' });

    const schema = useFormDesignerStore.getState().schema;
    expect(schema).toHaveLength(1);
    expect(schema[0]).toMatchObject({ id, type: 'text', label: '单行文本', props: { placeholder: 'x' } });
    expect(useFormDesignerStore.getState().selectedId).toBe(id);
  });

  it('inserts a node at a specific index', () => {
    useFormDesignerStore.getState().loadSchema([node('a'), node('b'), node('c')]);
    useFormDesignerStore.getState().insertNode(null, 'number', {}, 1);

    const schema = useFormDesignerStore.getState().schema;
    expect(schema.map((n) => n.id)).toEqual(['a', expect.any(String), 'b', 'c']);
    expect(schema[1]?.type).toBe('number');
  });

  it('moves an existing node to a new index', () => {
    useFormDesignerStore.getState().loadSchema([node('a'), node('b'), node('c')]);
    useFormDesignerStore.getState().moveNode('a', 2);

    expect(useFormDesignerStore.getState().schema.map((n) => n.id)).toEqual(['b', 'c', 'a']);
  });

  it('removes a node and clears its selection', () => {
    useFormDesignerStore.getState().loadSchema([node('a'), node('b')]);
    useFormDesignerStore.getState().select('a');
    useFormDesignerStore.getState().removeNode('a');

    const state = useFormDesignerStore.getState();
    expect(state.schema.map((n) => n.id)).toEqual(['b']);
    expect(state.selectedId).toBeNull();
  });

  it('undoes and redoes schema mutations', () => {
    useFormDesignerStore.getState().addNode(null, 'text', {});
    useFormDesignerStore.getState().addNode(null, 'number', {});
    expect(useFormDesignerStore.getState().schema).toHaveLength(2);

    useFormDesignerStore.getState().undo();
    expect(useFormDesignerStore.getState().schema).toHaveLength(1);

    useFormDesignerStore.getState().redo();
    expect(useFormDesignerStore.getState().schema).toHaveLength(2);
  });

  it('updates node fields in place', () => {
    useFormDesignerStore.getState().loadSchema([node('a')]);
    useFormDesignerStore.getState().updateNode('a', { label: '姓名' });

    expect(useFormDesignerStore.getState().schema[0]?.label).toBe('姓名');
  });

  it('keeps the schema flat after container and move operations', () => {
    useFormDesignerStore.getState().loadSchema([node('a'), node('b')]);
    useFormDesignerStore.getState().addNode(null, 'table_list', {});
    useFormDesignerStore.getState().moveNode('b', 0);

    const schema = useFormDesignerStore.getState().schema;
    expect(schema).toHaveLength(3);
    expect(schema.map((n) => n.type)).toEqual(['text', 'text', 'table_list']);
  });

  it('deep-copies a node after its source and remaps internal conditions', () => {
    useFormDesignerStore.getState().loadSchema([{
      id: 'layout',
      type: 'span_layout',
      children: [
        { id: 'source', type: 'select', props: { options: [{ label: '是', value: 'yes' }] } },
        { id: 'target', type: 'text', props: { displayCondition: { fieldId: 'source', operator: 'eq', value: 'yes' } } },
      ],
    }, node('after')]);

    const copyId = useFormDesignerStore.getState().duplicateNode('layout');
    const state = useFormDesignerStore.getState();
    const copy = state.schema[1];
    expect(copy.id).toBe(copyId);
    expect(copy.children?.map((child) => child.id)).not.toEqual(['source', 'target']);
    expect(copy.children?.[1]?.props?.displayCondition?.fieldId).toBe(copy.children?.[0]?.id);
    expect(state.selectedId).toBe(copyId);
    useFormDesignerStore.getState().undo();
    expect(useFormDesignerStore.getState().schema).toHaveLength(2);
  });

  it('updates display rules atomically and cleans deleted option references', () => {
    useFormDesignerStore.getState().loadSchema([
      { id: 'source', type: 'select', props: { options: [{ label: '甲', value: 'a' }, { label: '乙', value: 'b' }] } },
      node('target'),
    ]);
    useFormDesignerStore.getState().updateDisplayRules('source', [{ targetId: 'target', values: ['a', 'b'] }]);
    expect(useFormDesignerStore.getState().schema[1].props?.displayCondition).toEqual({
      fieldId: 'source', operator: 'in', value: ['a', 'b'],
    });
    expect(useFormDesignerStore.getState().history.past).toHaveLength(1);

    useFormDesignerStore.getState().updateNode('source', {
      props: { options: [{ label: '乙', value: 'b' }] },
    });
    expect(useFormDesignerStore.getState().schema[1].props?.displayCondition).toEqual({
      fieldId: 'source', operator: 'eq', value: 'b',
    });
  });

  it('removes conditions whose source field is deleted', () => {
    useFormDesignerStore.getState().loadSchema([
      { id: 'source', type: 'select', props: { options: [{ label: '甲', value: 'a' }] } },
      { id: 'target', type: 'text', props: { displayCondition: { fieldId: 'source', operator: 'eq', value: 'a' } } },
    ]);
    useFormDesignerStore.getState().removeNode('source');
    expect(useFormDesignerStore.getState().schema[0].props?.displayCondition).toBeUndefined();
  });

  it('rejects preceding and conflicting display-rule targets', () => {
    useFormDesignerStore.getState().loadSchema([
      node('before'),
      { id: 'source', type: 'select', props: { options: [{ label: '甲', value: 'a' }] } },
      { id: 'conflict', type: 'text', props: { displayCondition: { fieldId: 'other', operator: 'eq', value: 'x' } } },
    ]);
    useFormDesignerStore.getState().updateDisplayRules('source', [
      { targetId: 'before', values: ['a'] },
      { targetId: 'conflict', values: ['a'] },
    ]);
    expect(useFormDesignerStore.getState().schema[0].props?.displayCondition).toBeUndefined();
    expect(useFormDesignerStore.getState().schema[2].props?.displayCondition?.fieldId).toBe('other');
  });

  it('rejects a display rule that would close a dependency cycle', () => {
    useFormDesignerStore.getState().loadSchema([
      {
        id: 'source',
        type: 'select',
        props: {
          options: [{ label: '甲', value: 'a' }],
          displayCondition: { fieldId: 'target', operator: 'eq', value: 'x' },
        },
      },
      { id: 'target', type: 'select', props: { options: [{ label: '乙', value: 'x' }] } },
    ]);

    useFormDesignerStore.getState().updateDisplayRules('source', [
      { targetId: 'target', values: ['a'] },
    ]);

    expect(useFormDesignerStore.getState().schema[1].props?.displayCondition).toBeUndefined();
    expect(useFormDesignerStore.getState().history.past).toHaveLength(0);
  });
});
