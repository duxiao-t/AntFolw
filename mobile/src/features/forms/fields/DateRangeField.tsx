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
      label={label}
      required={isRequired(props.node)}
      error={fieldError({ ...props, value: localStart && localEnd ? [localStart, localEnd] : [] })}
      summary={summary}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
        <label htmlFor={startId} style={visuallyHiddenStyle}>
          {`${label.replace(/时间|日期$/, '')}开始`}
        </label>
        <Input
          id={startId}
          type="date"
          value={localStart}
          onChange={(value) => {
            setLocalStart(value);
            props.onValueChange(props.node.id, [value, localEnd]);
          }}
        />
        <label htmlFor={endId} style={visuallyHiddenStyle}>
          {`${label.replace(/时间|日期$/, '')}结束`}
        </label>
        <Input
          id={endId}
          type="date"
          value={localEnd}
          onChange={(value) => {
            setLocalEnd(value);
            props.onValueChange(props.node.id, [localStart, value]);
          }}
        />
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
