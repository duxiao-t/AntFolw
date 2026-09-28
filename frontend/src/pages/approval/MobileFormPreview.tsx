import { useCallback, useEffect, useRef, useState } from 'react';
import { request } from '@umijs/max';
import type { SchemaNode } from '../../registry/types';
import './MobileFormPreview.less';

export type MobileFormPreviewProps = {
  title: string;
  description?: string;
  schema: SchemaNode[];
  formId?: number;
};

const PREVIEW_URL = '/mobile/form-preview';

export function MobileFormPreview(props: MobileFormPreviewProps) {
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const [ready, setReady] = useState(false);
  const sendPreview = useCallback(() => {
    iframeRef.current?.contentWindow?.postMessage(
      {
        type: 'antflow:form-preview:set',
        payload: props,
      },
      window.location.origin,
    );
  }, [props]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (
        event.origin !== window.location.origin ||
        event.source !== iframeRef.current?.contentWindow ||
        event.data?.type !== 'antflow:form-preview:ready'
      )
        return;
      setReady(true);
      sendPreview();
    };
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [sendPreview]);

  useEffect(() => {
    if (ready) sendPreview();
  }, [ready, sendPreview]);

  // Phone preview runs inside an iframe without the auth token; resolve option
  // queries here so the runtime endpoint still sees a logged-in caller.
  useEffect(() => {
    const handleQuery = async (event: MessageEvent) => {
      if (
        event.origin !== window.location.origin ||
        event.source !== iframeRef.current?.contentWindow ||
        event.data?.type !== 'antflow:option-preview:query' ||
        !props.formId
      )
        return;
      const reply = (payload: Record<string, unknown>) =>
        iframeRef.current?.contentWindow?.postMessage(
          { type: 'antflow:option-preview:result', id: event.data.id, ...payload },
          window.location.origin,
        );
      try {
        const result = await request(`/api/runtime/form-options/preview/${props.formId}`, {
          method: 'POST',
          data: { schema: props.schema, query: event.data.query },
        });
        reply({ result });
      } catch (error: any) {
        reply({ error: error?.message ?? '选项查询失败' });
      }
    };
    window.addEventListener('message', handleQuery);
    return () => window.removeEventListener('message', handleQuery);
  }, [props.formId, props.schema]);

  return (
    <div className="approval-mobile-preview" data-testid="mobile-form-preview">
      <iframe
        ref={iframeRef}
        title="手机端表单预览"
        src={PREVIEW_URL}
        allow="camera; microphone; geolocation"
        sandbox="allow-forms allow-modals allow-same-origin allow-scripts"
        onLoad={() => setReady(false)}
      />
    </div>
  );
}

export default MobileFormPreview;
