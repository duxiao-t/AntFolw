import { useEffect, useRef, useState } from 'react';
import { apiRequest } from '../../../shared/api/http';
import type { MobileFieldProps, MobileFormValues } from '../schema/types';

export type OptionContext = {
  formCode?: string;
  formVersion?: number;
  instanceId?: number;
  dataId?: number;
  previewFormId?: number;
};
export type OptionItem = { value: string; label: string };
export type OptionPage = { stage: 'LEVEL' | 'OPTIONS'; level: number; totalLevels: number;
  items: OptionItem[]; total: number; page: number; size: number };
export type OptionRequest = { fieldId: string; values?: MobileFormValues; path?: string[];
  keyword?: string; page?: number; size?: number; selectedValues?: string[] };

export function queryOptions(context: OptionContext, query: OptionRequest): Promise<OptionPage> {
  const { previewFormId, ...publicContext } = context;
  if (!previewFormId) return apiRequest('/api/runtime/form-options/query', {
    method: 'POST', body: JSON.stringify({ ...publicContext, ...query }),
  });
  const origin = document.referrer ? new URL(document.referrer).origin : window.location.origin;
  const id = `${Date.now()}-${Math.random()}`;
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => { window.removeEventListener('message', receive); reject(new Error('预览选项加载超时')); }, 15000);
    function receive(event: MessageEvent) {
      if (event.source !== window.parent || event.origin !== origin || event.data?.type !== 'antflow:option-preview:result'
        || event.data.id !== id) return;
      window.clearTimeout(timeout);
      window.removeEventListener('message', receive);
      if (event.data.error) reject(new Error(event.data.error));
      else resolve(event.data.result as OptionPage);
    }
    window.addEventListener('message', receive);
    window.parent.postMessage({ type: 'antflow:option-preview:query', id, formId: previewFormId, query }, origin);
  });
}

export function useDynamicOptions(props: MobileFieldProps, visible: boolean) {
  const source = props.node.props?.optionSource as Record<string, unknown> | undefined;
  const dependency = source?.dependency as { fieldId?: string } | undefined;
  const parentValue = dependency?.fieldId ? props.values[dependency.fieldId] : undefined;
  const [path, setPath] = useState<string[]>([]);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<OptionPage>();
  const [labels, setLabels] = useState<OptionItem[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const selected = Array.isArray(props.value) ? props.value : props.value == null || props.value === '' ? [] : [props.value];
  const selectedKey = JSON.stringify(selected.map(String));
  const filterKey = JSON.stringify(Object.fromEntries(Object.entries(props.values).filter(([id]) => id !== props.node.id)));
  const contextKey = JSON.stringify(props.optionContext ?? {});
  const context = props.optionContext;

  useEffect(() => { setPath([]); setPage(1); setKeyword(''); }, [parentValue, props.node.id, source?.versionId]);
  useEffect(() => {
    if (!context || selected.length === 0) { setLabels([]); return; }
    let active = true;
    void queryOptions(context, { fieldId: props.node.id, selectedValues: selected.map(String) })
      .then((response) => { if (active) setLabels(response.items); }).catch(() => {});
    return () => { active = false; };
  }, [contextKey, props.node.id, selectedKey]);
  useEffect(() => {
    if (!visible || !context || dependency?.fieldId && (parentValue == null || parentValue === '')) return;
    let active = true;
    setLoading(true); setError('');
    void queryOptions(context, { fieldId: props.node.id, values: JSON.parse(filterKey), path,
      keyword, page, size: 20 }).then((response) => { if (active) setResult(response); })
      .catch((reason) => { if (active) setError(reason?.message ?? '选项加载失败'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [visible, contextKey, props.node.id, parentValue, dependency?.fieldId, filterKey, path, keyword, page]);
  const choose = (value: string) => {
    if (result?.stage === 'LEVEL') {
      setPath((before) => [...before, value]); setKeyword(''); setPage(1);
      return false;
    }
    return true;
  };
  return { result, labels, path, page, keyword, error, loading, choose,
    setKeyword: (v: string) => { setKeyword(v); setPage(1); },
    back: () => { setPath((before) => before.slice(0, -1)); setPage(1); setKeyword(''); },
    next: () => setPage((before) => before + 1),
    previous: () => setPage((before) => Math.max(1, before - 1)),
  };
}

export function useLinkedValue(props: MobileFieldProps) {
  const link = props.node.props?.dataLinkage as { fieldId?: string; valueColumn?: string } | undefined;
  const parentValue = link?.fieldId ? props.values[link.fieldId] : undefined;
  const previous = useRef(parentValue);
  const callback = useRef(props.onValueChange);
  callback.current = props.onValueChange;
  const contextKey = JSON.stringify(props.optionContext ?? {});
  useEffect(() => {
    if (!link?.fieldId || props.mode !== 'fill' || !props.optionContext || previous.current === parentValue) return;
    previous.current = parentValue;
    callback.current(props.node.id, undefined);
    if (parentValue == null || parentValue === '') return;
    let active = true;
    void queryOptions(props.optionContext, { fieldId: props.node.id, values: props.values, size: 2 })
      .then((result) => {
        const item = result.items[0];
        if (!active || result.total !== 1 || !item) return;
        const raw = item.value;
        const value = props.node.type === 'number' ? Number(raw) : raw;
        if (props.node.type !== 'number' || raw.trim() && Number.isFinite(value)) callback.current(props.node.id, value);
      }).catch(() => {});
    return () => { active = false; };
  }, [parentValue, link?.fieldId, link?.valueColumn, props.node.id, props.node.type, props.mode, contextKey]);
}
