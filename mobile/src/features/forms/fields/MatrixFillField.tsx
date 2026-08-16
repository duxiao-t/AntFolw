import { Dialog } from 'antd-mobile';
import { AddOutline, DeleteOutline, EditSOutline } from 'antd-mobile-icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  composeMatrixAxis,
  createRuntimeAxisId,
  matrixCell,
  normalizeMatrixProps,
  normalizeMatrixValue,
  removeCustomAxis,
  setMatrixCell,
  type MatrixAxis,
  type MatrixAxisItem,
} from '../schema/matrixFill';
import type { MobileFieldProps } from '../schema/types';
import { fieldLabel, FieldShell } from './fieldShared';

type EditorState = {
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
  const rowHeader = scroller.querySelector<HTMLTableCellElement>('tbody .af-matrix__row-head');
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

export function MatrixFillField(fieldProps: MobileFieldProps) {
  const props = normalizeMatrixProps(fieldProps.node.props);
  const value = normalizeMatrixValue(fieldProps.value, fieldProps.node.props);
  const rows = composeMatrixAxis(props, value, 'row');
  const columns = composeMatrixAxis(props, value, 'column');
  const [editor, setEditor] = useState<EditorState>(null);
  const [draftLabel, setDraftLabel] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<{ axis: MatrixAxis; item: MatrixAxisItem } | null>(null);
  const [activeCell, setActiveCell] = useState<ActiveCell>(null);
  const [scrollEdges, setScrollEdges] = useState<ScrollEdges>({ left: false, right: false });
  const scrollRef = useRef<HTMLDivElement>(null);
  const readonly = fieldProps.mode === 'readonly';

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
    if (fieldProps.mode === 'fill' && fieldProps.value != null
      && JSON.stringify(fieldProps.value) !== JSON.stringify(value)) {
      fieldProps.onValueChange(fieldProps.node.id, value);
    }
  }, [fieldProps.mode, fieldProps.node.id, fieldProps.onValueChange, fieldProps.value, value]);

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

  const openEditor = (axis: MatrixAxis, action: 'add' | 'rename', item?: MatrixAxisItem) => {
    setDraftLabel(item?.label ?? '');
    setEditor({ axis, action, item });
  };

  const editorItems = editor?.axis === 'row' ? rows : columns;
  const editorError = editor && draftLabel.trim()
    ? editorItems.some((item) => item.id !== editor.item?.id && item.label === draftLabel.trim())
      ? '名称不能重复'
      : null
    : editor ? '请输入名称' : null;

  const commitEditor = () => {
    if (!editor || editorError) return;
    const label = draftLabel.trim();
    const key = editor.axis === 'row' ? 'customRows' : 'customColumns';
    const custom = value[key];
    if (editor.action === 'add') {
      fieldProps.onValueChange(fieldProps.node.id, {
        ...value,
        [key]: [...custom, { id: createRuntimeAxisId(editor.axis), label }],
      });
    } else if (editor.item) {
      fieldProps.onValueChange(fieldProps.node.id, {
        ...value,
        [key]: custom.map((item) => item.id === editor.item?.id ? { ...item, label } : item),
      });
    }
    setEditor(null);
  };

  const customActions = (axis: MatrixAxis, item: MatrixAxisItem) => {
    if (readonly) return null;
    const custom = axis === 'row' ? value.customRows : value.customColumns;
    if (!custom.some((candidate) => candidate.id === item.id)) return null;
    return (
      <span className="af-matrix__axis-actions">
        <button type="button" aria-label={`重命名${item.label}`} onClick={() => openEditor(axis, 'rename', item)}>
          <EditSOutline aria-hidden="true" />
        </button>
        <button type="button" aria-label={`删除${item.label}`} onClick={() => setDeleteTarget({ axis, item })}>
          <DeleteOutline aria-hidden="true" />
        </button>
      </span>
    );
  };

  const renderCell = (row: MatrixAxisItem, column: MatrixAxisItem) => {
    const cell = matrixCell(value, row.id, column.id, props.cellType);
    if (readonly) {
      return <span className="af-matrix__readonly">{cell == null || cell === '' ? '未填写' : String(cell)}</span>;
    }
    if (props.cellType === 'number') {
      return (
        <input
          className="af-matrix__input"
          type="number"
          inputMode="decimal"
          aria-label={`${row.label} / ${column.label}`}
          min={props.min}
          max={props.max}
          step={props.precision > 0 ? 10 ** -props.precision : 1}
          value={typeof cell === 'number' ? cell : ''}
          onFocus={(event) => handleCellFocus(row, column, event.currentTarget.closest('td'))}
          onBlur={() => setActiveCell(null)}
          onChange={(event) => {
            const next = event.target.value.trim() === '' ? null : Number(event.target.value);
            fieldProps.onValueChange(
              fieldProps.node.id,
              setMatrixCell(value, row.id, column.id, typeof next === 'number' && Number.isFinite(next) ? next : null),
            );
          }}
        />
      );
    }
    return (
      <textarea
        className="af-matrix__input af-matrix__textarea"
        aria-label={`${row.label} / ${column.label}`}
        rows={2}
        maxLength={props.maxLength}
        value={typeof cell === 'string' ? cell : ''}
        onFocus={(event) => handleCellFocus(row, column, event.currentTarget.closest('td'))}
        onBlur={() => setActiveCell(null)}
        onChange={(event) => fieldProps.onValueChange(
          fieldProps.node.id,
          setMatrixCell(value, row.id, column.id, event.target.value),
        )}
      />
    );
  };

  return (
    <FieldShell
      node={fieldProps.node}
      label={fieldLabel(fieldProps.node)}
      error={fieldProps.error}
      className="af-field--matrix"
    >
      <div
        className="af-matrix__viewport"
        data-scroll-left={scrollEdges.left ? 'true' : 'false'}
        data-scroll-right={scrollEdges.right ? 'true' : 'false'}
      >
        <div
          ref={scrollRef}
          className="af-matrix__scroll"
          data-scroll-left={scrollEdges.left ? 'true' : 'false'}
          data-scroll-right={scrollEdges.right ? 'true' : 'false'}
          onScroll={updateScrollEdges}
        >
          <table className="af-matrix__table">
            <caption className="af-visually-hidden">{fieldLabel(fieldProps.node)}</caption>
            <thead>
              <tr>
                <th className="af-matrix__corner" scope="col">行 / 列</th>
                {columns.map((column) => (
                  <th
                    key={column.id}
                    scope="col"
                    className={activeCell?.columnId === column.id ? 'af-matrix__axis--active' : undefined}
                  >
                    <span>{column.label}</span>
                    {customActions('column', column)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <th
                    className={`af-matrix__row-head${activeCell?.rowId === row.id ? ' af-matrix__axis--active' : ''}`}
                    scope="row"
                  >
                    <span>{row.label}</span>
                    {customActions('row', row)}
                  </th>
                  {columns.map((column) => (
                    <td
                      key={`${row.id}:${column.id}`}
                      className={activeCell?.rowId === row.id && activeCell.columnId === column.id
                        ? 'af-matrix__cell--active'
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

      {!readonly ? (
        <div className="af-matrix__toolbar">
          <button type="button" disabled={rows.length >= props.maxRows} onClick={() => openEditor('row', 'add')}>
            <AddOutline aria-hidden="true" />
            新增行
          </button>
          <button type="button" disabled={columns.length >= props.maxColumns} onClick={() => openEditor('column', 'add')}>
            <AddOutline aria-hidden="true" />
            新增列
          </button>
          <small>{rows.length} 行 × {columns.length} 列</small>
        </div>
      ) : null}

      <Dialog
        visible={editor != null}
        title={`${editor?.action === 'rename' ? '重命名' : '新增'}${editor?.axis === 'row' ? '行' : '列'}`}
        content={(
          <div className="af-matrix__dialog-field">
            <input
              value={draftLabel}
              maxLength={100}
              placeholder="请输入名称"
              onChange={(event) => setDraftLabel(event.target.value)}
            />
            {editorError ? <small role="alert">{editorError}</small> : null}
          </div>
        )}
        actions={[[
          { key: 'cancel', text: '取消', onClick: () => setEditor(null) },
          { key: 'confirm', text: '确定', bold: true, onClick: commitEditor },
        ]]}
        onClose={() => setEditor(null)}
      />

      <Dialog
        visible={deleteTarget != null}
        title={`删除${deleteTarget?.axis === 'row' ? '行' : '列'}`}
        content={`删除“${deleteTarget?.item.label ?? ''}”后，对应单元格值也会删除。`}
        actions={[[
          { key: 'cancel', text: '取消', onClick: () => setDeleteTarget(null) },
          {
            key: 'delete',
            text: '删除',
            danger: true,
            onClick: () => {
              if (deleteTarget) {
                fieldProps.onValueChange(
                  fieldProps.node.id,
                  removeCustomAxis(value, deleteTarget.axis, deleteTarget.item.id),
                );
              }
              setDeleteTarget(null);
            },
          },
        ]]}
        onClose={() => setDeleteTarget(null)}
      />
    </FieldShell>
  );
}
