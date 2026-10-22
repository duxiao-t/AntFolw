import type { ProgressStage } from './detailPresentation';
import { SectionHeading } from './detailSections';

export function ProcessProgressPanel({ stages }: { stages: ProgressStage[] }) {
  return (
    <section className="process-detail-panel process-progress-panel" aria-labelledby="process-progress-title">
      <SectionHeading
        kicker="FLOW ROUTE"
        title="流程进度"
        id="process-progress-title"
        extra={`${stages.filter((stage) => stage.state === 'done').length}/${stages.length} 个阶段完成`}
      />
      <div className="process-rail-scroll">
        <ol className="process-rail">
          {stages.map((stage, index) => (
            <li
              key={stage.id}
              className={`process-stage process-stage--${stage.state}`}
              aria-current={stage.state === 'current' ? 'step' : undefined}
            >
              <div className="process-stage__track" aria-hidden="true">
                <span>{stage.state === 'done' ? '✓' : index + 1}</span>
              </div>
              <strong title={stage.label}>{stage.label}</strong>
              <small title={stage.detail}>{stage.detail}</small>
              <em>{progressStateLabel(stage.state)}</em>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

function progressStateLabel(state: ProgressStage['state']) {
  return ({ done: '已完成', current: '处理中', waiting: '待到达', error: '异常结束' } as const)[state];
}
