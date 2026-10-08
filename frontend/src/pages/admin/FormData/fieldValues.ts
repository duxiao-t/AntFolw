export type FormDataFieldValue = {
  fieldId: string;
  fieldName: string;
  value: unknown;
  /** 后端按**该记录自己的表单版本**解析好的显示文本（字典、外链选项名都在里面）。 */
  displayText?: string;
  detailText?: string;
};

/**
 * 按首次出现顺序取并集：本页出现过哪些字段，台账就有哪些列。
 *
 * <p>显示文本（下拉选项名、检查项汇总、明细表展开）一律由后端给：数据接口不回字段类型与选项，
 * 前端自己取表单定义既会撞权限（只有 form:data:read 的账号取定义 403），又和导出说不到一块去。
 */
export function fieldColumns(fields: FormDataFieldValue[][]): Array<{ id: string; label: string }> {
  const seen = new Map<string, string>();
  fields.forEach((list) => {
    list.forEach((field) => {
      if (!field.fieldId || seen.has(field.fieldId)) return;
      seen.set(field.fieldId, field.fieldName || field.fieldId);
    });
  });
  return [...seen.entries()].map(([id, label]) => ({ id, label }));
}
