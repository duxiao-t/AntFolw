import { TextArea } from 'antd-mobile';
import type { CSSProperties } from 'react';
import { ReadonlyMediaList } from '../components/MediaPreview';
import type { ChecklistEntry, ChecklistItemDef, ChecklistResultOption, ChecklistValue } from './checklistValue';
import { ChecklistPhotoUpload } from './ChecklistPhotoUpload';

/** 只读态：每条检查项的结果色标签 + 描述 + 历史图片/视频。 */
export function ChecklistReadonlyList({
  items,
  entries,
  results,
}: {
  items: ChecklistItemDef[];
  entries: ChecklistValue;
  results: ChecklistResultOption[];
}) {
  return (
    <div className="af-checklist__list">
      {items.length === 0 ? <div className="af-checklist__empty">尚未配置检查项</div> : null}
      {entries.map((entry) => {
        const result = results.find((option) => option.id === entry.status);
        return (
          <div key={entry.id} className="af-check__readonly-card">
            <div className="af-check__readonly-head">
              <strong>{entry.name || items.find((item) => item.id === entry.id)?.label}</strong>
              <span style={{ color: result?.color }}>{result?.label ?? entry.status ?? '未完成'}</span>
            </div>
            {entry.description.trim() ? (
              <p className="af-check__readonly-description">{entry.description}</p>
            ) : null}
            {entry.images.length > 0 ? <ReadonlyMediaList files={entry.images} /> : null}
          </div>
        );
      })}
    </div>
  );
}

/** 单条可编辑检查项卡片：未选态只露结果按钮，已选态可取消/补描述/传图。 */
export function CheckCard({
  item,
  entry,
  results,
  selectedResult,
  expanded,
  allowDescription,
  photoMaxCount,
  onStatus,
  onExpand,
  onEntry,
}: {
  item: ChecklistItemDef;
  entry: ChecklistEntry;
  results: ChecklistResultOption[];
  selectedResult?: ChecklistResultOption;
  expanded: boolean;
  allowDescription: boolean;
  photoMaxCount: number;
  onStatus(status: string | null): void;
  onExpand(expanded: boolean): void;
  onEntry(patch: Partial<Omit<ChecklistEntry, 'id' | 'name'>>): void;
}) {
  if (!selectedResult) {
    return (
      <div className="af-check__card af-check__card--none">
        <div className="af-check__head">
          <span className="af-check__name">{item.label}</span>
          <span className="af-check__actions">
            {results.map((result, index) => (
              <span key={result.id} className="af-check__actions-inner">
                {index > 0 ? <span className="af-check__divider" aria-hidden="true" /> : null}
                <button
                  type="button"
                  className="af-check__result-btn"
                  aria-label={result.label}
                  style={{ '--af-check-color': result.color } as CSSProperties}
                  onClick={() => onStatus(result.id)}
                >
                  {result.label}
                </button>
              </span>
            ))}
          </span>
        </div>
      </div>
    );
  }

  const statusLabel = selectedResult.label;
  return (
    <div
      className="af-check__card af-check__card--selected"
      style={{ '--af-check-color': selectedResult.color } as CSSProperties}
    >
      <div className="af-check__head">
        <button
          type="button"
          className="af-check__name"
          onClick={() => onExpand(false)}
        >
          {item.label}
        </button>
        <span className="af-check__status-area">
          <button
            type="button"
            className="af-check__status-button"
            aria-label={`${statusLabel}，点击取消选择`}
            onClick={(event) => {
              event.stopPropagation();
              onStatus(null);
              onExpand(false);
            }}
          >
            {statusLabel}
          </button>
          {allowDescription && !expanded ? (
            <button
              type="button"
              className="af-check__add-desc"
              onClick={(event) => {
                event.stopPropagation();
                onExpand(true);
              }}
            >
              添加描述
            </button>
          ) : null}
        </span>
      </div>
      {expanded ? (
        <div className="af-check__detail">
          <TextArea
            className="af-check__desc-input"
            placeholder="请输入描述"
            autoSize={{ minRows: 3 }}
            value={entry.description}
            onChange={(value) => onEntry({ description: value })}
          />
          <ChecklistPhotoUpload
            photos={entry.images}
            maxCount={photoMaxCount}
            onChange={(images) => onEntry({ images })}
          />
        </div>
      ) : null}
    </div>
  );
}
