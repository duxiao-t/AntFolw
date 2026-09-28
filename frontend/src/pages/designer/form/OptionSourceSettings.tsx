import { Link, request } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { Checkbox, Input, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { isBoundOptionSource } from '../../../components/form-fields/dynamicOptions';
import type { SchemaNode } from '../../../registry/types';
import { type BindableSource, versionOptionGroups } from './optionSourceVersions';

type Source = BindableSource;

/** 这些下拉的候选项是列名/版本名，比默认宽度长得多；给足约 10 个汉字的宽度，避免只露两三个字。 */
const SELECT_WIDTH = 190;

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
  // 「每级取几位」的输入框文本（见下面的说明）。只在换字段/换分步方式时从 schema 同步。
  const [lengthsText, setLengthsText] = useState('');
  const lengthsSyncKey = `${node.id}|${binding?.cascade?.kind ?? ''}|${binding?.cascade?.sourceColumn ?? ''}`;
  useEffect(() => {
    // 只在换字段/换分步方式/换编码列时从 schema 取一次；不要跟着 lengths 走，
    // 否则用户敲下的 "1," 会被解析结果 "1" 立刻覆盖回去。
    setLengthsText((binding?.cascade?.split?.lengths ?? []).join(','));
  }, [lengthsSyncKey]);

  if (!formId) return <Typography.Text type="secondary">保存表单后，可以配置数据源。</Typography.Text>;
  if (error) return <Typography.Text type="danger">无法加载授权数据源：{(error as Error).message}</Typography.Text>;
  if (isPending) return <Typography.Text type="secondary">加载数据源中…</Typography.Text>;

  return <Space direction="vertical" style={{ width: '100%' }}>
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
        <Typography.Text type="secondary">选择已发布版本。更新数据后，重新发布表单才能切换版本。</Typography.Text>
        <Select style={{ width: SELECT_WIDTH }} placeholder="数据源与版本" value={selected?.versionId}
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
        {selected && binding && <>
          <Typography.Text>保存值所在列</Typography.Text>
          <Select style={{ width: SELECT_WIDTH }} placeholder="选择保存值的列"
            value={binding.valueColumn} options={columns.map((c) => ({ label: c, value: c }))}
            onChange={(valueColumn) => update({ optionSource: { ...binding, valueColumn } })} />
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
            <Input value={lengthsText} onChange={(event) => {
              // 输入框保留原始文本：早先把 value 绑成"解析后的数组再 join"，敲下逗号的瞬间
              // 解析结果里没有空项，回写的字符串就把逗号吃掉了，1, 会变回 1，根本输不进多级。
              setLengthsText(event.target.value);
              const lengths = event.target.value.split(',')
                .map((v) => Number(v.trim()))
                .filter((v) => Number.isInteger(v) && v > 0);
              update({ optionSource: { ...binding, cascade: { ...binding.cascade, split: { kind: 'fixed', lengths } } } });
            }} />
          </>}
          {binding.cascade?.kind === 'columns' && <>
            <Typography.Text>按顺序选择分类列</Typography.Text>
            <Select style={{ width: SELECT_WIDTH }} mode="multiple" value={binding.cascade.levelColumns ?? []}
              options={columns.map((c) => ({ label: c, value: c }))}
              onChange={(levelColumns) => update({ optionSource: { ...binding, cascade: { kind: 'columns', levelColumns } } })} />
          </>}
        </>}
      </>}
    </>}
    {!isSelect && <>
      <Typography.Text strong>下拉选择后自动填写</Typography.Text>
      <Select style={{ width: SELECT_WIDTH }} allowClear placeholder="不联动" value={link?.fieldId}
        options={parents.map((parent) => ({ value: parent.id, label: parent.label ?? parent.id }))}
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
