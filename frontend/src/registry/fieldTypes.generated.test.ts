import { describe, expect, it } from 'vitest';
import fieldTypeSpec from '../../../fixtures/field-semantics/field-types.json';
import { formRegistry } from './formRegistry';

/**
 * 字段类型对账：后端 `FieldTypeCatalog`（生成 `fixtures/field-semantics/field-types.json`）声明的类型，
 * 桌面注册表必须**要么有渲染器、要么显式列在下面**。
 *
 * <p>这条测试的价值在于把"缺口"从"没人发现"变成"写在这里、有人认领"。缺口曾经静默存在过：
 * 后端与移动端 26 种、桌面 21 种，缺的 5 种在桌面开出来是个洞（现在会画显式占位，仍没有渲染器）。
 *
 * <p>两个方向都拦：后端加了新类型 → 第一条红（必须补渲染器或显式登记）；桌面加了后端不认的类型 →
 * 第二条红（那种表单渲染得出来、却存不下去）。
 */
const KNOWN_MISSING = ['checkbox', 'radio', 'search', 'switch', 'time'];

const declared: string[] = (fieldTypeSpec as { fieldTypes: { id: string }[] }).fieldTypes
  .map((type) => type.id);

describe('字段类型 spec 对账（后端 FieldTypeCatalog 生成）', () => {
  it('后端声明、桌面没有渲染器的类型必须显式登记', () => {
    const missing = declared.filter((id) => !formRegistry[id]).sort();
    // 相等而不是"包含"：补上某个渲染器后这条会红，逼着把 KNOWN_MISSING 改小——缺口只减不增。
    expect(missing).toEqual([...KNOWN_MISSING].sort());
  });

  it('桌面注册表里不能有后端不认识的类型', () => {
    const known = new Set(declared);
    expect(Object.keys(formRegistry).filter((id) => !known.has(id))).toEqual([]);
  });
});
