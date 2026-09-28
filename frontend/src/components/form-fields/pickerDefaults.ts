import type { SchemaNode } from '../../registry/types';

/** 取当前用户/当前部门只需要这两个字段，其余（姓名、工号）是显示用的。 */
export type CurrentUserRef = { id?: unknown; departmentId?: unknown };

/**
 * 「默认填充当前用户 / 当前部门」的取值。
 *
 * 只有设计器显式勾了 `props.defaultToCurrent` 的选择器才有默认值：靠字段类型分用户还是部门，
 * 不去猜范围（`scopeType`）——范围是候选清单，默认值是选中的那个，两者独立。
 *
 * 多选也**只填一个**：`maxCount` 至少是 1，预填 1 个不会越界，也不需要用户来删。
 * 部署时 /api/auth/me 没有部门（`departmentId` 为 `""`）时返回 undefined，交给用户自己选。
 */
export function currentPickerDefault(
  node: SchemaNode,
  current: CurrentUserRef | undefined,
): number | number[] | undefined {
  if (node.props?.defaultToCurrent !== true) return undefined;
  const raw = node.type === 'dept_picker' ? current?.departmentId : current?.id;
  const id = Number(raw);
  if (raw == null || !Number.isInteger(id) || id <= 0) return undefined;
  return node.props?.multiple === true ? [id] : id;
}
