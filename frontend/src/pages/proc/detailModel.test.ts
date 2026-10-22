import { describe, expect, it } from 'vitest';
import { buildFieldModes, findApproverNodes, parseJsonValue } from './detailModel';
import type { ProcessNode } from './detailPresentation';

const node = (overrides: Partial<ProcessNode>): ProcessNode => ({
  id: 'n1',
  type: 'APPROVAL',
  ...overrides,
});

describe('findApproverNodes', () => {
  it('collects approval nodes but skips those inside parallel branches', () => {
    const root = node({
      id: 'root',
      type: 'ROOT',
      children: node({
        id: 'a1',
        children: node({
          id: 'p1',
          type: 'PARALLEL',
          branchs: [node({ id: 'b1', type: 'BRANCH', children: node({ id: 'a2' }) })],
          children: node({ id: 'a3', children: node({ id: 'a4' }) }),
        }),
      }),
    });

    expect(findApproverNodes(root).map((item) => item.id)).toEqual(['a1', 'a3', 'a4']);
  });
});

describe('buildFieldModes', () => {
  const snapshot = node({
    id: 'root',
    props: { formPerms: [{ fieldId: 'secret', mode: 'HIDDEN' }, { fieldId: 'name', mode: 'EDITABLE' }] },
    children: node({
      id: 'a1',
      props: { formPerms: [
        { fieldId: 'amount', mode: 'EDITABLE' },
        { fieldId: 'secret', mode: 'HIDDEN' },
        { fieldId: 'name', mode: 'READONLY' },
      ] },
    }),
  });

  it('keeps the starter view readonly even when ROOT is EDITABLE', () => {
    expect(buildFieldModes({ snapshot, currentUserId: 7, startedBy: 7 })).toEqual({
      secret: 'hidden',
      name: 'readonly',
    });
  });

  it('applies the pending node permissions for the assignee', () => {
    expect(buildFieldModes({ snapshot, pendingNodeId: 'a1', currentUserId: 7, startedBy: 1 })).toEqual({
      amount: 'runtime-fill',
      secret: 'hidden',
      name: 'readonly',
    });
  });

  it('returns empty modes without a snapshot', () => {
    expect(buildFieldModes({ snapshot: null, pendingNodeId: 'a1' })).toEqual({});
  });
});

describe('parseJsonValue', () => {
  it('parses json strings and passes objects through', () => {
    expect(parseJsonValue('[{"id":"a"}]', [])).toEqual([{ id: 'a' }]);
    expect(parseJsonValue({ a: 1 }, {})).toEqual({ a: 1 });
  });

  it('falls back on empty or invalid values', () => {
    expect(parseJsonValue(undefined, [])).toEqual([]);
    expect(parseJsonValue('{oops', [])).toEqual([]);
  });
});
