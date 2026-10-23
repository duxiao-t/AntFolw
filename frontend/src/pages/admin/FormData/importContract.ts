/** 后端冻结的导入契约：字段名照抄，前端不另起别名。 */

export type RowIssue = {
  row: number;
  fieldId?: string | null;
  fieldLabel?: string | null;
  code: string;
  message: string;
};

export type ColumnReport = {
  fieldId: string;
  label: string;
  type: string;
  required: boolean;
  supported: boolean;
  order: number;
};

export type ImportInspection = {
  templateVersion: string;
  formId: number;
  formCode: string;
  formName: string;
  formVersion: number;
  sheets: string[];
  sheetName?: string | null;
  columns: ColumnReport[];
  missingColumns: string[];
  rowCount: number;
  importableCount: number;
  errorCount: number;
  skippedCount: number;
  issues: RowIssue[];
  issuesTruncated: boolean;
  previews: Record<string, unknown>[];
  warnings: string[];
};

export type ImportOutcome = {
  formId: number;
  batchId: string;
  atomic: boolean;
  totalRows: number;
  successCount: number;
  failureCount: number;
  skippedCount: number;
  createdIds: number[];
  failures: RowIssue[];
  failuresTruncated: boolean;
  warnings: string[];
};

/** 422 的业务码翻成人话；码不认识就退回后端 message。 */
const ERROR_TEXT: Record<string, string> = {
  IMPORT_FORMAT_UNSUPPORTED: '只支持 .xlsx 文件',
  IMPORT_FILE_TOO_LARGE: '文件超过大小上限，请拆分后分批导入',
  IMPORT_FILE_UNREADABLE: '文件无法读取，请确认没有加密或损坏',
  IMPORT_TEMPLATE_INVALID: '导入模板无效，请重新下载模板',
  IMPORT_TEMPLATE_MISMATCH: '文件与所选表单的模板不一致，请重新下载模板',
  IMPORT_EMPTY: '文件里没有可导入的数据',
  IMPORT_ROW_LIMIT: '行数超过单次导入上限，请分批导入',
  IMPORT_SHEET_REQUIRED: '文件包含多个工作表，请选择一个工作表',
  IMPORT_FIELD_UNKNOWN: '文件包含表单里没有的字段',
  IMPORT_FIELD_UNSUPPORTED: '文件包含不支持导入的字段',
  IMPORT_FORM_NOT_PUBLISHED: '该表单尚未发布，不能导入数据',
  IMPORT_VALIDATION_FAILED: '存在校验不通过的行',
  IMPORT_DUPLICATE: '存在重复数据',
};

export const errorCode = (error: any): string | undefined => error?.response?.data?.code;

export const errorText = (error: any, fallback: string): string =>
  ERROR_TEXT[errorCode(error) ?? ''] ?? error?.message ?? fallback;

/** IMPORT_SHEET_REQUIRED 时预检没有结果，但错误体可能带 sheets，用它把下拉补出来。 */
export function sheetsFromError(error: any): string[] {
  const data = error?.response?.data;
  const sheets = data?.sheets ?? data?.details?.sheets;
  return Array.isArray(sheets) ? sheets.filter((item: unknown) => typeof item === 'string') : [];
}

export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 预览里的原始值可能是对象（多选、明细），直接 JSON 化，不吞内容。 */
export const cellText = (value: unknown): string =>
  value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value);
