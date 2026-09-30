import { Upload, Button, message } from 'antd';
import type { UploadFile } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  deleteNativeFile,
  toMediaFiles,
  uploadMediaFile,
  withPendingUpload,
  type MediaUploadEvent,
  type NativeFile,
} from './nativeMedia';
import type { FieldMode, SchemaNode } from '../../registry/types';

export type MediaKind = 'image' | 'video' | 'file';

type PendingItem = {
  /** 本地 id：服务端还没给 id。不能拿数组下标当 uid（并发上传/删除会串到别的行上）。 */
  uid: string;
  name: string;
  status: 'uploading' | 'processing' | 'error';
  percent: number;
  error?: string;
  file: File;
};

let localSequence = 0;
function nextUid() {
  localSequence += 1;
  return `local-${Date.now()}-${localSequence}`;
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '上传失败，请重试';
}

/**
 * 图片/视频/附件的上传控件（桌面端四处共用：图片、视频、文件、检查项照片）。
 *
 * <p>几条不能动的约定：
 * <ul>
 *   <li>真上传走 `customRequest`。以前是 `beforeUpload={() => false}`——antd 从不发请求，值里躺着本地
 *       `File`，提交时 `collectFileRefs` 认不出它（要 `id` + `contentType`），文件就静默丢了。</li>
 *   <li>`fileList` 全受控、**不接 `onChange`**：antd 在 uploading/done/error 每次流转都会触发它，
 *       照它写值就会把 raw UploadFile 灌回表单。表单值只在下面的成功/删除分支里写。</li>
 *   <li>**只有 READY 的服务端 DTO 进表单值**：服务端的附件关联只接受 READY（视频加水印时是异步的），
 *       PROCESSING 的留在本地状态里显示"处理中"。</li>
 *   <li>数量与大小在这里挡（`beforeUpload` 返回 LIST_IGNORE），不靠 antd 的 `maxCount`：受控列表被它
 *       trim 时会静默丢掉已经上传好的项。</li>
 * </ul>
 */
export function MediaUploadControl({
  node, mode, value, onChange, kind, multiple, maxCount, maxSizeMB, accept, watermark,
  watermarkText, buttonText, hint,
}: {
  node: SchemaNode;
  mode: FieldMode;
  value?: unknown;
  onChange?(value: NativeFile[]): void;
  kind: MediaKind;
  multiple?: boolean;
  maxCount?: number;
  maxSizeMB?: number;
  accept?: string;
  watermark?: boolean;
  watermarkText?: string;
  buttonText?: string;
  hint?: string;
}) {
  const [pending, setPending] = useState<PendingItem[]>([]);
  const removedRef = useRef(new Set<string>());
  const pendingFlagRef = useRef(false);
  /** 已提交的文件以 ref 为准：并发上传时用渲染期捕获的 `value` 会互相覆盖。 */
  const filesRef = useRef<NativeFile[]>([]);

  const uploaded = toMediaFiles(value).filter((file) => file.id);
  const limit = maxCount ?? (multiple ? undefined : 1);
  const remaining = limit == null ? undefined : Math.max(0, limit - uploaded.length);

  useEffect(() => {
    filesRef.current = (Array.isArray(value) ? value : [])
      .filter((item): item is NativeFile => !!item && typeof item === 'object'
        && typeof (item as NativeFile).id === 'string');
  }, [value]);

  // "还有文件在传"要能被提交校验看到：数组上打一个非枚举的 Symbol（见 nativeMedia）。
  // 只在标记状态**真的变化**时写一次，否则每次成功回调都会多写一轮。
  useEffect(() => {
    const shouldBePending = pending.length > 0;
    if (shouldBePending === pendingFlagRef.current) return;
    pendingFlagRef.current = shouldBePending;
    onChange?.(withPendingUpload([...filesRef.current], shouldBePending));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending.length]);

  const writeFiles = (files: NativeFile[]) => {
    filesRef.current = files;
    onChange?.(withPendingUpload([...files], pendingFlagRef.current));
  };

  const startUpload = async (item: PendingItem) => {
    const patch = (changes: Partial<PendingItem>) => setPending((entries) =>
      entries.map((entry) => (entry.uid === item.uid ? { ...entry, ...changes } : entry)));
    try {
      const file = await uploadMediaFile(item.file, {
        watermark,
        watermarkText,
        onProgress: (event: MediaUploadEvent) => {
          // 服务端说在转码就显示"处理中"：停在上传的百分比上会让人以为快好了。
          patch(event.phase === 'processing' ? { status: 'processing', percent: 97 }
            : { percent: event.percent });
        },
      });
      if (removedRef.current.has(item.uid)) {
        // 上传期间用户把它删了：服务端文件一起删掉，别留孤儿。
        void deleteNativeFile(file.id).catch(() => undefined);
        return;
      }
      setPending((entries) => entries.filter((entry) => entry.uid !== item.uid));
      writeFiles([...filesRef.current.filter((entry) => entry.id !== file.id), file]);
    } catch (error) {
      if (removedRef.current.has(item.uid)) {
        setPending((entries) => entries.filter((entry) => entry.uid !== item.uid));
        return;
      }
      patch({ status: 'error', percent: 0, error: errorMessage(error) });
    }
  };

  const enqueue = (file: File) => {
    const item: PendingItem = {
      uid: nextUid(), name: file.name, status: 'uploading', percent: 0, file,
    };
    removedRef.current.delete(item.uid);
    setPending((entries) => [...entries, item]);
    void startUpload(item);
  };

  const failed = pending.find((item) => item.status === 'error');

  const fileList: UploadFile[] = [
    ...uploaded.map((file) => ({
      uid: file.id as string,
      name: file.name ?? '文件',
      status: 'done' as const,
      // 服务端内容要鉴权：这里的 url 交出去只会加载失败，缩略图走只读态的鉴权 blob 组件。
      isImageUrl: () => false,
    })),
    ...pending.map((item) => ({
      uid: item.uid,
      name: item.name,
      status: 'uploading' as const,
      percent: item.percent,
    })),
  ];

  const remove = (uid: string) => {
    const pendingItem = pending.find((item) => item.uid === uid);
    if (pendingItem) {
      // 处理中的视频不给删：服务端后台任务正往同一个 key 写结果，删了会留下读不出来的孤儿对象
      // （那件事要单独修，先别把用户送进那个坑）。
      if (pendingItem.status === 'processing') {
        message.warning('视频正在加水印，处理完再删');
        return false;
      }
      removedRef.current.add(uid);
      setPending((entries) => entries.filter((entry) => entry.uid !== uid));
      return false;
    }
    const target = filesRef.current.find((file) => file.id === uid);
    if (target) {
      writeFiles(filesRef.current.filter((file) => file.id !== uid));
      void deleteNativeFile(target.id)
        .catch(() => message.warning('已从表单移除，但服务端删除失败'));
    }
    return false;
  };

  return (
    <Upload
      disabled={mode !== 'runtime-fill'}
      multiple={!!multiple}
      accept={accept}
      listType={kind === 'image' ? 'picture' : 'text'}
      fileList={fileList}
      customRequest={(options) => enqueue(options.file as File)}
      onRemove={(file) => remove(String(file.uid))}
      beforeUpload={(file) => {
        if (maxSizeMB && file.size / 1024 / 1024 > maxSizeMB) {
          message.error(`单个文件不能超过 ${maxSizeMB}MB`);
          return Upload.LIST_IGNORE;
        }
        if (remaining != null && remaining <= 0) {
          message.error(`最多上传 ${limit} 个`);
          return Upload.LIST_IGNORE;
        }
        return true;
      }}
    >
      <Button>
        {buttonText || (kind === 'image' ? '添加图片' : kind === 'video' ? '添加视频' : '选择文件')}
      </Button>
      {hint ? <span className="form-fields-media-hint">{hint}</span> : null}
      {failed ? (
        <span className="form-fields-media-hint">
          {failed.error}
          <Button type="link" size="small" onClick={() => {
            setPending((entries) => entries.filter((entry) => entry.uid !== failed.uid));
            enqueue(failed.file);
          }}>
            重试
          </Button>
        </span>
      ) : null}
      {node.props?.required && !uploaded.length ? null : null}
    </Upload>
  );
}
