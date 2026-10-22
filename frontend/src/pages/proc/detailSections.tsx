import { Typography } from 'antd';

/** 各面板共用的标题：kicker + 标题 + 右侧摘要。 */
export function SectionHeading({
  kicker,
  title,
  id,
  extra,
}: {
  kicker: string;
  title: string;
  id: string;
  extra?: string;
}) {
  return (
    <header className="process-section-heading">
      <div>
        <p>{kicker}</p>
        <h2 id={id}>{title}</h2>
      </div>
      {extra ? <span>{extra}</span> : null}
    </header>
  );
}

export function TechnicalValue({ label, value }: { label: string; value: unknown }) {
  const text = value == null || value === '' ? '—' : String(value);
  return (
    <div>
      <dt>{label}</dt>
      <dd>
        {text === '—' ? text : (
          <Typography.Text copyable={{ text, tooltips: ['复制', '已复制'] }}>
            {text}
          </Typography.Text>
        )}
      </dd>
    </div>
  );
}
