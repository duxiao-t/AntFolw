import type { SchemaNode } from '../../registry/types';

export type ChoiceOption = { value: string | number; label: string; color?: string };

/**
 * `radio` / `checkbox` 的选项读取：**保留选项原本的 value**。
 *
 * <p>**不要**换成 `normalizeSelectOptions`（`registry/selectOptions.ts`）：它会把 `isOther` 选项的 value
 * 改写成 `__antflow_other__`，而移动端 `fieldShared.tsx` 保留原值。导入自移动端的
 * `{ value: 'other', label: '其他', isOther: true }` 已经按 `other` 存进了 `t_form_data`，
 * 一旦改写成哨兵值，已提交的数据就再也匹配不上选项了。
 *
 * <p>顺带也丢掉 `id/color/isOther`：radio/checkbox 目前是普通静态选项，不需要这些属性。
 * 若将来要支持「其他」这种可填写选项，应该在这里显式加，而不是借用下拉那套。
 */
export function choiceOptions(node: SchemaNode): ChoiceOption[] {
  const raw = node.props?.options;
  if (!Array.isArray(raw)) return [];
  const options: ChoiceOption[] = [];
  for (const item of raw) {
    if (typeof item !== 'object' || item == null) continue;
    const source = item as Record<string, unknown>;
    const value = source.value;
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    if (source.hidden === true) continue;
    options.push({
      value,
      label: String(source.label ?? value),
      color: typeof source.color === 'string' ? source.color : undefined,
    });
  }
  return options;
}

/**
 * 选项配色：只在字段开了 `enableOptionColor` 时生效。
 * 编辑器里那个「配色」开关是 `SelectOptionsEditor` 的必填 prop，不接就会变成一个点了没反应的控件。
 */
export function optionColor(
  props: SchemaNode['props'],
  option: ChoiceOption,
): { color: string } | undefined {
  return props?.enableOptionColor === true && option.color ? { color: option.color } : undefined;
}

/** 只读态显示：命中选项给标签，没命中给原值，空给「未填写」。 */
export function choiceReadonlyText(options: ChoiceOption[], value: unknown): string {
  if (value === undefined || value === null || value === '') return '未填写';
  const label = options.find((option) => option.value === value)?.label;
  return label ?? String(value);
}
