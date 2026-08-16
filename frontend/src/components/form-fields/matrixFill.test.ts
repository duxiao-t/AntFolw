import { describe, expect, it } from 'vitest';
import {
  composeMatrixAxis,
  normalizeMatrixProps,
  normalizeMatrixValue,
  removeMatrixAxis,
  validateMatrixValue,
} from './matrixFill';

describe('matrixFill model', () => {
  it('creates a 3 by 3 matrix with the configured limits', () => {
    const props = normalizeMatrixProps();
    expect(props.rows).toHaveLength(3);
    expect(props.columns).toHaveLength(3);
    expect(props.maxRows).toBe(20);
    expect(props.maxColumns).toBe(10);
  });

  it('keeps orphaned cells while removing a deleted runtime row', () => {
    const value = normalizeMatrixValue({
      customRows: [{ id: 'runtime_row_a', label: '临时行' }],
      customColumns: [],
      cells: {
        runtime_row_a: { col_1: '删除我' },
        deleted_row: { deleted_col: '历史值' },
      },
    });
    const next = removeMatrixAxis(value, 'row', 'runtime_row_a');
    expect(next.cells.runtime_row_a).toBeUndefined();
    expect(next.cells.deleted_row?.deleted_col).toBe('历史值');
  });

  it('normalizes active numeric strings without touching orphaned values', () => {
    const props = normalizeMatrixProps({
      rows: [{ id: 'row_a', label: '行A' }],
      columns: [{ id: 'col_a', label: '列A' }],
      cellType: 'number',
      maxRows: 20,
      maxColumns: 10,
      precision: 2,
    });
    const value = normalizeMatrixValue({
      customRows: [],
      customColumns: [],
      cells: {
        row_a: { col_a: '12.5' },
        deleted_row: { deleted_col: 'legacy' },
      },
    }, props);
    expect(value.cells.row_a?.col_a).toBe(12.5);
    expect(value.cells.deleted_row?.deleted_col).toBe('legacy');
  });

  it('validates every configured and runtime cell by coordinate', () => {
    const props = normalizeMatrixProps({
      rows: [{ id: 'row_a', label: '固定行' }],
      columns: [{ id: 'col_a', label: '固定列' }],
      cellType: 'textarea',
      maxRows: 2,
      maxColumns: 2,
      maxLength: 4,
      required: true,
    });
    expect(validateMatrixValue(undefined, props)).toBe('请填写“固定行 / 固定列”');

    const value = normalizeMatrixValue({
      customRows: [{ id: 'runtime_row_a', label: '新增行' }],
      customColumns: [],
      cells: { row_a: { col_a: '完成' } },
    });
    expect(composeMatrixAxis(props, value, 'row')).toHaveLength(2);
    expect(validateMatrixValue(value, props)).toBe('请填写“新增行 / 固定列”');
  });

  it('checks numeric range and precision', () => {
    const props = normalizeMatrixProps({
      rows: [{ id: 'row_a', label: '行A' }],
      columns: [{ id: 'col_a', label: '列A' }],
      cellType: 'number',
      maxRows: 1,
      maxColumns: 1,
      min: 0,
      max: 20,
      precision: 1,
    });
    expect(validateMatrixValue({ cells: { row_a: { col_a: 10.25 } } }, props))
      .toContain('最多保留1位小数');
    expect(validateMatrixValue({ cells: { row_a: { col_a: -1 } } }, props))
      .toContain('不能小于0');
  });
});
