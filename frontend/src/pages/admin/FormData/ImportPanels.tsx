import { Table, Typography } from 'antd';
import type { CSSProperties } from 'react';
import type { RowIssue } from './importContract';

const STAT: CSSProperties = {
  minWidth: 132,
  padding: '10px 14px',
  border: '1px solid var(--af-color-line)',
  borderRadius: 8,
  background: 'var(--af-color-surface)',
};

/** 三色统计卡：数字比一句话更快被读懂，成功/失败各用语义色。 */
export function Stat({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div style={STAT} role="group" aria-label={label}>
      <div style={{ fontSize: 12, color: 'var(--af-color-muted)' }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, color }}>{value}</div>
    </div>
  );
}

type IssueRow = { key: number; issue: RowIssue };

/** 预检错误与导入失败明细共用一张表：结构一样，样式就不该有两套。 */
export function IssueTable({ issues, maxHeight = 200 }: { issues: RowIssue[]; maxHeight?: number }) {
  return (
    <Table<IssueRow>
      size="small"
      pagination={false}
      scroll={{ y: maxHeight }}
      rowKey="key"
      dataSource={issues.map((issue, key) => ({ key, issue }))}
      columns={[
        { title: '行号', width: 70, dataIndex: ['issue', 'row'] },
        { title: '字段', width: 150, render: (_, item) => item.issue.fieldLabel ?? item.issue.fieldId ?? '—' },
        {
          title: '原因',
          render: (_, item) => (
            <span>
              {item.issue.message}
              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>{item.issue.code}</Typography.Text>
            </span>
          ),
        },
      ]}
    />
  );
}
