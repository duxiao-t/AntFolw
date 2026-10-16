import { Space, Checkbox, InputNumber } from 'antd';
import { isBoundOptionSource } from './dynamicOptions';
import { SelectOptionsEditor } from './SelectOptionsEditor';
import { PanelField } from './panelField';
import { Input, Select } from 'antd';
import { useEffect, useState } from 'react';
import type { FieldType } from '../../registry/types';
import { isDynamicOption, useDynamicOptions } from './dynamicOptions';
import {
  customValuesFrom,
  InlineSelectOptions,
  normalizeSelectDisplayStyle,
  normalizeSelectOptions,
  optionForSelect,
  selectedOptionSummary,
  selectOptionNode,
  visibleSelectOptions,
  OTHER_OPTION_VALUE,
} from './selectFieldShared';

export const SelectField: FieldType = {
  type: 'select',
  label: '下拉单选',
  icon: 'unordered-list',
  defaultProps: {
    required: false,
    displayStyle: 'dropdown',
    options: [
      { id: 'option_1', label: '选项1', value: 'option_1' },
      { id: 'option_2', label: '选项2', value: 'option_2' },
      { id: 'option_3', label: '选项3', value: 'option_3' },
    ],
  },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        {!isBoundOptionSource(node.props) && <SelectOptionsEditor
          value={props.options}
          multiple={node.type === 'multi_select'}
          defaultValue={props.defaultValue}
          enableColors={props.enableOptionColor === true}
          onChange={(options) => updateProps({ options })}
          onDefaultChange={(defaultValue) => updateProps({ defaultValue })}
          onEnableColorsChange={(enableOptionColor, options) =>
            updateProps({ enableOptionColor, options })
          }
        />}
        <Checkbox
          checked={props.allowClear !== false}
          onChange={(event) => updateProps({ allowClear: event.target.checked })}
        >
          允许清空
        </Checkbox>
        <Checkbox
          checked={!!props.showSearch}
          disabled={normalizeSelectDisplayStyle(props.displayStyle) !== 'dropdown'}
          onChange={(event) => updateProps({ showSearch: event.target.checked })}
        >
          支持搜索
        </Checkbox>
        {node.type === 'multi_select' && (
          <PanelField label="最多选择数">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxCount}
              onChange={(value) => updateProps({ maxCount: value })}
            />
          </PanelField>
        )}
      </Space>
    );
  },
  Component: ({ node, mode, value, onChange, values, optionContext }) => {
    // Hooks below always run: a node can switch between static and external options while editing.
    const options = visibleSelectOptions(node.props?.options);
    const allOptions = normalizeSelectOptions(node.props?.options);
    const displayStyle = normalizeSelectDisplayStyle(node.props?.displayStyle);
    const useColor = node.props?.enableOptionColor === true;
    const selectOptions = options.map((option) => optionForSelect(option, useColor));
    const otherOption = options.find((option) => option.isOther);
    const currentValue = value as string | number | undefined;
    const inferredOther = Boolean(
      otherOption &&
      (customValuesFrom(currentValue, allOptions, false).length > 0 || currentValue === OTHER_OPTION_VALUE),
    );
    const [otherSelected, setOtherSelected] = useState(inferredOther);
    useEffect(() => {
      if (!otherOption) setOtherSelected(false);
      else if (currentValue != null && currentValue !== '') setOtherSelected(inferredOther);
    }, [currentValue, inferredOther, otherOption]);
    const otherActive = Boolean(otherOption && otherSelected);
    const selectValue = otherActive ? OTHER_OPTION_VALUE : currentValue;
    const otherText = customValuesFrom(currentValue, allOptions, false)[0];

    const updateOther = (next: string) => {
      onChange?.(next.trim() ? next : undefined);
    };

    if (isDynamicOption(node)) {
      return <DynamicSelect node={node} mode={mode} value={value} onChange={onChange} values={values} optionContext={optionContext} />;
    }

    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        {mode === 'readonly' ? (
          <div className="form-select-readonly">
            {selectedOptionSummary(currentValue, allOptions, false)}
          </div>
        ) : displayStyle === 'dropdown' ? (
          <Select
            disabled={mode !== 'runtime-fill'}
            value={selectValue}
            onChange={(nextValue) => {
              if (nextValue === OTHER_OPTION_VALUE) {
                setOtherSelected(true);
                onChange?.(undefined);
                return;
              }
              setOtherSelected(false);
              onChange?.(nextValue);
            }}
            options={selectOptions}
            placeholder={node.props?.placeholder}
            allowClear={node.props?.allowClear !== false}
            showSearch={!!node.props?.showSearch}
            optionRender={(option: any) => option.data?.renderedLabel ?? option.label}
            labelRender={(option: any) => {
              const selected = selectOptions.find((item) => item.value === option.value)?.option;
              return selected ? selectOptionNode(selected, useColor) : option.label;
            }}
            onClear={() => {
              setOtherSelected(false);
              onChange?.(undefined);
            }}
            style={{ width: '100%' }}
          />
        ) : (
          <InlineSelectOptions
            label={node.label ?? '下拉单选'}
            displayStyle={displayStyle}
            options={options}
            selectedValues={otherActive
              ? [OTHER_OPTION_VALUE]
              : currentValue == null || currentValue === '' ? [] : [currentValue]}
            multiple={false}
            useColor={useColor}
            disabled={mode !== 'runtime-fill'}
            onToggle={(option, selected) => {
              if (selected) {
                if (node.props?.allowClear === false) return;
                setOtherSelected(false);
                onChange?.(undefined);
                return;
              }
              if (option.isOther) {
                setOtherSelected(true);
                onChange?.(undefined);
              } else {
                setOtherSelected(false);
                onChange?.(option.value);
              }
            }}
          />
        )}
        {mode !== 'readonly' && otherOption && otherActive && (
          <Input
            aria-label={`${node.label ?? '其他'}自定义内容`}
            disabled={mode !== 'runtime-fill'}
            value={otherText == null ? '' : String(otherText)}
            placeholder="请输入"
            onChange={(event) => updateOther(event.target.value)}
            style={{ marginTop: 8 }}
          />
        )}
      </div>
    );
  },
};

function DynamicSelect({ node, mode, value, onChange, values, optionContext }: any) {
  const dynamic = useDynamicOptions(node, values, optionContext, onChange);
  const current = typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
  const isLevel = dynamic.stage === 'LEVEL';
  const display = isLevel && dynamic.path.length ? `${dynamic.path.join(' / ')} / ` : '';
  // 与静态下拉一致：只认字段自己的设置。
  const searchable = node.props?.showSearch === true;
  const clearable = node.props?.allowClear !== false;
  return (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
      {mode === 'readonly' ? <div className="form-select-readonly">{dynamic.labels.find((item) => item.value === String(current))?.label ?? current ?? '未填写'}</div> : (
        <Select
          disabled={mode !== 'runtime-fill' || dynamic.loading}
          loading={dynamic.loading}
          value={isLevel ? undefined : current}
          options={[...dynamic.labels, ...dynamic.options].filter((item, index, all) => all.findIndex((candidate) => candidate.value === item.value) === index)
            .map((item) => ({ value: item.value, label: `${display}${item.label}` }))}
          placeholder={dynamic.error ?? (isLevel ? `请选择第 ${dynamic.path.length + 1} 级` : node.props?.placeholder)}
          showSearch={searchable}
          filterOption={false}
          onSearch={searchable ? dynamic.search : undefined}
          onChange={(next: string) => {
            if (dynamic.advance(next)) onChange?.(next);
          }}
          onClear={() => { dynamic.reset(); onChange?.(undefined); }}
          allowClear={clearable}
          // 滚到底继续取下一页，替代原来的「更多选项」按钮。
          onPopupScroll={(event: any) => {
            const list = event.currentTarget as HTMLElement;
            if (list.scrollHeight - list.scrollTop - list.clientHeight < 48) dynamic.loadMore();
          }}
          popupRender={(menu: any) => (
            <>
              {menu}
              {dynamic.loadingMore
                ? <div style={{ padding: '8px 12px', color: 'rgba(0,0,0,0.45)', fontSize: 12 }}>加载中…</div>
                : null}
            </>
          )}
          style={{ width: '100%' }}
        />
      )}
      {dynamic.path.length > 0 ? <button type="button" onClick={dynamic.back}>上一步</button> : null}
    </div>
  );
}
