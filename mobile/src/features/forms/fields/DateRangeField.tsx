import { Input } from 'antd-mobile';
import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, visuallyHiddenStyle } from './fieldShared';

export function DateRangeField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const startId = `${props.node.id}-start`;
  const endId = `${props.node.id}-end`;
  const valueRange = useMemo(() => rangeValue(props.value), [props.value]);
  const [localStart, setLocalStart] = useState(valueRange[0]);
  const [localEnd, setLocalEnd] = useState(valueRange[1]);
  useEffect(() => {
    setLocalStart(valueRange[0]);
    setLocalEnd(valueRange[1]);
  }, [valueRange]);
  const summary = props.mode === 'readonly' ? (
    <div style={{ minHeight: 32, color: 'rgba(0,0,0,0.72)' }}>
      {localStart && localEnd ? `${localStart} 至 ${localEnd}` : '未填写'}
    </div>
  ) : undefined;
  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError({ ...props, value: localStart && localEnd ? [localStart, localEnd] : [] })}
      summary={summary}
    >
      <div className="af-date-range-control date-range-control">
        <label htmlFor={startId} style={visuallyHiddenStyle}>
          {`${label.replace(/时间|日期$/, '')}开始`}
        </label>
        <span className="date-range-control__value">
          <small>开始日期</small>
          <Input
            id={startId}
            className="af-date-range-control__input"
            type="date"
            value={localStart}
            onChange={(value) => {
              setLocalStart(value);
              props.onValueChange(props.node.id, [value, localEnd]);
            }}
          />
        </span>
        <span className="date-range-control__to" aria-hidden="true">至</span>
        <label htmlFor={endId} style={visuallyHiddenStyle}>
          {`${label.replace(/时间|日期$/, '')}结束`}
        </label>
        <span className="date-range-control__value">
          <small>结束日期</small>
          <Input
            id={endId}
            className="af-date-range-control__input"
            type="date"
            value={localEnd}
            onChange={(value) => {
              setLocalEnd(value);
              props.onValueChange(props.node.id, [localStart, value]);
            }}
          />
        </span>
      </div>
    </FieldShell>
  );
}

function rangeValue(value: unknown): [string, string] {
  if (!Array.isArray(value)) {
    return ['', ''];
  }
  return [String(value[0] ?? ''), String(value[1] ?? '')];
}
