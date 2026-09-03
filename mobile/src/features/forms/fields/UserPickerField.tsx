import { RightOutline, UserOutline } from 'antd-mobile-icons';
import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';
import { fetchMobileUser, searchMobileUsers, type MobilePickerUser } from '../files.api';
import { MobileSelectionPopup } from './MobileSelectionPopup';

type PickerState = {
  open: boolean;
  keyword: string;
  loading: boolean;
  results: MobilePickerUser[];
  selectedUser: MobilePickerUser | null;
  selectedValue: number | null;
};

export function UserPickerField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.searchEndpoint ?? '/api/mobile/users');
  const value = useMemo(() => numericValue(props.value), [props.value]);
  const pendingSelection = useRef<number | null>(null);
  const [state, setState] = useState<PickerState>({
    open: false,
    keyword: '',
    loading: false,
    results: [],
    selectedUser: null,
    selectedValue: value,
  });

  useEffect(() => {
    if (value == null) {
      if (pendingSelection.current != null) return;
      setState((current) => current.selectedValue == null ? current : {
        ...current, selectedValue: null, selectedUser: null,
      });
      return;
    }
    if (pendingSelection.current === value) pendingSelection.current = null;
    if (state.selectedValue === value && state.selectedUser?.id === value) return;
    let active = true;
    setState((current) => ({ ...current, selectedValue: value }));
    fetchMobileUser(endpoint, value).then((selectedUser) => {
      if (active) setState((current) => ({ ...current, selectedUser }));
    }).catch(() => {
      if (active) setState((current) => ({ ...current, selectedUser: fallbackUser(value) }));
    });
    return () => { active = false; };
  }, [endpoint, state.selectedUser?.id, state.selectedValue, value]);

  useEffect(() => {
    if (!state.open) return;
    let active = true;
    setState((current) => ({ ...current, loading: true }));
    searchMobileUsers(endpoint, state.keyword)
      .then((results) => {
        if (active) setState((current) => ({ ...current, loading: false, results }));
      })
      .catch(() => {
        if (active) setState((current) => ({ ...current, loading: false, results: [] }));
      });
    return () => { active = false; };
  }, [endpoint, state.keyword, state.open]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(identityText(state.selectedUser, value)) : undefined}
    >
      {props.mode === 'readonly' ? null : (
        <>
          <button
            type="button"
            className="control form-picker user-picker-control"
            onClick={() => setState((current) => ({ ...current, open: true }))}
          >
            <span className="user-stack" aria-hidden="true">
              <span className="user-avatar">{pickerInitial(state.selectedUser?.displayName ?? '') || <UserOutline />}</span>
            </span>
            <span className="picker-value user-picker-control__identity">
              <strong>{state.selectedUser?.displayName || (value == null ? `选择${label}` : `用户${value}`)}</strong>
              {state.selectedUser || value != null ? <small>{identityMeta(state.selectedUser, value)}</small> : null}
            </span>
            <RightOutline aria-hidden="true" />
          </button>
          {state.open ? (
            <MobileSelectionPopup
              visible={state.open}
              title={`选择${label}`}
              subtitle="搜索姓名或工号后选择"
              onClose={() => setState((current) => ({ ...current, open: false }))}
            >
              <input
                className="af-full-picker__search"
                type="search"
                aria-label={`搜索${label}`}
                placeholder="搜索姓名或工号"
                value={state.keyword}
                onChange={(event: ChangeEvent<HTMLInputElement>) =>
                  setState((current) => ({ ...current, keyword: event.target.value }))}
              />
              {state.loading ? <div className="af-full-picker__hint">加载中</div> : null}
              <div role="listbox" aria-label={label} className="af-full-picker__list">
                {state.results.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    role="option"
                    aria-label={identityText(item, item.id)}
                    aria-selected={value === item.id}
                    className="af-full-picker__option"
                    onClick={() => {
                      pendingSelection.current = item.id;
                      setState((current) => ({
                        ...current, open: false, selectedUser: item, selectedValue: item.id,
                      }));
                      props.onValueChange(props.node.id, item.id);
                    }}
                  >
                    <span className="af-full-picker__avatar" aria-hidden="true">{pickerInitial(item.displayName)}</span>
                    <span className="af-full-picker__option-text">
                      <strong>{item.displayName}</strong>
                      <small>{identityMeta(item, item.id)}</small>
                    </span>
                  </button>
                ))}
                {!state.loading && state.results.length === 0 ? <div className="af-full-picker__empty">暂无匹配人员</div> : null}
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

function fallbackUser(id: number): MobilePickerUser {
  return { id, displayName: `用户${id}` };
}

function identityMeta(user: MobilePickerUser | null | undefined, id: number | null) {
  const department = user?.department || '未设置部门';
  const employeeNo = user?.employeeNo || user?.username || (id == null ? '未设置' : String(id));
  return `${department} · 工号 ${employeeNo}`;
}

function identityText(user: MobilePickerUser | null | undefined, id: number | null) {
  const name = user?.displayName || (id == null ? '' : `用户${id}`);
  return name ? `${name} · ${identityMeta(user, id)}` : '';
}
