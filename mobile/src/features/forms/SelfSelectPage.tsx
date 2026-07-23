import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { fetchMobileForm } from "./drafts.api";
import {
  findSelfSelectRules,
  updateSelfSelected,
  useSubmitFlowStore,
} from "./submitFlow.store";

export function SelfSelectPage() {
  const { code = "" } = useParams();
  const navigate = useNavigate();
  const flow = useSubmitFlowStore();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const formQuery = useQuery({
    queryKey: queryKeys.form(code),
    queryFn: () => fetchMobileForm(code),
    enabled: code.length > 0,
    retry: 0,
  });
  const rules = useMemo(
    () => findSelfSelectRules(formQuery.data?.process),
    [formQuery.data?.process],
  );

  if (!flow.formCode || flow.formCode !== code) {
    return (
      <AppPage title="选择审批人">
        <p>当前提交信息已失效，请返回表单重新提交。</p>
        <button
          type="button"
          className="af-btn af-btn--block"
          onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}
        >
          返回表单
        </button>
      </AppPage>
    );
  }

  if (formQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (formQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage
      title="选择审批人"
      action={
        <button
          type="button"
          className="af-link-button"
          style={{ fontSize: 13 }}
          onClick={confirmSelection}
        >
          确定
        </button>
      }
    >
      <div className="af-search">{"\u2315 搜索姓名"}</div>

      <div className="af-stack" style={{ marginTop: 10 }}>
        {rules.map((rule) => (
          <section key={rule.nodeId} className="af-card">
            <div className="af-card__title">
              <span>{rule.name}</span>
              <small style={{ fontSize: 10, fontWeight: 400 }}>{rule.multiple ? "多选" : "单选"}</small>
            </div>
            <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
              {rule.assignees.map((assignee) => {
                const selected = (flow.selfSelected[rule.nodeId] ?? []).map(Number).includes(Number(assignee.id));
                return (
                  <li key={assignee.id}>
                    <button
                      type="button"
                      className="af-radio-row"
                      onClick={() => {
                        const current = (flow.selfSelected[rule.nodeId] ?? []).map(Number);
                        let next: number[];
                        if (rule.multiple) {
                          next = current.includes(Number(assignee.id))
                            ? current.filter((value) => value !== Number(assignee.id))
                            : [...current, Number(assignee.id)];
                        } else {
                          next = [Number(assignee.id)];
                        }
                        updateSelfSelected(rule.nodeId, next);
                        setErrors((current) => {
                          const copy = { ...current };
                          delete copy[rule.nodeId];
                          return copy;
                        });
                      }}
                    >
                      <span className="af-avatar af-avatar--sm" aria-hidden="true">
                        {assignee.name.slice(0, 1)}
                      </span>
                      <span className="af-radio-row__main">
                        <b>{assignee.name}</b>
                        <small>{assignee.department ?? ""}</small>
                      </span>
                      <span className={`af-radio${selected ? " is-on" : ""}`} aria-hidden="true" />
                    </button>
                  </li>
                );
              })}
            </ul>
            {errors[rule.nodeId] ? (
              <span role="alert" style={{ color: "var(--af-color-danger)", fontSize: 11 }}>
                {errors[rule.nodeId]}
              </span>
            ) : null}
          </section>
        ))}
      </div>

      <div className="af-bottom-bar">
        <button type="button" className="af-btn af-btn--block" onClick={confirmSelection}>
          确认选择
        </button>
      </div>
    </AppPage>
  );

  function confirmSelection() {
    const nextErrors: Record<string, string> = {};
    rules.forEach((rule) => {
      const current = useSubmitFlowStore.getState().selfSelected[rule.nodeId] ?? [];
      if (current.length === 0) {
        nextErrors[rule.nodeId] = `请选择${rule.name}`;
      }
    });
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      return;
    }
    void navigate(`/forms/${encodeURIComponent(code)}/confirm`);
  }
}

export default SelfSelectPage;
