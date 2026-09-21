import { request } from '@umijs/max';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { SchemaNode } from '../../registry/types';

export type RuntimeOption = { value: string; label: string };
export type OptionContext = {
  formCode?: string;
  formVersion?: number;
  instanceId?: number;
  dataId?: number;
};

type Page = {
  stage: 'LEVEL' | 'OPTIONS';
  level: number;
  totalLevels: number;
  items: RuntimeOption[];
  total: number;
};

/**
 * 只有带正整数 sourceId 的 optionSource 才算"已绑定"。
 * 设计器在"选择数据源版本"之前会写入空对象 {}，那不是绑定：若按绑定额处理，
 * 前端会去查选项、后端会报"数据源和版本不能为空"，表单既存不了版本也发不出去。
 */
export function isBoundOptionSource(props: SchemaNode['props'] | undefined) {
  const source = props?.optionSource as { sourceId?: unknown } | undefined;
  return Boolean(source) && typeof source === 'object'
    && Number.isInteger(source?.sourceId) && (source?.sourceId as number) > 0;
}

export function isDynamicOption(node: SchemaNode) {
  return isBoundOptionSource(node.props);
}

export function useLinkedValue(
  node: SchemaNode,
  values: Record<string, any> = {},
  context: OptionContext | undefined,
  onChange?: (value: any) => void,
) {
  const linkage = node.props?.dataLinkage as Record<string, any> | undefined;
  const parentId = linkage?.fieldId as string | undefined;
  const parentValue = parentId ? values[parentId] : undefined;
  const previous = useRef(parentValue);
  const callback = useRef(onChange);
  callback.current = onChange;
  useEffect(() => {
    if (!linkage || !context || !(context.formCode || context.instanceId || context.dataId)) return;
    // Hydrated drafts and history keep their saved values; only a user changing A triggers a refill.
    if (previous.current === parentValue) return;
    let cancelled = false;
    previous.current = parentValue;
    callback.current?.(undefined);
    if (parentValue == null || parentValue === '') return;
    request<Page>('/api/runtime/form-options/query', {
      method: 'POST',
      data: { ...context, fieldId: node.id, values, page: 1, size: 2, path: [] },
    }).then((result) => {
      if (cancelled) return;
      if (result.total === 1) {
        const raw = result.items[0].value;
        const next = node.type === 'number' ? Number(raw) : raw;
        callback.current?.(node.type === 'number' && (!Number.isFinite(next) || raw.trim() === '') ? undefined : next);
      } else {
        callback.current?.(undefined);
      }
    }).catch(() => { if (!cancelled) callback.current?.(undefined); });
    return () => { cancelled = true; };
  }, [context?.dataId, context?.formCode, context?.formVersion, context?.instanceId, linkage?.fieldId, linkage?.valueColumn, node.id, node.type, parentValue]);
}

export function useDynamicOptions(
  node: SchemaNode,
  values: Record<string, any> = {},
  context?: OptionContext,
) {
  const [path, setPath] = useState<string[]>([]);
  const [result, setResult] = useState<Page | null>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [labels, setLabels] = useState<RuntimeOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const source = node.props?.optionSource as Record<string, any> | undefined;
  const dynamic = Boolean(source && context && (context.formCode || context.instanceId || context.dataId));
  const parentId = source?.dependency?.fieldId as string | undefined;
  const parentValue = parentId ? values[parentId] : undefined;
  const selected = values[node.id];
  const selectedValues = Array.isArray(selected) ? selected : selected == null ? [] : [selected];
  const selectedKey = JSON.stringify(selectedValues);
  const filterKey = JSON.stringify(Object.fromEntries(Object.entries(values).filter(([id]) => id !== node.id)));

  useEffect(() => {
    setPath([]);
    setPageNumber(1);
    setKeyword('');
  }, [node.id, parentValue, source?.versionId]);

  useEffect(() => {
    if (!dynamic || selectedValues.length === 0) { setLabels([]); return; }
    let cancelled = false;
    request<Page>('/api/runtime/form-options/query', {
      method: 'POST', data: { ...context, fieldId: node.id, selectedValues, page: 1, size: 100 },
    }).then((response) => { if (!cancelled) setLabels(response.items); }).catch(() => {});
    return () => { cancelled = true; };
  }, [context?.dataId, context?.formCode, context?.formVersion, context?.instanceId, dynamic, node.id, selectedKey]);

  useEffect(() => {
    if (!dynamic) {
      setResult(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(undefined);
    const body = {
      ...context,
      fieldId: node.id,
      keyword,
      page: pageNumber,
      size: 20,
      values: JSON.parse(filterKey),
      path,
    };
    request<Page>('/api/runtime/form-options/query', { method: 'POST', data: body })
      .then((response) => { if (!cancelled) setResult(response); })
      .catch((reason) => { if (!cancelled) setError(reason?.message ?? '选项加载失败'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [context?.dataId, context?.formCode, context?.formVersion, context?.instanceId, dynamic, node.id, path, parentId, parentValue, pageNumber, keyword, filterKey]);

  const options = useMemo(() => result?.items ?? [], [result]);
  const advance = (value: string) => {
    if (result?.stage === 'LEVEL') { setPath((current) => [...current, value]); setPageNumber(1); setKeyword(''); }
    return result?.stage === 'OPTIONS';
  };
  const reset = () => { setPath([]); setPageNumber(1); setKeyword(''); };
  return { dynamic, options, labels, loading, error, stage: result?.stage ?? 'OPTIONS', path,
    total: result?.total ?? 0, page: pageNumber, keyword, search: (next: string) => { setPageNumber(1); setKeyword(next); },
    next: () => setPageNumber((n) => n + 1), back: () => { setPath((current) => current.slice(0, -1)); setPageNumber(1); }, advance, reset };
}
