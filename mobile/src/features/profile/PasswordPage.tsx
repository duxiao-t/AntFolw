import { useState } from "react";
import { Input, Toast } from "antd-mobile";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { isApiError } from "../../shared/api/errors";
import { AppPage } from "../../shared/ui/AppPage";
import { changeOwnPassword } from "./profile.api";

const MIN_LENGTH = 8;
const MAX_LENGTH = 64;

/**
 * 自助修改密码。后端 /api/account/password 只要求已登录，改成功后下线其它会话；
 * 这里只做与后端一致的 8–64 位长度与两次输入一致性校验，长度策略不在此处加严。
 */
export function PasswordPage() {
  const navigate = useNavigate();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  // 三处校验与后端 UserService.validatePassword 对齐（8–64 位）。刻意**不**在前端加更严的强度规则：
  // 前端比后端严会出现"后端能改、前端改不了"的假故障，用户没有任何绕过的办法。
  const lengthInvalid = newPassword.length > 0 && (newPassword.length < MIN_LENGTH || newPassword.length > MAX_LENGTH);
  const mismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const incomplete = !currentPassword || !newPassword || !confirmPassword;
  const canSubmit = !incomplete && !lengthInvalid && !mismatch;

  const changeMutation = useMutation({
    mutationFn: () => changeOwnPassword({ currentPassword, newPassword }),
    onSuccess: () => {
      // 只提示、不强制退出：改密后**当前**会话仍然有效（后端只下线其它设备），
      // 强制重登会把用户刚填的表单/草稿状态一起清掉，体验与后端语义也不一致。
      Toast.show({ icon: "success", content: "密码已修改，其它设备需重新登录" });
      navigate(-1);
    },
    onError: (error) => {
      // ApiError.message 就是后端的中文文案（如"原密码不正确"），直接透出比前端再映射一层更准确；
      // 只有非 ApiError（网络层异常）才用兜底文案。
      const content = isApiError(error) ? error.message : "修改失败，请重试";
      Toast.show({ icon: "fail", content });
    },
  });

  return (
    <AppPage title="修改密码" contentStyle={{ paddingBottom: 0 }}>
      <section className="section">
        <div className="field-card">
          <div className="field">
            <label className="field__label" htmlFor="current-password">原密码</label>
            <Input
              id="current-password"
              type="password"
              className="af-control"
              placeholder="请输入原密码"
              value={currentPassword}
              onChange={setCurrentPassword}
            />
          </div>
          <div className="field">
            <label className="field__label" htmlFor="new-password">新密码</label>
            <Input
              id="new-password"
              type="password"
              className="af-control"
              placeholder={`请输入 ${MIN_LENGTH} 到 ${MAX_LENGTH} 位新密码`}
              value={newPassword}
              onChange={setNewPassword}
            />
            {lengthInvalid ? <div className="field__help">密码长度为 {MIN_LENGTH} 到 {MAX_LENGTH} 位</div> : null}
          </div>
          <div className="field">
            <label className="field__label" htmlFor="confirm-password">确认新密码</label>
            <Input
              id="confirm-password"
              type="password"
              className="af-control"
              placeholder="请再次输入新密码"
              value={confirmPassword}
              onChange={setConfirmPassword}
            />
            {mismatch ? <div className="field__help">两次输入的密码不一致</div> : null}
          </div>
        </div>
        <p className="field__help" style={{ margin: "8px 4px 0" }}>
          忘记原密码请联系组织管理员重置；修改成功后其它设备需要重新登录。
        </p>
      </section>
      <div className="action-bar">
        <button
          className="btn btn--primary btn--lg"
          type="button"
          disabled={!canSubmit || changeMutation.isPending}
          onClick={() => changeMutation.mutate()}
        >
          {changeMutation.isPending ? "提交中..." : "确认修改"}
        </button>
      </div>
    </AppPage>
  );
}

export default PasswordPage;
