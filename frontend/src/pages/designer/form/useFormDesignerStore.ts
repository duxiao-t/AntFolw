import { create } from 'zustand';
import { nanoid } from '@reduxjs/toolkit';
import { updateAt, removeAt } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';

type State = {
  schema: SchemaNode[];
  selectedId: string | null;
  history: { past: SchemaNode[][]; future: SchemaNode[][] };
  // SILENT — loading from server must NOT pollute the undo stack.
  loadSchema(next: SchemaNode[]): void;
  resetSchema(next: SchemaNode[]): void;
  addNode(parentId: string | null, type: string, defaultProps: any): void;
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
    set((s) => ({ ...s, schema: next, history: pushPast(s) })),

  addNode: (parentId, type, defaultProps) =>
    set((s) => {
      const newNode: SchemaNode = {
        id: nanoid(8),
        type,
        props: { ...defaultProps },
      };
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
    }),

  updateNode: (id, patch) =>
    set((s) => ({
      ...s,
      schema: updateAt(s.schema, id, patch),
      history: pushPast(s),
    })),

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
