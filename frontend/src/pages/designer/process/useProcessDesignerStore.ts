import { nanoid } from '@reduxjs/toolkit';
import { create } from 'zustand';
import type { DesignerNodeType, TreeNode } from './types';
import {
  APPROVAL_PROPS,
  BRANCH_PROPS,
  CC_PROPS,
  CONDITION_PROPS,
  DELAY_PROPS,
  TRIGGER_PROPS,
} from './types';

const rid = () => `node_${nanoid(8)}`;

type State = {
  process: TreeNode;
  selectedId: string | null;
  load(tree: TreeNode | null | undefined): void;
  select(id: string | null): void;
  insertAfter(parentId: string, type: DesignerNodeType): void;
  removeNode(id: string): void;
  addBranch(ownerId: string): void;
  copyBranch(ownerId: string, branchId: string): void;
  removeBranch(ownerId: string, branchId: string): void;
  moveBranch(ownerId: string, branchId: string, direction: -1 | 1): void;
  reconcileFormFields(fieldIds: string[]): void;
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

function mutate(
  node: TreeNode,
  id: string,
  fn: (n: TreeNode) => void,
): TreeNode {
  const clone: TreeNode = { ...node };
  if (clone.id === id) fn(clone);
  if (clone.branchs)
    clone.branchs = clone.branchs.map((b) => mutate(b, id, fn));
  if (clone.children) clone.children = mutate(clone.children, id, fn);
  return clone;
}

function removeFromTree(root: TreeNode, id: string): TreeNode {
  const walk = (node: TreeNode): TreeNode => {
    const clone: TreeNode = { ...node };
    if (clone.branchs) clone.branchs = clone.branchs.map(walk);
    if (clone.children) {
      clone.children =
        clone.children.id === id
          ? (clone.children.children ?? null)
          : walk(clone.children);
    }
    return clone;
  };
  return walk(root);
}

function findNode(
  node: TreeNode | null | undefined,
  id: string,
): TreeNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? []) {
    const match = findNode(branch, id);
    if (match) return match;
  }
  return findNode(node.children, id);
}

function containsNode(
  node: TreeNode | null | undefined,
  id: string | null,
): boolean {
  return !!id && !!findNode(node, id);
}

function unwrapJoin(node: TreeNode | null | undefined): TreeNode | null {
  let current = node ?? null;
  while (current?.type === 'EMPTY') current = current.children ?? null;
  return current;
}

function appendSuccessor(
  node: TreeNode | null | undefined,
  successor: TreeNode | null,
): TreeNode | null {
  if (!node) return successor;
  const clone: TreeNode = { ...node };
  clone.children = clone.children
    ? appendSuccessor(clone.children, successor)
    : successor;
  return clone;
}

function removeBranchFromTree(
  root: TreeNode,
  ownerId: string,
  branchId: string,
): TreeNode {
  const walk = (node: TreeNode): TreeNode | null => {
    if (node.id === ownerId) {
      const branches = node.branchs ?? [];
      if (branches.length === 2) {
        const survivor = branches.find((branch) => branch.id !== branchId);
        return appendSuccessor(survivor?.children, unwrapJoin(node.children));
      }
      return {
        ...node,
        branchs: branches.filter((branch) => branch.id !== branchId),
      };
    }

    return {
      ...node,
      branchs: node.branchs?.map((branch) => walk(branch) as TreeNode),
      children: node.children ? walk(node.children) : null,
    };
  };

  return walk(root) ?? root;
}

function cloneSubtree(node: TreeNode | null | undefined): TreeNode | null {
  if (!node) return null;
  return {
    ...node,
    id: rid(),
    props: node.props ? structuredClone(node.props) : undefined,
    branchs: node.branchs?.map((branch) => cloneSubtree(branch) as TreeNode),
    children: cloneSubtree(node.children),
  };
}

function pruneMissingFormPerms(node: TreeNode, allowedIds: Set<string>): TreeNode {
  const branchs = node.branchs?.map((branch) =>
    pruneMissingFormPerms(branch, allowedIds),
  );
  const children = node.children
    ? pruneMissingFormPerms(node.children, allowedIds)
    : node.children;
  let props = node.props;
  if (node.type === 'APPROVAL' && Array.isArray(node.props?.formPerms)) {
    const formPerms = node.props.formPerms.filter((entry: any) => {
      const fieldId = entry?.fieldId;
      return (
        typeof fieldId !== 'string' ||
        !fieldId.trim() ||
        allowedIds.has(fieldId)
      );
    });
    if (formPerms.length !== node.props.formPerms.length) {
      props = { ...node.props, formPerms };
    }
  }
  const branchsChanged = branchs?.some(
    (branch, index) => branch !== node.branchs?.[index],
  );
  if (props === node.props && !branchsChanged && children === node.children) {
    return node;
  }
  return { ...node, props, branchs, children };
}

function createLinearNode(
  type: Exclude<DesignerNodeType, 'CONDITIONS' | 'PARALLEL'>,
  children: TreeNode | null,
): TreeNode {
  if (type === 'APPROVAL') {
    return {
      id: rid(),
      type,
      name: '审批人',
      props: APPROVAL_PROPS(),
      children,
    };
  }
  if (type === 'CC') {
    return { id: rid(), type, name: '抄送人', props: CC_PROPS(), children };
  }
  if (type === 'DELAY') {
    return {
      id: rid(),
      type,
      name: '延时等待',
      props: DELAY_PROPS(),
      children,
    };
  }
  return {
    id: rid(),
    type,
    name: 'Webhook 触发器',
    props: TRIGGER_PROPS(),
    children,
  };
}

export const useProcessDesignerStore = create<State>((set) => ({
  process: freshRoot(),
  selectedId: null,

  load: (tree) => set({ process: tree ?? freshRoot(), selectedId: null }),
  select: (id) => set({ selectedId: id }),

  insertAfter: (parentId, type) =>
    set((state) => ({
      process: mutate(state.process, parentId, (parent) => {
        const after = parent.children ?? null;
        if (type === 'PARALLEL') {
          parent.children = {
            id: rid(),
            type: 'PARALLEL',
            name: '并行分支',
            children: { id: rid(), type: 'EMPTY', children: after },
            branchs: [
              {
                id: rid(),
                type: 'BRANCH',
                name: '分支 1',
                props: BRANCH_PROPS(),
                children: null,
              },
              {
                id: rid(),
                type: 'BRANCH',
                name: '分支 2',
                props: BRANCH_PROPS(),
                children: null,
              },
            ],
          };
        } else if (type === 'CONDITIONS') {
          parent.children = {
            id: rid(),
            type: 'CONDITIONS',
            name: '条件分支',
            children: { id: rid(), type: 'EMPTY', children: after },
            branchs: [
              {
                id: rid(),
                type: 'CONDITION',
                name: '条件 1',
                props: CONDITION_PROPS(),
                children: null,
              },
              {
                id: rid(),
                type: 'CONDITION',
                name: '默认分支',
                props: { isDefault: true },
                children: null,
              },
            ],
          };
        } else {
          parent.children = createLinearNode(type, after);
        }
      }),
    })),

  addBranch: (ownerId) =>
    set((state) => ({
      process: mutate(state.process, ownerId, (owner) => {
        if ((owner.branchs?.length ?? 0) >= 8) return;
        const next = [...(owner.branchs ?? [])];
        const index = next.length + 1;
        if (owner.type === 'PARALLEL') {
          next.push({
            id: rid(),
            type: 'BRANCH',
            name: `分支 ${index}`,
            props: BRANCH_PROPS(),
            children: null,
          });
        } else {
          next.splice(Math.max(0, next.length - 1), 0, {
            id: rid(),
            type: 'CONDITION',
            name: `条件 ${index}`,
            props: CONDITION_PROPS(),
            children: null,
          });
        }
        owner.branchs = next;
      }),
    })),

  copyBranch: (ownerId, branchId) =>
    set((state) => ({
      process: mutate(state.process, ownerId, (owner) => {
        const branches = owner.branchs ?? [];
        if (branches.length >= 8) return;
        const index = branches.findIndex((branch) => branch.id === branchId);
        const source = branches[index];
        if (!source || source.props?.isDefault) return;
        const copy = cloneSubtree(source);
        if (!copy) return;
        copy.name = `${source.name ?? '分支'} 副本`;
        owner.branchs = [
          ...branches.slice(0, index + 1),
          copy,
          ...branches.slice(index + 1),
        ];
      }),
    })),

  removeBranch: (ownerId, branchId) =>
    set((state) => {
      const owner = findNode(state.process, ownerId);
      const branches = owner?.branchs ?? [];
      const target = branches.find((branch) => branch.id === branchId);
      if (!owner || branches.length < 2 || !target || target.props?.isDefault) {
        return state;
      }

      const collapsing = branches.length === 2;
      const selectionRemoved =
        state.selectedId === ownerId ||
        containsNode(target, state.selectedId) ||
        (collapsing &&
          branches.some((branch) => branch.id === state.selectedId));

      return {
        process: removeBranchFromTree(state.process, ownerId, branchId),
        selectedId: selectionRemoved ? null : state.selectedId,
      };
    }),

  moveBranch: (ownerId, branchId, direction) =>
    set((state) => ({
      process: mutate(state.process, ownerId, (owner) => {
        const branches = [...(owner.branchs ?? [])];
        const index = branches.findIndex((branch) => branch.id === branchId);
        const target = index + direction;
        if (index < 0 || target < 0 || target >= branches.length) return;
        if (
          branches[index].props?.isDefault ||
          branches[target].props?.isDefault
        )
          return;
        [branches[index], branches[target]] = [
          branches[target],
          branches[index],
        ];
        owner.branchs = branches;
      }),
    })),

  reconcileFormFields: (fieldIds) =>
    set((state) => {
      const process = pruneMissingFormPerms(
        state.process,
        new Set(fieldIds),
      );
      return process === state.process ? state : { ...state, process };
    }),

  removeNode: (id) =>
    set((state) => ({
      process: removeFromTree(state.process, id),
      selectedId: state.selectedId === id ? null : state.selectedId,
    })),

  updateProps: (id, props) =>
    set((state) => ({
      process: mutate(state.process, id, (node) => {
        node.props = props;
      }),
    })),

  updateName: (id, name) =>
    set((state) => ({
      process: mutate(state.process, id, (node) => {
        node.name = name;
      }),
    })),
}));
