import { RightOutline, UserOutline } from 'antd-mobile-icons';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { fetchMobileUser, searchMobileUsers, type MobilePickerUser } from '../files.api';
import { MobileSelectionPopup } from './MobileSelectionPopup';

type PickerState = {
  open: boolean;
  keyword: string;
  loading: boolean;
  results: MobilePickerUser[];
  selectedIds: number[];
  draftIds: number[];
  users: Record<number, MobilePickerUser>;
};

export function UserPickerField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.searchEndpoint ?? '/api/mobile/users');
  const multiple = props.node.props?.multiple === true;
  const maxCount = positiveInteger(props.node.props?.maxCount);
  const valueIds = useMemo(() => pickerValues(props.value, multiple), [multiple, props.value]);
  const [state, setState] = useState<PickerState>({
    open: false,
    keyword: '',
    loading: false,
    results: [],
    selectedIds: valueIds,
    draftIds: valueIds,
    users: {},
  });
  useEffect(() => {
    setState((current) => sameIds(current.selectedIds, valueIds) ? current : {
      ...current,
      selectedIds: valueIds,
      draftIds: current.open ? current.draftIds : valueIds,
    });
  }, [valueIds]);

  useEffect(() => {
    const ids = state.selectedIds;
    if (ids.length === 0) return;
    let active = true;
    Promise.all(ids.map((id) => fetchMobileUser(endpoint, id).catch(() => fallbackUser(id))))
      .then((users) => {
        if (!active) return;
        setState((current) => ({
          ...current,
          users: { ...current.users, ...Object.fromEntries(users.map((user) => [user.id, user])) },
        }));
      });
    return () => { active = false; };
  }, [endpoint, state.selectedIds]);

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

  const selectedUsers = state.selectedIds.map((id) => state.users[id] ?? fallbackUser(id));
  const readonly = props.mode === 'readonly';

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={readonly ? (
        <div className="af-field__summary user-picker-summary">
          {selectedUsers.length > 0 ? selectedUsers.map((user) => (
            <div key={user.id} className="user-picker-summary__item">
              <strong>{user.displayName}</strong>
              <small>{identityMeta(user, user.id)}</small>
            </div>
          )) : '未填写'}
        </div>
      ) : undefined}
    >
      {!readonly ? (
        <>
          <button
            type="button"
            className="control form-picker user-picker-control"
            onClick={() => setState((current) => ({
              ...current,
              open: true,
              keyword: '',
              draftIds: current.selectedIds,
            }))}
          >
            <span className="user-stack" aria-hidden="true">
              {selectedUsers.slice(0, 3).map((user) => (
                <span key={user.id} className="user-avatar">
                  {pickerInitial(user.displayName) || <UserOutline />}
                </span>
              ))}
              {selectedUsers.length === 0 ? <span className="user-avatar"><UserOutline /></span> : null}
            </span>
            <span className="picker-value user-picker-control__identity">
              <strong>
                {selectedUsers.length > 0
                  ? selectedUsers.map((user) => user.displayName).join('、')
                  : `选择${label}`}
              </strong>
              {selectedUsers.length === 1 ? (
                <small>{identityMeta(selectedUsers[0], selectedUsers[0]?.id ?? null)}</small>
              ) : selectedUsers.length > 1 ? <small>已选择 {selectedUsers.length} 人</small> : null}
            </span>
            <RightOutline aria-hidden="true" />
          </button>
          {state.open ? (
            <MobileSelectionPopup
              visible
              title={`选择${label}`}
              subtitle={multiple
                ? `已选 ${state.draftIds.length}${maxCount ? ` / ${maxCount}` : ''} 人`
                : '搜索姓名或工号后选择'}
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
                placeholder="搜索姓名或工号"
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
                  const checked = (multiple ? state.draftIds : state.selectedIds).includes(item.id);
                  const disabled = multiple && !checked && maxCount != null
                    && state.draftIds.length >= maxCount;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      role="option"
                      aria-label={identityText(item, item.id)}
                      aria-selected={checked}
                      disabled={disabled}
                      className="af-full-picker__option"
                      onClick={() => selectUser(item)}
                    >
                      <span className="af-full-picker__avatar" aria-hidden="true">{pickerInitial(item.displayName)}</span>
                      <span className="af-full-picker__option-text">
                        <strong>{item.displayName}</strong>
                        <small>{identityMeta(item, item.id)}</small>
                      </span>
                      {multiple ? (
                        <span className="af-full-picker__option-status" aria-hidden="true">
                          {checked ? '✓' : ''}
                        </span>
                      ) : null}
                    </button>
                  );
                })}
                {!state.loading && state.results.length === 0 ? <div className="af-full-picker__empty">暂无匹配人员</div> : null}
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

  function selectUser(user: MobilePickerUser) {
    if (!multiple) {
      setState((current) => ({
        ...current,
        open: false,
        selectedIds: [user.id],
        draftIds: [user.id],
        users: { ...current.users, [user.id]: user },
      }));
      props.onValueChange(props.node.id, user.id);
      return;
    }
    setState((current) => {
      const checked = current.draftIds.includes(user.id);
      if (!checked && maxCount != null && current.draftIds.length >= maxCount) return current;
      return {
        ...current,
        draftIds: checked
          ? current.draftIds.filter((id) => id !== user.id)
          : [...current.draftIds, user.id],
        users: { ...current.users, [user.id]: user },
      };
    });
  }
}

export function pickerValues(value: unknown, multiple: boolean) {
  const values = multiple ? (Array.isArray(value) ? value : [value]) : [value];
  return [...new Set(values.filter((item): item is number =>
    typeof item === 'number' && Number.isSafeInteger(item)))]
    .slice(0, multiple ? undefined : 1);
}

function positiveInteger(value: unknown) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined;
}

function sameIds(left: number[], right: number[]) {
  return left.length === right.length && left.every((id, index) => id === right[index]);
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
