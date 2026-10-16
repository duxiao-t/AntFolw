import { describe, expect, it } from 'vitest';
import fixtureJson from '../../../../../fixtures/field-semantics/visibility.json';
import type { MobileFormValues, MobileSchemaNode } from './types';
import { visibleNodeIds } from './validators';

/**
 * 可见性口径的**共同约定**测试：与桌面 `frontend/src/registry/displayConditions.fixture.test.ts`
 * 读同一份 `fixtures/field-semantics/visibility.json`。
 *
 * <p>移动端这份实现曾经是桌面"边走边判"的旧版拷贝，**声明顺序**一变结果就不同（前向引用）；
 * 数值比较也没先拒空值（空值满足 `gte 0`）。两个漂移都是这个 fixture 抓到的——复制不漂移的办法
 * 不是靠自律，是靠同一份期望同时跑两端。
 *
 * <p>移动端只读渲染、不保存 schema，所以这里不断言环路径（环由保存/发布路径拒绝，
 * 见后端 `FormDefinitionServiceSchemaTest` 与桌面 `findDisplayConditionCycle`）。
 */
type FixtureCase = {
  id: string;
  schema: MobileSchemaNode[];
  values: MobileFormValues;
  expect: { visible: string[] };
};

const fixture = fixtureJson as { cases: FixtureCase[] };

const sorted = (ids: Iterable<string>) => [...ids].sort();

describe('字段可见性口径（与桌面共用 fixture）', () => {
  for (const testCase of fixture.cases) {
    it(testCase.id, () => {
      expect(sorted(visibleNodeIds(testCase.schema, testCase.values)))
        .toEqual(sorted(testCase.expect.visible));
    });
  }
});
