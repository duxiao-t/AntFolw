import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button, Dialog } from 'antd-mobile';
import { useBeforeUnload, useBlocker, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { queryKeys } from '../../shared/api/queryKeys';
import { useAuthStore } from '../auth/auth.store';
import { DynamicFormRenderer } from './components/DynamicFormRenderer';
import {
  createMobileDraft,
  fetchMobileDraft,
  fetchMobileForm,
  updateMobileDraft,
} from './drafts.api';
import {
  createRecoveryDraftWriter,
  readRecoveryDraft,
  removeRecoveryDraft,
  shouldDiscardMismatchedRecovery,
  type RecoveryDraftWriter,
} from './recoveryDraft.store';
import { validateSchemaValues } from './schema/fieldRegistry';
import type { FieldValidationErrors, MobileFormValues } from './schema/types';

const bottomActionStyle: React.CSSProperties = {
  position: 'fixed',
  left: 0,
  right: 0,
  bottom: 0,
  display: 'grid',
  gap: 8,
  padding: '12px 16px 16px',
  background: 'var(--af-color-bg)',
  boxShadow: '0 -8px 20px rgba(0,0,0,0.08)',
};

export function FormFillPage() {
  const { code = '' } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const draftIdFromUrl = numberParam(searchParams.get('draftId'));
  const [savedDraftId, setSavedDraftId] = useState<number | null>(draftIdFromUrl);
  const draftId = savedDraftId;
  const [values, setValues] = useState<MobileFormValues>({});
  const [initialValues, setInitialValues] = useState<MobileFormValues>({});
  const [errors, setErrors] = useState<FieldValidationErrors>({});
  const [initialized, setInitialized] = useState(false);
  const [status, setStatus] = useState('');
  const recoveryWriterRef = useRef<RecoveryDraftWriter | null>(null);

  const formQuery = useQuery({
    queryKey: queryKeys.form(code),
    queryFn: () => fetchMobileForm(code),
    enabled: code.length > 0,
    retry: 0,
  });
  const draftQuery = useQuery({
    queryKey: queryKeys.draft(draftIdFromUrl ?? 0),
    queryFn: () => fetchMobileDraft(draftIdFromUrl ?? 0),
    enabled: draftIdFromUrl != null,
    retry: 0,
  });

  const isDirty = initialized && !sameValues(values, initialValues);
  const blocker = useBlocker(isDirty);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (draftId != null) {
        await updateMobileDraft(draftId, code, values);
        return draftId;
      }
      return createMobileDraft(code, values);
    },
    onSuccess(nextDraftId) {
      setSavedDraftId(nextDraftId);
      setInitialValues(values);
      setStatus('草稿已保存');
      if (user) {
        removeRecoveryDraft(user.id, code, draftId);
      }
    },
  });

  useEffect(() => {
    if (!formQuery.data || (draftIdFromUrl != null && draftQuery.isPending)) {
      return;
    }
    const baseValues = draftQuery.data?.data ?? {};
    const nextValues = chooseInitialValues({
      baseValues,
      code,
      draftId: draftIdFromUrl,
      schemaVersion: formQuery.data.version,
      userId: user?.id ?? null,
    });
    setValues(nextValues);
    setInitialValues(nextValues);
    setInitialized(true);
  }, [code, draftIdFromUrl, draftQuery.data, draftQuery.isPending, formQuery.data, user?.id]);

  useEffect(() => {
    if (!user || !formQuery.data) {
      return;
    }
    const writer = createRecoveryDraftWriter({
      userId: user.id,
      formCode: code,
      draftId,
      schemaVersion: formQuery.data.version,
    });
    recoveryWriterRef.current = writer;
    return () => {
      writer.dispose();
      if (recoveryWriterRef.current === writer) {
        recoveryWriterRef.current = null;
      }
    };
  }, [code, draftId, formQuery.data, user]);

  useEffect(() => {
    if (!isDirty) {
      return;
    }
    recoveryWriterRef.current?.schedule(values);
  }, [isDirty, values]);

  useBeforeUnload(
    useCallback(
      (event) => {
        if (!isDirty) {
          return;
        }
        recoveryWriterRef.current?.flush();
        event.preventDefault();
        event.returnValue = '';
      },
      [isDirty],
    ),
  );

  const schema = formQuery.data?.schema ?? [];
  const title = formQuery.data?.name ?? '表单填写';

  if (formQuery.isPending || (draftIdFromUrl != null && draftQuery.isPending)) {
    return <PageSkeleton rows={5} />;
  }

  if (formQuery.isError || draftQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage
      title={title}
      toolbar={
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, marginTop: 12 }}>
          <Button onClick={() => navigate('/workbench')}>返回</Button>
          <Button color="primary" loading={saveMutation.isPending} onClick={() => saveDraft()}>
            保存草稿
          </Button>
        </div>
      }
      style={{ paddingBottom: 104 }}
    >
      <DynamicFormRenderer
        schema={schema}
        values={values}
        mode={draftQuery.data?.readOnly ? 'readonly' : 'fill'}
        errors={errors}
        onValueChange={(fieldId, value) => {
          setValues((current) => ({ ...current, [fieldId]: value }));
          setErrors((current) => {
            const next = { ...current };
            delete next[fieldId];
            return next;
          });
          setStatus('');
        }}
      />
      {status ? <p role="status">{status}</p> : null}
      <div style={bottomActionStyle}>
        <Button block color="primary" size="large" onClick={goNext}>
          下一步
        </Button>
      </div>
      {blocker.state === 'blocked' ? (
        <Dialog
          visible
          title="离开表单"
          content="表单尚未保存，离开后可以从本地恢复继续填写。"
          actions={[
            [
              {
                key: 'stay',
                text: '继续填写',
                onClick: () => blocker.reset(),
              },
              {
                key: 'leave',
                text: '继续离开',
                danger: true,
                onClick: () => {
                  recoveryWriterRef.current?.flush();
                  blocker.proceed();
                },
              },
            ],
          ]}
        />
      ) : null}
    </AppPage>
  );

  function saveDraft() {
    void saveMutation.mutateAsync();
  }

  function goNext() {
    const nextErrors = validateSchemaValues(schema, values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      return;
    }
    recoveryWriterRef.current?.flush();
    setStatus('表单校验通过');
  }
}

function chooseInitialValues({
  baseValues,
  code,
  draftId,
  schemaVersion,
  userId,
}: {
  baseValues: MobileFormValues;
  code: string;
  draftId: number | null;
  schemaVersion: number;
  userId: number | null;
}) {
  if (userId == null) {
    return baseValues;
  }
  const recovery = readRecoveryDraft(userId, code, draftId);
  if (!recovery) {
    return baseValues;
  }
  if (recovery.schemaVersion === schemaVersion) {
    if (confirmDialog('发现未提交内容，是否恢复？')) {
      return recovery.values;
    }
    removeRecoveryDraft(userId, code, draftId);
    return baseValues;
  }
  if (shouldDiscardMismatchedRecovery(recovery, schemaVersion)) {
    removeRecoveryDraft(userId, code, draftId);
  }
  return baseValues;
}

function numberParam(value: string | null) {
  if (!value) {
    return null;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function sameValues(left: MobileFormValues, right: MobileFormValues) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function confirmDialog(message: string) {
  if (typeof window.confirm === 'function') {
    return window.confirm(message);
  }
  return true;
}

export default FormFillPage;
