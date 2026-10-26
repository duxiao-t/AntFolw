// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/system/backups */
export async function list1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.BackupFile[]>("/api/system/backups", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/system/backups */
export async function create1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.create1Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.Status>("/api/system/backups", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/system/backups/${param0} */
export async function delete1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.delete1Params,
  options?: { [key: string]: any }
) {
  const { name: param0, ...queryParams } = params;
  return request<any>(`/api/system/backups/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/system/backups/${param0}/download */
export async function download(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.downloadParams,
  options?: { [key: string]: any }
) {
  const { name: param0, ...queryParams } = params;
  return request<string>(`/api/system/backups/${param0}/download`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/system/backups/settings */
export async function settings1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Settings>("/api/system/backups/settings", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/system/backups/settings */
export async function settings(
  body: AntFlowAPI.SettingsWrite,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.Settings>("/api/system/backups/settings", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/system/backups/status */
export async function status(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Status>("/api/system/backups/status", {
    method: "GET",
    ...(options || {}),
  });
}
