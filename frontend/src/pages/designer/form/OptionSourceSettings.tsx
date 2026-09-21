import { request } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { Input, Select, Space, Typography } from 'antd';
import type { SchemaNode } from '../../../registry/types';

type Source = { id: number; code: string; name: string; versionId: number;
  versionNo: number; columns: string[]; rowCount: number };

function siblings(nodes: SchemaNode[], id: string): SchemaNode[] {
  const flat = nodes.flatMap((node) => node.type === 'span_layout' ? [node, ...node.children ?? []] : [node]);
  if (flat.some((node) => node.id === id)) return flat;
  for (const node of flat) {
    if (node.type !== 'table_list') continue;
    const nested = siblings(node.children ?? [], id);
    if (nested.length) return nested;
  }
  return [];
}

export function OptionSourceSettings({ formId, node, schema, update }: {
  formId?: number;
  node: SchemaNode;
  schema: SchemaNode[];
  update: (patch: Record<string, any>) => void;
}) {
  const { data: sources = [], isPending, error } = useQuery({
    queryKey: ['form-option-sources', formId],
    enabled: !!formId,
    queryFn: () => request<Source[]>(`/api/forms/${formId}/option-sources`),
  });
  const scope = siblings(schema, node.id);
  const isSelect = node.type === 'select' || node.type === 'multi_select';
  const binding = node.props?.optionSource as Record<string, any> | undefined;
  const link = node.props?.dataLinkage as Record<string, any> | undefined;
  const selected = sources.find((s) => s.id === binding?.sourceId && s.versionId === binding?.versionId);
  const columns = selected?.columns ?? [];
  const parents = scope.filter((candidate) => candidate.type === 'select'
    && candidate.id !== node.id && !!candidate.props?.optionSource);
  const currentParent = parents.find((parent) => parent.id === (isSelect ? binding?.dependency?.fieldId : link?.fieldId));

  if (!formId) return <Typography.Text type="secondary">保存表单后，可以配置数据源。</Typography.Text>;
  if (error) return <Typography.Text type="danger">无法加载授权数据源：{(error as Error).message}</Typography.Text>;
  if (isPending) return <Typography.Text type="secondary">加载数据源中…</Typography.Text>;

  return <Space direction="vertical" style={{ width: '100%' }}>
    {isSelect && <>
      <Typography.Text strong>选项来源</Typography.Text>
      <Select value={binding ? 'source' : 'static'} options={[{ label: '手动设置', value: 'static' }, { label: '已导入的数据', value: 'source' }]}
        onChange={(value) => update(value === 'source'
          ? { optionSource: {}, displayStyle: 'dropdown', defaultValue: undefined }
          : { optionSource: undefined, defaultValue: undefined })} />
      {binding && <>
        <Typography.Text type="secondary">选择已发布版本。更新数据后，重新发布表单才能切换版本。</Typography.Text>
        <Select placeholder="选择数据源与版本" value={selected?.versionId}
          options={sources.map((s) => ({ value: s.versionId, label: `${s.name} · v${s.versionNo} (${s.rowCount} 行)` }))}
          onChange={(versionId) => {
            const source = sources.find((s) => s.versionId === versionId);
            if (!source) return;
            update({ optionSource: { sourceId: source.id, versionId: source.versionId,
              valueColumn: source.columns[0], labelColumn: source.columns[0] }, defaultValue: undefined });
          }} />
        {selected && <>
          <Typography.Text>保存值所在列</Typography.Text>
          <Select value={binding.valueColumn} options={columns.map((c) => ({ label: c, value: c }))}
            onChange={(valueColumn) => update({ optionSource: { ...binding, valueColumn } })} />
          <Typography.Text>显示名称所在列</Typography.Text>
          <Select value={binding.labelColumn} options={columns.map((c) => ({ label: c, value: c }))}
            onChange={(labelColumn) => update({ optionSource: { ...binding, labelColumn } })} />
          <Typography.Text>根据上游下拉筛选</Typography.Text>
          <Select allowClear placeholder="无联动" value={binding.dependency?.fieldId}
            options={parents.filter((parent) => parent.props?.optionSource?.sourceId === selected.id
              && parent.props?.optionSource?.versionId === selected.versionId)
              .map((parent) => ({ label: parent.label ?? parent.id, value: parent.id }))}
            onChange={(fieldId) => {
              const parent = parents.find((item) => item.id === fieldId);
              update({ optionSource: { ...binding, dependency: parent
                ? { fieldId, matchColumn: parent.props?.optionSource?.valueColumn } : undefined } });
            }} />
          <Typography.Text>逐步缩小候选</Typography.Text>
          <Select value={binding.cascade?.kind ?? 'none'} options={[
            { value: 'none', label: '直接搜索完整选项' },
            { value: 'split', label: '按编码前缀分步' },
            { value: 'columns', label: '按分类列分步' },
          ]} onChange={(kind) => update({ optionSource: { ...binding, cascade: kind === 'none' ? undefined
            : kind === 'split' ? { kind, sourceColumn: binding.valueColumn, split: { kind: 'fixed', lengths: [1, 1, 1] } }
              : { kind, levelColumns: [columns[0]] } } })} />
          {binding.cascade?.kind === 'split' && <>
            <Typography.Text>用于拆分的编码列</Typography.Text>
            <Select value={binding.cascade.sourceColumn} options={columns.map((c) => ({ label: c, value: c }))}
              onChange={(sourceColumn) => update({ optionSource: { ...binding, cascade: { ...binding.cascade, sourceColumn } } })} />
            <Typography.Text>每级取几位（逗号分隔，J→K→L 输入 1,1,1）</Typography.Text>
            <Input value={(binding.cascade.split?.lengths ?? []).join(',')} onChange={(event) => {
              const lengths = event.target.value.split(',').map((v) => Number(v.trim())).filter((v) => Number.isInteger(v) && v > 0);
              update({ optionSource: { ...binding, cascade: { ...binding.cascade, split: { kind: 'fixed', lengths } } } });
            }} />
          </>}
          {binding.cascade?.kind === 'columns' && <>
            <Typography.Text>按顺序选择分类列</Typography.Text>
            <Select mode="multiple" value={binding.cascade.levelColumns ?? []}
              options={columns.map((c) => ({ label: c, value: c }))}
              onChange={(levelColumns) => update({ optionSource: { ...binding, cascade: { kind: 'columns', levelColumns } } })} />
          </>}
        </>}
      </>}
    </>}
    {!isSelect && <>
      <Typography.Text strong>下拉选择后自动填写</Typography.Text>
      <Select allowClear placeholder="不联动" value={link?.fieldId}
        options={parents.map((parent) => ({ value: parent.id, label: parent.label ?? parent.id }))}
        onChange={(fieldId) => update({ dataLinkage: fieldId ? { fieldId } : undefined })} />
      {currentParent && <>
        <Typography.Text>填写外表中的哪一列</Typography.Text>
        <Select value={link?.valueColumn}
          options={sources.find((s) => s.versionId === currentParent.props?.optionSource?.versionId)?.columns.map((c) => ({ label: c, value: c })) ?? []}
          onChange={(valueColumn) => update({ dataLinkage: { fieldId: currentParent.id, valueColumn } })} />
        <Typography.Text type="secondary">只有一条结果时自动填写；后续可以手动修改。</Typography.Text>
      </>}
    </>}
  </Space>;
}
