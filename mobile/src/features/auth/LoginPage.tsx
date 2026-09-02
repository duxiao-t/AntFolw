import { useEffect, useState } from "react";
import { Toast } from "antd-mobile";
import { useNavigate, useSearchParams } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { useBranding } from "../branding/BrandProvider";
import { safeReturnUrl, useAuthStore } from "./auth.store";
import { apiRequest } from "../../shared/api/http";

const REMEMBERED_USERNAME_KEY = "antflow-mobile-remembered-username";
type WecomLoginState = 'loading' | 'enabled' | 'disabled' | 'unavailable' | 'redirecting';

export function LoginPage() {
  const branding = useBranding();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const restore = useAuthStore((state) => state.restore);
  const [submitting, setSubmitting] = useState(false);
  const [username, setUsername] = useState(readRememberedUsername);
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(() => readRememberedUsername().length > 0);
  const [errorMessage, setErrorMessage] = useState("");
  const [providers, setProviders] = useState<Array<{ code: string; displayName: string }>>([]);
  const [wecomState, setWecomState] = useState<WecomLoginState>('loading');
  const externalReturnUrl = `/mobile${safeReturnUrl(params.get('returnUrl')) ?? '/workbench'}`;

  useEffect(() => {
    void apiRequest<Array<{ code: string; displayName: string }>>('/api/public/auth/providers')
      .then((result) => setProviders(Array.isArray(result) ? result : [])).catch(() => setProviders([]));
    void apiRequest<{ oauthEnabled: boolean }>('/api/public/auth/wecom/status')
      .then((result) => setWecomState(result.oauthEnabled ? 'enabled' : 'disabled'))
      .catch(() => setWecomState('unavailable'));
  }, []);

  useEffect(() => {
    if (status === 'unknown') void restore();
  }, [restore, status]);

  useEffect(() => {
    if (status === "authenticated") {
      navigate(safeReturnUrl(params.get("returnUrl")) ?? "/workbench", { replace: true });
    }
  }, [navigate, params, status]);

  async function handleSubmit() {
    const nextUsername = username.trim();
    if (!nextUsername || !password) {
      setErrorMessage(!nextUsername ? "请输入账号" : "请输入密码");
      return;
    }
    setErrorMessage("");
    setSubmitting(true);
    try {
      await login(nextUsername, password);
      writeRememberedUsername(rememberMe ? nextUsername : "");
      navigate(safeReturnUrl(params.get("returnUrl")) ?? "/workbench", { replace: true });
    } catch (error) {
      const message = isApiError(error) && error.status === 401 ? "账号或密码错误" : "登录失败，请稍后再试";
      setErrorMessage(message);
      if (import.meta.env.MODE !== "test") Toast.show({ icon: "fail", content: message });
    } finally {
      setSubmitting(false);
    }
  }

  function handleWecomLogin() {
    if (wecomState !== 'enabled') return;
    setWecomState('redirecting');
    window.requestAnimationFrame(() => {
      window.location.assign(`/api/public/auth/wecom/authorize?returnUrl=${encodeURIComponent(externalReturnUrl)}`);
    });
  }

  return (
    <main className="login">
      <section className="login__hero">
        <p className="login__eyebrow">{branding.companyName || "ANTFLOW"}</p>
        <h1 className="login__title">移动审批</h1>
        <p className="login__subtitle">{branding.loginTitle || "欢迎使用移动审批工作台"}</p>
      </section>

      <form className="login__panel" noValidate onSubmit={(event) => { event.preventDefault(); void handleSubmit(); }}>
        <div className="login__field">
          <label htmlFor="login-username">用户名</label>
          <div className="login__field__box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 4-7 8-7s8 3 8 7" /></svg>
            <input id="login-username" placeholder="请输入账号" autoComplete="username" value={username} disabled={submitting} onChange={(event) => { setUsername(event.currentTarget.value); setErrorMessage(""); }} />
          </div>
        </div>
        <div className="login__field">
          <label htmlFor="login-password">密码</label>
          <div className="login__field__box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg>
            <input id="login-password" type="password" placeholder="请输入密码" autoComplete="current-password" value={password} disabled={submitting} onChange={(event) => { setPassword(event.currentTarget.value); setErrorMessage(""); }} />
          </div>
        </div>
        <div className="login__row">
          <label><input type="checkbox" checked={rememberMe} disabled={submitting} onChange={(event) => setRememberMe(event.currentTarget.checked)} /> 记住我</label>
          <button className="link" type="button">忘记密码</button>
        </div>
        {errorMessage ? <div className="login__error" role="alert"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16v.5" /></svg><span>{errorMessage}</span></div> : null}
        <button className="btn btn--primary btn--block btn--lg" type="submit" disabled={submitting}>{submitting ? "登录中..." : "登录"}</button>
      </form>

      <section className="login__third" aria-label="第三方登录">
        <span>第三方登录</span>
        <div className="login__socials">
          <button
            className="login__social login__social--wecom"
            type="button"
            disabled={wecomState !== 'enabled'}
            aria-busy={wecomState === 'loading' || wecomState === 'redirecting'}
            aria-live="polite"
            onClick={handleWecomLogin}
          >
            {wecomState === 'redirecting'
              ? <span className="login__spinner" aria-hidden="true" />
              : <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8.5 11a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm7 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z" /><path d="M3 13.5C3 9 7 6 12 6s9 3 9 7.5c0 4-3.5 7-8 7l-2-.3-3 1.5.8-2.6C5 18 3 16 3 13.5Z" /></svg>}
            <span>{wecomState === 'loading' ? '正在检查企业微信' : wecomState === 'redirecting' ? '正在进入企业微信' : '企业微信登录'}</span>
          </button>
          {providers.map((provider) => <button key={provider.code} className="login__social login__social--oidc" type="button" onClick={() => { window.location.assign(`/api/public/auth/oidc/${encodeURIComponent(provider.code)}/authorize?returnUrl=${encodeURIComponent(externalReturnUrl)}`); }}><b aria-hidden="true">{provider.displayName.slice(0, 1)}</b><span>{provider.displayName}</span></button>)}
        </div>
        {wecomState === 'disabled' ? <p className="login__third-status">企业微信登录未启用，请联系管理员</p> : null}
        {wecomState === 'unavailable' ? <p className="login__third-status">企业微信登录暂不可用，请稍后重试</p> : null}
      </section>
      <p className="login__agreement">登录即代表你已阅读并同意 <button className="link" type="button">用户协议</button> 与 <button className="link" type="button">隐私政策</button></p>
    </main>
  );
}

function readRememberedUsername() {
  return typeof localStorage === "undefined" ? "" : localStorage.getItem(REMEMBERED_USERNAME_KEY) ?? "";
}

function writeRememberedUsername(username: string) {
  if (typeof localStorage === "undefined") return;
  if (username) localStorage.setItem(REMEMBERED_USERNAME_KEY, username);
  else localStorage.removeItem(REMEMBERED_USERNAME_KEY);
}

export default LoginPage;
