import type { MobileSchemaNode } from './types';

export type MatrixAxis = 'row' | 'column';

export type MatrixAxisItem = {
  id: string;
  label: string;
};

export type MatrixFillProps = {
  rows: MatrixAxisItem[];
  columns: MatrixAxisItem[];
  cellType: 'textarea' | 'number';
  maxRows: number;
  maxColumns: number;
  maxLength: number;
  min?: number;
  max?: number;
  precision: number;
  required: boolean;
};

export type MatrixFillValue = {
  customRows: MatrixAxisItem[];
  customColumns: MatrixAxisItem[];
  cells: Record<string, Record<string, unknown>>;
};

const defaultRows: MatrixAxisItem[] = [
  { id: 'row_1', label: '矩阵行1' },
  { id: 'row_2', label: '矩阵行2' },
  { id: 'row_3', label: '矩阵行3' },
];

const defaultColumns: MatrixAxisItem[] = [
  { id: 'col_1', label: '值1' },
  { id: 'col_2', label: '值2' },
  { id: 'col_3', label: '值3' },
];

export function normalizeMatrixProps(input?: Record<string, unknown>): MatrixFillProps {
  const rows = normalizeAxis(input?.rows, defaultRows);
  const columns = normalizeAxis(input?.columns, defaultColumns);
  const maxRows = Math.max(positiveInteger(input?.maxRows, 20), rows.length);
  const maxColumns = Math.max(positiveInteger(input?.maxColumns, 10), columns.length);
  const min = finiteNumber(input?.min);
  const max = finiteNumber(input?.max);
  return {
    rows,
    columns,
    cellType: input?.cellType === 'number' ? 'number' : 'textarea',
    maxRows,
    maxColumns,
    maxLength: positiveInteger(input?.maxLength, 2000),
    min: min != null && max != null && min > max ? max : min,
    max,
    precision: nonNegativeInteger(input?.precision, 0),
    required: input?.required === true,
  };
}

export function normalizeMatrixValue(value: unknown, inputProps?: Record<string, unknown>): MatrixFillValue {
  if (!isRecord(value)) return { customRows: [], customColumns: [], cells: {} };
  const customRows = normalizeAxis(value.customRows, []);
  const customColumns = normalizeAxis(value.customColumns, []);
  const cells = isRecord(value.cells) ? cloneCells(value.cells) : {};
  if (inputProps) {
    const props = normalizeMatrixProps(inputProps);
    const activeValue = { customRows, customColumns, cells };
    const rows = composeMatrixAxis(props, activeValue, 'row');
    const columns = composeMatrixAxis(props, activeValue, 'column');
    for (const row of rows) {
      const activeRow = cells[row.id];
      if (!activeRow) continue;
      for (const column of columns) {
        const raw = activeRow[column.id];
        if (props.cellType === 'number' && typeof raw === 'string' && raw.trim()) {
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

export function composeMatrixAxis(props: MatrixFillProps, value: MatrixFillValue, axis: MatrixAxis) {
  const configured = axis === 'row' ? props.rows : props.columns;
  const custom = axis === 'row' ? value.customRows : value.customColumns;
  const configuredIds = new Set(configured.map((item) => item.id));
  return [...configured, ...custom.filter((item) => !configuredIds.has(item.id))];
}

export function matrixCell(
  value: MatrixFillValue,
  rowId: string,
  columnId: string,
  cellType: MatrixFillProps['cellType'],
): string | number | null {
  const raw = value.cells[rowId]?.[columnId];
  if (cellType === 'number') {
    if (typeof raw === 'number' && Number.isFinite(raw)) return raw;
    if (typeof raw === 'string' && raw.trim()) {
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

export function removeCustomAxis(value: MatrixFillValue, axis: MatrixAxis, id: string) {
  const cells = cloneCells(value.cells);
  if (axis === 'row') {
    delete cells[id];
    return { ...value, customRows: value.customRows.filter((item) => item.id !== id), cells };
  }
  for (const rowId of Object.keys(cells)) delete cells[rowId]?.[id];
  return { ...value, customColumns: value.customColumns.filter((item) => item.id !== id), cells };
}

export function validateMatrixValue(node: MobileSchemaNode, value: unknown) {
  const props = normalizeMatrixProps(node.props);
  const matrix = normalizeMatrixValue(value, node.props);
  const rows = composeMatrixAxis(props, matrix, 'row');
  const columns = composeMatrixAxis(props, matrix, 'column');
  const label = node.label ?? node.id;
  if (rows.length > props.maxRows) return `${label}最多添加${props.maxRows}行`;
  if (columns.length > props.maxColumns) return `${label}最多添加${props.maxColumns}列`;
  for (const row of rows) {
    for (const column of columns) {
      const cell = matrixCell(matrix, row.id, column.id, props.cellType);
      if (props.cellType === 'textarea') {
        const text = typeof cell === 'string' ? cell : '';
        if (text.length > props.maxLength) {
          return `“${row.label} / ${column.label}”不能超过${props.maxLength}个字符`;
        }
        if (props.required && !text.trim()) return `请填写“${row.label} / ${column.label}”`;
      } else if (typeof cell === 'number') {
        if (props.min != null && cell < props.min) return `“${row.label} / ${column.label}”不能小于${props.min}`;
        if (props.max != null && cell > props.max) return `“${row.label} / ${column.label}”不能大于${props.max}`;
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

export function summarizeMatrix(node: MobileSchemaNode, value: unknown) {
  const props = normalizeMatrixProps(node.props);
  const matrix = normalizeMatrixValue(value, node.props);
  const rows = composeMatrixAxis(props, matrix, 'row');
  const columns = composeMatrixAxis(props, matrix, 'column');
  let filled = 0;
  for (const row of rows) {
    for (const column of columns) {
      const cell = matrixCell(matrix, row.id, column.id, props.cellType);
      if (typeof cell === 'number' || (typeof cell === 'string' && cell.trim())) filled += 1;
    }
  }
  return `${rows.length}行 × ${columns.length}列，已填写${filled}格`;
}

export function createRuntimeAxisId(axis: MatrixAxis) {
  const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID().replaceAll('-', '').slice(0, 12)
    : `${Date.now()}_${Math.random().toString(16).slice(2)}`;
  return `runtime_${axis}_${random}`;
}

function normalizeAxis(input: unknown, fallback: MatrixAxisItem[]) {
  if (!Array.isArray(input)) return fallback.map((item) => ({ ...item }));
  const seen = new Set<string>();
  const result: MatrixAxisItem[] = [];
  for (const raw of input) {
    if (!isRecord(raw)) continue;
    const id = String(raw.id ?? '').trim();
    const label = String(raw.label ?? '').trim();
    if (!id || !label || seen.has(id)) continue;
    seen.add(id);
    result.push({ id, label });
  }
  return result.length > 0 ? result : fallback.map((item) => ({ ...item }));
}

function cloneCells(input: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(input).map(([rowId, row]) => [
    rowId,
    isRecord(row) ? { ...row } : {},
  ])) as Record<string, Record<string, unknown>>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
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

function decimalPlaces(value: number) {
  const text = String(value).toLowerCase();
  if (text.includes('e-')) {
    const [coefficient = '', exponent = '0'] = text.split('e-');
    return Number(exponent) + (coefficient.split('.')[1]?.length ?? 0);
  }
  return text.split('.')[1]?.length ?? 0;
}
