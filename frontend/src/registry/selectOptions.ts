export type SelectOptionValue = string | number;

export const SELECT_DISPLAY_STYLES = [
  'list',
  'dropdown',
  'block_single',
  'block_double',
] as const;

export type SelectDisplayStyle = (typeof SELECT_DISPLAY_STYLES)[number];

export function normalizeSelectDisplayStyle(value: unknown): SelectDisplayStyle {
  return SELECT_DISPLAY_STYLES.includes(value as SelectDisplayStyle)
    ? (value as SelectDisplayStyle)
    : 'dropdown';
}

export type SelectOption = {
  id?: string;
  label: string;
  value: SelectOptionValue;
  hidden?: boolean;
  disabled?: boolean;
  color?: string;
  isOther?: boolean;
};

export const OTHER_OPTION_VALUE = '__antflow_other__';

export const SELECT_OPTION_COLORS = [
  { name: '绿色', value: '#12B76A' },
  { name: '红色', value: '#F04438' },
  { name: '蓝色', value: '#1677FF' },
  { name: '橙色', value: '#F79009' },
  { name: '紫色', value: '#7F56D9' },
  { name: '灰色', value: '#98A2B3' },
] as const;

export function createDefaultSelectOptions(): SelectOption[] {
  return [1, 2, 3].map((index) => ({
    id: `option_${index}`,
    label: `选项${index}`,
    value: `option_${index}`,
  }));
}

export function createSelectOption(index: number, color?: string): SelectOption {
  const suffix = Math.max(index, 1);
  const id = `option_${Date.now()}_${suffix}`;
  return {
    id,
    label: `选项${suffix}`,
    value: id,
    color,
  };
}

export function normalizeSelectOptions(value: unknown): SelectOption[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item, index) => {
    if (typeof item !== 'object' || item == null) return [];
    const source = item as Record<string, unknown>;
    const rawValue = source.value ?? source.label;
    const value =
      typeof rawValue === 'string' || typeof rawValue === 'number'
        ? rawValue
        : `option_${index + 1}`;
    const label = source.label == null ? String(value) : String(source.label);
    const isOther = source.isOther === true;
    return [
      {
        id:
          typeof source.id === 'string' && source.id.trim()
            ? source.id
            : `option_${index}_${String(value)}`,
        label: isOther ? label || '其他' : label,
        value: isOther ? OTHER_OPTION_VALUE : value,
        hidden: source.hidden === true,
        disabled: source.disabled === true,
        color: typeof source.color === 'string' ? source.color : undefined,
        isOther,
      },
    ];
  });
}

export function visibleSelectOptions(value: unknown): SelectOption[] {
  return normalizeSelectOptions(value).filter((option) => !option.hidden);
}

export function isOptionValue(value: unknown): value is SelectOptionValue {
  return typeof value === 'string' || typeof value === 'number';
}

export function defaultValues(value: unknown, multiple: boolean): SelectOptionValue[] {
  if (multiple) {
    return Array.isArray(value) ? value.filter(isOptionValue) : [];
  }
  return isOptionValue(value) ? [value] : [];
}

export function normalizeDefaultValue(
  value: unknown,
  options: SelectOption[],
  multiple: boolean,
): SelectOptionValue | SelectOptionValue[] | undefined {
  const allowed = new Set(
    options.filter((option) => !option.hidden && !option.isOther).map((option) => option.value),
  );
  const values = defaultValues(value, multiple).filter((item) => allowed.has(item));
  if (multiple) return values;
  return values[0];
}

function cleanOptionText(value: string) {
  return value
    .trim()
    .replace(/^[(（]?\d+[)）.、]\s*/, '')
    .replace(/^[-•]\s*/, '')
    .trim();
}

export function parseBulkSelectOptions(text: string): SelectOption[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const cleanedLine = cleanOptionText(line);
      const tabParts = cleanedLine.split('\t');
      if (tabParts.length === 2) {
        const [label, value] = tabParts.map((item) => item.trim());
        return { label: cleanOptionText(label ?? ''), value: cleanOptionText(value || label || ''), id: `bulk_${index}` };
      }
      const pipeParts = cleanedLine.split('|');
      if (pipeParts.length === 2) {
        const [value, label] = pipeParts.map((item) => item.trim());
        return { label: cleanOptionText(label || value || ''), value: cleanOptionText(value || label || ''), id: `bulk_${index}` };
      }
      return { label: cleanedLine, value: cleanedLine, id: `bulk_${index}` };
    })
    .filter((item) => item.label || item.value)
    .map((item) => ({
      ...item,
      label: item.label || item.value,
      value: item.value || item.label,
    }))
    .filter((item, index, items) => items.findIndex((candidate) => candidate.value === item.value) === index)
    .map((item, index) => ({ ...item, id: `bulk_${Date.now()}_${index}` }));
}

export function mergeSelectOptions(
  base: SelectOption[],
  next: SelectOption[],
): SelectOption[] {
  const map = new Map<SelectOptionValue, SelectOption>();
  let other: SelectOption | undefined;
  [...base, ...next].forEach((option) => {
    if (option.isOther) {
      other = option;
      return;
    }
    map.set(option.value, option);
  });
  return [...map.values(), ...(other ? [other] : [])];
}
