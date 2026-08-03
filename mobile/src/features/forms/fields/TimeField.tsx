import { Picker } from 'antd-mobile';
import { ClockCircleOutline } from 'antd-mobile-icons';
import { useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';

const hourColumn = Array.from({ length: 24 }, (_, hour) => ({
  label: `${String(hour).padStart(2, '0')}时`,
  value: String(hour).padStart(2, '0'),
}));
const minuteColumn = Array.from({ length: 60 }, (_, minute) => ({
  label: `${String(minute).padStart(2, '0')}分`,
  value: String(minute).padStart(2, '0'),
}));

export function TimeField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const [visible, setVisible] = useState(false);
  const value = stringValue(props.value);
  const pickerValue = useMemo(() => {
    const [hour = '', minute = ''] = value.split(':');
    return [hour || '09', minute || '00'];
  }, [value]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(props.value) : undefined}
    >
      <button
        type="button"
        className={`control form-picker af-field-picker${value ? '' : ' af-field-picker--placeholder'}`}
        onClick={() => setVisible(true)}
      >
        <span>{value || String(props.node.props?.placeholder ?? '请选择时间')}</span>
        <span className="picker-icon-slot" aria-hidden="true">
          <ClockCircleOutline />
        </span>
      </button>
      <Picker
        title={label}
        columns={[hourColumn, minuteColumn]}
        visible={visible}
        value={pickerValue}
        onClose={() => setVisible(false)}
        onConfirm={(next) => {
          props.onValueChange(props.node.id, `${next[0]}:${next[1]}`);
        }}
      />
    </FieldShell>
  );
}
