import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';
import { searchMobileUsers, type MobilePickerUser } from '../files.api';

type PickerState = {
  open: boolean;
  keyword: string;
  loading: boolean;
  results: MobilePickerUser[];
  selectedLabel: string;
  selectedValue: number | null;
};

export function UserPickerField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.searchEndpoint ?? '/api/mobile/users');
  const value = useMemo(() => numericValue(props.value), [props.value]);
  const [state, setState] = useState<PickerState>({
    open: false,
    keyword: '',
    loading: false,
    results: [],
    selectedLabel: value == null ? '' : `用户${value}`,
    selectedValue: value,
  });

  useEffect(() => {
    setState((current) =>
      current.selectedValue === value
        ? current
        : {
            ...current,
            selectedValue: value,
            selectedLabel: value == null ? '' : `用户${value}`,
          },
    );
  }, [value]);

  useEffect(() => {
    if (!state.open) {
      return;
    }
    let active = true;
    setState((current) => ({ ...current, loading: true }));
    searchMobileUsers(endpoint, state.keyword)
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
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(state.selectedLabel || value) : undefined}
    >
      {props.mode === 'readonly' ? null : (
        <>
          <button type="button" onClick={() => setState((current) => ({ ...current, open: true }))}>
            {state.selectedLabel || (value == null ? `选择${label}` : String(value))}
          </button>
          {state.open ? (
            <div role="dialog" aria-label={label}>
              <input
                aria-label={`搜索${label}`}
                placeholder="搜索姓名或账号"
                value={state.keyword}
                onChange={(event) => setState((current) => ({ ...current, keyword: event.target.value }))}
              />
              <button type="button" onClick={() => setState((current) => ({ ...current, open: false }))}>
                关闭
              </button>
              {state.loading ? <div>加载中</div> : null}
              <div role="listbox" aria-label={label}>
                {state.results.map((item) => {
                  const title = `${item.displayName} ${item.id}`;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      role="option"
                      aria-selected={value === item.id}
                      onClick={() => {
                        setState((current) => ({
                          ...current,
                          open: false,
                          selectedLabel: item.displayName,
                          selectedValue: item.id,
                        }));
                        props.onValueChange(props.node.id, item.id);
                      }}
                    >
                      {title}
                    </button>
                  );
                })}
              </div>
            </div>
          ) : null}
        </>
      )}
    </FieldShell>
  );
}

function numericValue(value: unknown) {
  return typeof value === 'number' ? value : null;
}
