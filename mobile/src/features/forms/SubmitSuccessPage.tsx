import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";

export function SubmitSuccessPage() {
  const { instanceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const direct = searchParams.get("mode") === "direct";
  return (
    <AppPage title="提交成功" back={false} action={<button className="app-bar__action" type="button" onClick={() => navigate("/workbench")}>关闭</button>}>
      <div className="empty-hero" style={{ padding: "48px 16px 24px" }}>
        <div className="empty-hero__art"><svg className="form-success-mark" viewBox="0 0 160 160" fill="none" aria-hidden="true"><circle className="form-success-mark__halo" cx="80" cy="80" r="62" /><circle className="form-success-mark__disc" cx="80" cy="80" r="48" /><path d="M62 82l14 14 24-30" stroke="white" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" /></svg></div>
        <h3 style={{ fontSize: 20 }}>提交成功</h3><p>{direct ? "表单数据已提交完成。" : "流程已进入审批队列，可随时在“我发起的”中查看进度。"}</p>
        <div className="success-summary"><div><span className="muted">编号</span><b>#{instanceId}</b></div><div><span className="muted">当前状态</span><b style={{ color: "var(--af-color-primary)" }}>{direct ? "已完成" : "等待审批"}</b></div></div>
        <div className="empty-hero__cta" style={{ marginTop: 14 }}>{!direct ? <button className="btn btn--primary btn--lg btn--block" type="button" onClick={() => navigate(`/processes/${instanceId}`)}>查看流程</button> : null}<button className="btn btn--ghost btn--block" type="button" onClick={() => navigate("/apps")}>继续填写其他表单</button><button className="btn btn--ghost btn--block" style={{ background: "transparent" }} type="button" onClick={() => navigate("/workbench")}>返回工作台</button></div>
      </div>
    </AppPage>
  );
}

export default SubmitSuccessPage;
