import { create } from 'zustand';
import { nanoid } from '@reduxjs/toolkit';
import type { NodeType, TreeNode } from './types';
import { APPROVAL_PROPS, CC_PROPS, CONDITION_PROPS } from './types';

const rid = () => `node_${nanoid(8)}`;

type State = {
  process: TreeNode;
  selectedId: string | null;
  load(tree: TreeNode | null | undefined): void;
  select(id: string | null): void;
  insertAfter(parentId: string, type: NodeType): void;
  removeNode(id: string): void;
  addBranch(conditionsId: string): void;
  updateProps(id: string, props: any): void;
  updateName(id: string, name: string): void;
};

function freshRoot(): TreeNode {
  return {
    id: 'root',
    type: 'ROOT',
    name: '发起人',
    props: { assignedUser: [] },
    children: null,
  };
}

// Deep-clone the tree, applying `fn` to the node whose id matches.
function mutate(
  node: TreeNode,
  id: string,
  fn: (n: TreeNode) => void,
): TreeNode {
  const clone: TreeNode = { ...node };
  if (clone.id === id) {
    fn(clone);
  }
  if (clone.branchs) {
    clone.branchs = clone.branchs.map((b) => mutate(b, id, fn));
  }
  if (clone.children) {
    clone.children = mutate(clone.children, id, fn);
  }
  return clone;
}

// Remove a node by id; its children are spliced in place of it.
function removeFromTree(root: TreeNode, id: string): TreeNode {
  const walk = (n: TreeNode): TreeNode => {
    const c: TreeNode = { ...n };
    if (c.branchs) {
      c.branchs = c.branchs.map(walk);
    }
    if (c.children) {
      c.children =
        c.children.id === id ? (c.children.children ?? null) : walk(c.children);
    }
    return c;
  };
  return walk(root);
}

export const useProcessDesignerStore = create<State>((set) => ({
  process: freshRoot(),
  selectedId: null,

  load: (tree) => set({ process: tree ?? freshRoot(), selectedId: null }),
  select: (id) => set({ selectedId: id }),

  insertAfter: (parentId, type) =>
    set((s) => ({
      process: mutate(s.process, parentId, (parent) => {
        const after = parent.children ?? null;
        if (type === 'CONDITIONS') {
          const empty: TreeNode = { id: rid(), type: 'EMPTY', children: after };
          parent.children = {
            id: rid(),
            type: 'CONDITIONS',
            name: '条件分支',
            children: empty,
            branchs: [
              {
                id: rid(),
                type: 'CONDITION',
                name: '条件1',
                props: CONDITION_PROPS(),
                children: null,
              },
              {
                id: rid(),
                type: 'CONDITION',
                name: '默认条件',
                props: { isDefault: true },
                children: null,
              },
            ],
          };
        } else {
          const props = type === 'APPROVAL' ? APPROVAL_PROPS() : CC_PROPS();
          const name = type === 'APPROVAL' ? '审批人' : '抄送人';
          parent.children = { id: rid(), type, name, props, children: after };
        }
      }),
    })),

  addBranch: (conditionsId) =>
    set((s) => ({
      process: mutate(s.process, conditionsId, (c) => {
        if ((c.branchs?.length ?? 0) >= 8) return;
        const idx = (c.branchs?.length ?? 0) + 1;
        const next = [...(c.branchs ?? [])];
        // Insert before the default branch (always the last one).
        next.splice(Math.max(0, next.length - 1), 0, {
          id: rid(),
          type: 'CONDITION',
          name: `条件${idx}`,
          props: CONDITION_PROPS(),
          children: null,
        });
        c.branchs = next;
      }),
    })),

  removeNode: (id) =>
    set((s) => ({
      process: removeFromTree(s.process, id),
      selectedId: null,
    })),

  updateProps: (id, props) =>
    set((s) => ({
      process: mutate(s.process, id, (n) => {
        n.props = props;
      }),
    })),

  updateName: (id, name) =>
    set((s) => ({
      process: mutate(s.process, id, (n) => {
        n.name = name;
      }),
    })),
}));