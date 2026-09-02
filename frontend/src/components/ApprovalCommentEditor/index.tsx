import { Button, Flex, Input } from 'antd';
import { request } from '@umijs/max';

export type ApprovalCommentPresets = {
  approve: string[];
  reject: string[];
};

export async function fetchApprovalCommentPresets(
  taskId: number,
): Promise<ApprovalCommentPresets> {
  return request(`/api/tasks/${taskId}/comment-presets`);
}

export function ApprovalCommentEditor({
  action,
  presets,
  value,
  onChange,
  rows = 4,
}: {
  action: 'approve' | 'reject';
  presets?: ApprovalCommentPresets;
  value: string;
  onChange(value: string): void;
  rows?: number;
}) {
  const options = presets?.[action] ?? [];
  return (
    <Flex vertical gap="middle" style={{ width: '100%' }}>
      {options.length > 0 && (
        <Flex gap={8} wrap aria-label={action === 'approve' ? '同意意见预设' : '驳回意见预设'}>
          {options.map((option) => (
            <Button
              key={option}
              size="small"
              type={value === option ? 'primary' : 'default'}
              danger={action === 'reject' && value === option}
              onClick={() => onChange(option)}
            >
              {option}
            </Button>
          ))}
        </Flex>
      )}
      <Input.TextArea
        rows={rows}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={action === 'approve' ? '审批意见（可选）' : '请说明驳回原因'}
      />
    </Flex>
  );
}
