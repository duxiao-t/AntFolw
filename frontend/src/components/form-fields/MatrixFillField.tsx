import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { Button, Input, InputNumber, Modal, Popconfirm, Tooltip, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { FieldType } from '../../registry/types';
import {
  composeMatrixAxis,
  createMatrixAxisId,
  getMatrixCell,
  normalizeMatrixProps,
  normalizeMatrixValue,
  removeMatrixAxis,
  setMatrixCell,
  validateMatrixValue,
  type MatrixAxis,
  type MatrixAxisItem,
} from './matrixFill';
import './matrix-fill.less';

type DialogState = {
  axis: MatrixAxis;
  action: 'add' | 'rename';
  item?: MatrixAxisItem;
} | null;

type ActiveCell = {
  rowId: string;
  columnId: string;
} | null;

type ScrollEdges = {
  left: boolean;
  right: boolean;
};

const SCROLL_EDGE_EPSILON = 1;

function revealMatrixCell(scroller: HTMLDivElement, cell: HTMLTableCellElement) {
  const scrollerRect = scroller.getBoundingClientRect();
  const cellRect = cell.getBoundingClientRect();
  const rowHeader = scroller.querySelector<HTMLTableCellElement>('tbody .matrix-fill__row-header');
  const leftBoundary = scrollerRect.left + (rowHeader?.getBoundingClientRect().width ?? 0);
  let scrollDelta = 0;

  if (cellRect.left < leftBoundary) {
    scrollDelta = cellRect.left - leftBoundary;
  } else if (cellRect.right > scrollerRect.right) {
    scrollDelta = cellRect.right - scrollerRect.right;
  }

  if (Math.abs(scrollDelta) > SCROLL_EDGE_EPSILON) {
    scroller.scrollLeft += scrollDelta;
  }
}

export const MatrixFillField: FieldType = {
  type: 'matrix_fill',
  label: '自增矩阵',
  icon: 'table',
  defaultProps: {
    rows: [
      { id: 'row_1', label: '矩阵行1' },
      { id: 'row_2', label: '矩阵行2' },
      { id: 'row_3', label: '矩阵行3' },
    ],
    columns: [
      { id: 'col_1', label: '值1' },
      { id: 'col_2', label: '值2' },
      { id: 'col_3', label: '值3' },
    ],
    cellType: 'textarea',
    maxRows: 20,
    maxColumns: 10,
    maxLength: 2000,
    precision: 0,
  },
  Component: ({ node, mode, value, onChange }) => {
    const props = normalizeMatrixProps(node.props);
    const matrixValue = normalizeMatrixValue(value, node.props);
    const rows = composeMatrixAxis(props, matrixValue, 'row');
    const columns = composeMatrixAxis(props, matrixValue, 'column');
    const [dialog, setDialog] = useState<DialogState>(null);
    const [draftLabel, setDraftLabel] = useState('');
    const [activeCell, setActiveCell] = useState<ActiveCell>(null);
    const [scrollEdges, setScrollEdges] = useState<ScrollEdges>({ left: false, right: false });
    const scrollRef = useRef<HTMLDivElement>(null);
    const isRuntime = mode === 'runtime-fill';
    const isReadonly = mode === 'readonly';
    const dialogItems = dialog?.axis === 'row' ? rows : columns;
    const dialogError = dialog && draftLabel.trim()
      ? dialogItems.some((item) => item.id !== dialog.item?.id && item.label === draftLabel.trim())
        ? '名称不能重复'
        : null
      : dialog ? '请输入名称' : null;

    const updateScrollEdges = useCallback(() => {
      const scroller = scrollRef.current;
      if (!scroller) return;
      const nextEdges = {
        left: scroller.scrollLeft > SCROLL_EDGE_EPSILON,
        right: scroller.scrollWidth - scroller.clientWidth - scroller.scrollLeft > SCROLL_EDGE_EPSILON,
      };
      setScrollEdges((current) => current.left === nextEdges.left && current.right === nextEdges.right
        ? current
        : nextEdges);
    }, []);

    useEffect(() => {
      if (isRuntime && value != null && JSON.stringify(value) !== JSON.stringify(matrixValue)) {
        onChange?.(matrixValue);
      }
    }, [isRuntime, matrixValue, onChange, value]);

    useEffect(() => {
      const scroller = scrollRef.current;
      if (!scroller) return undefined;

      updateScrollEdges();
      const resizeObserver = typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(updateScrollEdges);
      resizeObserver?.observe(scroller);
      const table = scroller.querySelector('table');
      if (table) resizeObserver?.observe(table);
      window.addEventListener('resize', updateScrollEdges);

      return () => {
        resizeObserver?.disconnect();
        window.removeEventListener('resize', updateScrollEdges);
      };
    }, [updateScrollEdges]);

    const handleCellFocus = (
      row: MatrixAxisItem,
      column: MatrixAxisItem,
      cell: HTMLTableCellElement | null,
    ) => {
      setActiveCell({ rowId: row.id, columnId: column.id });
      const scroller = scrollRef.current;
      if (scroller && cell) {
        revealMatrixCell(scroller, cell);
        updateScrollEdges();
      }
    };

    const openAddDialog = (axis: MatrixAxis) => {
      setDraftLabel('');
      setDialog({ axis, action: 'add' });
    };

    const openRenameDialog = (axis: MatrixAxis, item: MatrixAxisItem) => {
      setDraftLabel(item.label);
      setDialog({ axis, action: 'rename', item });
    };

    const commitDialog = () => {
      if (!dialog || dialogError) return;
      const label = draftLabel.trim();
      const current = dialog.axis === 'row' ? matrixValue.customRows : matrixValue.customColumns;

      if (dialog.action === 'add') {
        const nextItem = { id: createMatrixAxisId(dialog.axis, true), label };
        const key = dialog.axis === 'row' ? 'customRows' : 'customColumns';
        onChange?.({ ...matrixValue, [key]: [...current, nextItem] });
      } else if (dialog.item) {
        const key = dialog.axis === 'row' ? 'customRows' : 'customColumns';
        onChange?.({
          ...matrixValue,
          [key]: current.map((item) => item.id === dialog.item?.id ? { ...item, label } : item),
        });
      }
      setDialog(null);
    };

    const removeItem = (axis: MatrixAxis, item: MatrixAxisItem) => {
      onChange?.(removeMatrixAxis(matrixValue, axis, item.id));
    };

    const renderAxisActions = (axis: MatrixAxis, item: MatrixAxisItem) => {
      if (!isRuntime) return null;
      const isCustom = (axis === 'row' ? matrixValue.customRows : matrixValue.customColumns)
        .some((candidate) => candidate.id === item.id);
      if (!isCustom) return null;
      return (
        <span className="matrix-fill__axis-actions">
          <Tooltip title={`重命名${axis === 'row' ? '行' : '列'}`}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              aria-label={`重命名${item.label}`}
              onClick={() => openRenameDialog(axis, item)}
            />
          </Tooltip>
          <Popconfirm
            title={`删除${axis === 'row' ? '行' : '列'}“${item.label}”？`}
            description="该项的当前单元格值也会删除。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => removeItem(axis, item)}
          >
            <Tooltip title={`删除${axis === 'row' ? '行' : '列'}`}>
              <Button
                type="text"
                danger
                size="small"
                icon={<DeleteOutlined />}
                aria-label={`删除${item.label}`}
              />
            </Tooltip>
          </Popconfirm>
        </span>
      );
    };

    const renderCell = (row: MatrixAxisItem, column: MatrixAxisItem) => {
      const cell = getMatrixCell(matrixValue, row.id, column.id, props.cellType);
      const disabled = isReadonly || mode === 'designer-preview';
      if (isReadonly || mode === 'designer-preview') {
        return <span className="matrix-fill__readonly-value">{cell == null || cell === '' ? '未填写' : String(cell)}</span>;
      }
      if (props.cellType === 'number') {
        return (
          <InputNumber
            aria-label={`${row.label} / ${column.label}`}
            className="matrix-fill__number-input"
            controls={false}
            disabled={disabled}
            min={props.min}
            max={props.max}
            precision={props.precision}
            step={props.precision > 0 ? 10 ** -props.precision : 1}
            value={typeof cell === 'number' ? cell : null}
            onFocus={(event) => handleCellFocus(row, column, event.currentTarget.closest('td'))}
            onBlur={() => setActiveCell(null)}
            onChange={(next) => {
              const numeric = typeof next === 'number' && Number.isFinite(next) ? next : null;
              onChange?.(setMatrixCell(matrixValue, row.id, column.id, numeric));
            }}
          />
        );
      }
      return (
        <Input.TextArea
          aria-label={`${row.label} / ${column.label}`}
          className="matrix-fill__text-input"
          autoSize={{ minRows: 2, maxRows: 4 }}
          disabled={disabled}
          maxLength={props.maxLength}
          value={typeof cell === 'string' ? cell : ''}
          onFocus={(event) => handleCellFocus(row, column, event.currentTarget.closest('td'))}
          onBlur={() => setActiveCell(null)}
          onChange={(event) => onChange?.(setMatrixCell(matrixValue, row.id, column.id, event.target.value))}
        />
      );
    };

    return (
      <div className="matrix-fill" data-matrix-field-id={node.id} data-cell-type={props.cellType}>
        <div
          className="matrix-fill__viewport"
          data-scroll-left={scrollEdges.left ? 'true' : 'false'}
          data-scroll-right={scrollEdges.right ? 'true' : 'false'}
        >
          <div
            ref={scrollRef}
            className="matrix-fill__scroll"
            data-scroll-left={scrollEdges.left ? 'true' : 'false'}
            data-scroll-right={scrollEdges.right ? 'true' : 'false'}
            onScroll={updateScrollEdges}
          >
            <table className="matrix-fill__table">
              <caption className="matrix-fill__caption">{node.label || '自增矩阵'}</caption>
              <thead>
                <tr>
                  <th scope="col" className="matrix-fill__corner">行 / 列</th>
                  {columns.map((column) => (
                    <th
                      scope="col"
                      key={column.id}
                      className={activeCell?.columnId === column.id ? 'matrix-fill__axis--active' : undefined}
                    >
                      <span className="matrix-fill__axis-label">{column.label}</span>
                      {renderAxisActions('column', column)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <th
                      scope="row"
                      className={`matrix-fill__row-header${activeCell?.rowId === row.id ? ' matrix-fill__axis--active' : ''}`}
                    >
                      <span className="matrix-fill__axis-label">{row.label}</span>
                      {renderAxisActions('row', row)}
                    </th>
                    {columns.map((column) => (
                      <td
                        key={`${row.id}:${column.id}`}
                        className={activeCell?.rowId === row.id && activeCell.columnId === column.id
                          ? 'matrix-fill__cell--active'
                          : undefined}
                      >
                        {renderCell(row, column)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        {isRuntime && (
          <div className="matrix-fill__toolbar">
            <Button
              size="small"
              icon={<PlusOutlined />}
              aria-label="新增行"
              disabled={rows.length >= props.maxRows}
              onClick={() => openAddDialog('row')}
            >
              新增行
            </Button>
            <Button
              size="small"
              icon={<PlusOutlined />}
              aria-label="新增列"
              disabled={columns.length >= props.maxColumns}
              onClick={() => openAddDialog('column')}
            >
              新增列
            </Button>
            <span className="matrix-fill__counter">{rows.length} 行 × {columns.length} 列</span>
          </div>
        )}
        <Modal
          title={`${dialog?.action === 'rename' ? '重命名' : '新增'}${dialog?.axis === 'row' ? '行' : '列'}`}
          open={dialog != null}
          okText="确定"
          cancelText="取消"
          okButtonProps={{ disabled: !!dialogError }}
          onOk={commitDialog}
          onCancel={() => setDialog(null)}
        >
          <Input
            value={draftLabel}
            maxLength={100}
            placeholder={`请输入${dialog?.axis === 'row' ? '行' : '列'}名称`}
            status={dialogError ? 'error' : undefined}
            onChange={(event) => setDraftLabel(event.target.value)}
            onPressEnter={commitDialog}
          />
          {dialogError ? <Typography.Text type="danger">{dialogError}</Typography.Text> : null}
        </Modal>
      </div>
    );
  },
  ConfigPanel: () => null,
  validate: (value, props) => validateMatrixValue(value, props, '矩阵'),
};
