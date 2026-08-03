import {
  ApartmentOutlined,
  CalendarOutlined,
  DownOutlined,
  FileOutlined,
  LeftOutlined,
  PlusOutlined,
  UploadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import { formRegistry } from '../../registry/formRegistry';
import type { SchemaNode } from '../../registry/types';
import './MobileFormPreview.less';

type PreviewValues = Record<string, any>;
type PreviewErrors = Record<string, string>;


export type MobileFormPreviewProps = {
  title: string;
  description?: string;
  schema: SchemaNode[];
  values: PreviewValues;
  errors?: PreviewErrors;
  savePending?: boolean;
  submitDisabled?: boolean;
  onValueChange(fieldId: string, value: any): void;
  onSaveDraft?(): void;
  onSubmit(): void;
};

export function MobileFormPreview({
  title,
  description,
  schema,
  values,
  errors = {},
  savePending = false,
  submitDisabled = false,
  onValueChange,
  onSaveDraft,
  onSubmit,
}: MobileFormPreviewProps) {
  return (
    <div className="approval-mobile-preview" data-testid="mobile-form-preview">
      <header className="app-bar">
        <button type="button" aria-label="返回" disabled>
          <LeftOutlined />
        </button>
        <strong>填写表单</strong>
        <span aria-hidden="true" />
      </header>

      <main className="page form-fill-page">
        <div className="form-fill-page__body">
          <section className="form-fill-intro" aria-label="表单说明">
            <div className="form-fill-intro__kicker">
              <span>预览模式</span>
            </div>
            <h2>{title || '未命名表单'}</h2>
            {description ? (
              <p>{description}</p>
            ) : (
              <p>请填写以下内容，带 * 为必填项。</p>
            )}
          </section>

          <PreviewNodeList
            nodes={schema}
            values={values}
            errors={errors}
            onValueChange={onValueChange}
          />
        </div>
      </main>

      <div className="action-bar form-fill-action-bar">
        <button
          type="button"
          className="btn btn--ghost btn--lg"
          disabled={savePending || !onSaveDraft}
          onClick={onSaveDraft}
        >
          {savePending ? '保存中' : '保存草稿'}
        </button>
        <button
          type="button"
          className="btn btn--success btn--lg"
          disabled={submitDisabled}
          onClick={onSubmit}
        >
          提交
        </button>
      </div>
    </div>
  );
}

export function collectPreviewFieldErrors(
  nodes: SchemaNode[],
  values: PreviewValues,
): PreviewErrors {
  const errors: PreviewErrors = {};
  collectNodeErrors(nodes, values, errors);
  return errors;
}

function PreviewNodeList({
  nodes,
  values,
  errors,
  onValueChange,
}: {
  nodes: SchemaNode[];
  values: PreviewValues;
  errors: PreviewErrors;
  onValueChange(fieldId: string, value: any): void;
}) {
  const visibleNodes = visibleDescendantNodes(nodes, values);
  return (
    <div className="af-form-renderer">
      {visibleNodes.map((node) => (
        <div key={node.id} className="af-form-renderer__item" data-field-id={node.id}>
          <PreviewField
            node={node}
            values={values}
            errors={errors}
            error={errors[node.id]}
            onValueChange={onValueChange}
          />
        </div>
      ))}
    </div>
  );
}

function PreviewField({
  node,
  values,
  errors,
  error,
  onValueChange,
}: {
  node: SchemaNode;
  values: PreviewValues;
  errors: PreviewErrors;
  error?: string;
  onValueChange(fieldId: string, value: any): void;
}) {
  const value = values[node.id] ?? node.props?.defaultValue;
  const label = fieldLabel(node);
  const description = fieldDescription(node);
  const help = fieldHelp(node);


  if (node.type === 'span_layout') {
    return (
      <FieldShell node={node} label={label} error={error} description={description} help={help}>
        <div className="preview-span-layout">
          <PreviewNodeList
            nodes={node.children ?? []}
            values={values}
            errors={errors}
            onValueChange={onValueChange}
          />
        </div>
      </FieldShell>
    );
  }

  if (node.type === 'table_list') {
    return (
      <FieldShell node={node} label={label} error={error} description={description} help={help}>
        <TableListPreview node={node} value={value} />
      </FieldShell>
    );
  }

  if (node.type === 'description') {
    return (
      <FieldShell node={node} label={label}>
        <p className="af-field__description-text">
          {String(node.props?.text ?? node.props?.description ?? '')}
        </p>
      </FieldShell>
    );
  }

  return (
    <FieldShell
      node={node}
      label={label}
      error={error}
      description={description}
      help={help}
    >
      {renderControl({
        node,
        value,
        values,
        onValueChange,
      })}
    </FieldShell>
  );
}

function FieldShell({
  node,
  label,
  description,
  help,
  error,
  children,
}: {
  node: SchemaNode;
  label: string;
  description?: string | null;
  help?: string | null;
  error?: string;
  children: ReactNode;
}) {
  return (
    <section
      className={`af-field${error ? ' af-field--error' : ''}`}
      data-field-id={node.id}
    >
      <div className="af-field__head">
        <strong className="af-field__label">{label}</strong>
        {node.props?.required ? <span className="af-field__required">*</span> : null}
      </div>
      {description ? <p className="af-field__desc">{description}</p> : null}
      {children}
      {help ? <p className="af-field__help">{help}</p> : null}
      {error ? (
        <span role="alert" className="af-field__error">
          {error}
        </span>
      ) : null}
    </section>
  );
}

function renderControl({
  node,
  value,
  values,
  onValueChange,
}: {
  node: SchemaNode;
  value: any;
  values: PreviewValues;
  onValueChange(fieldId: string, value: any): void;
}) {
  const placeholder = String(node.props?.placeholder ?? '请输入');
  switch (node.type) {
    case 'textarea':
      return (
        <textarea
          className="af-control af-control--textarea"
          value={stringValue(value)}
          placeholder={placeholder}
          rows={3}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        />
      );
    case 'number':
      return (
        <input
          className="af-control"
          type="number"
          value={stringValue(value)}
          placeholder={placeholder}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        />
      );
    case 'money':
      return (
        <div className="input-unit">
          <b>¥</b>
          <input
            type="number"
            inputMode="decimal"
            value={stringValue(value)}
            placeholder="0.00"
            onChange={(event) => onValueChange(node.id, event.target.value)}
          />
        </div>
      );
    case 'date':
      return (
        <input
          className="af-control"
          type="date"
          value={stringValue(value)}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        />
      );
    case 'date_range':
      return (
        <div className="date-range-control">
          <input
            aria-label={`${fieldLabel(node)}开始日期`}
            type="date"
            value={stringValue(Array.isArray(value) ? value[0] : undefined)}
            onChange={(event) => {
              const next = Array.isArray(value) ? [...value] : ['', ''];
              next[0] = event.target.value;
              onValueChange(node.id, next);
            }}
          />
          <span className="date-range-control__to">至</span>
          <input
            aria-label={`${fieldLabel(node)}结束日期`}
            type="date"
            value={stringValue(Array.isArray(value) ? value[1] : undefined)}
            onChange={(event) => {
              const next = Array.isArray(value) ? [...value] : ['', ''];
              next[1] = event.target.value;
              onValueChange(node.id, next);
            }}
          />
          <span className="picker-icon-slot" aria-hidden="true">
            <CalendarOutlined />
          </span>
        </div>
      );
    case 'select':
    case 'radio':
      return (
        <select
          className="control form-picker"
          value={primitiveValue(value)}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        >
          <option value="">{String(node.props?.placeholder ?? `选择${fieldLabel(node)}`)}</option>
          {fieldOptions(node).map((option) => (
            <option key={String(option.value)} value={option.value} disabled={option.disabled}>
              {option.label}
            </option>
          ))}
        </select>
      );
    case 'multi_select':
    case 'checkbox':
      return (
        <MultiSelectControl
          node={node}
          value={value}
          onValueChange={onValueChange}
        />
      );
    case 'user_picker':
      return (
        <PickerButton
          icon={<UserOutlined />}
          label={pickerLabel(value, `选择${fieldLabel(node)}`)}
        />
      );
    case 'dept_picker':
      return (
        <PickerButton
          icon={<ApartmentOutlined />}
          label={pickerLabel(value, `选择${fieldLabel(node)}`)}
        />
      );
    case 'file_upload':
    case 'image_upload':
      return (
        <button type="button" className="upload-control">
          <UploadOutlined />
          <span>
            {Array.isArray(value) && value.length > 0
              ? `已上传 ${value.length} 个文件`
              : String(node.props?.buttonText ?? '上传附件')}
          </span>
        </button>
      );
    case 'time':
      return (
        <input
          className="af-control"
          type="time"
          value={stringValue(value)}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        />
      );
    case 'switch':
      return (
        <label className="preview-switch">
          <input
            type="checkbox"
            checked={value === true}
            onChange={(event) => onValueChange(node.id, event.target.checked)}
          />
          <span>{value === true ? '已开启' : '未开启'}</span>
        </label>
      );
    default:
      if (node.children?.length) {
        return (
          <PreviewNodeList
            nodes={node.children}
            values={values}
            errors={{}}
            onValueChange={onValueChange}
          />
        );
      }
      return (
        <input
          className="af-control"
          value={stringValue(value)}
          placeholder={placeholder}
          onChange={(event) => onValueChange(node.id, event.target.value)}
        />
      );
  }
}

function MultiSelectControl({
  node,
  value,
  onValueChange,
}: {
  node: SchemaNode;
  value: any;
  onValueChange(fieldId: string, value: any): void;
}) {
  const selected = Array.isArray(value) ? value.map(String) : [];
  const options = fieldOptions(node);
  if (options.length === 0) {
    return <div className="af-field__empty-options">暂无可选项</div>;
  }
  return (
    <div className="preview-choice-grid">
      {options.map((option) => {
        const valueKey = String(option.value);
        const checked = selected.includes(valueKey);
        return (
          <label key={valueKey} data-checked={checked ? 'true' : 'false'}>
            <input
              type="checkbox"
              checked={checked}
              disabled={option.disabled}
              onChange={() => {
                const next = checked
                  ? selected.filter((item) => item !== valueKey)
                  : [...selected, valueKey];
                onValueChange(node.id, next);
              }}
            />
            <span>{option.label}</span>
          </label>
        );
      })}
    </div>
  );
}

function PickerButton({ icon, label }: { icon: ReactNode; label: string }) {
  return (
    <button type="button" className="control form-picker user-picker-control">
      <span className="user-stack" aria-hidden="true">
        <span className="user-avatar">{icon}</span>
      </span>
      <span className="picker-value">{label}</span>
      <DownOutlined aria-hidden="true" />
    </button>
  );
}

function TableListPreview({ node, value }: { node: SchemaNode; value: any }) {
  const rows = Array.isArray(value) && value.length > 0 ? value : [{}];
  return (
    <div className="mobile-detail-table">
      <div className="mobile-detail-table__head">
        <div>
          <strong>明细表</strong>
          <small>{rows.length} 行</small>
        </div>
        <button type="button">
          <PlusOutlined />
          添加
        </button>
      </div>
      {rows.map((row, index) => (
        <article key={rowKey(node.id, row)} className="detail-entry">
          <header className="detail-entry__head">
            <span className="detail-entry__index">{String(index + 1).padStart(2, '0')}</span>
            <div>
              <strong>{rowTitle(row, node.children ?? [], index)}</strong>
              <small>{rowSubtitle(row, node.children ?? [])}</small>
            </div>
            <FileOutlined aria-hidden="true" />
          </header>
        </article>
      ))}
    </div>
  );
}






function collectNodeErrors(
  nodes: SchemaNode[],
  values: PreviewValues,
  errors: PreviewErrors,
) {
  for (const node of nodes) {
    if (!isVisibleNode(node, values)) continue;
    if (node.type === 'span_layout') {
      collectNodeErrors(node.children ?? [], values, errors);
      continue;
    }
    const value = values[node.id] ?? node.props?.defaultValue;
    const error = validateCommonRules(node, value);
    if (error) {
      errors[node.id] = error;
    }
    if (node.children && node.type !== 'table_list') {
      collectNodeErrors(node.children, values, errors);
    }
  }
}

function validateCommonRules(node: SchemaNode, value: any) {
  const label = fieldLabel(node);
  if (node.props?.required && isEmptyValue(value)) {
    return String(node.props?.validationMessage ?? `请填写${label}`);
  }
  if (isEmptyValue(value)) {
    return null;
  }
  if (typeof node.props?.minLength === 'number' && String(value).length < node.props.minLength) {
    return `${label}不能少于${node.props.minLength}个字符`;
  }
  if (typeof node.props?.maxLength === 'number' && String(value).length > node.props.maxLength) {
    return `${label}不能超过${node.props.maxLength}个字符`;
  }
  if (Array.isArray(value)) {
    const minChecked = numericRule(node, 'minChecked');
    const maxChecked = numericRule(node, 'maxChecked') ?? numericRule(node, 'maxSelected');
    if (minChecked != null && value.length < minChecked) {
      return `${label}至少选择${minChecked}项`;
    }
    if (maxChecked != null && value.length > maxChecked) {
      return `${label}最多选择${maxChecked}项`;
    }
  }
  if (node.props?.pattern) {
    try {
      if (!new RegExp(String(node.props.pattern)).test(String(value))) {
        return String(node.props.validationMessage ?? `${label}格式不正确`);
      }
    } catch {
      return `${label}校验规则无效`;
    }
  }
  return null;
}

function visibleDescendantNodes(nodes: SchemaNode[], values: PreviewValues): SchemaNode[] {
  return nodes.flatMap((node) => {
    if (!isVisibleNode(node, values)) {
      return [];
    }
    return [{
      ...node,
      children: node.children ? visibleDescendantNodes(node.children, values) : undefined,
    }];
  });
}

function isVisibleNode(node: SchemaNode, values: PreviewValues) {
  return node.props?.hidden !== true && matchesDisplayCondition(node.props?.displayCondition, values);
}

function matchesDisplayCondition(condition: any, values: PreviewValues) {
  if (!condition || typeof condition !== 'object') return true;
  const fieldId = condition.fieldId ?? condition.field;
  if (typeof fieldId !== 'string' || !fieldId) return true;
  const sourceValue = values[fieldId];
  const targetValue = condition.value;
  switch (String(condition.operator ?? 'eq')) {
    case 'ne':
    case '!=':
    case '!==':
      return String(sourceValue ?? '') !== String(targetValue ?? '');
    case 'contains':
      return Array.isArray(sourceValue)
        ? sourceValue.map(String).includes(String(targetValue ?? ''))
        : String(sourceValue ?? '').includes(String(targetValue ?? ''));
    case 'empty':
      return isEmptyValue(sourceValue);
    case 'notEmpty':
      return !isEmptyValue(sourceValue);
    case 'gt':
    case '>':
      return numberCompare(sourceValue, targetValue, (left, right) => left > right);
    case 'gte':
    case '>=':
      return numberCompare(sourceValue, targetValue, (left, right) => left >= right);
    case 'lt':
    case '<':
      return numberCompare(sourceValue, targetValue, (left, right) => left < right);
    case 'lte':
    case '<=':
      return numberCompare(sourceValue, targetValue, (left, right) => left <= right);
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
}







function fieldLabel(node: SchemaNode) {
  return node.label || String(node.props?.title ?? node.props?.label ?? formRegistry[node.type]?.label ?? node.id);
}

function fieldDescription(node: SchemaNode) {
  const text = node.props?.questionDescription ?? node.props?.description;
  return typeof text === 'string' && text.trim() ? text : null;
}

function fieldHelp(node: SchemaNode) {
  const text = node.props?.helpText ?? node.props?.help;
  return typeof text === 'string' && text.trim() ? text : null;
}



function fieldOptions(node: SchemaNode) {
  const options = node.props?.options;
  if (!Array.isArray(options)) return [];
  return options.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const option = item as Record<string, any>;
    if (typeof option.value !== 'string' && typeof option.value !== 'number') {
      return [];
    }
    return [{
      label: String(option.label ?? option.value),
      value: option.value,
      disabled: option.disabled === true,
    }];
  });
}

function pickerLabel(value: any, placeholder: string) {
  if (isEmptyValue(value)) return placeholder;
  if (Array.isArray(value)) return value.join('、');
  if (typeof value === 'object') return String(value.name ?? value.label ?? placeholder);
  return String(value);
}

function primitiveValue(value: any) {
  return typeof value === 'string' || typeof value === 'number' ? value : '';
}

function stringValue(value: any) {
  if (value == null) return '';
  return typeof value === 'string' ? value : String(value);
}

function isEmptyValue(value: any) {
  return (
    value == null ||
    value === '' ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

function numericRule(node: SchemaNode, key: string) {
  const value = node.props?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function numberCompare(
  sourceValue: any,
  targetValue: any,
  compare: (left: number, right: number) => boolean,
) {
  const left = Number(sourceValue);
  const right = Number(targetValue);
  return Number.isFinite(left) && Number.isFinite(right) && compare(left, right);
}

function rowTitle(row: any, children: SchemaNode[], index: number) {
  const firstValue = children
    .map((child) => row?.[child.id])
    .find((item) => !isEmptyValue(item));
  return firstValue == null ? `第${index + 1}行` : String(firstValue);
}

function rowSubtitle(row: any, children: SchemaNode[]) {
  const values = children
    .slice(0, 3)
    .map((child) => {
      const value = row?.[child.id];
      return isEmptyValue(value) ? '' : `${fieldLabel(child)}: ${String(value)}`;
    })
    .filter(Boolean);
  return values.length > 0 ? values.join(' · ') : '展开后填写明细';
}

function rowKey(nodeId: string, row: any) {
  if (row && typeof row === 'object') {
    const key = row.id ?? row.key ?? row.localId;
    if (typeof key === 'string' || typeof key === 'number') {
      return `${nodeId}-${key}`;
    }
    return `${nodeId}-${JSON.stringify(row)}`;
  }
  return `${nodeId}-${String(row)}`;
}

export default MobileFormPreview;
