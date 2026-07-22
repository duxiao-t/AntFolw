import { Link, useSearchParams } from "react-router-dom";
import { AppPage } from "../../shared/ui/AppPage";

/** Placeholder until Task 10 completes approve/reject detail. */
export function TaskDetailPage() {
  const [searchParams] = useSearchParams();
  const returnQuery = buildReturnQuery(searchParams);
  return (
    <AppPage title="任务详情" description="审批详情与操作将在下一阶段完成">
      <p style={{ margin: 0, color: "rgba(0,0,0,0.55)" }}>
        正在加载任务详情能力，请稍后从任务中心返回。
      </p>
      <Link to={`/tasks?${returnQuery}`}>返回任务中心</Link>
    </AppPage>
  );
}

function buildReturnQuery(searchParams: URLSearchParams): string {
  const params = new URLSearchParams();
  const view = searchParams.get("returnView");
  const keyword = searchParams.get("returnKeyword");
  const status = searchParams.get("returnStatus");
  if (view) params.set("view", view);
  if (keyword) params.set("keyword", keyword);
  if (status) params.set("status", status);
  return params.toString();
}

export default TaskDetailPage;