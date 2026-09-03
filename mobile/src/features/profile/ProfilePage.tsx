import { useState } from "react";
import { Toast } from "antd-mobile";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { useMobileBootstrap } from "../workbench/workbench.api";

export function ProfilePage() {
  const bootstrapQuery = useMobileBootstrap();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme === "dark");
  if (bootstrapQuery.isPending) return <PageSkeleton rows={4} />;
  if (bootstrapQuery.isError) return <PageError title="个人中心加载失败" message="请稍后重试。" onRetry={() => void bootstrapQuery.refetch()} />;
  const data = bootstrapQuery.data;
  const user = data.user;

  return (
    <AppPage title="我的" back={false} tabbar brandHeader contentStyle={{ paddingTop: 0 }} action={<button className="app-bar__action" type="button" aria-label="设置" onClick={() => navigate("/profile/security")}><SettingsIcon /></button>}>
      <section className="profile-hero"><div className="avatar-lg">{user.displayName.slice(0, 1)}</div><h2>{user.displayName}</h2><small>{user.department || "未设置部门"} · 工号 {user.employeeNo || "未设置"}</small></section>
      <section className="section"><div className="list-card">
        <MenuRow icon={<FileIcon />} title="我的草稿" hint="未提交的表单" onClick={() => navigate("/forms/drafts")} />
        <MenuRow icon={<MessageIcon />} title="消息中心" hint="审批结果与通知" badge={data.unreadNotificationCount || undefined} onClick={() => navigate("/profile/notifications")} />
        <MenuRow icon={<StarIcon />} title="我的收藏" hint={`${data.favoriteApps.length} 个常用表单`} onClick={() => navigate("/apps/favorites")} />
        <MenuRow icon={<LockIcon />} title="账号安全" hint="登录设备 · 密码 · 双因子" onClick={() => navigate("/profile/security")} />
        <MenuRow icon={<ClockIcon />} title="操作日志" hint="近 30 天" />
        <MenuRow icon={<MoonIcon />} title="外观" hint={dark ? "深色" : "跟随系统"} onClick={() => { const next = !dark; setDark(next); if (next) document.documentElement.dataset.theme = "dark"; else delete document.documentElement.dataset.theme; }} />
        <MenuRow icon={<LogoutIcon />} title="退出登录" hint="退出当前设备" danger onClick={() => {
          void logout().then(() => { queryClient.clear(); navigate("/login", { replace: true }); })
            .catch(() => Toast.show({ icon: "fail", content: "退出失败，请重试" }));
        }} />
      </div></section><div className="spacer-24" />
    </AppPage>
  );
}

function MenuRow({ icon, title, hint, badge, danger, onClick }: { icon: React.ReactNode; title: string; hint: string; badge?: number; danger?: boolean; onClick?: () => void }) { return <button className="list-item" type="button" onClick={onClick}><span className="list-item__icon">{icon}</span><div className="list-item__main" style={danger ? { color: "var(--af-color-danger)" } : undefined}><b>{title}</b><small>{hint}</small></div>{badge ? <span className="badge-soft badge-soft--on">{badge}</span> : null}<span className="list-item__chev">›</span></button>; }
const Svg = ({ children }: { children: React.ReactNode }) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;
const SettingsIcon = () => <Svg><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06A1.65 1.65 0 0 0 15 19.4a1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6h.08A1.65 1.65 0 0 0 10 3.09V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9v.08A1.65 1.65 0 0 0 20.91 10H21a2 2 0 1 1 0 4h-.09A1.65 1.65 0 0 0 19.4 15z" /></Svg>;
const FileIcon = () => <Svg><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6M9 13h6M9 17h6" /></Svg>;
const MessageIcon = () => <Svg><path d="M21 11.5a8.5 8.5 0 0 1-12.3 7.6L3 21l1.9-5.7A8.5 8.5 0 1 1 21 11.5z" /></Svg>;
const StarIcon = () => <Svg><path d="M12 17.3 6.18 21l1.64-7.03L2 9.74l7.19-.61L12 2.5l2.81 6.63L22 9.74l-5.82 4.23L17.82 21z" /></Svg>;
const LockIcon = () => <Svg><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></Svg>;
const ClockIcon = () => <Svg><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 3" /></Svg>;
const MoonIcon = () => <Svg><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></Svg>;
const LogoutIcon = () => <Svg><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" /></Svg>;

export default ProfilePage;
