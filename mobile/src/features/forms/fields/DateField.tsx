import { Input } from 'antd-mobile';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';

export function DateField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const format =
    typeof props.node.props?.format === 'string' && props.node.props.format
      ? props.node.props.format
      : 'YYYY-MM-DD';
  const hasTime = format.includes('HH:mm');
  const inputType = hasTime ? 'datetime-local' : 'date';

  // 存储值按 format 字符串保存（如 2026-08-04 10:30）；
  // 原生 input 的 date 需要 YYYY-MM-DD、datetime-local 需要 YYYY-MM-DDTHH:mm。
  const toInputValue = (value: unknown) => {
    const text = stringValue(value);
    if (!text) return '';
    return hasTime ? text.replace(' ', 'T') : text.slice(0, 10);
  };
  const fromInputValue = (value: string) =>
    hasTime ? value.replace('T', ' ') : value;

  return (
    <FieldShell
      node={props.node}
      label={label}
      controlId={props.node.id}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(props.value) : undefined}
    >
      <Input
        id={props.node.id}
        className="af-control"
        type={inputType}
        value={toInputValue(props.value)}
        onChange={(value) => props.onValueChange(props.node.id, fromInputValue(value))}
      />
    </FieldShell>
  );
}
