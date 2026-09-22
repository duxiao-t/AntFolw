import { request } from '@umijs/max';
import { useEffect, useRef, useState } from 'react';
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

/**
 * 上游字段变更后拉一次候选：**恰好一条就回填**（“一一对应”），否则清空、交回用户自己选。
 *
 * 文本联动（dataLinkage）与依赖型下拉（optionSource.dependency）都遵循这一条规则，
 * 所以抽在一处，避免两份分头漂移。
 * 已有草稿与历史回显的原值不触发——只有用户真的改了上游才重算。
 */
function useUniqueMatchRefill({ active, nodeId, parentId, parentValue, values, context,
  refillKey, apply }: {
  active: boolean;
  nodeId: string;
  parentId?: string;
  parentValue: unknown;
  values: Record<string, any>;
  context?: OptionContext;
  /** 额外触发重算的键（如目标列、数据源版本）。 */
  refillKey?: unknown;
  apply: (match: RuntimeOption | undefined) => void;
}) {
  const previous = useRef(parentValue);
  const applyRef = useRef(apply);
  applyRef.current = apply;
  const valuesRef = useRef(values);
  valuesRef.current = values;
  useEffect(() => {
    if (!active || !context || !(context.formCode || context.instanceId || context.dataId)) return;
    if (previous.current === parentValue) return;
    previous.current = parentValue;
    let cancelled = false;
    applyRef.current(undefined);
    if (parentValue == null || parentValue === '') return;
    request<Page>('/api/runtime/form-options/query', {
      method: 'POST',
      data: { ...context, fieldId: nodeId, values: valuesRef.current, page: 1, size: 2, path: [] },
    }).then((result) => {
      if (cancelled) return;
      applyRef.current(result.total === 1 ? result.items[0] : undefined);
    }).catch(() => { if (!cancelled) applyRef.current(undefined); });
    return () => { cancelled = true; };
  }, [active, context?.dataId, context?.formCode, context?.formVersion, context?.instanceId,
    nodeId, parentId, parentValue, refillKey]);
}

export function useLinkedValue(
  node: SchemaNode,
  values: Record<string, any> = {},
  context: OptionContext | undefined,
  onChange?: (value: any) => void,
) {
  const linkage = node.props?.dataLinkage as Record<string, any> | undefined;
  const parentId = linkage?.fieldId as string | undefined;
  useUniqueMatchRefill({
    active: Boolean(linkage),
    nodeId: node.id,
    parentId,
    parentValue: parentId ? values[parentId] : undefined,
    values,
    context,
    refillKey: `${linkage?.valueColumn ?? ''}|${node.type}`,
    apply: (match) => {
      if (!match) { onChange?.(undefined); return; }
      const raw = match.value;
      if (node.type === 'number') {
        const next = Number(raw);
        onChange?.(!Number.isFinite(next) || String(raw).trim() === '' ? undefined : next);
        return;
      }
      onChange?.(raw);
    },
  });
}

export function useDynamicOptions(
  node: SchemaNode,
  values: Record<string, any> = {},
  context?: OptionContext,
  onChange?: (value: any) => void,
) {
  const [path, setPath] = useState<string[]>([]);
  const [result, setResult] = useState<Page | null>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [labels, setLabels] = useState<RuntimeOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  // 候选按页累积：下拉滚到底继续取下一页，列表里只出现一次。
  const [items, setItems] = useState<RuntimeOption[]>([]);
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

  useUniqueMatchRefill({
    // 依赖型**单选**下拉：上游变更后过滤结果只剩一个候选就自动选中；
    // 多个候选保持现状由用户自己挑。多选不自动选中——用户可能一个都不要。
    active: Boolean(source?.dependency?.fieldId) && node.type === 'select' && dynamic,
    nodeId: node.id,
    parentId,
    parentValue,
    values,
    context,
    refillKey: source?.versionId,
    apply: (match) => onChange?.(match ? match.value : undefined),
  });

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
      setItems([]);
      return;
    }
    let cancelled = false;
    setError(undefined);
    if (pageNumber === 1) setLoading(true); else setLoadingMore(true);
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
      .then((response) => {
        if (cancelled) return;
        setResult(response);
        setItems((current) => (pageNumber === 1 ? response.items : [...current, ...response.items]));
      })
      .catch((reason) => { if (!cancelled) setError(reason?.message ?? '选项加载失败'); })
      .finally(() => { if (!cancelled) { setLoading(false); setLoadingMore(false); } });
    return () => { cancelled = true; };
  }, [context?.dataId, context?.formCode, context?.formVersion, context?.instanceId, dynamic, node.id, path, parentId, parentValue, pageNumber, keyword, filterKey]);

  const total = result?.total ?? 0;
  const stage = result?.stage ?? 'OPTIONS';
  const hasMore = stage === 'OPTIONS' && items.length > 0 && items.length < total;
  const advance = (value: string) => {
    if (result?.stage === 'LEVEL') { setPath((current) => [...current, value]); setPageNumber(1); setKeyword(''); }
    return result?.stage === 'OPTIONS';
  };
  const reset = () => { setPath([]); setPageNumber(1); setKeyword(''); };
  return { dynamic, options: items, labels, loading, loadingMore, hasMore, error, stage, path,
    total, keyword, search: (next: string) => { setPageNumber(1); setKeyword(next); },
    // 下拉滚到底继续取下一页；加载中或已到底时不重复触发。
    loadMore: () => { if (!loading && !loadingMore && hasMore) setPageNumber((n) => n + 1); },
    back: () => { setPath((current) => current.slice(0, -1)); setPageNumber(1); }, advance, reset };
}
