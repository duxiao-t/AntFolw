import { describe, expect, it } from 'vitest';
import {
  normalizeMatrixProps,
  normalizeMatrixValue,
  removeCustomAxis,
  summarizeMatrix,
  validateMatrixValue,
} from './matrixFill';
import type { MobileSchemaNode } from './types';

const node: MobileSchemaNode = {
  id: 'matrix',
  type: 'matrix_fill',
  label: '检查矩阵',
  props: {
    rows: [{ id: 'row_1', label: '设备' }],
    columns: [{ id: 'col_1', label: '结果' }],
    cellType: 'number',
    maxRows: 2,
    maxColumns: 2,
    min: 0,
    max: 10,
    precision: 1,
    required: true,
  },
};

describe('mobile matrixFill model', () => {
  it('normalizes numeric strings to numbers', () => {
    const value = normalizeMatrixValue({ cells: { row_1: { col_1: '3.5' } } }, node.props);
    expect(value.cells.row_1?.col_1).toBe(3.5);
  });

  it('reports the exact required coordinate', () => {
    expect(validateMatrixValue(node, undefined)).toBe('请填写“设备 / 结果”');
  });

  it('validates number precision and creates a readable summary', () => {
    const value = { cells: { row_1: { col_1: 3.25 } } };
    expect(validateMatrixValue(node, value)).toContain('最多保留1位小数');
    expect(summarizeMatrix(node, { cells: { row_1: { col_1: 3.5 } } }))
      .toBe('1行 × 1列，已填写1格');
  });

  it('removes runtime cells but preserves unrelated historical cells', () => {
    const value = normalizeMatrixValue({
      customRows: [{ id: 'runtime_row_a', label: '新增行' }],
      cells: {
        runtime_row_a: { col_1: 1 },
        deleted_row: { deleted_col: 'legacy' },
      },
    });
    const next = removeCustomAxis(value, 'row', 'runtime_row_a');
    expect(next.cells.runtime_row_a).toBeUndefined();
    expect(next.cells.deleted_row?.deleted_col).toBe('legacy');
    expect(normalizeMatrixProps(node.props).maxRows).toBe(2);
  });
});
