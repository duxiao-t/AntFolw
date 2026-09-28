import { CheckOutline, DownOutline } from 'antd-mobile-icons';
import type { ReactNode } from 'react';

/**
 * 下拉选择器的共用外观件。
 *
 * 静态选项与外部数据源选项只是「候选从哪来」不同，呈现必须完全一致：
 * 触发按钮、搜索框、选项行都在这里定义，字段实现只负责提供数据与选中态，
 * 避免同一件事维护两套 UI。
 */

export type PickerOption = {
  value: string | number;
  label: string;
  color?: string;
  disabled?: boolean;
  selected: boolean;
};

/**
 * 触发按钮。单选展示当前文案，多选展示已选标签（与静态多选下拉同构）；
 * 都未选中时展示 placeholder 并带上占位样式。
 */
export function PickerTrigger({ value, labels, placeholder, disabled, onClick }: {
  /** 单选：当前显示文案。 */
  value?: string;
  /** 多选：已选项的显示名。传入即表示多选形态。 */
  labels?: string[];
  placeholder: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  const multiple = labels !== undefined;
  const hasContent = multiple ? labels.length > 0 : Boolean(value);
  return (
    <button
      type="button"
      className={`control form-picker${multiple ? ' control--multi' : ''}${hasContent ? '' : ' af-field-picker--placeholder'}`}
      disabled={disabled}
      onClick={onClick}
    >
      {multiple && hasContent ? (
        <span className="selected-tags">
          {labels.map((item) => <span key={item}>{item}</span>)}
        </span>
      ) : (
        <span className="picker-value">{multiple ? placeholder : value || placeholder}</span>
      )}
      <DownOutline aria-hidden="true" />
    </button>
  );
}

export function PickerSearchInput({ label, placeholder, value, onChange }: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <input
      type="search"
      className="af-full-picker__search"
      aria-label={label}
      placeholder={placeholder}
      value={value}
      onChange={(event) => onChange(event.currentTarget.value)}
    />
  );
}

function OptionAvatar({ label, color, useColor }: { label: string; color?: string; useColor?: boolean }) {
  return (
    <span
      className="af-full-picker__avatar af-full-picker__avatar--choice"
      aria-hidden="true"
      style={useColor && color ? { background: color } : undefined}
    >
      {label.trim().slice(0, 1)}
    </span>
  );
}

/**
 * 选项列表。单选沿用 listbox/option + 勾选态；多选沿用 fieldset + 原生 checkbox。
 * 两种形态分别与静态单/多选下拉保持一致，因此静态与动态字段渲染出来的列表是同一套。
 */
export function PickerOptionList({ label, multiple, options, useColor, empty, onSelect,
  onScrollEnd, footer }: {
  label: string;
  multiple?: boolean;
  options: PickerOption[];
  useColor?: boolean;
  empty?: string | null;
  onSelect: (option: PickerOption) => void;
  /** 滚动接近底部时触发（用于继续取下一页）。 */
  onScrollEnd?: () => void;
  /** 挂在列表末尾的内容，例如「加载中…」。 */
  footer?: ReactNode;
}) {
  // 空态提示只在真的没有候选时出现，文案由调用方决定（如动态侧的「正在加载…」或错误信息）。
  const emptyText = options.length === 0 ? empty ?? '没有匹配的选项' : null;
  // 接近底部就继续取下一页。
  const maybeLoadMore = (el: HTMLElement) => {
    if (onScrollEnd && el.scrollHeight - el.scrollTop - el.clientHeight < 48) onScrollEnd();
  };
  if (multiple) {
    return (
      <fieldset className="af-full-picker__list af-full-picker__fieldset"
        onScroll={(event) => maybeLoadMore(event.currentTarget)}>
        <legend className="visually-hidden">{label}</legend>
        {options.map((option) => (
          <label
            key={String(option.value)}
            data-checked={option.selected ? 'true' : 'false'}
            data-disabled={option.disabled ? 'true' : 'false'}
            className="af-full-picker__option af-full-picker__option--select af-full-picker__option--check"
          >
            <OptionAvatar label={option.label} color={option.color} useColor={useColor} />
            <span className="af-full-picker__option-text">
              <strong>{option.label}</strong>
            </span>
            <input
              type="checkbox"
              aria-label={option.label}
              checked={option.selected}
              disabled={option.disabled}
              className="af-full-picker__native-check"
              onChange={() => onSelect(option)}
            />
          </label>
        ))}
        {emptyText ? <p className="af-full-picker__empty" role="status">{emptyText}</p> : null}
        {footer}
      </fieldset>
    );
  }
  return (
    <div role="listbox" aria-label={label} className="af-full-picker__list"
      onScroll={(event) => maybeLoadMore(event.currentTarget)}>
      {options.map((option) => (
        <button
          key={String(option.value)}
          type="button"
          role="option"
          aria-label={option.label}
          aria-selected={option.selected}
          className="af-full-picker__option af-full-picker__option--select"
          disabled={option.disabled}
          onClick={() => onSelect(option)}
        >
          <OptionAvatar label={option.label} color={option.color} useColor={useColor} />
          <span className="af-full-picker__option-text">
            <strong>{option.label}</strong>
          </span>
          <span className="af-full-picker__option-status" aria-hidden="true">
            {option.selected ? <CheckOutline /> : null}
          </span>
        </button>
      ))}
      {emptyText ? <p className="af-full-picker__empty" role="status">{emptyText}</p> : null}
      {footer}
    </div>
  );
}
