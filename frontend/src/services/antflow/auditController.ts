// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/audit/archives */
export async function archives(options?: { [key: string]: any }) {
  return request<AntFlowAPI.ArchiveDto[]>("/api/audit/archives", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/audit/archives/${param0}/download */
export async function download1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.download1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<string>(`/api/audit/archives/${param0}/download`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/audit/events */
export async function events2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.events2Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.AuditPage>("/api/audit/events", {
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

/** 此处后端没有提供注释 GET /api/audit/events/${param0} */
export async function detail2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.detail2Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.AuditEventDto>(`/api/audit/events/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/audit/export */
export async function export2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.export2Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.StreamingResponseBody>("/api/audit/export", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
