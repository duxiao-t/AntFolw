import { useState } from 'react';
import { DownOutline } from 'antd-mobile-icons';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { useDynamicOptions } from './dynamicOptions';
import { MobileSelectionPopup } from './MobileSelectionPopup';

export function DynamicSelectField(props: MobileFieldProps & { multiple?: boolean }) {
  const label = fieldLabel(props.node);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<string[]>([]);
  const dynamic = useDynamicOptions(props, open);
  const selected = (Array.isArray(props.value) ? props.value : props.value == null || props.value === '' ? [] : [props.value]).map(String);
  const labels = selected.map((item) => dynamic.labels.find((label) => label.value === item)?.label ?? item);
  const parentId = (props.node.props?.optionSource as { dependency?: { fieldId?: string } })?.dependency?.fieldId;
  const parentMissing = parentId && (props.values[parentId] == null || props.values[parentId] === '');
  const isLevel = dynamic.result?.stage === 'LEVEL';
  return (
    <FieldShell node={props.node} label={label} required={isRequired(props.node)} error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{labels.join('、') || '未填写'}</div> : undefined}>
      {props.mode === 'fill' && <>
        <button type="button" className={`control form-picker${selected.length ? '' : ' af-field-picker--placeholder'}`}
          disabled={!!parentMissing || !props.optionContext} onClick={() => { setDraft(selected); setOpen(true); }}>
          <span className="picker-value">{labels.join('、') || (parentMissing ? '请先选择上游选项' : `选择${label}`)}</span>
          <DownOutline aria-hidden="true" />
        </button>
        <MobileSelectionPopup visible={open} title={`选择${label}`}
          subtitle={isLevel ? `第 ${dynamic.path.length + 1} 步 / 共 ${dynamic.result?.totalLevels ?? '…'} 步` : `${dynamic.result?.total ?? 0} 个匹配选项`}
          presentation="sheet" onClose={() => setOpen(false)}
          headerAction={dynamic.path.length ? <button type="button" onClick={dynamic.back}>上一步</button> : undefined}
          footer={<>
            {selected.length || draft.length ? <button type="button" className="btn btn--ghost btn--lg"
              onClick={() => { props.onValueChange(props.node.id, props.multiple ? [] : undefined); setDraft([]); setOpen(false); }}>清空</button> : null}
            {props.multiple && <button type="button" className="btn btn--success btn--lg" onClick={() => {
              props.onValueChange(props.node.id, draft); setOpen(false);
            }}>完成（{draft.length}）</button>}
            <button type="button" className="btn btn--ghost btn--lg" onClick={() => setOpen(false)}>关闭</button>
          </>}>
          <input type="search" aria-label="搜索选项" className="af-full-picker__search"
            placeholder="搜索当前步骤" value={dynamic.keyword} onChange={(e) => dynamic.setKeyword(e.currentTarget.value)} />
          {dynamic.error && <p role="alert" className="af-full-picker__empty">{dynamic.error}</p>}
          {dynamic.loading && <p role="status" className="af-full-picker__empty">正在加载…</p>}
          <div className="af-full-picker__list">
            {dynamic.result?.items.map((item) => <button key={item.value} type="button"
              className="af-full-picker__option af-full-picker__option--select"
              aria-pressed={!isLevel && (props.multiple ? draft : selected).includes(item.value)}
              onClick={() => {
                if (!dynamic.choose(item.value)) return;
                if (props.multiple) setDraft((current) => current.includes(item.value)
                  ? current.filter((v) => v !== item.value) : [...current, item.value]);
                else { props.onValueChange(props.node.id, item.value); setOpen(false); }
              }}>
              <span className="af-full-picker__option-text"><strong>{item.label}</strong></span>
              {!isLevel && props.multiple && draft.includes(item.value) ? '✓' : null}
            </button>)}
            {!dynamic.loading && !dynamic.result?.items.length && <p role="status" className="af-full-picker__empty">没有匹配的选项</p>}
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, paddingTop: 12 }}>
            <button type="button" disabled={dynamic.page <= 1} onClick={dynamic.previous}>上一页</button>
            <span>{dynamic.page} / {Math.max(1, Math.ceil((dynamic.result?.total ?? 0) / 20))}</span>
            <button type="button" disabled={dynamic.page * 20 >= (dynamic.result?.total ?? 0)} onClick={dynamic.next}>下一页</button>
          </div>
        </MobileSelectionPopup>
      </>}
    </FieldShell>
  );
}
