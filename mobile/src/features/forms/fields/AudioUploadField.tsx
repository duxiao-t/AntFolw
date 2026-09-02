import { useEffect, useRef, useState } from 'react';
import { DeleteOutline, SoundOutline } from 'antd-mobile-icons';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import type { MobileFile } from '../../../shared/api/types';
import { deleteMobileFile, uploadMobileFile } from '../files.api';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { createFileUploadValue } from './FileUploadField';
import { AudioAttachmentRow, ReadonlyMediaList } from '../components/MediaPreview';
import { NativeActionContent } from './NativeActionContent';

export function AudioUploadField(props: MobileFieldProps) {
  const platform = usePlatformAdapter();
  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [localError, setLocalError] = useState('');
  const [retryFile, setRetryFile] = useState<{ file: File; durationSeconds: number } | null>(null);
  const timerRef = useRef<number>(undefined);
  const intervalRef = useRef<number>(undefined);
  const recordingRef = useRef(false);
  const createdIds = useRef(new Set<string>());
  const files = audioFiles(props.value);
  const maxCount = numberProp(props.node.props?.maxCount, 3, 1, 10);
  const maxDuration = numberProp(props.node.props?.maxDuration, 60, 5, 60);
  const label = fieldLabel(props.node);
  useEffect(() => () => {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    if (intervalRef.current) window.clearInterval(intervalRef.current);
  }, []);
  if (props.mode === 'readonly') {
    return <FieldShell node={props.node} label={label} required={isRequired(props.node)} summary={<ReadonlyMediaList files={files} />} />;
  }
  return (
    <FieldShell node={props.node} label={label} required={isRequired(props.node)} error={localError || fieldError(props)}>
      <div className="upload-control af-upload-control">
        <div className="af-upload-list">
          {files.map((file) => (
            <div className="af-audio-edit-row af-upload-list__item" key={file.id}>
              <AudioAttachmentRow file={file} />
              <button type="button" className="af-link-button" aria-label={`删除 ${file.name}`} onClick={() => {
                const next = files.filter((item) => item.id !== file.id);
                props.onValueChange(props.node.id, next);
                if (createdIds.current.delete(file.id)) void deleteMobileFile(file.id).catch(() => undefined);
              }}><DeleteOutline /></button>
            </div>
          ))}
        </div>
        <button type="button" className="upload-trigger upload-trigger--platform af-platform-record" disabled={uploading || (!recording && files.length >= maxCount)} onClick={() => {
          setLocalError('');
          if (recording) {
            if (timerRef.current) window.clearTimeout(timerRef.current);
            void finishRecording();
            return;
          }
          if (platform.startAudioRecording && platform.stopAudioRecording) {
            void platform.startAudioRecording().then(() => {
              recordingRef.current = true; setRecording(true); setElapsed(0);
              intervalRef.current = window.setInterval(() => setElapsed((value) => Math.min(maxDuration, value + 1)), 1000);
              timerRef.current = window.setTimeout(() => void finishRecording(), maxDuration * 1000);
            }).catch((error) => setLocalError(error instanceof Error ? error.message : '录音失败'));
            return;
          }
        }}>
          <NativeActionContent
            icon={<SoundOutline aria-hidden="true" />}
            title={uploading ? '录音上传中…' : recording ? `停止并上传 ${formatDuration(elapsed)}` : files.length >= maxCount ? `已达到 ${maxCount} 段上限` : '开始录音'}
            hint={recording ? `最长 ${maxDuration} 秒，到时自动结束` : `直接录制，不选择本地音频 · 最多 ${maxCount} 段`}
          />
        </button>
      </div>
      {retryFile ? <button type="button" className="btn btn--secondary btn--block" disabled={uploading} onClick={() => void uploadRecording(retryFile.file, retryFile.durationSeconds)}>重试上传</button> : null}
    </FieldShell>
  );

  async function finishRecording() {
    if (!recordingRef.current) return;
    recordingRef.current = false;
    if (timerRef.current) window.clearTimeout(timerRef.current);
    if (intervalRef.current) window.clearInterval(intervalRef.current);
    try {
      const audio = await platform.stopAudioRecording?.();
      if (audio?.uploaded) {
        const uploaded = { ...audio.uploaded, durationSeconds: audio.durationSeconds };
        createdIds.current.add(uploaded.id);
        props.onValueChange(props.node.id, [...files, uploaded]);
      } else if (audio?.file) {
        await uploadRecording(audio.file, audio.durationSeconds);
      }
    } catch (error) {
      setLocalError(error instanceof Error ? error.message : '录音上传失败');
    } finally {
      setRecording(false);
    }
  }

  async function uploadRecording(file: File, durationSeconds: number) {
    setUploading(true); setLocalError(''); setRetryFile(null);
    props.onValueChange(props.node.id, createFileUploadValue(files, true));
    try {
      const uploaded = await uploadMobileFile('/api/mobile/files', file);
      const value = { ...uploaded, durationSeconds };
      createdIds.current.add(value.id);
      props.onValueChange(props.node.id, [...files, value]);
    } catch (error) {
      setRetryFile({ file, durationSeconds });
      props.onValueChange(props.node.id, createFileUploadValue(files, true));
      setLocalError(error instanceof Error ? error.message : '录音上传失败');
    } finally {
      setUploading(false);
    }
  }
}

function audioFiles(value: unknown): MobileFile[] {
  return Array.isArray(value) ? value.filter((item): item is MobileFile =>
    typeof item === 'object' && item != null && typeof (item as MobileFile).id === 'string') : [];
}

function numberProp(value: unknown, fallback: number, min: number, max: number) {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.min(max, Math.max(min, Math.round(value))) : fallback;
}

function formatDuration(value?: number) {
  const seconds = Math.max(0, Math.round(value ?? 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
