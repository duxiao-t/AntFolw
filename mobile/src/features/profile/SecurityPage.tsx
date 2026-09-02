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
      <div className="hero" style={{ marginTop: 12 }}><div className="hero__head"><small>账号安全等级</small></div><div className="hero__greeting"><h2 style={{ fontSize: 22 }}>较高</h2><p>已开启设备锁，建议定期检查登录设备。</p></div></div>
      <section className="section"><div className="list-card">
        <SecurityRow icon={<LockIcon />} title="登录密码" hint="建议每 90 天更新" badge="已设置" />
        <SecurityRow icon={<EyeIcon />} title="双因子认证" hint="短信验证码" badge="已开启" />
        <SecurityRow icon={<ShieldIcon />} title="生物特征" hint="Face ID / 指纹" badge="未开启" off />
        <SecurityRow icon={<PhoneIcon />} title="登录设备" hint={`${sessions.length} 台 · 含本机`} onClick={() => setShowSessions((value) => !value)} />
        <SecurityRow icon={<MessageIcon />} title="消息推送" hint="审批结果 · 通知" badge="已开启" />
        <SecurityRow icon={<ContactIcon />} title="应急联系人" hint="未设置" />
      </div></section>
      {showSessions ? <section className="security-sessions">{sessions.map((session) => <div className="security-session" key={session.id}><div><b>{session.deviceName}</b><small>{session.platform === "wecom" ? "企业微信" : "浏览器"} · {session.lastActiveAt}</small></div>{session.isCurrent ? <span className="badge-soft badge-soft--on">当前设备</span> : <button type="button" className="link" disabled={revokeMutation.isPending} onClick={() => revokeMutation.mutate(session.id)}>移除</button>}</div>)}</section> : null}
      <p style={{ textAlign: "center", color: "var(--af-color-muted)", fontSize: 11, margin: "24px 0" }}>所有操作均会被记录到操作日志。</p>
      <div className="action-bar"><button className="btn btn--ghost btn--lg" type="button">查看操作日志</button><button className="btn btn--danger btn--lg" type="button" disabled={logoutMutation.isPending} onClick={() => logoutMutation.mutate()}>{logoutMutation.isPending ? "退出中..." : "退出全部设备"}</button></div>
    </AppPage>
  );
}

function SecurityRow({ icon, title, hint, badge, off, onClick }: { icon: React.ReactNode; title: string; hint: string; badge?: string; off?: boolean; onClick?: () => void }) { return <button className="list-item" type="button" onClick={onClick}><span className="list-item__icon">{icon}</span><div className="list-item__main"><b>{title}</b><small>{hint}</small></div>{badge ? <span className={`badge-soft badge-soft--${off ? "off" : "on"}`}>{badge}</span> : null}<span className="list-item__chev">›</span></button>; }
const Svg = ({ children }: { children: React.ReactNode }) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;
const LockIcon = () => <Svg><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></Svg>;
const EyeIcon = () => <Svg><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z" /><circle cx="12" cy="12" r="3" /></Svg>;
const ShieldIcon = () => <Svg><path d="M12 2 4 6v6c0 5 3.4 9.4 8 10 4.6-.6 8-5 8-10V6l-8-4z" /></Svg>;
const PhoneIcon = () => <Svg><rect x="5" y="2" width="14" height="20" rx="2" /><path d="M12 18h.01" /></Svg>;
const MessageIcon = () => <Svg><path d="M21 11.5a8.5 8.5 0 0 1-12.3 7.6L3 21l1.9-5.7A8.5 8.5 0 1 1 21 11.5z" /></Svg>;
const ContactIcon = () => <Svg><circle cx="12" cy="12" r="9" /><path d="M8 15c1-2 2.3-3 4-3s3 1 4 3M12 8h.01" /></Svg>;

export default SecurityPage;
