import { Typography } from 'antd';

export function PanelField({
  children,
  label,
}: {
  children: React.ReactNode;
  label: string;
}) {
  return (
    <div style={{ display: 'grid', gap: 6 }}>
      <Typography.Text type="secondary">{label}</Typography.Text>
      {children}
    </div>
  );
}
