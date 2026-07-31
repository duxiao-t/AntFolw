import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Dialog, Toast } from "antd-mobile";
import { useBeforeUnload, useBlocker, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { queryKeys } from "../../shared/api/queryKeys";
import { useAuthStore } from "../auth/auth.store";
import { DynamicFormRenderer } from "./components/DynamicFormRenderer";
import { FormStepHeader } from "./components/FormStepHeader";
import { FormNextStepHint, FormStepNavigator } from "./components/FormStepNavigator";
import {
  createMobileDraft,
  fetchMobileDraft,
  fetchMobileForm,
  updateMobileDraft,
} from "./drafts.api";
import {
  beginSubmitFlow,
  findSelfSelectRules,
  formSchemaWithoutSelfSelectRules,
} from "./submitFlow.store";
import {
  createRecoveryDraftWriter,
  readRecoveryDraft,
  removeRecoveryDraft,
  shouldDiscardMismatchedRecovery,
  type RecoveryDraftWriter,
} from "./recoveryDraft.store";
import { validateSchemaValues } from "./schema/fieldRegistry";
import { buildFormStepGroups } from "./schema/stepGroups";
import { collectVisibleValues } from "./schema/validators";
import type { FieldValidationErrors, MobileFormValues } from "./schema/types";
import { fetchReworkTask, saveReworkTask } from "./rework.api";

export function FormFillPage() {
  const { code = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const draftIdFromUrl = numberParam(searchParams.get("draftId"));
  const reworkTaskId = numberParam(searchParams.get("reworkTaskId"));
  const [savedDraftId, setSavedDraftId] = useState<number | null>(draftIdFromUrl);
  const draftId = savedDraftId;
  const [values, setValues] = useState<MobileFormValues>({});
  const [initialValues, setInitialValues] = useState<MobileFormValues>({});
  const [errors, setErrors] = useState<FieldValidationErrors>({});
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [completedStepIds, setCompletedStepIds] = useState<Set<string>>(() => new Set());
  const [initialized, setInitialized] = useState(false);
  const [status, setStatus] = useState("");
  const recoveryWriterRef = useRef<RecoveryDraftWriter | null>(null);
  const [submitNavigationAllowed, setSubmitNavigationAllowed] = useState(false);
  const [pendingSubmitPath, setPendingSubmitPath] = useState<string | null>(null);

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
  const reworkQuery = useQuery({
    queryKey: ["mobile", "rework-task", reworkTaskId ?? 0],
    queryFn: () => fetchReworkTask(reworkTaskId ?? 0),
    enabled: reworkTaskId != null,
    retry: 0,
  });

  const isDirty = initialized && !submitNavigationAllowed && !sameValues(values, initialValues);
  const blocker = useBlocker(isDirty);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (reworkTaskId != null) {
        await saveReworkTask(reworkTaskId, values);
        return { draftId: null, rework: true };
      }
      if (draftId != null) {
        await updateMobileDraft(draftId, code, values);
        return { draftId, rework: false };
      }
      return { draftId: await createMobileDraft(code, values), rework: false };
    },
    onSuccess(result) {
      if (!result.rework) setSavedDraftId(result.draftId);
      setInitialValues(values);
      setStatus(result.rework ? "原单已保存" : "草稿已保存");
      showToast({ icon: "success", content: result.rework ? "原单已保存" : "草稿已保存" });
      if (user) {
        removeRecoveryDraft(user.id, code, recoveryId(reworkTaskId, draftId));
      }
    },
    onError(errorValue) {
      showToast({
        icon: "fail",
        content: errorValue instanceof Error ? errorValue.message : "草稿保存失败",
      });
    },
  });

  useEffect(() => {
    if (!formQuery.data || (draftIdFromUrl != null && draftQuery.isPending)
      || (reworkTaskId != null && reworkQuery.isPending)) {
      return;
    }
    const baseValues = reworkQuery.data?.formData ?? draftQuery.data?.data ?? {};
    const nextValues = chooseInitialValues({
      baseValues,
      code,
      draftId: recoveryId(reworkTaskId, draftIdFromUrl),
      schemaVersion: formQuery.data.version,
      userId: user?.id ?? null,
    });
    setValues(nextValues);
    setInitialValues(nextValues);
    setInitialized(true);
  }, [code, draftIdFromUrl, draftQuery.data, draftQuery.isPending, formQuery.data, reworkQuery.data, reworkQuery.isPending, reworkTaskId, user?.id]);

  useEffect(() => {
    if (!user || !formQuery.data) {
      return;
    }
    const writer = createRecoveryDraftWriter({
      userId: user.id,
      formCode: code,
      draftId: recoveryId(reworkTaskId, draftId),
      schemaVersion: formQuery.data.version,
    });
    recoveryWriterRef.current = writer;
    return () => {
      writer.dispose();
      if (recoveryWriterRef.current === writer) {
        recoveryWriterRef.current = null;
      }
    };
  }, [code, draftId, formQuery.data, reworkTaskId, user]);

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
        event.returnValue = "";
      },
      [isDirty],
    ),
  );

  useEffect(() => {
    if (!pendingSubmitPath) {
      return;
    }
    void navigate(pendingSubmitPath);
  }, [navigate, pendingSubmitPath]);

  const schema = formQuery.data?.schema ?? [];
  const process = formQuery.data?.process;
  const formSchema = formSchemaWithoutSelfSelectRules(schema);
  const stepGroups = buildFormStepGroups(formSchema, values);
  const currentStep = stepGroups[Math.min(currentStepIndex, Math.max(stepGroups.length - 1, 0))];
  const currentStepErrors = currentStep ? pickErrors(errors, currentStep.fieldIds) : {};
  const stepErrorCounts = errorCountsByStep(stepGroups, errors);
  const title = formQuery.data?.name ?? "表单填写";
  const workflowEnabled = formQuery.data?.settings?.workflowEnabled !== false;
  const hasSelfSelect = findSelfSelectRules(process).length > 0;
  const description = typeof formQuery.data?.description === "string" ? formQuery.data.description : "";

  if (formQuery.isPending || (draftIdFromUrl != null && draftQuery.isPending)
    || (reworkTaskId != null && reworkQuery.isPending)) {
    return <PageSkeleton rows={5} />;
  }

  if (formQuery.isError || draftQuery.isError || reworkQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage
      title="填写表单"
      onBack={() => navigateBack(navigate)}
      contentClassName="form-fill-page"
      action={
        <button type="button" className="app-bar__action" disabled={saveMutation.isPending} onClick={() => saveDraft()}>{saveMutation.isPending ? "保存中" : reworkTaskId ? "保存" : "草稿"}</button>
      }
    >
      <div>
        <FormStepHeader
          title={title}
          description={currentStep?.description ?? currentStep?.title ?? description}
          currentIndex={currentStepIndex}
          total={stepGroups.length}
          completedCount={completedStepIds.size}
          fieldCount={currentStep?.fieldIds.length ?? 0}
          sectionLabel={currentStep?.title}
          autosaveLabel={status || undefined}
        >
          <FormStepNavigator
            groups={stepGroups}
            currentIndex={currentStepIndex}
            completedStepIds={completedStepIds}
            errorCounts={stepErrorCounts}
            onSelect={setCurrentStepIndex}
          />
        </FormStepHeader>
        <FormNextStepHint
          groups={stepGroups}
          currentIndex={currentStepIndex}
          errorCounts={stepErrorCounts}
          finalTitle={hasSelfSelect ? "下一步：审批人确认" : undefined}
          finalHint={hasSelfSelect ? "表单填写完成后，选择审批人与抄送人。" : undefined}
        />
        <section className="af-card--form form-main-card">
          <DynamicFormRenderer
            schema={currentStep?.nodes ?? []}
            values={values}
            mode={draftQuery.data?.readOnly ? "readonly" : "fill"}
            errors={currentStepErrors}
            onValueChange={(fieldId, value) => {
              setValues((current) => ({ ...current, [fieldId]: value }));
              setErrors((current) => {
                const next = { ...current };
                delete next[fieldId];
                return next;
              });
              setStatus("");
            }}
          />
        </section>
      </div>

      <div className="action-bar form-fill-action-bar">
        <button
          type="button"
          className="btn btn--ghost btn--lg"
          onClick={() => currentStepIndex > 0 ? setCurrentStepIndex((index) => index - 1) : navigateBack(navigate)}
        >
          上一步
        </button>
        <button type="button" className="btn btn--success btn--lg" onClick={goNext}>
          {workflowEnabled ? "下一步" : "提交"}
        </button>
      </div>

      {blocker.state === "blocked" ? (
        <Dialog
          visible
          title="离开表单"
          content="表单尚未保存，离开后可以从本地恢复继续填写。"
          actions={[
            [
              {
                key: "stay",
                text: "继续填写",
                onClick: () => blocker.reset(),
              },
              {
                key: "leave",
                text: "继续离开",
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
    const currentErrors = validateSchemaValues(currentStep?.nodes ?? [], values);
    if (Object.keys(currentErrors).length > 0) {
      setErrors((existing) => ({ ...existing, ...currentErrors }));
      showToast({ icon: "fail", content: "请先完善当前步骤" });
      scrollToFirstError(currentErrors);
      return;
    }

    if (currentStep) {
      setCompletedStepIds((existing) => new Set(existing).add(currentStep.id));
    }

    if (currentStepIndex < stepGroups.length - 1) {
      setCurrentStepIndex((index) => index + 1);
      return;
    }

    const nextErrors = validateSchemaValues(formSchema, values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      const firstErrorStepIndex = stepGroups.findIndex((group) =>
        group.fieldIds.some((fieldId) => Boolean(nextErrors[fieldId])),
      );
      if (firstErrorStepIndex >= 0) {
        setCurrentStepIndex(firstErrorStepIndex);
      }
      showToast({ icon: "fail", content: "请完善必填或格式错误字段" });
      scrollToFirstError(nextErrors);
      return;
    }
    const submitValues = collectVisibleValues(formSchema, values);
    recoveryWriterRef.current?.flush();
    beginSubmitFlow({ formCode: code, draftId: reworkTaskId ? null : draftId, reworkTaskId, values: submitValues });
    const nextPath =
      reworkTaskId == null && findSelfSelectRules(process).length > 0
        ? `/forms/${encodeURIComponent(code)}/self-select`
        : `/forms/${encodeURIComponent(code)}/confirm`;
    setSubmitNavigationAllowed(true);
    setPendingSubmitPath(nextPath);
  }
}

function pickErrors(errors: FieldValidationErrors, fieldIds: string[]): FieldValidationErrors {
  return fieldIds.reduce<FieldValidationErrors>((next, fieldId) => {
    if (errors[fieldId]) next[fieldId] = errors[fieldId];
    return next;
  }, {});
}

function errorCountsByStep(
  groups: Array<{ id: string; fieldIds: string[] }>,
  errors: FieldValidationErrors,
): Record<string, number> {
  return groups.reduce<Record<string, number>>((next, group) => {
    const count = group.fieldIds.filter((fieldId) => Boolean(errors[fieldId])).length;
    if (count > 0) next[group.id] = count;
    return next;
  }, {});
}

function scrollToFirstError(errors: FieldValidationErrors) {
  const firstFieldId = Object.keys(errors)[0];
  if (!firstFieldId || typeof document === "undefined") {
    return;
  }
  window.requestAnimationFrame(() => {
    const target = document.querySelector(`[data-field-id="${escapeCssIdent(firstFieldId)}"]`);
    target?.scrollIntoView({ behavior: "smooth", block: "center" });
  });
}

function escapeCssIdent(value: string) {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(value);
  }
  return value.replace(/[^a-zA-Z0-9_-]/g, "\\$&");
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
    if (confirmDialog("发现未提交内容，是否恢复？")) {
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

function recoveryId(reworkTaskId: number | null, draftId: number | null) {
  return reworkTaskId == null ? draftId : -reworkTaskId;
}

function sameValues(left: MobileFormValues, right: MobileFormValues) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function confirmDialog(message: string) {
  if (typeof window.confirm === "function") {
    return window.confirm(message);
  }
  return true;
}

function navigateBack(navigate: ReturnType<typeof useNavigate>) {
  if (typeof window !== "undefined" && window.history.length > 1) {
    navigate(-1);
    return;
  }
  navigate("/workbench");
}

function showToast(options: Parameters<typeof Toast.show>[0]) {
  if (import.meta.env.MODE === "test") {
    return;
  }
  Toast.show(options);
}

export default FormFillPage;
