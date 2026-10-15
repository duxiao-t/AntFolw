// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/workflow-monitor */
export async function overview1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.overview1Params,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/workflow-monitor", {
    method: "GET",
    params: {
      // limit has a default value: 50
      limit: "50",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/workflow-monitor/outbox */
export async function outbox(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.outboxParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.OutboxPage>("/api/workflow-monitor/outbox", {
    method: "GET",
    params: {
      // status has a default value: DEAD
      status: "DEAD",
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/workflow-monitor/outbox/${param0}/retry */
export async function retryOutbox(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.retryOutboxParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/workflow-monitor/outbox/${param0}/retry`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}
