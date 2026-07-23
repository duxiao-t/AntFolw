import { useEffect, useState } from "react";
import { SafeArea, Toast } from "antd-mobile";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuthStore, safeReturnUrl } from "./auth.store";
import { useBranding } from "../branding/BrandProvider";
import { isApiError } from "../../shared/api/errors";
import "./LoginPage.css";

export function LoginPage() {
  const branding = useBranding();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const [submitting, setSubmitting] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  useEffect(() => {
    if (status === "authenticated") {
      const target = safeReturnUrl(params.get("returnUrl")) ?? "/workbench";
      navigate(target, { replace: true });
    }
  }, [status, params, navigate]);

  async function handleSubmit(): Promise<void> {
    if (!username || !password) {
      Toast.show({ icon: "fail", content: "请输入账号与密码" });
      return;
    }
    setSubmitting(true);
    try {
      await login(username, password);
      const target = safeReturnUrl(params.get("returnUrl")) ?? "/workbench";
      navigate(target, { replace: true });
    } catch (error) {
      Toast.show({
        icon: "fail",
        content: isApiError(error) && error.status === 401 ? "账号或密码错误" : "登录失败，请稍后再试",
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="af-login page page-login">
      <SafeArea position="top" />
      <div className="af-login__logo" aria-hidden="true">
        {branding.companyName.slice(0, 1) || "A"}
      </div>
      <h1 className="af-login__title">{branding.loginTitle || "登录 AntFlow"}</h1>
      <p className="af-login__subtitle">移动审批，让每一次流转清晰可见</p>

      <form
        className="af-login__form"
        onSubmit={(event) => {
          event.preventDefault();
          void handleSubmit();
        }}
      >
        <div className="af-login__field">
          <label htmlFor="login-username">用户名</label>
          <input
            id="login-username"
            className="af-input"
            placeholder="请输入账号"
            autoComplete="username"
            inputMode="text"
            value={username}
            onChange={(event) => setUsername(event.currentTarget.value)}
          />
        </div>
        <div className="af-login__field">
          <label htmlFor="login-password">密码</label>
          <input
            id="login-password"
            className="af-input"
            placeholder="请输入密码"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.currentTarget.value)}
          />
        </div>
        <div className="af-login__submit">
          <button type="submit" className="af-btn af-btn--block" disabled={submitting}>
            {submitting ? "登录中..." : "登录"}
          </button>
        </div>
      </form>

      <p className="af-login__wecom">企业微信免登录（二期）</p>
      {branding.showLoginFooter ? <footer className="af-login__footer">{branding.footerText}</footer> : null}
      <SafeArea position="bottom" />
    </main>
  );
}

export default LoginPage;
