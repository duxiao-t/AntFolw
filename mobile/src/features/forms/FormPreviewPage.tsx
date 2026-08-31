import { Toast } from 'antd-mobile';
import { useEffect, useState } from 'react';
import { AppPage } from '../../shared/ui/AppPage';
import { DynamicFormRenderer } from './components/DynamicFormRenderer';
import { applySchemaDefaults } from './schema/defaults';
import { validateSchemaValues } from './schema/fieldRegistry';
import type { FieldValidationErrors, MobileFormValues, MobileSchemaNode } from './schema/types';
import { formSchemaWithoutSelfSelectRules } from './submitFlow.store';

type PreviewPayload = {
  title: string;
  description?: string;
  schema: MobileSchemaNode[];
};

export function FormPreviewPage() {
  const [preview, setPreview] = useState<PreviewPayload>({ title: '未命名表单', schema: [] });
  const [values, setValues] = useState<MobileFormValues>({});
  const [errors, setErrors] = useState<FieldValidationErrors>({});

  useEffect(() => {
    const parentOrigin = referrerOrigin();
    const handleMessage = (event: MessageEvent) => {
      if (event.source !== window.parent
        || event.origin !== parentOrigin
        || event.data?.type !== 'antflow:form-preview:set') return;
      const payload = event.data.payload as PreviewPayload;
      if (!payload || !Array.isArray(payload.schema)) return;
      setPreview(payload);
      setValues(applySchemaDefaults(payload.schema, {}));
      setErrors({});
    };
    window.addEventListener('message', handleMessage);
    window.parent.postMessage({ type: 'antflow:form-preview:ready' }, parentOrigin);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const schema = formSchemaWithoutSelfSelectRules(preview.schema);
  return (
    <AppPage title="填写表单" onBack={() => undefined} contentClassName="form-fill-page">
      <div className="form-fill-page__body">
        <section className="form-fill-intro" aria-label="表单说明">
          <div className="form-fill-intro__kicker"><span>预览模式</span></div>
          <h2>{preview.title || '未命名表单'}</h2>
          <p>{preview.description || '请填写以下内容，带 * 为必填项。'}</p>
        </section>
        <DynamicFormRenderer
          schema={schema}
          values={values}
          mode="fill"
          errors={errors}
          onValueChange={(fieldId, value) => {
            setValues((current) => ({ ...current, [fieldId]: value }));
            setErrors((current) => {
              if (!current[fieldId]) return current;
              const next = { ...current };
              delete next[fieldId];
              return next;
            });
          }}
        />
      </div>
      <div className="action-bar form-fill-action-bar">
        <button type="button" className="btn btn--ghost btn--lg" onClick={() => Toast.show('预览内容不会保存')}>
          保存草稿
        </button>
        <button type="button" className="btn btn--success btn--lg" onClick={submitPreview}>
          提交
        </button>
      </div>
    </AppPage>
  );

  function submitPreview() {
    const nextErrors = validateSchemaValues(schema, values);
    setErrors(nextErrors);
    const firstError = Object.values(nextErrors)[0];
    Toast.show(firstError ? { icon: 'fail', content: firstError } : { icon: 'success', content: '模拟提交校验通过' });
  }
}

function referrerOrigin() {
  try {
    return document.referrer ? new URL(document.referrer).origin : window.location.origin;
  } catch {
    return window.location.origin;
  }
}

export default FormPreviewPage;
