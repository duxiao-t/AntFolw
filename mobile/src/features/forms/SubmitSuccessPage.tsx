import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";

export function SubmitSuccessPage() {
  const { instanceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const mode = searchParams.get("mode") === "direct" ? "direct" : "workflow";

  return (
    <AppPage title="提交成功" variant="blank" back={false}>
      <section className="af-success-page">
        <div className="af-success-mark" aria-hidden="true">{"\u2713"}</div>
        <strong className="af-success-page__title">提交成功</strong>
        <p>{mode === "direct" ? "表单已提交完成。" : "申请已进入审批流程，请等待审批人处理。"}</p>
        <div className="af-success-page__buttons">
          <button
            type="button"
            className="af-btn af-btn--ghost"
            onClick={() => navigate("/workbench")}
          >
            返回工作台
          </button>
          <button type="button" className="af-btn" onClick={() => navigate(`/processes/${instanceId}`)}>
            查看进度
          </button>
        </div>
      </section>
    </AppPage>
  );
}

export default SubmitSuccessPage;
