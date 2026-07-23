import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useMobileBootstrap } from "../workbench/workbench.api";

export function ProfilePage() {
  const bootstrapQuery = useMobileBootstrap();

  if (bootstrapQuery.isPending) {
    return <PageSkeleton rows={3} />;
  }

  if (bootstrapQuery.isError) {
    return (
      <PageError
        title="个人中心加载失败"
        message="请稍后重试。"
        onRetry={() => void bootstrapQuery.refetch()}
      />
    );
  }

  const user = bootstrapQuery.data.user;
  const pendingCount = bootstrapQuery.data.pendingCount;

  return (
    <main className="af-page" data-testid="profile" style={{ paddingTop: 0 }}>
      <header className="af-head-bar">
        <h3>我的</h3>
      </header>

      <section className="af-card" aria-label="个人信息" style={{ display: "flex", alignItems: "center", gap: 10, padding: 16 }}>
        <span className="af-avatar af-avatar--lg" aria-hidden="true">{user.displayName.slice(0, 1)}</span>
        <div style={{ display: "grid", gap: 2, minWidth: 0 }}>
          <strong style={{ fontSize: 15 }}>{user.displayName}</strong>
          <small style={{ fontSize: 10, color: "var(--af-color-muted)" }}>
            {user.department ? `${user.department} · ` : ""}
            {user.roles?.join("、") ?? "员工"}
          </small>
        </div>
        <span style={{ marginLeft: "auto", color: "#9aa2aa" }} aria-hidden="true">{"\u203A"}</span>
      </section>

      <fieldset className="af-stat-row" aria-label="数据统计">
        <legend className="visually-hidden" style={{ position: "absolute", clip: "rect(0 0 0 0)", height: 1, width: 1, overflow: "hidden" }}>数据统计</legend>
        <div>
          <b>{pendingCount}</b>
          <small>待处理</small>
        </div>
        <div>
          <b>0</b>
          <small>进行中</small>
        </div>
        <div>
          <b>0</b>
          <small>本月完成</small>
        </div>
      </fieldset>

      <nav className="af-menu" aria-label="个人中心入口">
        <a className="af-menu__row" href="/apps/favorites">
          <span>常用应用管理</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </a>
        <a className="af-menu__row" href="/forms/drafts">
          <span>草稿箱</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </a>
        <a className="af-menu__row" href="/profile/security">
          <span>账号与安全</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </a>
      </nav>

      <nav className="af-menu" aria-label="其他">
        <a className="af-menu__row" href="#help">
          <span>帮助与反馈</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </a>
        <a className="af-menu__row" href="#about">
          <span>关于 AntFlow</span>
          <small>v0.1.0</small>
          <span aria-hidden="true">{"\u203A"}</span>
        </a>
      </nav>

      <nav className="af-menu" aria-label="退出">
        <a className="af-menu__row" href="/profile/security" style={{ justifyContent: "center", color: "var(--af-color-danger)" }}>
          退出登录
        </a>
      </nav>
    </main>
  );
}

export default ProfilePage;
