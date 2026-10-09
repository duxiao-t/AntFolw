import { describe, expect, it } from 'vitest';
import fieldTypeSpec from '../../../../../fixtures/field-semantics/field-types.json';
import { registeredFields } from './fieldRegistry';

/**
 * 字段类型对账：后端 `FieldTypeCatalog`（生成 `fixtures/field-semantics/field-types.json`）声明的类型，
 * 移动端注册表必须**要么有渲染器、要么显式列在下面**（与桌面
 * `frontend/src/registry/fieldTypes.generated.test.ts` 同一条约定）。
 *
 * <p>移动端当前是全的：26 种都有渲染器——所以 `radio/checkbox/search/time/switch` 这些桌面缺的类型，
 * 在手机上是正常的。缺口写在这里就是有人认领的东西，不再靠"没人发现"。
 */
const KNOWN_MISSING: string[] = [];

const declared: string[] = (fieldTypeSpec as { fieldTypes: { id: string }[] }).fieldTypes
  .map((type) => type.id);
const local: string[] = registeredFields.map((field) => field.type);

describe('字段类型 spec 对账（后端 FieldTypeCatalog 生成）', () => {
  it('后端声明、移动端没有渲染器的类型必须显式登记', () => {
    const missing = declared.filter((id) => !local.includes(id)).sort();
    expect(missing).toEqual([...KNOWN_MISSING].sort());
  });

  it('移动端注册表里不能有后端不认识的类型', () => {
    const known = new Set(declared);
    expect(local.filter((id) => !known.has(id))).toEqual([]);
  });
});
