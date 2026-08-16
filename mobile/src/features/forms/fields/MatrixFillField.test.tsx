import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { MatrixFillField } from './MatrixFillField';

const node: MobileSchemaNode = {
  id: 'matrix',
  type: 'matrix_fill',
  label: '检查矩阵',
  props: {
    rows: [{ id: 'row_1', label: '设备' }],
    columns: [{ id: 'col_1', label: '结果' }],
    cellType: 'textarea',
    maxRows: 2,
    maxColumns: 2,
    maxLength: 20,
    precision: 0,
  },
};

function props(value: unknown, onValueChange = vi.fn()) {
  return {
    node,
    value,
    values: { matrix: value },
    mode: 'fill' as const,
    onValueChange,
  };
}

describe('MatrixFillField', () => {
  it('writes cells and adds one named row', () => {
    const onValueChange = vi.fn();
    render(<MatrixFillField {...props({ customRows: [], customColumns: [], cells: {} }, onValueChange)} />);

    fireEvent.change(screen.getByLabelText('设备 / 结果'), { target: { value: '正常' } });
    expect(onValueChange).toHaveBeenCalledWith('matrix', {
      customRows: [],
      customColumns: [],
      cells: { row_1: { col_1: '正常' } },
    });

    fireEvent.click(screen.getByRole('button', { name: '新增行' }));
    fireEvent.change(screen.getByPlaceholderText('请输入名称'), { target: { value: '备用设备' } });
    fireEvent.click(screen.getByText('确定'));
    expect(onValueChange).toHaveBeenCalledWith('matrix', expect.objectContaining({
      customRows: [expect.objectContaining({ label: '备用设备' })],
    }));
  });

  it('keeps scroll edge hints in sync and reveals a focused third column', () => {
    const wideNode: MobileSchemaNode = {
      ...node,
      props: {
        ...node.props,
        columns: [
          { id: 'col_1', label: '结果' },
          { id: 'col_2', label: '备注' },
          { id: 'col_3', label: '复核' },
        ],
      },
    };
    render(<MatrixFillField {...props({ customRows: [], customColumns: [], cells: {} })} node={wideNode} />);

    const scroll = document.querySelector('.af-matrix__scroll') as HTMLDivElement;
    const viewport = document.querySelector('.af-matrix__viewport') as HTMLDivElement;
    Object.defineProperties(scroll, {
      clientWidth: { configurable: true, value: 300 },
      scrollWidth: { configurable: true, value: 500 },
      scrollLeft: { configurable: true, writable: true, value: 0 },
    });
    fireEvent.scroll(scroll);
    expect(viewport).toHaveAttribute('data-scroll-left', 'false');
    expect(viewport).toHaveAttribute('data-scroll-right', 'true');

    const focusedInput = screen.getByLabelText('设备 / 复核');
    const focusedCell = focusedInput.closest('td') as HTMLTableCellElement;
    scroll.getBoundingClientRect = () => ({ left: 0, right: 300, width: 300 } as DOMRect);
    focusedCell.getBoundingClientRect = () => ({ left: 292, right: 410, width: 118 } as DOMRect);
    const rowHeader = document.querySelector('.af-matrix__row-head') as HTMLTableCellElement;
    rowHeader.getBoundingClientRect = () => ({ width: 76 } as DOMRect);

    fireEvent.focus(focusedInput);
    expect(focusedCell).toHaveClass('af-matrix__cell--active');
    expect(document.querySelector('.af-matrix__row-head')).toHaveClass('af-matrix__axis--active');
    expect(screen.getByRole('columnheader', { name: '复核' })).toHaveClass('af-matrix__axis--active');
    expect(scroll.scrollLeft).toBe(110);

    fireEvent.scroll(scroll);
    expect(viewport).toHaveAttribute('data-scroll-left', 'true');
    expect(viewport).toHaveAttribute('data-scroll-right', 'true');
  });

  it('keeps the matrix visible in readonly mode without edit actions', () => {
    render(
      <MatrixFillField
        {...props({ customRows: [], customColumns: [], cells: { row_1: { col_1: '正常' } } })}
        mode="readonly"
      />,
    );
    expect(screen.getByText('正常')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新增行' })).not.toBeInTheDocument();
  });
});
