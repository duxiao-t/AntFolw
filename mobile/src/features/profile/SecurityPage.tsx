import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { queryKeys } from "../../shared/api/queryKeys";
import type { DeviceSession } from "../../shared/api/types";
import { useAuthStore } from "../auth/auth.store";
import { revokeSession, useDeviceSessions } from "./profile.api";
import { useMobileBootstrap } from "../workbench/workbench.api";

const enableWeComBinding = import.meta.env.VITE_ENABLE_WECOM_BINDING === "true";

function formatPlatform(platform: DeviceSession["platform"]) {
  return platform === "wecom" ? "企业微信" : "浏览器";
}

function clearUserRecoveryDrafts(userId: number | undefined): void {
  if (!userId) return;
  localStorage.removeItem(`antflow-mobile:drafts:${userId}`);
  const recoveryPrefix = `af:recovery:${userId}:`;
  const recoveryKeys = Array.from({ length: localStorage.length }, (_, index) =>
    localStorage.key(index),
  ).filter((key): key is string => Boolean(key?.startsWith(recoveryPrefix)));
  for (const key of recoveryKeys) {
    localStorage.removeItem(key);
  }
}

export function SecurityPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const sessionsQuery = useDeviceSessions();
  const bootstrapQuery = useMobileBootstrap();

  const revokeMutation = useMutation({
    mutationFn: revokeSession,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.sessions });
    },
  });

  const logoutMutation = useMutation({
    mutationFn: async () => {
      const userId = useAuthStore.getState().user?.id;
      await logout();
      clearUserRecoveryDrafts(userId);
    },
    onSuccess: () => {
      queryClient.clear();
      navigate("/login", { replace: true });
    },
  });

  if (sessionsQuery.isPending) {
    return <PageSkeleton rows={3} />;
  }

  if (sessionsQuery.isError) {
    return (
      <PageError
        title="设备会话加载失败"
        message="请稍后重试。"
        onRetry={() => void sessionsQuery.refetch()}
      />
    );
  }

  const sessions = sessionsQuery.data ?? [];
  const user = bootstrapQuery.data?.user;
  const displayName = user?.displayName ?? "—";
  const username = user?.username ?? "";

  return (
    <AppPage title="账号与安全">
      <section className="af-stack" style={{ gap: 0 }}>
        <div className="af-security-row">
          <span>登录账号</span>
          <small>{displayName} · {username}</small>
        </div>
        <div className="af-security-row">
          <span>修改密码</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </div>
        <div className="af-security-row">
          <span>登录设备</span>
          <span>{sessions.length} 台 {"\u203A"}</span>
        </div>
        {enableWeComBinding ? (
          <div className="af-security-row">
            <span>企业微信绑定</span>
            <small>二期开放</small>
          </div>
        ) : null}
        <div className="af-security-row">
          <span>隐私与数据</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </div>
      </section>

      {sessions.length > 0 ? (
        <section className="af-card" style={{ marginTop: 12 }}>
          <div className="af-card__title"><span>当前会话</span></div>
          <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: 8 }}>
            {sessions.map((session) => (
              <li
                key={session.id}
                style={{ display: "flex", alignItems: "center", gap: 12, padding: "6px 0", borderTop: "1px solid var(--af-color-line)" }}
              >
                <div style={{ minWidth: 0, flex: 1 }}>
                  <strong style={{ display: "block", fontSize: 12 }}>{session.deviceName}</strong>
                  <small style={{ color: "var(--af-color-muted)", fontSize: 10 }}>
                    {formatPlatform(session.platform)} · {session.lastActiveAt}
                  </small>
                  {session.isCurrent ? (
                    <span style={{ display: "inline-block", marginTop: 4, padding: "1px 8px", borderRadius: 6, background: "var(--af-color-success-soft)", color: "var(--af-color-success)", fontSize: 10 }}>
                      当前设备
                    </span>
                  ) : null}
                </div>
                {session.isCurrent ? (
                  <button
                    type="button"
                    className="af-btn af-btn--danger"
                    style={{ height: 32 }}
                    onClick={() => logoutMutation.mutate()}
                    disabled={logoutMutation.isPending}
                  >
                    退出当前设备
                  </button>
                ) : (
                  <button
                    type="button"
                    className="af-btn af-btn--danger"
                    style={{ height: 32 }}
                    onClick={() => revokeMutation.mutate(session.id)}
                    disabled={revokeMutation.isPending}
                  >
                    移除
                  </button>
                )}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <button
        type="button"
        className="af-btn af-btn--danger af-btn--block"
        style={{ marginTop: 20 }}
        onClick={() => logoutMutation.mutate()}
        disabled={logoutMutation.isPending}
      >
        退出当前账号
      </button>
    </AppPage>
  );
}

export default SecurityPage;
