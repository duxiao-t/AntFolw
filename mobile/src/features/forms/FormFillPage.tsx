import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Dialog, Toast } from "antd-mobile";
import { useBeforeUnload, useBlocker, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { queryKeys } from "../../shared/api/queryKeys";
import { useAuthStore } from "../auth/auth.store";
import { DynamicFormRenderer } from "./components/DynamicFormRenderer";
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
import { buildFormSections } from "./schema/sections";
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
  const sections = buildFormSections(formSchema, values);
  const sectionErrorCounts = errorCountsBySection(sections, errors);
  const title = formQuery.data?.name ?? "表单填写";
  const description = typeof formQuery.data?.description === "string" ? formQuery.data.description : "";
  const fieldMode = draftQuery.data?.readOnly ? "readonly" : "fill";

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
    >
      <div className="form-fill-page__body">
        <section className="form-fill-intro" aria-label="表单说明">
          <div className="form-fill-intro__kicker">
            <span>{sections.length} 个业务分区</span>
            {status ? <span>{status}</span> : null}
          </div>
          <h2>{title}</h2>
          {description ? <p>{description}</p> : <p>请按分区填写内容，带 * 为必填项。</p>}
        </section>

        <nav className="section-anchor-nav" aria-label="业务分区导航">
          {sections.map((section, index) => {
            const errorCount = sectionErrorCounts[section.id] ?? 0;
            return (
              <button
                key={section.id}
                type="button"
                className={`section-anchor${errorCount > 0 ? " section-anchor--error" : ""}`}
                aria-label={`${section.title}${errorCount > 0 ? `，${errorCount} 项需补充` : ""}`}
                onClick={() => scrollToSection(section.id)}
              >
                <span>{index + 1}</span>
                <strong>{section.title}</strong>
                {errorCount > 0 ? <em>{errorCount}</em> : null}
              </button>
            );
          })}
        </nav>

        <div className="form-section-stack">
          {sections.map((section, index) => {
            const sectionErrors = pickErrors(errors, section.fieldIds);
            const errorCount = sectionErrorCounts[section.id] ?? 0;
            return (
              <section
                key={section.id}
                className={`af-card--form business-section-card${errorCount > 0 ? " business-section-card--error" : ""}`}
                data-section-id={section.id}
                aria-labelledby={`form-section-${section.id}`}
              >
                <header className="business-section-card__head">
                  <div>
                    <span>分区 {index + 1}</span>
                    <h3 id={`form-section-${section.id}`}>{section.title}</h3>
                    {section.description ? <small>{section.description}</small> : null}
                  </div>
                  {errorCount > 0 ? <strong>{errorCount} 项需补充</strong> : null}
                </header>
                <div className="business-section-card__body">
                  {section.nodes.length > 0 ? (
                    <DynamicFormRenderer
                      schema={section.nodes}
                      values={values}
                      mode={fieldMode}
                      errors={sectionErrors}
                      onValueChange={handleValueChange}
                    />
                  ) : (
                    <p className="af-empty-text">暂无可填写内容</p>
                  )}
                </div>
              </section>
            );
          })}
        </div>
      </div>

      <div className="action-bar form-fill-action-bar">
        <button
          type="button"
          className="btn btn--ghost btn--lg"
          disabled={saveMutation.isPending}
          onClick={() => saveDraft()}
        >
          {saveMutation.isPending ? "保存中" : reworkTaskId ? "保存原单" : "保存草稿"}
        </button>
        <button type="button" className="btn btn--success btn--lg" onClick={submitForm}>
          提交
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

  function handleValueChange(fieldId: string, value: unknown) {
    setValues((current) => ({ ...current, [fieldId]: value }));
    setErrors((current) => {
      const next = { ...current };
      delete next[fieldId];
      return next;
    });
    setStatus("");
  }

  function submitForm() {
    const nextErrors = validateSchemaValues(formSchema, values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
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

function errorCountsBySection(
  groups: Array<{ id: string; fieldIds: string[] }>,
  errors: FieldValidationErrors,
): Record<string, number> {
  return groups.reduce<Record<string, number>>((next, group) => {
    const count = group.fieldIds.filter((fieldId) => Boolean(errors[fieldId])).length;
    if (count > 0) next[group.id] = count;
    return next;
  }, {});
}

function scrollToSection(sectionId: string) {
  if (typeof document === "undefined") {
    return;
  }
  const target = document.querySelector(`[data-section-id="${escapeCssIdent(sectionId)}"]`);
  target?.scrollIntoView({ behavior: "smooth", block: "start" });
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
