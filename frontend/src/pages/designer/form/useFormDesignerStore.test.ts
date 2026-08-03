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
});