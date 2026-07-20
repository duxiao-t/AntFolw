import type { PropsWithChildren, ReactNode } from 'react';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { summarizeValue, validateRequired } from '../schema/validators';

type FieldShellProps = PropsWithChildren<{
  label: string;
  controlId?: string;
  required?: boolean;
  error?: string | null;
  summary?: ReactNode;
}>;

export function FieldShell({ label, controlId, required, error, summary, children }: FieldShellProps) {
  return (
    <section
      style={{
        display: 'grid',
        gap: 6,
        padding: '12px 0',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
        {controlId ? (
          <label htmlFor={controlId} style={{ fontSize: 14, fontWeight: 600 }}>
            {label}
          </label>
        ) : (
          <strong style={{ fontSize: 14, fontWeight: 600 }}>{label}</strong>
        )}
        {required ? <span style={{ color: 'var(--af-color-danger)' }}>*</span> : null}
      </div>
      {summary ?? children}
      {error ? (
        <span role="alert" style={{ color: 'var(--af-color-danger)', fontSize: 12 }}>
          {error}
        </span>
      ) : null}
    </section>
  );
}

export function fieldLabel(node: MobileSchemaNode) {
  return node.label ?? node.id;
}

export function fieldError(props: MobileFieldProps) {
  if (props.mode === 'readonly') {
    return null;
  }
  return props.error ?? validateRequired(props.node, props.value);
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
  return <div style={{ minHeight: 32, color: 'rgba(0,0,0,0.72)' }}>{summarizeValue(value)}</div>;
}

export type FieldOption = {
  label: string;
  value: string | number;
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
    return [{ label: String(option.label ?? value), value }];
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
