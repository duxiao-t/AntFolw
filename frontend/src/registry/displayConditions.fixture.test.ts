import { describe, expect, it } from 'vitest';
import fixtureJson from '../../../fixtures/field-semantics/visibility.json';
import { findDisplayConditionCycle, visibleNodeIds } from './displayConditions';
import type { SchemaNode } from './types';

/**
 * 可见性口径的**共同约定**测试：桌面、移动端、后端读同一份
 * `fixtures/field-semantics/visibility.json`（移动端对应 `validators.fixture.test.ts`，
 * 后端对应 `FormDefinitionServiceSchemaTest` 的环用例）。
 *
 * <p>期望值来自 fixture 里写明的规则，**不取自本实现**。实现与 fixture 不一致时改实现——
 * 只有口径本身变了才动期望，而且要三端一起动。这个文件存在的理由：可见性曾经在桌面与移动端
 * 漂移过两个版本（前向引用、空值比较），没人发现，直到两端显示不一致才被当成"偶发 bug"。
 */
type FixtureCase = {
  id: string;
  intent: string;
  schema: SchemaNode[];
  values: Record<string, unknown>;
  expect: { visible: string[]; cycle?: string[] };
};

const fixture = fixtureJson as { cases: FixtureCase[] };

const sorted = (ids: Iterable<string>) => [...ids].sort();

describe('字段可见性口径（三端共用 fixture）', () => {
  for (const testCase of fixture.cases) {
    it(testCase.id, () => {
      // 顺序不是约定的一部分，比较前排序。
      expect(sorted(visibleNodeIds(testCase.schema, testCase.values)))
        .toEqual(sorted(testCase.expect.visible));
      // 互指/自指：桌面必须报出环路径（后端同口径拒绝），且**不许抛 RangeError**。
      expect(findDisplayConditionCycle(testCase.schema)).toEqual(testCase.expect.cycle ?? null);
    });
  }
});
