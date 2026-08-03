import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { AppPage } from "../../shared/ui/AppPage";
import { PageEmpty, PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { fetchMobileForm } from "./drafts.api";
import { findSelfSelectRules, updateSelfSelected, useSubmitFlowStore } from "./submitFlow.store";

const AVATAR_TONES = ["blue", "mint", "amber", "coral", "cyan"];

export function SelfSelectPage() {
  const { code = "" } = useParams();
  const navigate = useNavigate();
  const flow = useSubmitFlowStore();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [keyword, setKeyword] = useState("");
  const [activeRuleId, setActiveRuleId] = useState("");
  const formQuery = useQuery({ queryKey: queryKeys.form(code), queryFn: () => fetchMobileForm(code), enabled: code.length > 0, retry: 0 });
  const rules = useMemo(() => findSelfSelectRules(formQuery.data?.process), [formQuery.data?.process]);
  const activeRule = rules.find((rule) => rule.nodeId === activeRuleId) ?? rules[0];
  const people = (activeRule?.assignees ?? []).filter((person) => `${person.name} ${person.department ?? ""} ${person.id}`.toLowerCase().includes(keyword.trim().toLowerCase()));
  const selectedPeople = rules.flatMap((rule) => (flow.selfSelected[rule.nodeId] ?? []).flatMap((id) => { const person = rule.assignees.find((entry) => entry.id === id); return person ? [{ rule, person }] : []; }));

  if (!flow.formCode || flow.formCode !== code) return <AppPage title="选择审批人"><PageEmpty title="提交信息已失效" hint="请返回表单重新进入提交流程。" action={<button className="btn btn--primary" type="button" onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}>返回表单</button>} /></AppPage>;
  if (formQuery.isPending) return <PageSkeleton rows={4} />;
  if (formQuery.isError) return <PageError onRetry={() => void formQuery.refetch()} />;

  return (
    <AppPage title="选择审批人" action={<button type="button" className="app-bar__action" onClick={confirmSelection}>完成</button>}>
      <label className="searchbar" style={{ margin: "8px 0 14px" }}><SearchIcon /><input placeholder="按姓名 / 工号 / 部门搜索" aria-label="搜索审批人" value={keyword} onChange={(event) => setKeyword(event.currentTarget.value)} /></label>
      <div className="chip-row">{rules.map((rule) => <button key={rule.nodeId} type="button" className={`chip${activeRule?.nodeId === rule.nodeId ? " is-active" : ""}`} onClick={() => setActiveRuleId(rule.nodeId)}>{rule.name}</button>)}</div>
      {activeRule ? <h4 style={{ fontSize: 13, color: "var(--af-color-muted)", margin: "12px 4px 6px", fontWeight: 600 }}>{activeRule.name} · {activeRule.multiple ? "可多选" : "单选"}</h4> : null}
      {activeRule && people.length > 0 ? <div className="people-grid">{people.map((person, index) => { const selected = (flow.selfSelected[activeRule.nodeId] ?? []).includes(person.id); return <button key={person.id} type="button" className={`person${selected ? " is-active" : ""}`} aria-pressed={selected} onClick={() => togglePerson(activeRule.nodeId, activeRule.multiple, person.id)}><span className={`person__avatar avatar-tone avatar-tone--${AVATAR_TONES[index % AVATAR_TONES.length]}`}>{person.name.slice(0, 1)}</span><span className="person__name">{person.name}</span><span className="person__sub">{person.department || `工号 ${person.id}`}</span></button>; })}</div> : <PageEmpty title="暂无可选审批人" hint="请联系管理员检查流程候选人配置。" />}
      {activeRule && errors[activeRule.nodeId] ? <p role="alert" className="form-error">{errors[activeRule.nodeId]}</p> : null}

      <h4 style={{ fontSize: 13, color: "var(--af-color-muted)", margin: "18px 4px 6px", fontWeight: 600 }}>已选 {selectedPeople.length} 人</h4>
      <div className="list-card">{selectedPeople.map(({ rule, person }, index) => <button className="list-item" type="button" key={`${rule.nodeId}-${person.id}`} onClick={() => togglePerson(rule.nodeId, rule.multiple, person.id)}><span className={`list-item__avatar compact-person-avatar avatar-tone avatar-tone--${AVATAR_TONES[index % AVATAR_TONES.length]}`}>{person.name.slice(0, 1)}</span><div className="list-item__main"><b>{person.name}</b><small>{person.department || `工号 ${person.id}`} · 顺序 {index + 1}</small></div><span className="chip chip--soft">{rule.multiple ? "会签" : "主审"}</span></button>)}</div>
    </AppPage>
  );

  function togglePerson(nodeId: string, multiple: boolean, personId: number) {
    const current = (useSubmitFlowStore.getState().selfSelected[nodeId] ?? []).map(Number);
    const next = multiple ? (current.includes(personId) ? current.filter((id) => id !== personId) : [...current, personId]) : [personId];
    updateSelfSelected(nodeId, next);
    setErrors((value) => { const copy = { ...value }; delete copy[nodeId]; return copy; });
  }

  function confirmSelection() {
    const nextErrors: Record<string, string> = {};
    rules.forEach((rule) => { if ((useSubmitFlowStore.getState().selfSelected[rule.nodeId] ?? []).length === 0) nextErrors[rule.nodeId] = `请选择${rule.name}`; });
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0) void navigate(`/forms/${encodeURIComponent(code)}/confirm`);
  }
}

function SearchIcon() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>; }

export default SelfSelectPage;
