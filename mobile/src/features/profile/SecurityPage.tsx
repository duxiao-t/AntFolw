import { useState } from "react";
import { Toast } from "antd-mobile";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { queryKeys } from "../../shared/api/queryKeys";
import { clearUserScopedRecovery } from "../../shared/recovery/userScopedStorage";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { revokeSession, useDeviceSessions } from "./profile.api";

export function SecurityPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const user = useAuthStore((state) => state.user);
  const sessionsQuery = useDeviceSessions();
  const [showSessions, setShowSessions] = useState(false);
  const revokeMutation = useMutation({ mutationFn: revokeSession, onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: queryKeys.sessions }); } });
  const logoutMutation = useMutation({ mutationFn: async () => { const sessions = sessionsQuery.data ?? []; await Promise.all(sessions.filter((session) => !session.isCurrent).map((session) => revokeSession(session.id))); await logout(); if (user) clearUserScopedRecovery(user.id); }, onSuccess: () => { queryClient.clear(); navigate("/login", { replace: true }); }, onError: () => Toast.show({ icon: "fail", content: "退出失败，请重试" }) });
  if (sessionsQuery.isPending) return <PageSkeleton rows={4} />;
  if (sessionsQuery.isError) return <PageError title="设备会话加载失败" onRetry={() => void sessionsQuery.refetch()} />;
  const sessions = sessionsQuery.data ?? [];

  return (
    <AppPage title="账号安全" contentStyle={{ paddingBottom: 0 }}>
      <section className="section"><div className="list-card">
        <SecurityRow icon={<PhoneIcon />} title="登录设备" hint={`${sessions.length} 台 · 含本机`} onClick={() => setShowSessions((value) => !value)} />
      </div></section>
      {showSessions ? <section className="security-sessions">{sessions.map((session) => <div className="security-session" key={session.id}><div><b>{session.deviceName}</b><small>{session.platform === "wecom" ? "企业微信" : "浏览器"} · {session.lastActiveAt}</small></div>{session.isCurrent ? <span className="badge-soft badge-soft--on">当前设备</span> : <button type="button" className="link" disabled={revokeMutation.isPending} onClick={() => revokeMutation.mutate(session.id)}>移除</button>}</div>)}</section> : null}
      <div className="action-bar"><button className="btn btn--danger btn--lg" type="button" disabled={logoutMutation.isPending} onClick={() => logoutMutation.mutate()}>{logoutMutation.isPending ? "退出中..." : "退出全部设备"}</button></div>
    </AppPage>
  );
}

function SecurityRow({ icon, title, hint, badge, off, onClick }: { icon: React.ReactNode; title: string; hint: string; badge?: string; off?: boolean; onClick?: () => void }) { return <button className="list-item" type="button" onClick={onClick}><span className="list-item__icon">{icon}</span><div className="list-item__main"><b>{title}</b><small>{hint}</small></div>{badge ? <span className={`badge-soft badge-soft--${off ? "off" : "on"}`}>{badge}</span> : null}<span className="list-item__chev">›</span></button>; }
const Svg = ({ children }: { children: React.ReactNode }) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;
const PhoneIcon = () => <Svg><rect x="5" y="2" width="14" height="20" rx="2" /><path d="M12 18h.01" /></Svg>;

export default SecurityPage;
