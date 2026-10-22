import { useEffect, useMemo, useRef, useState } from 'react';
import type { UploadProgressEvent } from '../files.api';
import {
  deleteMobileFile,
  FileStillProcessingError,
  uploadMobileFile,
  waitForProcessedFile,
} from '../files.api';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { createClientId } from '../../../shared/clientId';
import type { UploadItem } from './fileUploadValue';
import {
  asReadyFiles,
  createFileUploadValue,
  hasIncompleteUploads,
  isUploadActive,
  mergeReadyItems,
  readyFiles,
  sameUploadItems,
} from './fileUploadValue';
import type { FileUploadConfig } from './fileUploadRules';
import {
  errorMessage,
  heicToJpeg,
  isHeicFile,
  isVideoFile,
  maxCountError,
  readVideoDuration,
  uploadExtraFields,
  videoDurationError,
} from './fileUploadRules';

type UploadQueueOptions = {
  node: MobileSchemaNode;
  value: unknown;
  endpoint: string;
  config: Pick<FileUploadConfig, 'maxCount' | 'maxDuration' | 'unitLabel'>;
  onValueChange: MobileFieldProps['onValueChange'];
};

/** 上传队列：条目状态机 + 表单值回写。itemsRef 与 items 同步更新，供异步回调读最新值。 */
export function useUploadQueue({ node, value, endpoint, config, onValueChange }: UploadQueueOptions) {
  const { maxCount, maxDuration, unitLabel } = config;
  const [items, setItems] = useState<UploadItem[]>([]);
  const [localError, setLocalError] = useState<string | null>(null);
  const itemsRef = useRef<UploadItem[]>([]);
  const readyValues = useMemo(() => asReadyFiles(value), [value]);

  useEffect(() => {
    const next = mergeReadyItems(itemsRef.current, readyValues);
    if (sameUploadItems(itemsRef.current, next)) {
      return;
    }
    itemsRef.current = next;
    setItems(next);
  }, [readyValues]);

  return {
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
  };

  async function addFile(file: File) {
    let nextFile = file;
    if (node.props?.convertHeic === true && isHeicFile(file)) {
      try {
        const converted = await heicToJpeg(file);
        if (!converted) {
          throw new Error('无法处理 HEIC 图片，请在手机设置中把相机格式改为"兼容性最好"后重试');
        }
        nextFile = converted;
      } catch (error) {
        setLocalError(errorMessage(error));
        return;
      }
    }
    const error = await beforeAddError(nextFile);
    if (error) {
      setLocalError(error);
      return;
    }
    setLocalError(null);
    await queueFileUpload(nextFile);
  }

  async function beforeAddError(file: File) {
    if (typeof maxCount === 'number') {
      const currentCount = itemsRef.current.filter(
        (item) => item.status !== 'deleting' && item.status !== 'delete_failed',
      ).length;
      if (currentCount >= maxCount) {
        return maxCountError(node, currentCount) ?? `最多上传 ${maxCount} ${unitLabel}`;
      }
    }
    if (typeof maxDuration === 'number' && isVideoFile(file)) {
      const duration = await readVideoDuration(file);
      if (duration > maxDuration) {
        return videoDurationError(node, duration) ?? `视频不能超过 ${maxDuration} 秒`;
      }
    }
    return null;
  }

  async function queueFileUpload(file: File, localId = createLocalId()) {
    const uploadingItem: UploadItem = { localId, file, status: 'uploading', progress: 0 };
    commitItems([
      ...itemsRef.current.filter((item) => item.localId !== localId),
      uploadingItem,
    ]);
    await wait(20);
    try {
      const remote = await uploadMobileFile(
        endpoint,
        file,
        (event) => {
          updateUploadProgress(localId, event);
        },
        uploadExtraFields(node),
      );
      commitItems(
        itemsRef.current.map((item): UploadItem =>
          item.localId === localId
            ? { ...item, status: 'ready', progress: 100, remote, error: undefined, createdInSession: true }
            : item,
        ),
      );
    } catch (error) {
      failItem(localId, error);
    }
  }

  /**
   * 客户端轮询窗口用完但服务端还在处理：**不算失败**（否则用户会重传，视频不走去重）。
   * 保留 remote 与 processing 状态：值仍算"未完成上传"（提交会被拦），重试按钮变成"继续等待"。
   */
  function failItem(localId: string, error: unknown) {
    const stillProcessing = error instanceof FileStillProcessingError;
    commitItems(
      itemsRef.current.map((item): UploadItem =>
        item.localId === localId
          ? {
              ...item,
              status: stillProcessing ? 'processing' : 'failed',
              progress: stillProcessing ? Math.max(item.progress, 99) : item.progress,
              remote: stillProcessing ? error.file : item.remote,
              error: stillProcessing ? undefined : errorMessage(error),
            }
          : item,
      ),
    );
  }

  /** 已知服务端文件时的"重试"= 继续轮询状态，不是再上传一份。 */
  async function resumeProcessing(item: UploadItem) {
    if (!item.remote) return;
    commitItems(itemsRef.current.map((current): UploadItem =>
      current.localId === item.localId
        ? { ...current, status: 'processing', error: undefined }
        : current,
    ));
    try {
      const remote = await waitForProcessedFile(
        item.remote,
        (event) => updateUploadProgress(item.localId, event),
      );
      commitItems(itemsRef.current.map((current): UploadItem =>
        current.localId === item.localId
          ? { ...current, status: 'ready', progress: 100, remote, error: undefined, createdInSession: true }
          : current,
      ));
    } catch (error) {
      failItem(item.localId, error);
    }
  }

  function updateUploadProgress(localId: string, event: UploadProgressEvent) {
    if (event.phase === 'done') {
      return;
    }
    const bounded = Math.min(100, Math.max(0, Math.round(event.progress)));
    const nextStatus = event.phase === 'uploading' ? 'uploading' : 'processing';
    let changed = false;
    const next = itemsRef.current.map((item): UploadItem => {
      if (item.localId !== localId || !isUploadActive(item)) {
        return item;
      }
      if (item.status === 'processing' && event.phase === 'uploading') {
        return item;
      }
      if (item.progress >= bounded && item.status === nextStatus) {
        return item;
      }
      changed = true;
      return { ...item, status: nextStatus, progress: Math.max(item.progress, bounded), error: undefined };
    });
    if (changed) {
      commitItems(next, false);
    }
  }

  async function removeItem(localId: string) {
    const target = itemsRef.current.find((item) => item.localId === localId);
    if (target?.status === 'deleting') {
      return;
    }
    // 轮询窗口用完的条目也算"服务端已知"：不试删除的话，用户既提交不了（值里没有它）也摆脱不掉它。
    // shortcut: 服务端对转码中的行按设计拒绝删除（BAD_FILE_STATE），那种情况只做本地移除，对象要等
    // 服务端支持"吊销"才会被清掉——删不掉的残留好过把用户卡在"处理中"。
    const stillProcessing = target?.status === 'processing';
    if (target?.remote && (target.createdInSession || stillProcessing)) {
      commitItems(
        itemsRef.current.map((item): UploadItem =>
          item.localId === localId ? { ...item, status: 'deleting', progress: 100 } : item,
        ),
      );
      try {
        await deleteMobileFile(target.remote.id);
      } catch (error) {
        if (!stillProcessing) {
          commitItems(
            itemsRef.current.map((item): UploadItem =>
              item.localId === localId
                ? { ...item, status: 'delete_failed', progress: 100, error: errorMessage(error) }
                : item,
            ),
          );
          return;
        }
      }
    }
    commitItems(itemsRef.current.filter((item) => item.localId !== localId));
  }

  function commitItems(nextItems: UploadItem[], emitValue = true) {
    itemsRef.current = nextItems;
    setItems(nextItems);
    if (emitValue) {
      emitUploadValue(nextItems);
    }
  }

  function emitUploadValue(nextItems: UploadItem[]) {
    onValueChange(node.id, createFileUploadValue(readyFiles(nextItems), hasIncompleteUploads(nextItems)));
  }
}

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createLocalId() {
  return createClientId('local');
}
