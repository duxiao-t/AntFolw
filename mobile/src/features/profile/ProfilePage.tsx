import { Link, useNavigate } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";
import { PageError, PageSkeleton } from "../../shared/ui/PageStates";
import { useAuthStore } from "../auth/auth.store";
import { useMobileBootstrap } from "../workbench/workbench.api";

export function ProfilePage() {
  const bootstrapQuery = useMobileBootstrap();
  const navigate = useNavigate();
  const logout = useAuthStore((state) => state.logout);

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
    <AppPage back={false} flush>
      <div className="af-page af-profile" data-testid="profile">
      <header className="af-operational-head">
        <div>
          <h3>我的</h3>
          <small>账号、草稿和常用入口</small>
        </div>
      </header>

      <section className="af-profile-card" aria-label="个人信息">
        <span className="af-avatar af-avatar--lg" aria-hidden="true">{user.displayName.slice(0, 1)}</span>
        <div className="af-profile-card__main">
          <strong>{user.displayName}</strong>
          <small>
            {user.department ? `${user.department} · ` : ""}
            <span>{user.username}</span>
            {user.roles?.length ? ` · ${user.roles.join("、")}` : ""}
          </small>
        </div>
        <span className="af-profile-card__arrow" aria-hidden="true">{"\u203A"}</span>
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
        <Link className="af-menu__row" to="/apps/favorites">
          <span>常用应用管理</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </Link>
        <Link className="af-menu__row" to="/forms/drafts">
          <span>草稿箱</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </Link>
        <Link className="af-menu__row" to="/profile/security">
          <span>账号与安全</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </Link>
      </nav>

      <nav className="af-menu" aria-label="其他">
        <button type="button" className="af-menu__row">
          <span>帮助与反馈</span>
          <span aria-hidden="true">{"\u203A"}</span>
        </button>
        <button type="button" className="af-menu__row">
          <span>关于 AntFlow</span>
          <small>v0.1.0</small>
          <span aria-hidden="true">{"\u203A"}</span>
        </button>
      </nav>

      <nav className="af-menu" aria-label="退出">
        <button
          type="button"
          className="af-menu__row af-menu__row--danger"
          onClick={() => {
            void logout().finally(() => navigate("/login", { replace: true }));
          }}
        >
          退出登录
        </button>
      </nav>
      </div>
    </AppPage>
  );
}

export default ProfilePage;
