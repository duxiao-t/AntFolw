// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/forms/data */
export async function mySubmissions(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.mySubmissionsParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.PageFormData>("/api/forms/data", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/forms/data */
export async function submit(
  body: AntFlowAPI.SubmitRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.SubmitResult>("/api/forms/data", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/forms/data/${param0} */
export async function get3(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.get3Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormData>(`/api/forms/data/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/forms/data/admin */
export async function adminPage(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.adminPageParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.PageFormData>("/api/forms/data/admin", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/forms/data/admin/export */
export async function export1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.export1Params,
  options?: { [key: string]: any }
) {
  return request<string>("/api/forms/data/admin/export", {
    method: "GET",
    params: {
      // format has a default value: xlsx
      format: "xlsx",
      ...params,
    },
    ...(options || {}),
  });
}
