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

/** 每页候选数；列表滚到底再取下一页。 */
const PAGE_SIZE = 20;

/**
 * 只有带正整数 sourceId 的 optionSource 才算"已绑定"。
 * 设计器在"选择数据源版本"之前会写入空对象 {}，那不是绑定；按绑定额处理会去查选项并拿到
 * "数据源和版本不能为空"的后端错误。
 */
export function isBoundOptionSource(props: { optionSource?: unknown } | undefined): boolean {
  const source = props?.optionSource as { sourceId?: unknown } | undefined;
  return Boolean(source) && typeof source === 'object'
    && Number.isInteger(source?.sourceId) && (source?.sourceId as number) > 0;
}

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
  const [result, setResult] = useState<OptionPage>();
  // 候选按页累积：滚到底继续取下一页，列表里只出现一次。
  const [items, setItems] = useState<OptionItem[]>([]);
  const [page, setPage] = useState(1);
  const [labels, setLabels] = useState<OptionItem[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const selected = Array.isArray(props.value) ? props.value : props.value == null || props.value === '' ? [] : [props.value];
  const selectedKey = JSON.stringify(selected.map(String));
  const filterKey = JSON.stringify(Object.fromEntries(Object.entries(props.values).filter(([id]) => id !== props.node.id)));
  const contextKey = JSON.stringify(props.optionContext ?? {});
  const context = props.optionContext;

  useEffect(() => { setPath([]); setKeyword(''); setPage(1); }, [parentValue, props.node.id, source?.versionId]);
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
    setError('');
    if (page === 1) setLoading(true); else setLoadingMore(true);
    void queryOptions(context, { fieldId: props.node.id, values: JSON.parse(filterKey), path,
      keyword, page, size: PAGE_SIZE })
      .then((response) => {
        if (!active) return;
        setResult(response);
        setItems((current) => (page === 1 ? response.items : [...current, ...response.items]));
      })
      .catch((reason) => { if (active) setError(reason?.message ?? '选项加载失败'); })
      .finally(() => { if (active) { setLoading(false); setLoadingMore(false); } });
    return () => { active = false; };
  }, [visible, contextKey, props.node.id, parentValue, dependency?.fieldId, filterKey, path, keyword, page]);
  // 一一对应：上游变更后过滤结果只剩一个候选就自动选中，多个候选保持现状由用户自己挑。
  // 多选不自动选中（用户可能一个都不要）；已有草稿与历史回显不触发。
  const autoFillPrevious = useRef(parentValue);
  // 同桌面端：挂载时上游多为空、草稿值随后才回填，把"空 → 有值"当用户改动会清掉已保存的值。
  const autoFillLoaded = useRef(parentValue != null && parentValue !== '');
  const autoFillCallback = useRef(props.onValueChange);
  autoFillCallback.current = props.onValueChange;
  useEffect(() => {
    if (!dependency?.fieldId || props.node.type !== 'select' || !context) return;
    if (!autoFillLoaded.current) {
      if (parentValue == null || parentValue === '') return;
      autoFillLoaded.current = true;
      autoFillPrevious.current = parentValue;
      return;
    }
    if (autoFillPrevious.current === parentValue) return;
    autoFillPrevious.current = parentValue;
    autoFillCallback.current(props.node.id, undefined);
    if (parentValue == null || parentValue === '') return;
    let active = true;
    void queryOptions(context, { fieldId: props.node.id, values: props.values, size: 2, path: [] })
      .then((response) => {
        if (!active) return;
        const item = response.total === 1 ? response.items[0] : undefined;
        autoFillCallback.current(props.node.id, item ? item.value : undefined);
      }).catch(() => {});
    return () => { active = false; };
  }, [contextKey, dependency?.fieldId, parentValue, props.node.id, props.node.type, source?.versionId]);

  const choose = (value: string) => {
    if (result?.stage === 'LEVEL') {
      setPath((before) => [...before, value]); setKeyword(''); setPage(1);
      return false;
    }
    return true;
  };
  const hasMore = result?.stage === 'OPTIONS' && items.length > 0 && items.length < (result?.total ?? 0);
  return { result, items, labels, path, keyword, error, loading, loadingMore, hasMore, choose,
    setKeyword: (value: string) => { setKeyword(value); setPage(1); },
    back: () => { setPath((before) => before.slice(0, -1)); setKeyword(''); setPage(1); },
    // 滚到底继续取下一页；加载中或已到底时不重复触发。
    loadMore: () => { if (!loading && !loadingMore && hasMore) setPage((before) => before + 1); },
  };
}

export function useLinkedValue(props: MobileFieldProps) {
  const link = props.node.props?.dataLinkage as { fieldId?: string; valueColumn?: string } | undefined;
  const parentValue = link?.fieldId ? props.values[link.fieldId] : undefined;
  const previous = useRef(parentValue);
  // 同 useDynamicOptions：第一次看到有效上游值只当装载，否则加载草稿会清掉已保存的联动值。
  const loaded = useRef(parentValue != null && parentValue !== '');
  const callback = useRef(props.onValueChange);
  callback.current = props.onValueChange;
  const contextKey = JSON.stringify(props.optionContext ?? {});
  useEffect(() => {
    if (!link?.fieldId || props.mode !== 'fill' || !props.optionContext) return;
    if (!loaded.current) {
      if (parentValue == null || parentValue === '') return;
      loaded.current = true;
      previous.current = parentValue;
      return;
    }
    if (previous.current === parentValue) return;
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
