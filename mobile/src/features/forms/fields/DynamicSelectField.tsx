import { useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { useDynamicOptions } from './dynamicOptions';
import { MobileSelectionPopup } from './MobileSelectionPopup';
import { PickerOptionList, PickerSearchInput, PickerTrigger } from './SelectPicker';

/**
 * 外部数据源下拉。与静态下拉共用 PickerTrigger / PickerSearchInput / PickerOptionList，
 * 只在「候选从哪来」以及分页、分步导航上不同，外观不另起一套。
 */
export function DynamicSelectField(props: MobileFieldProps & { multiple?: boolean }) {
  const label = fieldLabel(props.node);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<string[]>([]);
  const dynamic = useDynamicOptions(props, open);
  const selected = (Array.isArray(props.value) ? props.value : props.value == null || props.value === '' ? [] : [props.value]).map(String);
  const labels = selected.map((item) => dynamic.labels.find((option) => option.value === item)?.label ?? item);
  const parentId = (props.node.props?.optionSource as { dependency?: { fieldId?: string } })?.dependency?.fieldId;
  const parentMissing = parentId && (props.values[parentId] == null || props.values[parentId] === '');
  const isLevel = dynamic.result?.stage === 'LEVEL';
  const chosen = props.multiple ? draft : selected;
  const options = dynamic.items.map((item) => ({
    value: item.value,
    label: item.label,
    selected: !isLevel && chosen.includes(item.value),
  }));
  const emptyText = dynamic.error ?? (dynamic.loading ? '正在加载…' : '没有匹配的选项');
  // 与静态下拉一致：只认字段自己的设置，不无条件显示。
  const searchable = props.node.props?.showSearch === true;
  const clearable = props.node.props?.allowClear !== false;
  const hasChosen = (props.multiple ? draft.length : selected.length) > 0;

  return (
    <FieldShell node={props.node} label={label} required={isRequired(props.node)} error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{labels.join('、') || '未填写'}</div> : undefined}>
      {props.mode === 'fill' && <>
        <PickerTrigger
          {...(props.multiple ? { labels } : { value: labels[0] ?? '' })}
          placeholder={parentMissing ? '请先选择上游选项' : `选择${label}`}
          disabled={!!parentMissing || !props.optionContext}
          onClick={() => { setDraft(selected); setOpen(true); }}
        />
        <MobileSelectionPopup visible={open} title={`选择${label}`}
          subtitle={isLevel ? `第 ${dynamic.path.length + 1} 步 / 共 ${dynamic.result?.totalLevels ?? '…'} 步` : `${dynamic.result?.total ?? 0} 个匹配选项`}
          presentation="sheet" onClose={() => setOpen(false)}
          // 头部是三列网格（左槽 / 标题 / ×），所以 headerAction 必须是**单个元素或 undefined**：
          // 传 fragment 会让子元素数量变化，标题被挤到左边甚至压进左槽。
          // 「上一步」因此放到列表上方，不占头部。
          headerAction={clearable && hasChosen ? <button type="button" className="af-full-picker__clear"
            onClick={() => {
              if (props.multiple) setDraft([]);
              else { props.onValueChange(props.node.id, undefined); setOpen(false); }
            }}>清空</button> : undefined}
          footer={props.multiple ? <button type="button" className="btn btn--success btn--lg" onClick={() => {
            props.onValueChange(props.node.id, draft); setOpen(false);
          }}>完成（{draft.length}）</button> : undefined}>
          {dynamic.path.length ? <button type="button" className="af-full-picker__back-step"
            onClick={dynamic.back}>← 上一步</button> : null}
          {searchable ? <PickerSearchInput label="搜索选项" placeholder="搜索当前步骤"
            value={dynamic.keyword} onChange={dynamic.setKeyword} /> : null}
          <PickerOptionList label={label} multiple={props.multiple} options={options} empty={emptyText}
            onScrollEnd={dynamic.hasMore ? dynamic.loadMore : undefined}
            footer={dynamic.loadingMore ? <p className="af-full-picker__empty" role="status">加载中…</p> : null}
            onSelect={(option) => {
              const value = String(option.value);
              if (!dynamic.choose(value)) return;
              if (props.multiple) setDraft((current) => current.includes(value)
                ? current.filter((item) => item !== value) : [...current, value]);
              else { props.onValueChange(props.node.id, option.value); setOpen(false); }
            }} />
        </MobileSelectionPopup>
      </>}
    </FieldShell>
  );
}
