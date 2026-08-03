import { FolderOutline, RightOutline } from 'antd-mobile-icons';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';
import { searchMobileDepartments, type MobilePickerDept } from '../files.api';
import { MobileSelectionPopup } from './MobileSelectionPopup';

type PickerState = {
  open: boolean;
  keyword: string;
  loading: boolean;
  results: MobilePickerDept[];
  selectedLabel: string;
  selectedValue: number | null;
};

export function DeptPickerField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.searchEndpoint ?? '/api/mobile/departments');
  const value = useMemo(() => numericValue(props.value), [props.value]);
  const [state, setState] = useState<PickerState>({
    open: false,
    keyword: '',
    loading: false,
    results: [],
    selectedLabel: value == null ? '' : `部门${value}`,
    selectedValue: value,
  });

  useEffect(() => {
    setState((current) =>
      current.selectedValue === value
        ? current
        : {
            ...current,
            selectedValue: value,
            selectedLabel: value == null ? '' : `部门${value}`,
          },
    );
  }, [value]);

  useEffect(() => {
    if (!state.open) {
      return;
    }
    let active = true;
    setState((current) => ({ ...current, loading: true }));
    searchMobileDepartments(endpoint, state.keyword)
      .then((results) => {
        if (!active) {
          return;
        }
        setState((current) => ({ ...current, loading: false, results }));
      })
      .catch(() => {
        if (!active) {
          return;
        }
        setState((current) => ({ ...current, loading: false, results: [] }));
      });
    return () => {
      active = false;
    };
  }, [endpoint, state.keyword, state.open]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(state.selectedLabel || value) : undefined}
    >
      {props.mode === 'readonly' ? null : (
        <>
          <button
            type="button"
            className="control form-picker department-picker-control"
            onClick={() => setState((current) => ({ ...current, open: true }))}
          >
            <span className="department-mark" aria-hidden="true">
              {pickerInitial(state.selectedLabel || (value == null ? '' : String(value))) || <FolderOutline />}
            </span>
            <span className="picker-value">{state.selectedLabel || (value == null ? `选择${label}` : String(value))}</span>
            <RightOutline aria-hidden="true" />
          </button>
          {state.open ? (
            <MobileSelectionPopup
              visible={state.open}
              title={`选择${label}`}
              subtitle="搜索部门名称后选择"
              onClose={() => setState((current) => ({ ...current, open: false }))}
            >
              <input
                className="af-full-picker__search"
                type="search"
                aria-label={`搜索${label}`}
                placeholder="搜索部门"
                value={state.keyword}
                onChange={(event: ChangeEvent<HTMLInputElement>) =>
                  setState((current) => ({ ...current, keyword: event.target.value }))}
              />
              {state.loading ? <div className="af-full-picker__hint">加载中</div> : null}
              <div role="listbox" aria-label={label} className="af-full-picker__list">
                {state.results.map((item) => {
                  const title = `${item.name} ${item.id}`;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      role="option"
                      aria-label={title}
                      aria-selected={value === item.id}
                      className="af-full-picker__option"
                      onClick={() => {
                        setState((current) => ({
                          ...current,
                          open: false,
                          selectedLabel: item.name,
                          selectedValue: item.id,
                        }));
                        props.onValueChange(props.node.id, item.id);
                      }}
                    >
                      <span className="af-full-picker__avatar af-full-picker__avatar--dept" aria-hidden="true">{pickerInitial(item.name)}</span>
                      <span className="af-full-picker__option-text">
                        <strong>{title}</strong>
                        <small>{`部门编号 ${item.id}`}</small>
                      </span>
                    </button>
                  );
                })}
                {!state.loading && state.results.length === 0 ? (
                  <div className="af-full-picker__empty">暂无匹配部门</div>
                ) : null}
              </div>
            </MobileSelectionPopup>
          ) : null}
        </>
      )}
    </FieldShell>
  );
}

function numericValue(value: unknown) {
  return typeof value === 'number' ? value : null;
}

function pickerInitial(value: string) {
  return value.trim().slice(0, 1);
}
