import { describe, expect, it } from 'vitest';
import { buildFormTemplate, parseFormTemplate } from './formTemplate';

describe('form templates', () => {
  it('exports only the reusable form structure and validates imports', () => {
    const template = buildFormTemplate({
      name: '请假申请',
      description: '请填写请假信息',
      schema: [{ id: 'reason', type: 'text', label: '原因' }],
    });

    expect(template).toEqual({
      format: 'antflow-form-template',
      version: 1,
      name: '请假申请',
      description: '请填写请假信息',
      schema: [{ id: 'reason', type: 'text', label: '原因' }],
    });
    expect(parseFormTemplate(JSON.stringify(template))).toEqual(template);
    expect(() =>
      parseFormTemplate(JSON.stringify({ ...template, version: 2 })),
    ).toThrow('不支持的表单模板格式或版本');
    expect(() =>
      parseFormTemplate(
        JSON.stringify({
          ...template,
          schema: [{ id: 'reason', type: 'unknown' }],
        }),
      ),
    ).toThrow('模板包含无法识别的表单字段');
  });
});
