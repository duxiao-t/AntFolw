import { Radio, Typography } from 'antd';
import type { FieldPerm, FieldPermMode, FormFieldOption } from '../types';

const EDITABLE_FORBIDDEN_TYPES = new Set([
  'image_upload',
  'video_upload',
  'file_upload',
  'audio_upload',
  'location',
  'checklist',
]);

const MODE_OPTIONS: { label: string; value: FieldPermMode }[] = [
  { label: '只读', value: 'READONLY' },
  { label: '隐藏', value: 'HIDDEN' },
  { label: '可编辑', value: 'EDITABLE' },
];

export function FieldPermissionEditor({
  formFields,
  value,
  onChange,
  defaultMode = 'READONLY',
  allowComplexEditable = false,
}: {
  formFields: FormFieldOption[];
  value?: FieldPerm[];
  onChange(perms: FieldPerm[]): void;
  defaultMode?: FieldPermMode;
  allowComplexEditable?: boolean;
}) {
  const perms = new Map((value ?? []).map((entry) => [entry.fieldId, entry.mode]));

  const update = (fieldId: string, mode: FieldPermMode) => {
    const next = new Map(perms);
    if (mode === defaultMode) {
      next.delete(fieldId);
    } else {
      next.set(fieldId, mode);
    }
    const allowedIds = new Set(formFields.map((field) => field.id));
    onChange(
      Array.from(next.entries())
        .filter(([id]) => allowedIds.has(id))
        .map(([id, nextMode]) => ({ fieldId: id, mode: nextMode })),
    );
  };

  return (
    <div style={{ display: 'grid', gap: 8 }}>
      {formFields.length === 0 ? (
        <Typography.Text type="secondary">表单还没有可配置的字段</Typography.Text>
      ) : (
        formFields.map((field) => {
          const editableDisabled =
            !allowComplexEditable && EDITABLE_FORBIDDEN_TYPES.has(field.type);
          return (
            <div
              key={field.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
                padding: '6px 0',
                borderBottom: '1px solid #f0f0f0',
              }}
            >
              <span
                style={{
                  minWidth: 0,
                  flex: 1,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {field.label}
                <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
                  {field.type}
                </Typography.Text>
              </span>
              <Radio.Group
                size="small"
                value={perms.get(field.id) ?? defaultMode}
                onChange={(event) => update(field.id, event.target.value)}
              >
                {MODE_OPTIONS.map((option) => (
                  <Radio.Button
                    key={option.value}
                    value={option.value}
                    disabled={editableDisabled && option.value === 'EDITABLE'}
                  >
                    {option.label}
                  </Radio.Button>
                ))}
              </Radio.Group>
            </div>
          );
        })
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        未配置的字段默认{defaultMode === 'EDITABLE' ? '可编辑' : '只读'}；
        {!allowComplexEditable ? '附件/图片/视频/检查项暂不支持审批时编辑。' : ''}
      </Typography.Text>
    </div>
  );
}
