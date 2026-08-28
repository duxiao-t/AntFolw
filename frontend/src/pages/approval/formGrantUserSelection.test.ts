import { describe, expect, it } from 'vitest';
import {
  buildGrantDepartmentTree,
  updateGrantUserSelection,
} from './formGrantUserSelection';

describe('form grant user selection', () => {
  it('builds a department tree from flat rows', () => {
    expect(buildGrantDepartmentTree([
      { id: 1, name: '总部' },
      { id: 2, parentId: 1, name: '研发部' },
      { id: 3, parentId: 2, name: '平台组' },
    ])).toEqual([{
      key: 1,
      title: '总部',
      children: [{
        key: 2,
        title: '研发部',
        children: [{ key: 3, title: '平台组', children: [] }],
      }],
    }]);
  });

  it('keeps selections from earlier pages while adding and removing users', () => {
    const firstPage = updateGrantUserSelection(new Map(), [
      { id: 1, name: 'A' }, { id: 2, name: 'B' },
    ], true);
    const secondPage = updateGrantUserSelection(firstPage, [{ id: 21, name: 'C' }], true);
    const removed = updateGrantUserSelection(secondPage, [{ id: 2, name: 'B' }], false);

    expect([...removed.keys()]).toEqual([1, 21]);
  });
});
