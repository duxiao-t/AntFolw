// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/approval-delegations */
export async function list6(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Delegation[]>("/api/approval-delegations", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/approval-delegations */
export async function create6(
  body: AntFlowAPI.CreateRequest,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/approval-delegations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/approval-delegations/${param0} */
export async function disable2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.disable2Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/approval-delegations/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}
