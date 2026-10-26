// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/departments */
export async function tree(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Department[]>("/api/departments", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/departments */
export async function create5(
  body: AntFlowAPI.Department,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.Department>("/api/departments", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/departments/${param0} */
export async function update4(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.update4Params,
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.Department>(`/api/departments/${param0}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/departments/${param0} */
export async function delete7(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.delete7Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/departments/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/departments/${param0}/order */
export async function moveOrder(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.moveOrderParams,
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.Department>(`/api/departments/${param0}/order`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/departments/${param0}/path */
export async function path(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.pathParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.Department[]>(`/api/departments/${param0}/path`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/departments/${param0}/position */
export async function movePosition(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.movePositionParams,
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.Department>(`/api/departments/${param0}/position`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}
