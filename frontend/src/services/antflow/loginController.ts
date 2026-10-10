// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 POST /api/auth/login */
export async function login(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.loginParams,
  body: AntFlowAPI.LoginReq,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: {
      ...params,
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/auth/logout */
export async function logout(options?: { [key: string]: any }) {
  return request<any>("/api/auth/logout", {
    method: "POST",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/auth/me */
export async function me(options?: { [key: string]: any }) {
  return request<Record<string, any>>("/api/auth/me", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/auth/refresh */
export async function refresh(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.refreshParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/auth/refresh", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/auth/sessions */
export async function sessions(options?: { [key: string]: any }) {
  return request<AntFlowAPI.DeviceSessionDto[]>("/api/auth/sessions", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/auth/sessions/${param0} */
export async function revokeSession(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.revokeSessionParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/auth/sessions/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}
