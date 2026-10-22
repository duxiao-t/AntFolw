import { Empty, Segmented, Typography } from 'antd';
import { useState } from 'react';
import { type ActivityCategory, type ActivityItem, formatDateTime } from './detailPresentation';
import { SectionHeading } from './detailSections';

/** 流转轨迹：类别筛选 + 时间线。筛选只影响本面板，state 就地保留。 */
export function ActivityPanel({ items }: { items: ActivityItem[] }) {
  const [activityFilter, setActivityFilter] = useState<'all' | ActivityCategory>('all');
  const counts = {
    approval: items.filter((item) => item.category === 'approval').length,
    cc: items.filter((item) => item.category === 'cc').length,
    system: items.filter((item) => item.category === 'system').length,
  };
  const filtered = activityFilter === 'all'
    ? items : items.filter((item) => item.category === activityFilter);

  return (
    <section className="process-detail-panel activity-panel">
      <SectionHeading
        kicker="ACTIVITY"
        title="流转轨迹"
        id="activity-title"
        extra={`${items.length} 条记录`}
      />
      <div className="activity-filter">
        <Segmented
          block
          size="small"
          value={activityFilter}
          onChange={(value) => setActivityFilter(value as 'all' | ActivityCategory)}
          options={[
            { label: `全部 ${items.length}`, value: 'all' },
            { label: `审批 ${counts.approval}`, value: 'approval' },
            { label: `抄送 ${counts.cc}`, value: 'cc' },
            { label: `系统 ${counts.system}`, value: 'system' },
          ]}
        />
      </div>
      <ActivityList items={filtered} />
    </section>
  );
}

function ActivityList({ items }: { items: ActivityItem[] }) {
  if (items.length === 0) {
    return (
      <div className="activity-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前筛选下暂无流转记录" />
      </div>
    );
  }
  return (
    <ol className="activity-list">
      {items.map((item) => (
        <li key={item.id} className={`activity-item activity-item--${item.state}`}>
          <span className="activity-item__marker" aria-hidden="true">
            {activityMarker(item.category)}
          </span>
          <article>
            <div className="activity-item__head">
              <div>
                {item.context ? <span className="activity-item__context">{item.context}</span> : null}
                <h3>{item.title}</h3>
              </div>
              <span className="activity-item__status">{item.status}</span>
            </div>
            <div className="activity-item__person">
              <strong>{item.person}</strong>
              {item.identity ? <span>{item.identity}</span> : null}
              {item.source ? <span>{item.source}</span> : null}
            </div>
            <Typography.Paragraph
              className="activity-item__comment"
              ellipsis={{ rows: 2, expandable: 'collapsible', symbol: (expanded) => expanded ? '收起' : '展开' }}
            >
              {item.comment}
            </Typography.Paragraph>
            <footer>
              <span>{activityCategoryLabel(item.category)}</span>
              {item.roundNo > 1 ? <span>第 {item.roundNo} 次提交</span> : null}
              <time>{formatDateTime(item.time)}</time>
            </footer>
          </article>
        </li>
      ))}
    </ol>
  );
}

function activityMarker(category: ActivityCategory) {
  return ({ approval: '审', cc: '抄', system: '系' } as const)[category];
}

function activityCategoryLabel(category: ActivityCategory) {
  return ({ approval: '审批记录', cc: '抄送记录', system: '系统记录' } as const)[category];
}
