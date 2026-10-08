import { Link, request } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { Alert, Checkbox, Input, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { isBoundOptionSource } from '../../../components/form-fields/dynamicOptions';
import type { SchemaNode } from '../../../registry/types';
import { type BindableSource, versionOptionGroups } from './optionSourceVersions';

type Source = BindableSource;

/** 这些下拉的候选项是列名/版本名，比默认宽度长得多；给足约 10 个汉字的宽度，避免只露两三个字。 */
const SELECT_WIDTH = 190;

/** 展开分栏容器里的字段。**必须递归**：服务端 scopeFor 是递归的，
 * 这里只摊平一层的话，嵌在第二层分栏里的下拉会找不到自己的 scope 和上游字段。 */
function flattenScopes(nodes: SchemaNode[]): SchemaNode[] {
  const flat: SchemaNode[] = [];
  for (const node of nodes) {
    flat.push(node);
    if (node.type === 'span_layout') flat.push(...flattenScopes(node.children ?? []));
  }
  return flat;
}

function siblings(nodes: SchemaNode[], id: string): SchemaNode[] {
  const flat = flattenScopes(nodes);
  if (flat.some((node) => node.id === id)) return flat;
  for (const node of flat) {
    if (node.type !== 'table_list') continue;
    const nested = siblings(node.children ?? [], id);
    if (nested.length) return nested;
  }
  return [];
}

type OptionItem = { value: string; label: string };

/**
 * 取前若干条真实候选项给管理员看：值列/显示列选错了，只有看到"存什么、显示什么"才发现得了。
 * 分步与联动字段在填写时才有最终候选（要逐级/上游值），这里给不出，直接说明。
 */
function OptionPreview({ formId, schema, nodeId, binding, enabled }: {
  formId: number;
  schema: SchemaNode[];
  nodeId: string;
  binding: Record<string, any>;
  enabled: boolean;
}) {
  const { data, isPending, error } = useQuery<{ items: OptionItem[]; total: number }>({
    queryKey: ['option-source-preview', formId, nodeId, binding.sourceId, binding.versionId],
    enabled,
    queryFn: () => request(`/api/runtime/form-options/preview/${formId}`, {
      method: 'POST',
      data: { schema, query: { fieldId: nodeId, values: {}, page: 1, size: 20 } },
    }),
  });
  if (!enabled) return null;
  if (isPending) return <Typography.Text type="secondary">预览中…</Typography.Text>;
  if (error) return <Typography.Text type="secondary">预览失败：{(error as Error).message}</Typography.Text>;
  const items = data?.items ?? [];
  return (
    <>
      <Typography.Text>预览</Typography.Text>
      {/* 直接列出来而不是塞进一个禁用的下拉：下拉里的选项要展开才在 DOM 里，
          而这里就是给管理员扫一眼"存什么、显示什么"。 */}
      {items.length === 0
        ? <Typography.Text type="secondary">暂时没有候选（版本可能还没发布）</Typography.Text>
        : items.slice(0, 5).map((item) => (
          <Typography.Text key={item.value} style={{ fontSize: 12 }}>
            {item.value === item.label ? item.label : `${item.label}（存 ${item.value}）`}
          </Typography.Text>
        ))}
      <Typography.Text type="secondary">
        共 {data?.total ?? 0} 条候选，这里显示前 {items.length} 条；
        存进表单的是「保存值所在列」，用户看到的是「显示名称所在列」。
      </Typography.Text>
    </>
  );
}

export function OptionSourceSettings({ formId, node, schema, update, updateNode }: {
  formId?: number;
  node: SchemaNode;
  schema: SchemaNode[];
  update: (patch: Record<string, any>) => void;
  /** 改本节点以外的字段用（改值列时要把下游的联动列一起带过去）。 */
  updateNode?: (id: string, patch: Record<string, any>) => void;
}) {
  const { data: sources = [], isPending, error } = useQuery({
    queryKey: ['form-option-sources', formId],
    enabled: !!formId,
    queryFn: () => request<Source[]>(`/api/forms/${formId}/option-sources`),
  });
  const scope = siblings(schema, node.id);
  const isSelect = node.type === 'select' || node.type === 'multi_select';
  const binding = node.props?.optionSource as Record<string, any> | undefined;
  const bound = isBoundOptionSource(node.props);
  // 「已选外部数据源、还没选版本」是编辑途中的临时状态：只留在组件内，不写进 schema，
  // 否则会留下 optionSource: {} 这种脏数据让表单发布失败。
  const [draftMode, setDraftMode] = useState<'source' | null>(null);
  // 「显示历史版本」也留在组件内：这是看的偏好，不是表单数据。
  const [showHistory, setShowHistory] = useState(false);
  const sourceMode = draftMode ?? (bound ? 'source' : 'static');
  useEffect(() => { setDraftMode(null); setShowHistory(false); }, [node.id]);
  const link = node.props?.dataLinkage as Record<string, any> | undefined;
  const selected = sources.find((s) => s.id === binding?.sourceId && s.versionId === binding?.versionId);
  const columns = selected?.columns ?? [];
  const parents = scope.filter((candidate) => candidate.type === 'select'
    && candidate.id !== node.id && isBoundOptionSource(candidate.props));
  const currentParent = parents.find((parent) => parent.id === (isSelect ? binding?.dependency?.fieldId : link?.fieldId));
  /** 上游字段钉着的数据源版本现在还可用吗：停用源 / 撤销授权后它就不在候选里了。 */
  const versionAvailable = (parent: SchemaNode) =>
    sources.some((source) => source.versionId === parent.props?.optionSource?.versionId);
  // 「每级取几位」的输入框文本（见下面的说明）。只在换字段/换分步方式时从 schema 同步。
  const [lengthsText, setLengthsText] = useState('');
  const [lengthsError, setLengthsError] = useState<string>();
  const lengthsSyncKey = `${node.id}|${binding?.cascade?.kind ?? ''}|${binding?.cascade?.sourceColumn ?? ''}`;
  useEffect(() => {
    // 只在换字段/换分步方式/换编码列时从 schema 取一次；不要跟着 lengths 走，
    // 否则用户敲下的 "1," 会被解析结果 "1" 立刻覆盖回去。
    setLengthsText((binding?.cascade?.split?.lengths ?? []).join(','));
    setLengthsError(undefined);
  }, [lengthsSyncKey]);

  /**
   * 换了「保存值所在列」：下游按旧列联动的字段要一起跟过去。
   * 服务端 validateChain 要求下游的 matchColumn 等于上游的 valueColumn，
   * 不跟过去就是"面板看着配好了、表单发布不出去"。
   */
  const syncDependents = (oldColumn: unknown, nextColumn: unknown) => {
    if (!oldColumn || oldColumn === nextColumn) return;
    for (const candidate of scope) {
      const dependency = candidate.props?.optionSource?.dependency;
      if (candidate.id === node.id || !dependency || dependency.fieldId !== (isSelect ? node.id : link?.fieldId)) continue;
      if (dependency.matchColumn !== oldColumn) continue;
      updateNode?.(candidate.id, {
        props: {
          ...candidate.props,
          optionSource: {
            ...candidate.props?.optionSource,
            dependency: { ...dependency, matchColumn: nextColumn },
          },
        },
      });
    }
  };

  if (!formId) return <Typography.Text type="secondary">保存表单后，可以配置数据源。</Typography.Text>;
  if (error) return <Typography.Text type="danger">无法加载授权数据源：{(error as Error).message}</Typography.Text>;
  if (isPending) return <Typography.Text type="secondary">加载数据源中…</Typography.Text>;

  return <Space direction="vertical" style={{ width: '100%' }} size={12}>
    {isSelect && <>
      <Typography.Text strong>选项来源</Typography.Text>
      <Select style={{ width: SELECT_WIDTH }} value={sourceMode}
        options={[{ label: '手动设置', value: 'static' }, { label: '已导入的数据', value: 'source' }]}
        onChange={(value) => {
          if (value === 'source') {
            setDraftMode('source');
            update({ displayStyle: 'dropdown', defaultValue: undefined });
            return;
          }
          setDraftMode(null);
          update({ optionSource: undefined, defaultValue: undefined });
        }} />
      {sourceMode === 'source' && sources.length === 0 && <Typography.Text type="secondary">
        本表单还没有引用任何选项数据源。先去 <Link to="/approval/option-sources">选项数据源</Link> 把它引用到这张表单。
      </Typography.Text>}
      {sourceMode === 'source' && sources.length > 0 && <>
        <Typography.Text>数据源与版本</Typography.Text>
        <Select style={{ width: SELECT_WIDTH }} placeholder="选择数据源与版本" value={selected?.versionId}
          options={versionOptionGroups(sources, { boundVersionId: binding?.versionId, showHistory })}
          onChange={(versionId) => {
            const source = sources.find((s) => s.versionId === versionId);
            if (!source) return;
            setDraftMode(null);
            // 一一对应：数据源只有一列时，值列与显示列只能是它，直接填好省掉两次手选。
            // 多列时留空，交由管理员自己挑——预填「第一列」几乎肯定是错的，反而误导。
            const onlyColumn = source.columns.length === 1 ? source.columns[0] : undefined;
            update({ optionSource: { sourceId: source.id, versionId: source.versionId,
              valueColumn: onlyColumn, labelColumn: onlyColumn }, defaultValue: undefined });
          }} />
        <Checkbox checked={showHistory} onChange={(event) => setShowHistory(event.target.checked)}>
          显示历史版本
        </Checkbox>
        {/* 「想要最新版」的出口就在这一格旁边的版本下拉里：说清"钉着的版本 + 最新版本"，
            并点明要用上新数据必须重新发布这张表单（AntFlow 不做"跟随最新"，见 DECISIONS）。 */}
        {selected && selected.latestVersionNo > selected.versionNo && (
          <Alert
            type="info"
            showIcon
            title={`这个数据源已经有 v${selected.latestVersionNo}，当前钉的是 v${selected.versionNo}`}
            description="要用上新数据：在上面选新版本，再重新发布这张表单——已发布的表单固定它绑定的版本。"
          />
        )}
        {selected && binding && <>
          <Typography.Text>保存值所在列</Typography.Text>
          <Select style={{ width: SELECT_WIDTH }} placeholder="选择保存值的列"
            value={binding.valueColumn} options={columns.map((c) => ({ label: c, value: c }))}
            onChange={(valueColumn) => {
              syncDependents(binding.valueColumn, valueColumn);
              update({ optionSource: { ...binding, valueColumn } });
            }} />
          <Typography.Text>显示名称所在列</Typography.Text>
          <Select style={{ width: SELECT_WIDTH }} placeholder="选择显示名的列"
            value={binding.labelColumn} options={columns.map((c) => ({ label: c, value: c }))}
            onChange={(labelColumn) => update({ optionSource: { ...binding, labelColumn } })} />
          <Typography.Text>根据上游下拉筛选</Typography.Text>
          <Select style={{ width: SELECT_WIDTH }} allowClear placeholder="无联动" value={binding.dependency?.fieldId}
            options={parents.filter((parent) => parent.props?.optionSource?.sourceId === selected.id
              && parent.props?.optionSource?.versionId === selected.versionId)
              .map((parent) => ({ label: parent.label ?? parent.id, value: parent.id }))}
            onChange={(fieldId) => {
              const parent = parents.find((item) => item.id === fieldId);
              update({ optionSource: { ...binding, dependency: parent
                ? { fieldId, matchColumn: parent.props?.optionSource?.valueColumn } : undefined } });
            }} />
          <Typography.Text>逐步缩小候选</Typography.Text>
          <Select style={{ width: SELECT_WIDTH }} value={binding.cascade?.kind ?? 'none'} options={[
            { value: 'none', label: '直接搜索完整选项' },
            { value: 'split', label: '按编码前缀分步' },
            { value: 'columns', label: '按分类列分步' },
          ]} onChange={(kind) => update({ optionSource: { ...binding, cascade: kind === 'none' ? undefined
            : kind === 'split' ? { kind, sourceColumn: binding.valueColumn, split: { kind: 'fixed', lengths: [1, 1, 1] } }
              : { kind, levelColumns: [columns[0]] } } })} />
          {binding.cascade?.kind === 'split' && <>
            <Typography.Text>用于拆分的编码列</Typography.Text>
            <Select style={{ width: SELECT_WIDTH }} value={binding.cascade.sourceColumn}
              options={columns.map((c) => ({ label: c, value: c }))}
              onChange={(sourceColumn) => update({ optionSource: { ...binding, cascade: { ...binding.cascade, sourceColumn } } })} />
            <Typography.Text>每级取几位（逗号分隔，J→K→L 输入 1,1,1）</Typography.Text>
            <Input value={lengthsText} status={lengthsError ? 'error' : undefined} onChange={(event) => {
              // 输入框保留原始文本：早先把 value 绑成"解析后的数组再 join"，敲下逗号的瞬间
              // 解析结果里没有空项，回写的字符串就把逗号吃掉了，1, 会变回 1，根本输不进多级。
              const raw = event.target.value;
              setLengthsText(raw);
              const parts = raw.split(',').map((part) => part.trim());
              const lengths = parts.map((part) => Number(part));
              // 整段都要是正整数才写进 schema：以前是"过滤掉坏项"，用户看着 1,0,2、
              // 存进去的却是 [1,2]，级联层级静默变了。
              if (parts.some((part) => !/^\d+$/.test(part) || Number(part) < 1)) {
                setLengthsError('每一级都要填正整数，用逗号分开');
                return;
              }
              setLengthsError(undefined);
              update({ optionSource: { ...binding, cascade: { ...binding.cascade,
                split: { kind: 'fixed', lengths } } } });
            }} />
            {lengthsError && <Typography.Text type="danger">{lengthsError}</Typography.Text>}
          </>}
          {binding.cascade?.kind === 'columns' && <>
            <Typography.Text>按顺序选择分类列</Typography.Text>
            <Select style={{ width: SELECT_WIDTH }} mode="multiple" value={binding.cascade.levelColumns ?? []}
              options={columns.map((c) => ({ label: c, value: c }))}
              onChange={(levelColumns) => update({ optionSource: { ...binding, cascade: { kind: 'columns', levelColumns } } })} />
          </>}
          <OptionPreview formId={formId} schema={schema} nodeId={node.id} binding={binding}
            enabled={Boolean(binding.valueColumn && binding.labelColumn
              && !binding.cascade && !binding.dependency)} />
        </>}
      </>}
    </>}
    {!isSelect && <>
      <Typography.Text strong>下拉选择后自动填写</Typography.Text>
      <Select style={{ width: SELECT_WIDTH }} allowClear placeholder="不联动" value={link?.fieldId}
        options={parents.map((parent) => {
          // 上游的数据源版本当前不可用（源停用 / 授权撤销）时不让选：选了也只能写出
          // 一个没有 valueColumn 的联动，服务端校验会拒，表单发不出去。
          const available = versionAvailable(parent);
          return {
            value: parent.id,
            label: available ? parent.label ?? parent.id
              : `${parent.label ?? parent.id}（数据源版本当前不可用）`,
            disabled: !available,
          };
        })}
        onChange={(fieldId) => {
          const parent = parents.find((item) => item.id === fieldId);
          if (!parent) { update({ dataLinkage: undefined }); return; }
          // 一一对应：上游数据源只有一列时，带出的列只能是它，直接填好；多列则留空由管理员选。
          const parentColumns = sources.find(
            (s) => s.versionId === parent.props?.optionSource?.versionId)?.columns ?? [];
          update({ dataLinkage: { fieldId, valueColumn: parentColumns.length === 1 ? parentColumns[0] : undefined } });
        }} />
      {currentParent && <>
        <Typography.Text>填写外表中的哪一列</Typography.Text>
        <Select style={{ width: SELECT_WIDTH }} placeholder="选择要带出的列" value={link?.valueColumn}
          options={sources.find((s) => s.versionId === currentParent.props?.optionSource?.versionId)?.columns.map((c) => ({ label: c, value: c })) ?? []}
          onChange={(valueColumn) => update({ dataLinkage: { fieldId: currentParent.id, valueColumn } })} />
        <Typography.Text type="secondary">只有一条结果时自动填写；后续可以手动修改。</Typography.Text>
      </>}
    </>}
  </Space>;
}
