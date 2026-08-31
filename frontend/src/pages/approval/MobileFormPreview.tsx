import { useCallback, useEffect, useRef, useState } from 'react';
import type { SchemaNode } from '../../registry/types';
import './MobileFormPreview.less';

export type MobileFormPreviewProps = {
  title: string;
  description?: string;
  schema: SchemaNode[];
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

  return (
    <div className="approval-mobile-preview" data-testid="mobile-form-preview">
      <iframe
        ref={iframeRef}
        title="手机端表单预览"
        src={PREVIEW_URL}
        sandbox="allow-forms allow-modals allow-same-origin allow-scripts"
        onLoad={() => setReady(false)}
      />
    </div>
  );
}

export default MobileFormPreview;
