import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";

export function SubmitSuccessPage() {
  const { instanceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const isDirectSubmit = searchParams.get("mode") === "direct";

  return (
    <AppPage title="" variant="blank">
      <div className="af-success-page af-fade-in">
        <span className="af-success-page__check" aria-hidden="true" />
        <h3>提交成功</h3>
        <p>
          {isDirectSubmit
            ? "表单已提交完成"
            : "申请已进入审批流程\n请等待审批人处理"}
        </p>
        <div className="af-success-page__buttons">
          <button
            type="button"
            className="af-btn af-btn--ghost"
            onClick={() => navigate("/workbench", { replace: true })}
          >
            返回工作台
          </button>
          {!isDirectSubmit ? (
            <Link
              className="af-btn"
              replace
              to={`/processes/${instanceId}`}
            >
              查看详情
            </Link>
          ) : null}
        </div>
      </div>
    </AppPage>
  );
}

export default SubmitSuccessPage;
