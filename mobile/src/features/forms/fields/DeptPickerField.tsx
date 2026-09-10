import { FolderOutline, RightOutline } from 'antd-mobile-icons';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { fetchMobileDepartment, searchMobileDepartments, type MobilePickerDept } from '../files.api';
import { MobileSelectionPopup } from './MobileSelectionPopup';

type PickerState = {
  open: boolean;
  keyword: string;
  loading: boolean;
  results: MobilePickerDept[];
  selectedIds: number[];
  draftIds: number[];
  departments: Record<number, MobilePickerDept>;
};

export function DeptPickerField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.searchEndpoint ?? '/api/mobile/departments');
  const multiple = props.node.props?.multiple === true;
  const maxCount = positiveInteger(props.node.props?.maxCount);
  const valueIds = useMemo(() => departmentValues(props.value, multiple), [multiple, props.value]);
  const [state, setState] = useState<PickerState>({
    open: false,
    keyword: '',
    loading: false,
    results: [],
    selectedIds: valueIds,
    draftIds: valueIds,
    departments: {},
  });

  useEffect(() => {
    setState((current) => sameIds(current.selectedIds, valueIds) ? current : {
      ...current,
      selectedIds: valueIds,
      draftIds: current.open ? current.draftIds : valueIds,
    });
  }, [valueIds]);

  useEffect(() => {
    if (state.selectedIds.length === 0) return;
    let active = true;
    void Promise.all(state.selectedIds.map((id) =>
      fetchMobileDepartment(endpoint, id).catch(() => fallbackDepartment(id))))
      .then((departments) => {
        if (!active) return;
        setState((current) => ({
          ...current,
          departments: {
            ...current.departments,
            ...Object.fromEntries(departments.map((department) => [department.id, department])),
          },
        }));
      });
    return () => { active = false; };
  }, [endpoint, state.selectedIds]);

  useEffect(() => {
    if (!state.open) return;
    let active = true;
    setState((current) => ({ ...current, loading: true }));
    searchMobileDepartments(endpoint, state.keyword)
      .then((results) => {
        if (active) setState((current) => ({ ...current, loading: false, results }));
      })
      .catch(() => {
        if (active) setState((current) => ({ ...current, loading: false, results: [] }));
      });
    return () => { active = false; };
  }, [endpoint, state.keyword, state.open]);

  const selectedDepartments = state.selectedIds.map(
    (id) => state.departments[id] ?? fallbackDepartment(id),
  );
  const selectedNames = selectedDepartments.map((department) => department.name).join('、');
  const readonly = props.mode === 'readonly';

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={readonly ? <div className="af-field__summary">{selectedNames || '未填写'}</div> : undefined}
    >
      {!readonly ? (
        <>
          <button
            type="button"
            className="control form-picker department-picker-control"
            onClick={() => setState((current) => ({
              ...current,
              open: true,
              keyword: '',
              draftIds: current.selectedIds,
            }))}
          >
            <span className="department-mark" aria-hidden="true">
              {pickerInitial(selectedDepartments[0]?.name ?? '') || <FolderOutline />}
            </span>
            <span className="picker-value">{selectedNames || `选择${label}`}</span>
            <RightOutline aria-hidden="true" />
          </button>
          {state.open ? (
            <MobileSelectionPopup
              visible
              title={`选择${label}`}
              subtitle={multiple
                ? `已选 ${state.draftIds.length}${maxCount ? ` / ${maxCount}` : ''} 个部门`
                : '搜索部门名称后选择'}
              onClose={closePicker}
              footer={multiple ? (
                <>
                  <button type="button" className="btn btn--ghost btn--lg" onClick={closePicker}>
                    取消
                  </button>
                  <button type="button" className="btn btn--success btn--lg" onClick={confirmPicker}>
                    完成
                  </button>
                </>
              ) : undefined}
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
              <div
                role="listbox"
                aria-label={label}
                aria-multiselectable={multiple || undefined}
                className="af-full-picker__list"
              >
                {state.results.map((item) => {
                  const title = `${item.name} ${item.id}`;
                  const checked = (multiple ? state.draftIds : state.selectedIds).includes(item.id);
                  const disabled = multiple && !checked && maxCount != null
                    && state.draftIds.length >= maxCount;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      role="option"
                      aria-label={title}
                      aria-selected={checked}
                      disabled={disabled}
                      className="af-full-picker__option"
                      onClick={() => selectDepartment(item)}
                    >
                      <span className="af-full-picker__avatar af-full-picker__avatar--dept" aria-hidden="true">{pickerInitial(item.name)}</span>
                      <span className="af-full-picker__option-text">
                        <strong>{title}</strong>
                        <small>{`部门编号 ${item.id}`}</small>
                      </span>
                      {multiple ? (
                        <span className="af-full-picker__option-status" aria-hidden="true">
                          {checked ? '✓' : ''}
                        </span>
                      ) : null}
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
      ) : null}
    </FieldShell>
  );

  function closePicker() {
    setState((current) => ({
      ...current,
      open: false,
      keyword: '',
      draftIds: current.selectedIds,
    }));
  }

  function confirmPicker() {
    const ids = state.draftIds;
    setState((current) => ({ ...current, open: false, keyword: '', selectedIds: ids }));
    props.onValueChange(props.node.id, ids);
  }

  function selectDepartment(department: MobilePickerDept) {
    if (!multiple) {
      setState((current) => ({
        ...current,
        open: false,
        selectedIds: [department.id],
        draftIds: [department.id],
        departments: { ...current.departments, [department.id]: department },
      }));
      props.onValueChange(props.node.id, department.id);
      return;
    }
    setState((current) => {
      const checked = current.draftIds.includes(department.id);
      if (!checked && maxCount != null && current.draftIds.length >= maxCount) return current;
      return {
        ...current,
        draftIds: checked
          ? current.draftIds.filter((id) => id !== department.id)
          : [...current.draftIds, department.id],
        departments: { ...current.departments, [department.id]: department },
      };
    });
  }
}

export function departmentValues(value: unknown, multiple: boolean) {
  const values = multiple ? (Array.isArray(value) ? value : [value]) : [value];
  return [...new Set(values.filter((item): item is number =>
    typeof item === 'number' && Number.isSafeInteger(item) && item > 0))]
    .slice(0, multiple ? undefined : 1);
}

function positiveInteger(value: unknown) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined;
}

function sameIds(left: number[], right: number[]) {
  return left.length === right.length && left.every((id, index) => id === right[index]);
}

function fallbackDepartment(id: number): MobilePickerDept {
  return { id, name: `部门 #${id}` };
}

function pickerInitial(value: string) {
  return value.trim().slice(0, 1);
}
