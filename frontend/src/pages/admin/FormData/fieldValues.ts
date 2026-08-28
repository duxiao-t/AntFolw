export type FormDataFieldValue = {
  fieldId: string;
  fieldName: string;
  value: unknown;
};

export function formatFieldValue(value: unknown) {
  if (value == null || value === '') return '—';
  if (typeof value === 'string') return value;
  return JSON.stringify(value) ?? String(value);
}

export function formatFieldSummary(fields?: FormDataFieldValue[]) {
  if (!fields?.length) return '—';
  return fields
    .map((field) => `${field.fieldName}（${field.fieldId}）：${formatFieldValue(field.value)}`)
    .join('；');
}
