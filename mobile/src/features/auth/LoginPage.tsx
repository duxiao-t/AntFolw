import { useEffect, useState } from "react";
import { SafeArea, Toast } from "antd-mobile";
import {
  CheckShieldOutline,
  EyeInvisibleOutline,
  EyeOutline,
  LockOutline,
  UserContactOutline,
} from "antd-mobile-icons";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuthStore, safeReturnUrl } from "./auth.store";
import { useBranding } from "../branding/BrandProvider";
import { isApiError } from "../../shared/api/errors";
import "./LoginPage.css";

const REMEMBERED_USERNAME_KEY = "antflow-mobile-remembered-username";
const LOGIN_ICON_BASE = `${import.meta.env.BASE_URL}login-icons`;

export function LoginPage() {
  const branding = useBranding();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const [submitting, setSubmitting] = useState(false);
  const [username, setUsername] = useState(() => readRememberedUsername());
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(() => readRememberedUsername().length > 0);
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  useEffect(() => {
    if (status === "authenticated") {
      const target = safeReturnUrl(params.get("returnUrl")) ?? "/workbench";
      navigate(target, { replace: true });
    }
  }, [status, params, navigate]);

  async function handleSubmit(): Promise<void> {
    const nextUsername = username.trim();
    if (!nextUsername || !password) {
      const message = !nextUsername ? "请输入账号" : "请输入密码";
      setErrorMessage(message);
      showLoginToast({ icon: "fail", content: message });
      return;
    }
    setErrorMessage("");
    setSubmitting(true);
    try {
      await login(nextUsername, password);
      writeRememberedUsername(rememberMe ? nextUsername : "");
      const target = safeReturnUrl(params.get("returnUrl")) ?? "/workbench";
      navigate(target, { replace: true });
    } catch (error) {
      const message = isApiError(error) && error.status === 401 ? "账号或密码错误" : "登录失败，请稍后再试";
      setErrorMessage(message);
      showLoginToast({
        icon: "fail",
        content: message,
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="af-login page page-login">
      <SafeArea position="top" />
      <section className="af-login__hero" aria-labelledby="login-title">
        <div className="af-login__hero-copy">
          <p className="af-login__eyebrow">{branding.companyName || "AntFlow"}</p>
          <h1 id="login-title" className="af-login__title">Hello!</h1>
          <p className="af-login__subtitle">{branding.loginTitle || "欢迎使用移动审批工作台"}</p>
        </div>
        <div className="af-login__illustration" aria-hidden="true">
          <div className="af-login__moon" />
          <div className="af-login__person">
            <span className="af-login__person-hair" />
            <span className="af-login__person-face" />
            <span className="af-login__person-body" />
            <span className="af-login__person-arm" />
          </div>
          <div className="af-login__approval-card">
            <span />
            <span />
            <span />
          </div>
          <i />
        </div>
      </section>

      <section className="af-login__panel" aria-label="账号登录">
        <form
          className="af-login__form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void handleSubmit();
          }}
        >
          <div className="af-login__field">
            <label htmlFor="login-username">用户名</label>
            <UserContactOutline aria-hidden="true" className="af-login__field-icon" />
            <input
              id="login-username"
              className="af-login__input"
              placeholder="请输入账号"
              autoComplete="username"
              inputMode="text"
              value={username}
              disabled={submitting}
              onChange={(event) => {
                setUsername(event.currentTarget.value);
                setErrorMessage("");
              }}
            />
          </div>
          <div className="af-login__field">
            <label htmlFor="login-password">密码</label>
            <LockOutline aria-hidden="true" className="af-login__field-icon" />
            <input
              id="login-password"
              className="af-login__input"
              placeholder="请输入密码"
              type={showPassword ? "text" : "password"}
              autoComplete="current-password"
              value={password}
              disabled={submitting}
              onChange={(event) => {
                setPassword(event.currentTarget.value);
                setErrorMessage("");
              }}
            />
            <button
              type="button"
              className="af-login__password-toggle"
              aria-label={showPassword ? "隐藏密码" : "显示密码"}
              disabled={submitting}
              onClick={() => setShowPassword((current) => !current)}
            >
              {showPassword ? <EyeInvisibleOutline /> : <EyeOutline />}
            </button>
          </div>
          <div className="af-login__form-row">
            <label className="af-login__remember">
              <input
                type="checkbox"
                checked={rememberMe}
                disabled={submitting}
                onChange={(event) => setRememberMe(event.currentTarget.checked)}
              />
              <span>记住我</span>
            </label>
            <button type="button" className="af-login__link" disabled={submitting}>
              忘记密码
            </button>
          </div>
          {errorMessage ? (
            <p role="alert" className="af-login__error">
              <CheckShieldOutline aria-hidden="true" />
              <span>{errorMessage}</span>
            </p>
          ) : null}
          <button type="submit" className="af-btn af-btn--block af-login__submit" disabled={submitting}>
            {submitting ? (
              <>
                <span className="af-login__spinner" aria-hidden="true" />
                登录中...
              </>
            ) : (
              "登录"
            )}
          </button>
        </form>
      </section>

      <p className="af-login__agreement">
        登录即代表你已阅读并同意 <button type="button">用户协议</button> 与 <button type="button">隐私政策</button>
      </p>

      <section className="af-login__third-party" aria-label="第三方登录">
        <div className="af-login__divider"><span>第三方登录</span></div>
        <div className="af-login__socials">
          <button type="button" className="af-login__social af-login__social--wechat" aria-label="企业微信登录">
            <img className="af-login__social-icon" src={`${LOGIN_ICON_BASE}/wechat.png`} alt="" aria-hidden="true" />
          </button>
          <button type="button" className="af-login__social af-login__social--dingtalk" aria-label="钉钉登录">
            <img className="af-login__social-icon" src={`${LOGIN_ICON_BASE}/dingtalk.png`} alt="" aria-hidden="true" />
          </button>
        </div>
      </section>

      {branding.showLoginFooter ? <footer className="af-login__footer">{branding.footerText}</footer> : null}
      <SafeArea position="bottom" />
    </main>
  );
}

function readRememberedUsername() {
  if (typeof localStorage === "undefined") {
    return "";
  }
  return localStorage.getItem(REMEMBERED_USERNAME_KEY) ?? "";
}

function writeRememberedUsername(username: string) {
  if (typeof localStorage === "undefined") {
    return;
  }
  if (username) {
    localStorage.setItem(REMEMBERED_USERNAME_KEY, username);
    return;
  }
  localStorage.removeItem(REMEMBERED_USERNAME_KEY);
}

function showLoginToast(options: Parameters<typeof Toast.show>[0]) {
  if (import.meta.env.MODE === "test") {
    return;
  }
  Toast.show(options);
}

export default LoginPage;
