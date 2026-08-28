import { describe, expect, it } from 'vitest';
import { formatFieldSummary, formatFieldValue } from './fieldValues';

describe('admin form data formatting', () => {
  it('shows field names, ids and contents in the list summary', () => {
    expect(formatFieldSummary([
      { fieldId: 'applicant', fieldName: '申请人', value: '张三' },
      { fieldId: 'amount', fieldName: '金额', value: 128.5 },
    ])).toBe('申请人（applicant）：张三；金额（amount）：128.5');
  });

  it('keeps structured field contents readable and marks empty values', () => {
    expect(formatFieldValue({ status: 'pass', images: [] }))
      .toBe('{"status":"pass","images":[]}');
    expect(formatFieldValue(null)).toBe('—');
    expect(formatFieldSummary()).toBe('—');
  });
});
