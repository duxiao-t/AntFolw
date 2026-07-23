import { useNavigate, useParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";

export function SubmitSuccessPage() {
  const { instanceId = "" } = useParams();
  const navigate = useNavigate();

  return (
    <AppPage title="" variant="blank">
      <div className="af-success-page af-fade-in">
        <span className="af-success-page__check" aria-hidden="true" />
        <h3>提交成功</h3>
        <p>{"请假申请已进入审批流程\n当前等待直属主管处理"}</p>
        <div className="af-success-page__buttons">
          <button
            type="button"
            className="af-btn af-btn--ghost"
            onClick={() => navigate("/workbench", { replace: true })}
          >
            返回工作台
          </button>
          <button
            type="button"
            className="af-btn"
            onClick={() => navigate(`/processes/${instanceId}`, { replace: true })}
          >
            查看进度
          </button>
        </div>
      </div>
    </AppPage>
  );
}

export default SubmitSuccessPage;
