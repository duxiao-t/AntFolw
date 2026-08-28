import { create } from 'zustand';
import { nanoid } from '@reduxjs/toolkit';
import { formRegistry, updateAt, removeAt } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';

type State = {
  schema: SchemaNode[];
  selectedId: string | null;
  history: { past: SchemaNode[][]; future: SchemaNode[][] };
  // SILENT — loading from server must NOT pollute the undo stack.
  loadSchema(next: SchemaNode[]): void;
  resetSchema(next: SchemaNode[]): void;
  addNode(parentId: string | null, type: string, defaultProps: any): string;
  insertNode(
    parentId: string | null,
    type: string,
    defaultProps: any,
    index: number,
  ): string;
  moveNode(id: string, index: number): void;
  duplicateNode(id: string): string | null;
  updateNode(id: string, patch: Partial<SchemaNode>): void;
  removeNode(id: string): void;
  select(id: string | null): void;
  undo(): void;
  redo(): void;
};

const HISTORY_LIMIT = 50;
function pushPast(state: State): State['history'] {
  return {
    past: [...state.history.past, state.schema].slice(-HISTORY_LIMIT),
    future: [],
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function insertAtIndex(
  nodes: SchemaNode[],
  newNode: SchemaNode,
  index: number,
): SchemaNode[] {
  const targetIndex = clamp(index, 0, nodes.length);
  return [
    ...nodes.slice(0, targetIndex),
    newNode,
    ...nodes.slice(targetIndex),
  ];
}

function isSameSchemaNode(a: SchemaNode, b: SchemaNode): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

function isSameSchema(a: SchemaNode[], b: SchemaNode[]): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

export const useFormDesignerStore = create<State>((set) => ({
  schema: [],
  selectedId: null,
  history: { past: [], future: [] },

  loadSchema: (next) =>
    set((s) => ({
      ...s,
      schema: next,
      history: { past: [], future: [] },
      selectedId: null,
    })),

  resetSchema: (next) =>
    set((s) =>
      isSameSchema(s.schema, next)
        ? s
        : { ...s, schema: next, selectedId: null, history: pushPast(s) },
    ),

  addNode: (parentId, type, defaultProps) => {
    const newNode: SchemaNode = {
      id: nanoid(8),
      type,
      label: formRegistry[type]?.label ?? type,
      props: { ...defaultProps },
    };
    set((s) => {
      const next = parentId
        ? s.schema.map((n) =>
            n.id === parentId
              ? { ...n, children: [...(n.children ?? []), newNode] }
              : n.children
                ? { ...n, children: recurseAdd(n.children, parentId, newNode) }
                : n,
          )
        : [...s.schema, newNode];
      return {
        ...s,
        schema: next,
        selectedId: newNode.id,
        history: pushPast(s),
      };
    });
    return newNode.id;
  },

  insertNode: (parentId, type, defaultProps, index) => {
    const newNode: SchemaNode = {
      id: nanoid(8),
      type,
      label: formRegistry[type]?.label ?? type,
      props: { ...defaultProps },
    };
    set((s) => {
      const next = parentId
        ? s.schema.map((n) =>
            n.id === parentId
              ? { ...n, children: [...(n.children ?? []), newNode] }
              : n.children
                ? { ...n, children: recurseAdd(n.children, parentId, newNode) }
                : n,
          )
        : insertAtIndex(s.schema, newNode, index);
      return {
        ...s,
        schema: next,
        selectedId: newNode.id,
        history: pushPast(s),
      };
    });
    return newNode.id;
  },

  moveNode: (id, index) =>
    set((s) => {
      const currentIndex = s.schema.findIndex((n) => n.id === id);
      if (currentIndex < 0) return s;
      const moving = s.schema[currentIndex];
      const without = s.schema.filter((n) => n.id !== id);
      const targetIndex = clamp(index, 0, without.length);
      const next = insertAtIndex(without, moving, targetIndex);
      if (isSameSchema(s.schema, next)) return s;
      return {
        ...s,
        schema: next,
        selectedId: id,
        history: pushPast(s),
      };
    }),

  duplicateNode: (id) => {
    let duplicatedId: string | null = null;
    set((s) => {
      const source = findNodeById(s.schema, id);
      if (!source) return s;
      const idMap = new Map<string, string>();
      collectNodeIds(source).forEach((nodeId) => {
        idMap.set(nodeId, nanoid(8));
      });
      const copy = cloneNode(source, idMap);
      duplicatedId = copy.id;
      return {
        ...s,
        schema: insertAfter(s.schema, id, copy),
        selectedId: copy.id,
        history: pushPast(s),
      };
    });
    return duplicatedId;
  },

  updateNode: (id, patch) =>
    set((s) => {
      const current = findNodeById(s.schema, id);
      const nextNode = current ? { ...current, ...patch } : null;
      if (current && nextNode && isSameSchemaNode(current, nextNode)) return s;
      return {
        ...s,
        schema: updateAt(s.schema, id, patch),
        history: pushPast(s),
      };
    }),

  removeNode: (id) =>
    set((s) => ({
      ...s,
      schema: removeAt(s.schema, id),
      selectedId: s.selectedId === id ? null : s.selectedId,
      history: pushPast(s),
    })),

  select: (id) => set((s) => ({ ...s, selectedId: id })),

  undo: () =>
    set((s) => {
      const prev = s.history.past.at(-1);
      if (!prev) return s;
      return {
        ...s,
        schema: prev,
        history: {
          past: s.history.past.slice(0, -1),
          future: [s.schema, ...s.history.future],
        },
      };
    }),

  redo: () =>
    set((s) => {
      const next = s.history.future[0];
      if (!next) return s;
      return {
        ...s,
        schema: next,
        history: {
          past: [...s.history.past, s.schema],
          future: s.history.future.slice(1),
        },
      };
    }),
}));

function recurseAdd(
  children: SchemaNode[],
  parentId: string,
  newNode: SchemaNode,
): SchemaNode[] {
  return children.map((c) =>
    c.id === parentId
      ? { ...c, children: [...(c.children ?? []), newNode] }
      : c.children
        ? { ...c, children: recurseAdd(c.children, parentId, newNode) }
        : c,
  );
}

function findNodeById(nodes: SchemaNode[], id: string): SchemaNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const child = findNodeById(node.children ?? [], id);
    if (child) return child;
  }
  return null;
}

function collectNodeIds(node: SchemaNode): string[] {
  return [node.id, ...(node.children ?? []).flatMap(collectNodeIds)];
}

function cloneNode(node: SchemaNode, idMap: Map<string, string>): SchemaNode {
  const copy = structuredClone(node);
  copy.id = idMap.get(node.id) ?? nanoid(8);
  const displayCondition = copy.props?.displayCondition;
  const fieldId = displayCondition?.fieldId;
  const mappedFieldId = fieldId ? idMap.get(fieldId) : undefined;
  if (mappedFieldId) {
    copy.props = {
      ...copy.props,
      displayCondition: {
        ...displayCondition,
        fieldId: mappedFieldId,
        operator: displayCondition?.operator ?? 'eq',
      },
    };
  }
  copy.children = node.children?.map((child) => cloneNode(child, idMap));
  return copy;
}

function insertAfter(nodes: SchemaNode[], id: string, copy: SchemaNode): SchemaNode[] {
  const index = nodes.findIndex((node) => node.id === id);
  if (index >= 0) return [...nodes.slice(0, index + 1), copy, ...nodes.slice(index + 1)];
  return nodes.map((node) => node.children
    ? { ...node, children: insertAfter(node.children, id, copy) }
    : node);
}
