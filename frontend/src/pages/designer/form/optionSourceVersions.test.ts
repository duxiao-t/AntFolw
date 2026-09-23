import { describe, expect, it } from 'vitest';
import { type BindableSource, versionOptionGroups } from './optionSourceVersions';

function source(id: number, name: string, versionNo: number, latestVersionNo: number,
  rowCount = 13): BindableSource {
  return { id, code: `s${id}`, name, versionId: id * 100 + versionNo, versionNo,
    columns: ['列'], rowCount, latestVersionNo };
}

describe('版本候选', () => {
  it('默认每个源只出最新版，旧版收起来', () => {
    const groups = versionOptionGroups([source(3, 'ces', 2, 2), source(3, 'ces', 1, 2)],
      { showHistory: false });
    expect(groups).toEqual([{ label: 'ces', options: [{ value: 302, label: 'v2 · 最新 · 13 行' }] }]);
  });

  it('勾上历史版本后全出，组内版本号倒序', () => {
    const groups = versionOptionGroups([source(3, 'ces', 2, 2), source(3, 'ces', 1, 2)],
      { showHistory: true });
    expect(groups[0].options).toEqual([
      { value: 302, label: 'v2 · 最新 · 13 行' },
      { value: 301, label: 'v1 · 13 行' },
    ]);
  });

  it('已绑定的旧版一定在，否则选择框会空掉', () => {
    const groups = versionOptionGroups([source(3, 'ces', 2, 2), source(3, 'ces', 1, 2)],
      { boundVersionId: 301, showHistory: false });
    expect(groups[0].options.map((option) => option.value)).toEqual([302, 301]);
  });

  it('按源分组，源之间按名称排序', () => {
    const groups = versionOptionGroups([
      source(3, 'ces', 2, 2), source(7, '招聘表', 5, 5),
    ], { showHistory: false });
    expect(groups.map((group) => group.label)).toEqual(['ces', '招聘表']);
  });

  it('没有已发布版本时给空分组，不炸', () => {
    expect(versionOptionGroups([], { showHistory: false })).toEqual([]);
  });
});
