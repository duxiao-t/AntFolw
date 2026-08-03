import { Switch } from 'antd-mobile';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';

export function SwitchField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const checked = props.value === true;
  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{checked ? '是' : '否'}</div> : undefined}
    >
      <div className="af-switch-row">
        <span>{String(props.node.props?.uncheckedText ?? '否')}</span>
        <Switch
          checked={checked}
          checkedText={props.node.props?.checkedText as string | undefined}
          uncheckedText={props.node.props?.uncheckedText as string | undefined}
          onChange={(next) => props.onValueChange(props.node.id, next)}
        />
        <span>{String(props.node.props?.checkedText ?? '是')}</span>
      </div>
    </FieldShell>
  );
}
