import { beforeEach, describe, expect, it } from 'vitest';
import type { TreeNode } from './types';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { validateProcessTree } from './validation';

function reset(tree?: TreeNode) {
  useProcessDesignerStore.getState().load(
    tree ?? {
      id: 'root',
      type: 'ROOT',
      name: '发起人',
      props: { assignedUser: [] },
      children: null,
    },
  );
}

describe('process designer tree operations', () => {
  beforeEach(() => reset());

  it('splices the successor into place when a normal node is removed', () => {
    reset({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'approval-1',
        type: 'APPROVAL',
        props: { assignedType: 'SELF', mode: 'OR' },
        children: {
          id: 'cc-1',
          type: 'CC',
          props: { assignedUser: [7] },
          children: null,
        },
      },
    });

    useProcessDesignerStore.getState().removeNode('approval-1');

    expect(useProcessDesignerStore.getState().process.children?.id).toBe(
      'cc-1',
    );
  });

  it('copies a complete branch subtree with fresh ids', () => {
    reset({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'conditions',
        type: 'CONDITIONS',
        children: null,
        branchs: [
          {
            id: 'condition-a',
            type: 'CONDITION',
            name: '金额较高',
            props: {
              isDefault: false,
              groups: [
                {
                  conditions: [
                    {
                      id: 'rule-a',
                      field: 'amount',
                      operator: '>',
                      value: '100',
                    },
                  ],
                },
              ],
            },
            children: {
              id: 'approval-a',
              type: 'APPROVAL',
              props: { assignedType: 'SELF', mode: 'OR' },
              children: null,
            },
          },
          {
            id: 'condition-default',
            type: 'CONDITION',
            name: '默认分支',
            props: { isDefault: true },
            children: null,
          },
        ],
      },
    });

    useProcessDesignerStore.getState().copyBranch('conditions', 'condition-a');

    const branches =
      useProcessDesignerStore.getState().process.children?.branchs ?? [];
    expect(branches).toHaveLength(3);
    expect(branches[1].id).not.toBe('condition-a');
    expect(branches[1].children?.id).not.toBe('approval-a');
    expect(branches[1].props).toEqual(branches[0].props);
    expect(branches[2].props?.isDefault).toBe(true);
  });

  it('keeps the default condition last and collapses a two-branch gateway', () => {
    useProcessDesignerStore.getState().insertAfter('root', 'CONDITIONS');
    const conditions = useProcessDesignerStore.getState().process
      .children as TreeNode;
    const defaultBranch = conditions.branchs?.at(-1) as TreeNode;
    useProcessDesignerStore
      .getState()
      .moveBranch(conditions.id, defaultBranch.id, -1);
    expect(
      useProcessDesignerStore.getState().process.children?.branchs?.at(-1)?.id,
    ).toBe(defaultBranch.id);

    reset();
    useProcessDesignerStore.getState().insertAfter('root', 'PARALLEL');
    const parallel = useProcessDesignerStore.getState().process
      .children as TreeNode;
    const first = parallel.branchs?.[0] as TreeNode;
    expect(first.props?.conditionMode).toBe('ALWAYS');
    useProcessDesignerStore.getState().removeBranch(parallel.id, first.id);
    expect(useProcessDesignerStore.getState().process.children).toBeNull();
  });

  it('keeps the surviving branch nodes and reconnects the successor', () => {
    reset({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'parallel',
        type: 'PARALLEL',
        branchs: [
          {
            id: 'branch-a',
            type: 'BRANCH',
            children: {
              id: 'approval-a',
              type: 'APPROVAL',
              children: null,
            },
          },
          {
            id: 'branch-b',
            type: 'BRANCH',
            children: {
              id: 'approval-b',
              type: 'APPROVAL',
              children: null,
            },
          },
        ],
        children: {
          id: 'parallel-join',
          type: 'EMPTY',
          children: { id: 'cc-after', type: 'CC', children: null },
        },
      },
    });

    useProcessDesignerStore.getState().removeBranch('parallel', 'branch-a');

    const promoted = useProcessDesignerStore.getState().process.children;
    expect(promoted?.id).toBe('approval-b');
    expect(promoted?.children?.id).toBe('cc-after');
  });

  it('collapses a two-way condition into its default path', () => {
    reset({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'conditions',
        type: 'CONDITIONS',
        branchs: [
          {
            id: 'condition-a',
            type: 'CONDITION',
            children: { id: 'approval-a', type: 'APPROVAL', children: null },
          },
          {
            id: 'condition-default',
            type: 'CONDITION',
            props: { isDefault: true },
            children: { id: 'cc-default', type: 'CC', children: null },
          },
        ],
        children: {
          id: 'condition-join',
          type: 'EMPTY',
          children: { id: 'approval-after', type: 'APPROVAL', children: null },
        },
      },
    });

    useProcessDesignerStore
      .getState()
      .removeBranch('conditions', 'condition-a');

    const promoted = useProcessDesignerStore.getState().process.children;
    expect(promoted?.id).toBe('cc-default');
    expect(promoted?.children?.id).toBe('approval-after');
  });
});

describe('process designer validation', () => {
  it('requires an array value for in conditions', () => {
    const branch = (value: string | string[]): TreeNode => ({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'conditions',
        type: 'CONDITIONS',
        branchs: [
          {
            id: 'matched',
            type: 'CONDITION',
            props: {
              groups: [
                {
                  groupType: 'AND',
                  conditions: [
                    { id: 'rule', field: 'city', operator: 'in', value },
                  ],
                },
              ],
            },
            children: {
              id: 'approval',
              type: 'APPROVAL',
              props: { assignedType: 'SELF' },
            },
          },
          {
            id: 'default',
            type: 'CONDITION',
            props: { isDefault: true },
          },
        ],
      },
    });

    expect(validateProcessTree(branch('BJ'))).toContainEqual({
      nodeId: 'matched',
      message: '请完整配置分支条件',
    });
    expect(validateProcessTree(branch(['BJ', 'SH']))).toEqual([]);
  });

  it('allows async nodes in parallel branches', () => {
    const tree: TreeNode = {
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'parallel',
        type: 'PARALLEL',
        children: null,
        branchs: [
          {
            id: 'branch-a',
            type: 'BRANCH',
            children: {
              id: 'delay-a',
              type: 'DELAY',
            props: { mode: 'DURATION', amount: 1, unit: 'HOURS' },
            },
          },
          {
            id: 'branch-b',
            type: 'BRANCH',
            children: {
              id: 'approval-b',
              type: 'APPROVAL',
            props: { assignedType: 'SELF', mode: 'OR' },
            },
          },
        ],
      },
    };

    expect(validateProcessTree(tree)).toEqual([]);
  });

  it('validates delay limits and webhook signing configuration', () => {
    const tree: TreeNode = {
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'approval',
        type: 'APPROVAL',
        props: { assignedType: 'SELF', mode: 'OR' },
        children: {
          id: 'delay',
          type: 'DELAY',
          props: { mode: 'DURATION', amount: 366, unit: 'DAYS' },
          children: {
            id: 'trigger',
            type: 'TRIGGER',
            props: {
              method: 'POST',
              url: 'https://hooks.example.com/flow',
              contentType: 'application/json',
              continueMode: 'ON_SUCCESS',
              secret: 'short',
              headers: [],
              parameters: [],
            },
          },
        },
      },
    };

    expect(validateProcessTree(tree).map((issue) => issue.nodeId)).toEqual([
      'delay',
      'trigger',
    ]);
  });

  it('validates the reporting manager level range', () => {
    const tree = (level: number): TreeNode => ({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'approval',
        type: 'APPROVAL',
        props: { assignedType: 'DIRECT_MANAGER', manager: { level }, mode: 'OR' },
      },
    });

    expect(validateProcessTree(tree(2))).toEqual([]);
    expect(validateProcessTree(tree(11))).toContainEqual({
      nodeId: 'approval',
      message: '请配置审批人',
    });
  });

  it('validates conditional parallel branches', () => {
    const tree: TreeNode = {
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'parallel',
        type: 'PARALLEL',
        children: { id: 'join', type: 'EMPTY' },
        branchs: [
          {
            id: 'branch-a',
            type: 'BRANCH',
            props: { conditionMode: 'ALWAYS' },
            children: {
              id: 'approval-a',
              type: 'APPROVAL',
              props: { assignedType: 'SELF' },
            },
          },
          {
            id: 'branch-b',
            type: 'BRANCH',
            props: { conditionMode: 'ALWAYS' },
            children: {
              id: 'approval-b',
              type: 'APPROVAL',
              props: { assignedType: 'SELF' },
            },
          },
        ],
      },
    };

    expect(validateProcessTree(tree)).toEqual([]);
  });

  it('limits the complete process tree depth to fifty nodes', () => {
    const linearTree = (depth: number): TreeNode => {
      let child: TreeNode = {
        id: `node-${depth - 1}`,
        type: 'APPROVAL',
        props: { assignedType: 'SELF' },
      };
      for (let index = depth - 2; index >= 1; index -= 1) {
        child = { id: `node-${index}`, type: 'EMPTY', children: child };
      }
      return { id: 'root', type: 'ROOT', children: child };
    };

    expect(validateProcessTree(linearTree(50))).toEqual([]);
    expect(validateProcessTree(linearTree(51))).toContainEqual({
      nodeId: 'node-50',
      message: '流程树深度不能超过 50 层',
    });
  });

  it('validates SELF_SELECT multiple while accepting legacy missing config', () => {
    const tree = (multiple: unknown, includeConfig = true): TreeNode => ({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'approval',
        type: 'APPROVAL',
        props: {
          assignedType: 'SELF_SELECT',
          ...(includeConfig ? { selfSelect: { multiple } } : {}),
        },
      },
    });

    expect(validateProcessTree(tree(false))).toEqual([]);
    expect(validateProcessTree(tree(undefined, false))).toEqual([]);
    expect(validateProcessTree(tree('true'))).toContainEqual({
      nodeId: 'approval',
      message: '请配置审批人',
    });
  });
});
