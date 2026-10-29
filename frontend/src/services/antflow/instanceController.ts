// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/instances */
export async function list4(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.list4Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.WorkflowPageProcessInstance>("/api/instances", {
    method: "GET",
    params: {
      // scope has a default value: authorized
      scope: "authorized",
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/instances/${param0} */
export async function detail1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.detail1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/instances/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/instances/${param0}/discard */
export async function discard1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.discard1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/instances/${param0}/discard`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/instances/${param0}/history */
export async function history(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.historyParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.TaskHistoryEntity[]>(
    `/api/instances/${param0}/history`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/instances/${param0}/jobs/${param1}/retry */
export async function retryAutomationJob(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.retryAutomationJobParams,
  options?: { [key: string]: any }
) {
  const { id: param0, jobId: param1, ...queryParams } = params;
  return request<any>(`/api/instances/${param0}/jobs/${param1}/retry`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/instances/${param0}/terminate */
export async function terminate(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.terminateParams,
  body: AntFlowAPI.AdminTerminateRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/instances/${param0}/terminate`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/instances/${param0}/withdraw */
export async function withdraw2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.withdraw2Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/instances/${param0}/withdraw`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/instances/start */
export async function start2(
  body: AntFlowAPI.StartCmd,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/instances/start", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
