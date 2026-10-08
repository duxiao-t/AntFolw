import { DownloadOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { Button, Image, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { toMediaFiles, type MediaFileLike } from './nativeMedia';

/**
 * 上传件的只读展示。
 *
 * <p>内容地址（`/api/mobile/files/{id}/content`）要鉴权，直接塞给 `<img src>` 在令牌鉴权下只会是坏图，
 * 所以统一走 umi 的 request 取 blob 再转 object URL——这也是原来 `ChecklistField` 里那套做法，
 * 现在抽出来给图片/视频/附件/检查项照片四处共用。
 */
export function AuthenticatedMedia({ file, size = 96, withName = true }:
  { file: MediaFileLike; size?: number; withName?: boolean }) {
  const contentUrl = file.contentUrl;
  const [url, setUrl] = useState('');
  const [failed, setFailed] = useState(false);
  const name = file.name ?? file.fileName ?? '媒体';
  const video = String(file.contentType ?? '').startsWith('video/')
    || /\.(mp4|mov|3gp|3gpp|webm|m4v)$/i.test(name);

  useEffect(() => {
    // 换图/清空时要先把上一个状态清掉：否则请求在飞的那段时间会把上一张图挂在新文件名下，
    // 而没有地址的文件会永远停在"加载中"。
    setUrl('');
    setFailed(false);
    if (!contentUrl) {
      setFailed(true);
      return;
    }
    let alive = true;
    let objectUrl = '';
    request<Blob>(contentUrl, { responseType: 'blob', skipErrorHandler: true })
      .then((blob) => {
        if (!alive) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => { if (alive) setFailed(true); });
    return () => {
      alive = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [contentUrl]);

  return (
    <div style={{ display: 'grid', gap: 4 }}>
      {url && video ? (
        // biome-ignore lint/a11y/useMediaCaption: 现场留证视频没有字幕轨。
        <video controls preload="metadata" src={url}
          style={{ width: Math.max(size, 220), maxWidth: '100%', borderRadius: 8 }} />
      ) : url ? <Image width={size} height={size} src={url} alt={name}
        style={{ objectFit: 'cover', borderRadius: 8 }} />
        : <Typography.Text type="secondary">
          {failed ? '媒体加载失败' : '媒体加载中…'}
        </Typography.Text>}
      {withName ? <Typography.Text type="secondary">{name}</Typography.Text> : null}
    </div>
  );
}

/** 普通附件（非图片/视频）：给个能看懂的下载入口。 */
export function MediaDownloadLink({ file }: { file: MediaFileLike }) {
  const [busy, setBusy] = useState(false);
  const name = file.name ?? file.fileName ?? '附件';
  if (!file.contentUrl) return <Typography.Text type="secondary">{name}</Typography.Text>;
  return (
    <Button size="small" icon={<DownloadOutlined />} loading={busy} onClick={async () => {
      setBusy(true);
      try {
        const blob = await request<Blob>(file.contentUrl as string,
          { responseType: 'blob', skipErrorHandler: true });
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = name;
        document.body.appendChild(anchor);
        anchor.click();
        document.body.removeChild(anchor);
        URL.revokeObjectURL(objectUrl);
      } catch {
        // 下载失败由全局错误提示接管，这里不额外弹。
      } finally {
        setBusy(false);
      }
    }}>
      {name}
    </Button>
  );
}

/**
 * 只读态的媒体列表。图片给缩略图、视频给播放器、其它给下载入口；**旧的垃圾值**（本次修复前桌面端
 * 写进表单的本地 File 记录、或更早的单文件名字串）没有服务端 id，只显示名字——它们的字节从来没上传过，
 * 没有可预览/可下载的东西。
 */
export function ReadonlyMediaList({ value, size = 96 }:
  { value: unknown; size?: number }) {
  const files = toMediaFiles(value);
  if (!files.length) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
      {files.map((file, index) => {
        const key = file.id ?? `${file.name ?? 'file'}-${index}`;
        if (!file.id) {
          return (
            <Typography.Text key={key} type="secondary">
              {file.name ?? '文件'}（历史记录，未上传）
            </Typography.Text>
          );
        }
        const media = String(file.contentType ?? '').startsWith('image/')
          || String(file.contentType ?? '').startsWith('video/');
        return media
          ? <AuthenticatedMedia key={key} file={file} size={size} />
          : <div key={key}><MediaDownloadLink file={file} /></div>;
      })}
    </div>
  );
}

/** 只读态的一句话摘要：没有可展示的媒体时退回数量/占位。 */
export function mediaSummary(value: unknown, kind: 'image' | 'video' | 'file') {
  const files = toMediaFiles(value);
  if (!files.length) return kind === 'image' ? '未上传图片' : kind === 'video' ? '未上传视频' : '未选择文件';
  return `已上传 ${files.length} 个`;
}
