import type { PropsWithChildren, ReactNode } from 'react';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import {
  fieldDescription,
  fieldHelp,
  fieldLabel as schemaFieldLabel,
  summarizeValue,
  validateCommonRules,
} from '../schema/validators';

type FieldShellProps = PropsWithChildren<{
  label: string;
  node?: MobileSchemaNode;
  controlId?: string;
  required?: boolean;
  error?: string | null;
  summary?: ReactNode;
  help?: ReactNode;
  description?: ReactNode;
  className?: string;
}>;

export function FieldShell({
  label,
  node,
  controlId,
  required,
  error,
  summary,
  help,
  description,
  className,
  children,
}: FieldShellProps) {
  const resolvedDescription = description ?? (node ? fieldDescription(node) : null);
  const resolvedHelp = help ?? (node ? fieldHelp(node) : null);
  const shellClassName = [
    'af-field',
    error ? 'af-field--error' : '',
    summary ? 'af-field--readonly' : '',
    className ?? '',
  ].filter(Boolean).join(' ');
  return (
    <section
      className={shellClassName}
      data-field-id={node?.id}
    >
      <div className="af-field__head">
        {controlId ? (
          <label htmlFor={controlId} className="af-field__label">
            {label}
          </label>
        ) : (
          <strong className="af-field__label">{label}</strong>
        )}
        {required ? <span className="af-field__required">*</span> : null}
      </div>
      {resolvedDescription ? <p className="af-field__desc">{resolvedDescription}</p> : null}
      {summary ?? children}
      {resolvedHelp ? <p className="af-field__help">{resolvedHelp}</p> : null}
      {error ? (
        <span role="alert" className="af-field__error">
          {error}
        </span>
      ) : null}
    </section>
  );
}

export function fieldLabel(node: MobileSchemaNode) {
  return schemaFieldLabel(node);
}

export function fieldError(props: MobileFieldProps) {
  if (props.mode === 'readonly') {
    return null;
  }
  return props.error ?? validateCommonRules(props.node, props.value);
}

export function isRequired(node: MobileSchemaNode) {
  return node.props?.required === true;
}

export function stringValue(value: unknown) {
  if (value == null) {
    return '';
  }
  return typeof value === 'string' ? value : String(value);
}

export function readonlySummary(value: unknown) {
  return <div className="af-field__summary">{summarizeValue(value)}</div>;
}

export type FieldOption = {
  label: string;
  value: string | number;
  disabled?: boolean;
};

export function fieldOptions(node: MobileSchemaNode): FieldOption[] {
  const options = node.props?.options;
  if (!Array.isArray(options)) {
    return [];
  }
  return options.flatMap((item) => {
    if (typeof item !== 'object' || item == null) {
      return [];
    }
    const option = item as Record<string, unknown>;
    const value = option.value;
    if (typeof value !== 'string' && typeof value !== 'number') {
      return [];
    }
    return [{ label: String(option.label ?? value), value, disabled: option.disabled === true }];
  });
}

export function optionLabel(node: MobileSchemaNode, value: unknown) {
  const hit = fieldOptions(node).find((option) => option.value === value);
  return hit?.label ?? summarizeValue(value);
}

export const visuallyHiddenStyle = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
} as const;
