import { AudioOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { request } from '@umijs/max';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import {
  beginAudioRecording,
  type AudioRecording,
  type NativeFile,
  uploadNativeFile,
} from './nativeMedia';

const pendingAudioUpload = Symbol.for('antflowPendingUpload');
type AudioValue = NativeFile[] & { [pendingAudioUpload]?: boolean };

export function hasPendingAudioUpload(value: unknown) {
  return Array.isArray(value) && (value as AudioValue)[pendingAudioUpload] === true;
}

function audioValue(files: NativeFile[], pending = false) {
  const value = [...files] as AudioValue;
  if (pending) Object.defineProperty(value, pendingAudioUpload, { value: true, enumerable: false });
  return value;
}

function AudioInput({ node, mode, value, onChange }: FieldComponentProps) {
  const { message } = App.useApp();
  const files = Array.isArray(value) ? value.filter(isNativeFile) : [];
  const filesRef = useRef(files); filesRef.current = files;
  const createdIds = useRef(new Set<string>());
  const [recording, setRecording] = useState<AudioRecording | null>(null);
  const recordingRef = useRef<AudioRecording | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [retry, setRetry] = useState<{ file: File; durationSeconds: number } | null>(null);
  const timer = useRef<number>(undefined);
  const interval = useRef<number>(undefined);
  const maxCount = numberProp(node.props?.maxCount, 3, 1, 10);
  const maxDuration = numberProp(node.props?.maxDuration, 60, 5, 60);

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
    if (interval.current) window.clearInterval(interval.current);
    if (recordingRef.current) void recordingRef.current.stop().catch(() => undefined);
  }, []);

  if (mode === 'designer-preview') {
    return <div data-field-id={node.id}>
      <div style={{ marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
      <div className="form-fields-media-placeholder">
        <AudioOutlined />
        <span>浏览器/企业微信直接录音 · 最多 {maxCount} 段，每段 {maxDuration} 秒</span>
      </div>
    </div>;
  }

  return <div data-field-id={node.id}>
    <div style={{ marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
    <div style={{ display: 'grid', gap: 8 }}>
      {files.length === 0 ? <Typography.Text type="secondary">暂无录音</Typography.Text> : files.map((file) => <div key={file.id} style={{ display: 'grid', gridTemplateColumns: '24px minmax(0, 1fr) auto', gap: 10, alignItems: 'center', padding: '10px 12px', border: '1px solid #e5e7eb', borderRadius: 8 }}>
        <AudioOutlined />
        <div style={{ minWidth: 0 }}>
          <Typography.Text ellipsis title={file.name} style={{ display: 'block' }}>{file.name}</Typography.Text>
          <Typography.Text type="secondary">{formatDuration(file.durationSeconds)} · 已上传</Typography.Text>
          <AuthenticatedAudio file={file} />
        </div>
        {mode === 'runtime-fill' ? <Button type="text" danger icon={<DeleteOutlined />} aria-label={`删除 ${file.name}`} onClick={() => {
          onChange?.(files.filter((item) => item.id !== file.id));
          if (createdIds.current.delete(file.id)) void request(`/api/mobile/files/${file.id}`, { method: 'DELETE' }).catch(() => undefined);
        }} /> : null}
      </div>)}
    </div>
    {mode === 'runtime-fill' ? <Space style={{ marginTop: 8 }} wrap>
      <Button icon={<AudioOutlined />} type={recording ? 'primary' : 'default'} danger={!!recording}
        loading={uploading} disabled={!recording && files.length >= maxCount} onClick={() => {
          if (recording) { void finish(recording); return; }
          void beginAudioRecording().then((controller) => {
            recordingRef.current = controller; setRecording(controller); setElapsed(0);
            interval.current = window.setInterval(() => setElapsed((seconds) => Math.min(maxDuration, seconds + 1)), 1000);
            timer.current = window.setTimeout(() => void finish(controller), maxDuration * 1000);
          }).catch((error) => message.error(error instanceof Error ? error.message : '录音失败'));
        }}>{uploading ? '上传中…' : recording ? `停止录音 ${formatDuration(elapsed)}` : files.length >= maxCount ? `已达到 ${maxCount} 段上限` : '开始录音'}</Button>
      {retry ? <Button icon={<ReloadOutlined />} disabled={uploading} onClick={() => void upload(retry.file, retry.durationSeconds)}>重试上传</Button> : null}
      <Typography.Text type="secondary">最长 {maxDuration} 秒，到时自动结束</Typography.Text>
    </Space> : null}
  </div>;

  async function finish(controller: AudioRecording) {
    if (recordingRef.current !== controller) return;
    recordingRef.current = null;
    if (timer.current) window.clearTimeout(timer.current);
    if (interval.current) window.clearInterval(interval.current);
    setRecording(null);
    try {
      const result = await controller.stop();
      await upload(result.file, result.durationSeconds);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '录音失败');
    }
  }

  async function upload(file: File, durationSeconds: number) {
    setUploading(true); setRetry(null);
    onChange?.(audioValue(filesRef.current, true));
    try {
      const uploaded = await uploadNativeFile(file, durationSeconds);
      createdIds.current.add(uploaded.id);
      onChange?.([...filesRef.current, uploaded]);
    } catch (error) {
      setRetry({ file, durationSeconds });
      onChange?.(audioValue(filesRef.current, true));
      message.error(error instanceof Error ? error.message : '录音上传失败');
    } finally {
      setUploading(false);
    }
  }
}

function AuthenticatedAudio({ file }: { file: NativeFile }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    let objectUrl = '';
    let alive = true;
    request<Blob>(file.contentUrl, { responseType: 'blob' }).then((blob) => {
      if (!alive) return;
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl);
    }).catch(() => undefined);
    return () => { alive = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [file.contentUrl]);
  if (!url) return <Typography.Text type="secondary" style={{ display: 'block' }}>音频加载中…</Typography.Text>;
  // biome-ignore lint/a11y/useMediaCaption: voice recordings do not provide caption tracks.
  return <audio controls preload="metadata" src={url} style={{ display: 'block', width: '100%', height: 34, marginTop: 4 }} />;
}

function isNativeFile(value: unknown): value is NativeFile {
  return typeof value === 'object' && value != null && typeof (value as NativeFile).id === 'string';
}

function numberProp(value: unknown, fallback: number, min: number, max: number) {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.min(max, Math.max(min, Math.round(value))) : fallback;
}

function formatDuration(value?: number) {
  const seconds = Math.max(0, Math.round(value ?? 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export const AudioUploadField: FieldType = {
  type: 'audio_upload', label: '录音', icon: 'audio',
  defaultProps: { required: false, maxCount: 3, maxDuration: 60 },
  Component: AudioInput,
  ConfigPanel: () => null,
  validate(value, props) {
    if (hasPendingAudioUpload(value)) return '仍有录音未完成上传';
    const files = Array.isArray(value) ? value : [];
    if (props.required && files.length === 0) return '请完成录音';
    if (files.length > numberProp(props.maxCount, 3, 1, 10)) return `最多录制 ${numberProp(props.maxCount, 3, 1, 10)} 段`;
    return null;
  },
};
