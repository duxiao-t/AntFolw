import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MatrixFillField } from './MatrixFillField';

const node = {
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

describe('MatrixFillField', () => {
  it('writes a cell using stable row and column ids', () => {
    const onChange = vi.fn();
    render(
      <MatrixFillField.Component
        node={node}
        mode="runtime-fill"
        value={{ customRows: [], customColumns: [], cells: {} }}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('设备 / 结果'), { target: { value: '正常' } });
    expect(onChange).toHaveBeenCalledWith({
      customRows: [],
      customColumns: [],
      cells: { row_1: { col_1: '正常' } },
    });
  });

  it('adds one named runtime row through the naming dialog', () => {
    const onChange = vi.fn();
    render(
      <MatrixFillField.Component
        node={node}
        mode="runtime-fill"
        value={{ customRows: [], customColumns: [], cells: {} }}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '新增行' }));
    fireEvent.change(screen.getByPlaceholderText('请输入行名称'), { target: { value: '备用设备' } });
    fireEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      customRows: [expect.objectContaining({ label: '备用设备' })],
    }));
  });

  it('keeps scroll edge hints in sync and reveals a focused third column', () => {
    const wideNode = {
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
    render(
      <MatrixFillField.Component
        node={wideNode}
        mode="runtime-fill"
        value={{ customRows: [], customColumns: [], cells: {} }}
        onChange={vi.fn()}
      />,
    );

    const scroll = document.querySelector('.matrix-fill__scroll') as HTMLDivElement;
    const viewport = document.querySelector('.matrix-fill__viewport') as HTMLDivElement;
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
    const rowHeader = document.querySelector('.matrix-fill__row-header') as HTMLTableCellElement;
    rowHeader.getBoundingClientRect = () => ({ width: 76 } as DOMRect);

    fireEvent.focus(focusedInput);
    expect(focusedCell).toHaveClass('matrix-fill__cell--active');
    expect(document.querySelector('.matrix-fill__row-header')).toHaveClass('matrix-fill__axis--active');
    expect(screen.getByRole('columnheader', { name: '复核' })).toHaveClass('matrix-fill__axis--active');
    expect(scroll.scrollLeft).toBe(110);

    fireEvent.scroll(scroll);
    expect(viewport).toHaveAttribute('data-scroll-left', 'true');
    expect(viewport).toHaveAttribute('data-scroll-right', 'true');
  });

  it('renders readonly cells without mutation controls', () => {
    render(
      <MatrixFillField.Component
        node={node}
        mode="readonly"
        value={{ customRows: [], customColumns: [], cells: { row_1: { col_1: '正常' } } }}
      />,
    );
    expect(screen.getByText('正常')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新增行' })).not.toBeInTheDocument();
  });
});
