import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MobileFormPreview from './MobileFormPreview';

describe('MobileFormPreview', () => {
  it('sends the form schema to the real mobile preview route', () => {
    render(
      <MobileFormPreview
        title="请假申请"
        description="请填写请假信息"
        schema={[{ id: 'reason', type: 'text', label: '原因' }]}
      />,
    );
    const iframe = screen.getByTitle('手机端表单预览') as HTMLIFrameElement;
    const postMessage = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    fireEvent(
      window,
      new MessageEvent('message', {
        origin: window.location.origin,
        source: iframe.contentWindow,
        data: { type: 'antflow:form-preview:ready' },
      }),
    );

    expect(iframe).toHaveAttribute('src', '/mobile/form-preview');
    expect(postMessage).toHaveBeenCalledWith(
      {
        type: 'antflow:form-preview:set',
        payload: {
          title: '请假申请',
          description: '请填写请假信息',
          schema: [{ id: 'reason', type: 'text', label: '原因' }],
        },
      },
      window.location.origin,
    );
  });
});
