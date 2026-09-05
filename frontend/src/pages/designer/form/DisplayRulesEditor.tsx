import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { App, Button, Checkbox, Modal, Select, Switch, Tag, Typography } from 'antd';
import { useMemo, useState } from 'react';
import { formRegistry } from '../../../registry/formRegistry';
import { normalizeSelectOptions, type SelectOptionValue } from '../../../registry/selectOptions';
import type { SchemaNode } from '../../../registry/types';
import { allowedDisplayRuleTargetIds, useFormDesignerStore } from './useFormDesignerStore';

type RuleRow = {
  id: string;
  option?: SelectOptionValue;
  targets: string[];
};

type TargetOption = {
  label: string;
  value: string;
  typeLabel: string;
};

export function DisplayRulesEditor({ source, schema }: { source: SchemaNode; schema: SchemaNode[] }) {
  const { modal } = App.useApp();
  const updateDisplayRules = useFormDesignerStore((state) => state.updateDisplayRules);
  const options = useMemo(
    () => normalizeSelectOptions(source.props?.options)
      .filter((option) => !option.hidden && !option.isOther && option.label.trim()),
    [source.props?.options],
  );
  const flat = useMemo(() => flatten(schema), [schema]);
  const allowedTargets = allowedDisplayRuleTargetIds(source.id, flat);
  const candidates = flat.filter((node) => allowedTargets.has(node.id));
  const storedRows = rowsFromSchema(source.id, flat);
  const enabled = storedRows.length > 0;
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<RuleRow[]>([]);
  const [validationError, setValidationError] = useState('');

  const openEditor = () => {
    setRows(storedRows);
    setValidationError('');
    setOpen(true);
  };

  const closeEditor = () => {
    setOpen(false);
    setValidationError('');
  };

  const changeRows = (next: RuleRow[]) => {
    setRows(next);
    setValidationError('');
  };

  const save = () => {
    if (rows.some((row) => row.option === undefined || row.targets.length === 0)) {
      setValidationError('请为每条规则选择一个选项和至少一个显示字段。');
      return;
    }
    const targets = new Map<string, SelectOptionValue[]>();
    rows.forEach((row) => {
      row.targets.forEach((targetId) => {
        targets.set(targetId, [...(targets.get(targetId) ?? []), row.option as SelectOptionValue]);
      });
    });
    updateDisplayRules(source.id, [...targets].map(([targetId, values]) => ({ targetId, values })));
    closeEditor();
  };

  const handleEnabledChange = (checked: boolean) => {
    if (checked) {
      openEditor();
      return;
    }
    modal.confirm({
      title: '关闭逻辑规则',
      content: '关闭后当前单选字段配置的逻辑规则将被清空，是否继续？',
      okText: '确认关闭',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => updateDisplayRules(source.id, []),
    });
  };

  const unavailableHint = !options.length
    ? '请先补全可见选项，再设置逻辑规则。'
    : !candidates.length
      ? '请先在当前单选项后添加可控制的字段。'
      : '';
  const controlledTargets = new Set(rows.flatMap((row) => row.targets));
  const usedOptions = new Set(rows.map((row) => row.option).filter((value) => value !== undefined));
  const targetOptions: TargetOption[] = candidates.map((node) => ({
    label: node.label || formRegistry[node.type]?.label || node.id,
    value: node.id,
    typeLabel: formRegistry[node.type]?.label || node.type,
  }));

  return (
    <div className="display-rules-setting">
      <div className="display-rules-setting__header">
        <Typography.Text strong>启用逻辑规则</Typography.Text>
        <Switch
          size="small"
          checked={enabled}
          disabled={!enabled && !!unavailableHint}
          aria-label="启用逻辑规则"
          onChange={handleEnabledChange}
        />
      </div>
      <div className={`display-rules-setting__summary${enabled ? '' : ' is-disabled'}`}>
        <span>{storedRows.length}条逻辑规则</span>
        <Button type="link" size="small" disabled={!enabled} onClick={openEditor}>
          设置
        </Button>
      </div>
      {unavailableHint ? (
        <Typography.Text type="secondary" className="display-rules-setting__hint">
          {unavailableHint}
        </Typography.Text>
      ) : null}

      <Modal
        title="设置字段显示规则"
        open={open}
        width={720}
        className="display-rules-modal"
        okText="保存"
        cancelText="取消"
        onOk={save}
        onCancel={closeEditor}
        destroyOnHidden
      >
        <div className="display-rules-modal__list">
          {rows.length === 0 ? (
            <div className="display-rules-modal__empty">暂无逻辑规则，请点击下方添加</div>
          ) : null}
          {rows.map((row, index) => (
            <div className="display-rules-modal__rule" key={row.id}>
              <div className="display-rules-modal__fields">
                <label className="display-rules-modal__field">
                  <span>选择</span>
                  <Select
                    aria-label={`规则${index + 1}选项`}
                    value={row.option}
                    placeholder="请选择选项"
                    options={options.map((option) => ({
                      label: option.label,
                      value: option.value,
                      disabled: usedOptions.has(option.value) && row.option !== option.value,
                    }))}
                    onChange={(option) => changeRows(rows.map((item) => (
                      item.id === row.id ? { ...item, option } : item
                    )))}
                  />
                </label>
                <label className="display-rules-modal__field">
                  <span>则显示</span>
                  <Select<string[], TargetOption>
                    mode="multiple"
                    aria-label={`规则${index + 1}显示字段`}
                    value={row.targets}
                    placeholder="请选择要显示的字段"
                    options={targetOptions}
                    optionLabelProp="label"
                    showSearch={{ optionFilterProp: 'label' }}
                    menuItemSelectedIcon={null}
                    maxTagCount="responsive"
                    onChange={(targets) => changeRows(rows.map((item) => (
                      item.id === row.id ? { ...item, targets } : item
                    )))}
                    optionRender={(option) => {
                      const target = option.data;
                      const selected = row.targets.includes(target.value);
                      const controlled = controlledTargets.has(target.value);
                      return (
                        <div className="display-rules-modal__target-option">
                          <Checkbox checked={selected} tabIndex={-1} aria-hidden style={{ pointerEvents: 'none' }} />
                          <span className="display-rules-modal__target-label">{target.label}</span>
                          <Tag variant="filled">{target.typeLabel}</Tag>
                          <span className={controlled ? 'is-hidden' : 'is-visible'}>
                            {controlled ? '已隐藏' : '已显示'}
                          </span>
                        </div>
                      );
                    }}
                  />
                </label>
              </div>
              <Button
                type="text"
                danger
                icon={<DeleteOutlined />}
                aria-label={`删除规则${index + 1}`}
                onClick={() => changeRows(rows.filter((item) => item.id !== row.id))}
              />
            </div>
          ))}
        </div>

        <Button
          type="link"
          className="display-rules-modal__add"
          icon={<PlusOutlined />}
          disabled={rows.length >= options.length}
          onClick={() => changeRows([
            ...rows,
            { id: `rule_${Date.now()}_${rows.length}`, targets: [] },
          ])}
        >
          添加新规则
        </Button>
        {validationError ? (
          <Typography.Text type="danger" className="display-rules-modal__error">
            {validationError}
          </Typography.Text>
        ) : null}
      </Modal>
    </div>
  );
}

function flatten(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);
}

function rowsFromSchema(sourceId: string, nodes: SchemaNode[]): RuleRow[] {
  const targets = new Map<SelectOptionValue, string[]>();
  nodes.forEach((node) => {
    const condition = node.props?.displayCondition;
    if (condition?.fieldId !== sourceId) return;
    const values = condition.operator === 'in' && Array.isArray(condition.value)
      ? condition.value
      : [condition.value];
    values.forEach((value) => {
      if (typeof value !== 'string' && typeof value !== 'number') return;
      targets.set(value, [...(targets.get(value) ?? []), node.id]);
    });
  });
  return [...targets].map(([option, targetIds], index) => ({
    id: `stored_${index}_${String(option)}`,
    option,
    targets: targetIds,
  }));
}
