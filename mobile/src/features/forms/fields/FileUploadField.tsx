import { useRef } from 'react';
import { UploadOutline } from 'antd-mobile-icons';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { ReadonlyMediaList } from '../components/MediaPreview';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import { localBlocker, readyFiles } from './fileUploadValue';
import { resolveFileUploadConfig } from './fileUploadRules';
import { uploadHint } from './fileUploadLabels';
import { useUploadQueue } from './useUploadQueue';
import { useUploadPreviewUrls } from './useUploadPreviewUrls';
import { FileUploadItemRow } from './FileUploadItemRow';
import { NativeActionContent } from './NativeActionContent';

export { createFileUploadValue, hasBlockingUploadQueue } from './fileUploadValue';
export type { FileUploadValue, UploadItem } from './fileUploadValue';
export { isHeicFile, maxCountError, uploadExtraFields, videoDurationError } from './fileUploadRules';

export function FileUploadField(props: MobileFieldProps) {
  const platform = usePlatformAdapter();
  const label = fieldLabel(props.node);
  const {
    endpoint,
    accept,
    multiple,
    previewImages,
    maxCount,
    maxDuration,
    capture,
    addLabel,
    unitLabel,
  } = resolveFileUploadConfig(props.node);
  const inputRef = useRef<HTMLInputElement>(null);
  const {
    items,
    itemsRef,
    localError,
    setLocalError,
    readyValues,
    addFile,
    commitItems,
    removeItem,
    queueFileUpload,
    resumeProcessing,
  } = useUploadQueue({
    node: props.node,
    value: props.value,
    endpoint,
    config: { maxCount, maxDuration, unitLabel },
    onValueChange: props.onValueChange,
  });
  const previewUrls = useUploadPreviewUrls(items, itemsRef, previewImages);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props) || localBlocker(items) || localError}
    >
      {props.mode === 'readonly' ? (
        <ReadonlyMediaList files={readyValues} />
      ) : (
        <div className="upload-control af-upload-control">
          <div className="af-upload-list">
            {items.map((item) => {
              const previewUrl = previewImages && item.remote ? previewUrls[item.localId] ?? '' : '';
              return (
                <FileUploadItemRow
                  key={item.localId}
                  item={item}
                  previewUrl={previewUrl}
                  onRetry={(target) => void (target.remote?.status === 'PROCESSING'
                    ? resumeProcessing(target)
                    : queueFileUpload(target.file, target.localId))}
                  onDelete={(localId) => void removeItem(localId)}
                />
              );
            })}
          </div>
          {previewImages && platform.chooseImages ? (
            <button type="button" className="upload-trigger upload-trigger--platform" onClick={() => {
              setLocalError(null);
              const remaining = Math.max(1, (maxCount ?? 20) - readyFiles(itemsRef.current).length);
              const configuredSource = props.node.props?.source;
              const source = configuredSource === 'camera' || configuredSource === 'album'
                ? configuredSource : 'both';
              void platform.chooseImages?.(remaining, source).then((selected) => {
                const nextItems = [...itemsRef.current, ...selected.map((remote) => ({
                  localId: remote.id,
                  file: new File([], remote.name || remote.id, { type: remote.contentType }),
                  status: 'ready' as const,
                  progress: 100,
                  remote,
                  createdInSession: true,
                }))].slice(0, maxCount ?? Number.POSITIVE_INFINITY);
                commitItems(nextItems);
              }).catch((error) => setLocalError(error instanceof Error ? error.message : '选择图片失败'));
            }}>
              <NativeActionContent
                icon={<UploadOutline aria-hidden="true" />}
                title={addLabel}
                hint="从相册选择或拍照"
              />
            </button>
          ) : <label className="upload-trigger">
            <input
              ref={inputRef}
              aria-label={label}
              type="file"
              accept={accept || undefined}
              multiple={multiple}
              capture={capture}
              onChange={(event) => {
                const files = Array.from(event.target.files ?? []);
                event.target.value = '';
                for (const file of files) {
                  void addFile(file);
                }
              }}
            />
            <NativeActionContent
              icon={<UploadOutline aria-hidden="true" />}
              title={addLabel}
              hint={uploadHint(accept, multiple)}
            />
          </label>}
        </div>
      )}
    </FieldShell>
  );
}
