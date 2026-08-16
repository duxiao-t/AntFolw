export type MatrixAxis = 'row' | 'column';

export type MatrixAxisItem = {
  id: string;
  label: string;
};

export type MatrixCellType = 'textarea' | 'number';

export type MatrixFillProps = {
  rows: MatrixAxisItem[];
  columns: MatrixAxisItem[];
  cellType: MatrixCellType;
  maxRows: number;
  maxColumns: number;
  maxLength?: number;
  min?: number;
  max?: number;
  precision: number;
  required?: boolean;
};

export type MatrixFillValue = {
  customRows: MatrixAxisItem[];
  customColumns: MatrixAxisItem[];
  cells: Record<string, Record<string, unknown>>;
};

export const DEFAULT_MATRIX_ROWS: MatrixAxisItem[] = [
  { id: 'row_1', label: '矩阵行1' },
  { id: 'row_2', label: '矩阵行2' },
  { id: 'row_3', label: '矩阵行3' },
];

export const DEFAULT_MATRIX_COLUMNS: MatrixAxisItem[] = [
  { id: 'col_1', label: '值1' },
  { id: 'col_2', label: '值2' },
  { id: 'col_3', label: '值3' },
];

export const DEFAULT_MATRIX_PROPS: MatrixFillProps = {
  rows: DEFAULT_MATRIX_ROWS,
  columns: DEFAULT_MATRIX_COLUMNS,
  cellType: 'textarea',
  maxRows: 20,
  maxColumns: 10,
  maxLength: 2000,
  precision: 0,
};

export function normalizeMatrixProps(input?: Record<string, any>): MatrixFillProps {
  const rows = normalizeAxis(input?.rows, DEFAULT_MATRIX_ROWS);
  const columns = normalizeAxis(input?.columns, DEFAULT_MATRIX_COLUMNS);
  const cellType: MatrixCellType = input?.cellType === 'number' ? 'number' : 'textarea';
  const maxRows = positiveInteger(input?.maxRows, DEFAULT_MATRIX_PROPS.maxRows);
  const maxColumns = positiveInteger(input?.maxColumns, DEFAULT_MATRIX_PROPS.maxColumns);
  const precision = nonNegativeInteger(input?.precision, DEFAULT_MATRIX_PROPS.precision);
  const maxLength = positiveInteger(input?.maxLength, DEFAULT_MATRIX_PROPS.maxLength ?? 2000);
  const min = finiteNumber(input?.min);
  const max = finiteNumber(input?.max);

  return {
    rows,
    columns,
    cellType,
    maxRows: Math.max(maxRows, rows.length),
    maxColumns: Math.max(maxColumns, columns.length),
    maxLength,
    min: min != null && max != null && min > max ? max : min,
    max,
    precision,
    required: input?.required === true,
  };
}

export function normalizeMatrixValue(
  value: unknown,
  inputProps?: Record<string, any> | MatrixFillProps,
): MatrixFillValue {
  if (!isRecord(value)) {
    return { customRows: [], customColumns: [], cells: {} };
  }
  const customRows = normalizeAxis(value.customRows, []);
  const customColumns = normalizeAxis(value.customColumns, []);
  const cells = isRecord(value.cells) ? cloneCells(value.cells) : {};
  if (inputProps) {
    const props = normalizeMatrixProps(inputProps as Record<string, any>);
    const normalizedRows = composeMatrixAxis(props, { customRows, customColumns, cells }, 'row');
    const normalizedColumns = composeMatrixAxis(props, { customRows, customColumns, cells }, 'column');
    for (const row of normalizedRows) {
      const activeRow = cells[row.id];
      if (!activeRow) continue;
      for (const column of normalizedColumns) {
        const raw = activeRow[column.id];
        if (props.cellType === 'number' && typeof raw === 'string' && raw.trim() !== '') {
          const parsed = Number(raw);
          activeRow[column.id] = Number.isFinite(parsed) ? parsed : null;
        } else if (props.cellType === 'textarea' && raw != null && typeof raw !== 'string') {
          activeRow[column.id] = String(raw);
        }
      }
    }
  }
  return { customRows, customColumns, cells };
}

export function composeMatrixAxis(
  props: MatrixFillProps,
  value: MatrixFillValue,
  axis: MatrixAxis,
): MatrixAxisItem[] {
  const configured = axis === 'row' ? props.rows : props.columns;
  const custom = axis === 'row' ? value.customRows : value.customColumns;
  const configuredIds = new Set(configured.map((item) => item.id));
  return [
    ...configured,
    ...custom.filter((item) => !configuredIds.has(item.id)),
  ];
}

export function getMatrixCell(
  value: MatrixFillValue,
  rowId: string,
  columnId: string,
  cellType: MatrixCellType,
): string | number | null {
  const raw = value.cells[rowId]?.[columnId];
  if (cellType === 'number') {
    if (typeof raw === 'number' && Number.isFinite(raw)) return raw;
    if (typeof raw === 'string' && raw.trim() !== '') {
      const parsed = Number(raw);
      return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
  }
  if (raw == null) return '';
  return typeof raw === 'string' ? raw : String(raw);
}

export function setMatrixCell(
  value: MatrixFillValue,
  rowId: string,
  columnId: string,
  next: string | number | null,
): MatrixFillValue {
  const cells = cloneCells(value.cells);
  cells[rowId] = { ...(cells[rowId] ?? {}), [columnId]: next };
  return { ...value, cells };
}

export function removeMatrixAxis(
  value: MatrixFillValue,
  axis: MatrixAxis,
  id: string,
): MatrixFillValue {
  const customKey = axis === 'row' ? 'customRows' : 'customColumns';
  const custom = value[customKey].filter((item) => item.id !== id);
  const cells = cloneCells(value.cells);
  if (axis === 'row') {
    delete cells[id];
  } else {
    for (const rowId of Object.keys(cells)) {
      if (isRecord(cells[rowId])) delete cells[rowId][id];
    }
  }
  return { ...value, [customKey]: custom, cells };
}

export function validateMatrixValue(
  value: unknown,
  inputProps?: Record<string, any> | MatrixFillProps,
  label = '矩阵',
): string | null {
  const props = normalizeMatrixProps(inputProps as Record<string, any> | undefined);
  const normalized = normalizeMatrixValue(value, props);
  const rows = composeMatrixAxis(props, normalized, 'row');
  const columns = composeMatrixAxis(props, normalized, 'column');
  if (rows.length > props.maxRows) return `${label}最多添加${props.maxRows}行`;
  if (columns.length > props.maxColumns) return `${label}最多添加${props.maxColumns}列`;

  for (const row of rows) {
    for (const column of columns) {
      const cell = getMatrixCell(normalized, row.id, column.id, props.cellType);
      if (props.cellType === 'textarea') {
        const textCell = typeof cell === 'string' ? cell : '';
        if (textCell && props.maxLength != null && textCell.length > props.maxLength) {
          return `“${row.label} / ${column.label}”不能超过${props.maxLength}个字符`;
        }
        if (props.required && textCell.trim() === '') {
          return `请填写“${row.label} / ${column.label}”`;
        }
      } else if (typeof cell === 'number') {
        if (!Number.isFinite(cell)) return `“${row.label} / ${column.label}”必须是数字`;
        if (props.min != null && cell < props.min) {
          return `“${row.label} / ${column.label}”不能小于${props.min}`;
        }
        if (props.max != null && cell > props.max) {
          return `“${row.label} / ${column.label}”不能大于${props.max}`;
        }
        if (decimalPlaces(cell) > props.precision) {
          return `“${row.label} / ${column.label}”最多保留${props.precision}位小数`;
        }
      } else if (props.required) {
        return `请填写“${row.label} / ${column.label}”`;
      }
    }
  }
  return null;
}

export function createMatrixAxisId(axis: MatrixAxis, runtime = false) {
  const prefix = runtime ? `runtime_${axis}` : axis;
  const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID().replaceAll('-', '').slice(0, 12)
    : `${Date.now()}_${Math.random().toString(16).slice(2)}`;
  return `${prefix}_${random}`;
}

export function decimalPlaces(value: number) {
  const text = String(value).toLowerCase();
  if (text.includes('e-')) {
    const [coefficient, exponent] = text.split('e-');
    return Number(exponent) + (coefficient?.split('.')[1]?.length ?? 0);
  }
  return text.split('.')[1]?.length ?? 0;
}

function normalizeAxis(input: unknown, fallback: MatrixAxisItem[]): MatrixAxisItem[] {
  if (!Array.isArray(input)) return fallback.map((item) => ({ ...item }));
  const seen = new Set<string>();
  const result: MatrixAxisItem[] = [];
  for (const item of input) {
    if (!isRecord(item)) continue;
    const id = String(item.id ?? '').trim();
    const label = String(item.label ?? '').trim();
    if (!id || !label || seen.has(id)) continue;
    seen.add(id);
    result.push({ id, label });
  }
  return result.length > 0 ? result : fallback.map((item) => ({ ...item }));
}

function cloneCells(input: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(input).map(([rowId, row]) => [
      rowId,
      isRecord(row) ? { ...row } : {},
    ]),
  ) as Record<string, Record<string, unknown>>;
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function finiteNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function positiveInteger(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : fallback;
}

function nonNegativeInteger(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : fallback;
}
