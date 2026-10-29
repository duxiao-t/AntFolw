// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/processes/definitions */
export async function list2(options?: { [key: string]: any }) {
  return request<AntFlowAPI.ProcessDefinition[]>("/api/processes/definitions", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/processes/definitions */
export async function save2(
  body: AntFlowAPI.SaveBody,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.ProcessDefinition>("/api/processes/definitions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/processes/definitions/${param0} */
export async function get(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.getParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ProcessDefinition>(
    `/api/processes/definitions/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/processes/definitions/${param0}/publish */
export async function publish(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.publishParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ProcessDefinition>(
    `/api/processes/definitions/${param0}/publish`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/processes/definitions/by-form/${param0} */
export async function byForm(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.byFormParams,
  options?: { [key: string]: any }
) {
  const { formDefId: param0, ...queryParams } = params;
  return request<AntFlowAPI.ProcessDefinition>(
    `/api/processes/definitions/by-form/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 DELETE /api/processes/definitions/by-form/${param0} */
export async function deleteByForm(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.deleteByFormParams,
  options?: { [key: string]: any }
) {
  const { formDefId: param0, ...queryParams } = params;
  return request<any>(`/api/processes/definitions/by-form/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/processes/definitions/draft/by-form/${param0} */
export async function draftByForm(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.draftByFormParams,
  options?: { [key: string]: any }
) {
  const { formDefId: param0, ...queryParams } = params;
  return request<AntFlowAPI.ProcessDefinition>(
    `/api/processes/definitions/draft/by-form/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}
