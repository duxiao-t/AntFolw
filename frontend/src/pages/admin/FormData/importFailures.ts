import type { RowIssue } from './importContract';

const HEADER = ['行号', '字段', '原因编码', '原因'] as const;

/** CSV 单元格：含逗号、引号、换行时必须整体加引号，内部引号翻倍。 */
function cell(value: unknown): string {
  const text = value == null ? '' : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * 失败明细转 CSV。开头写 BOM：Excel 双击打开 UTF-8 的 CSV 时才不会把中文读成乱码。
 * 行尾用 CRLF，与 Excel 自己导出的文件一致。
 */
export function failuresToCsv(issues: readonly RowIssue[]): string {
  const lines = issues.map((issue) => [
    issue.row,
    issue.fieldLabel ?? issue.fieldId ?? '',
    issue.code,
    issue.message,
  ].map(cell).join(','));
  return `\uFEFF${[HEADER.join(','), ...lines].join('\r\n')}\r\n`;
}

/** 直接在前端下载，不再为一份明细新增后端端点。 */
export function downloadImportFailures(issues: readonly RowIssue[], filename: string): void {
  const url = URL.createObjectURL(new Blob([failuresToCsv(issues)], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
