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
  hidden?: boolean;
  disabled?: boolean;
  color?: string;
  isOther?: boolean;
};

export type SelectDisplayStyle = 'list' | 'dropdown' | 'block_single' | 'block_double';

export function selectDisplayStyle(node: MobileSchemaNode): SelectDisplayStyle {
  const value = node.props?.displayStyle;
  return value === 'list' || value === 'block_single' || value === 'block_double'
    ? value
    : 'dropdown';
}

export function allFieldOptions(node: MobileSchemaNode): FieldOption[] {
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
    return [{
      label: String(option.label ?? value),
      value,
      hidden: option.hidden === true,
      disabled: option.disabled === true,
      color: typeof option.color === 'string' ? option.color : undefined,
      isOther: option.isOther === true,
    }];
  });
}

export function fieldOptions(node: MobileSchemaNode): FieldOption[] {
  return allFieldOptions(node).filter((option) => !option.hidden);
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

export function InlineFieldOptions({
  label,
  displayStyle,
  options,
  selectedValues,
  multiple,
  useColor,
  maxCount,
  onToggle,
}: {
  label: string;
  displayStyle: Exclude<SelectDisplayStyle, 'dropdown'>;
  options: FieldOption[];
  selectedValues: Array<string | number>;
  multiple: boolean;
  useColor: boolean;
  maxCount?: number;
  onToggle(option: FieldOption, selected: boolean): void;
}) {
  const limitReached = multiple && Number.isInteger(maxCount)
    && (maxCount as number) > 0 && selectedValues.length >= (maxCount as number);
  return (
    <div
      className={`af-select-choices af-select-choices--${displayStyle}`}
      role={multiple ? 'group' : 'radiogroup'}
    >
      {options.map((option) => {
        const selected = selectedValues.includes(option.value);
        const content = (
          <>
            <span className={`af-select-choice__indicator${multiple ? ' is-checkbox' : ''}`} aria-hidden="true" />
            {useColor && option.color ? (
              <span className="af-select-choice__color" style={{ backgroundColor: option.color }} aria-hidden="true" />
            ) : null}
            <span className="af-select-choice__label">{option.label}</span>
          </>
        );
        const disabled = option.disabled || (limitReached && !selected);
        return multiple ? (
          // biome-ignore lint/a11y/useSemanticElements: Styled choice buttons need a full-row touch target.
          <button
            key={String(option.value)}
            type="button"
            role="checkbox"
            aria-checked={selected}
            aria-label={`${label}：${option.label}`}
            disabled={disabled}
            className="af-select-choice"
            title={option.label}
            onClick={() => onToggle(option, selected)}
          >
            {content}
          </button>
        ) : (
          // biome-ignore lint/a11y/useSemanticElements: Styled choice buttons need a full-row touch target.
          <button
            key={String(option.value)}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={`${label}：${option.label}`}
            disabled={disabled}
            className="af-select-choice"
            title={option.label}
            onClick={() => onToggle(option, selected)}
          >
            {content}
          </button>
        );
      })}
    </div>
  );
}
