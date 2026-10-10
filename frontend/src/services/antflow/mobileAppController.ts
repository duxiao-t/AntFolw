// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/mobile/apps */
export async function apps(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.appsParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobileAppDto[]>("/api/mobile/apps", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/mobile/preferences/apps */
export async function saveFavorites(
  body: AntFlowAPI.FavoriteAppsRequest,
  options?: { [key: string]: any }
) {
  return request<any>("/api/mobile/preferences/apps", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
