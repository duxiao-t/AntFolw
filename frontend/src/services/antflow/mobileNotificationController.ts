// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/mobile/notifications */
export async function notifications(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.notificationsParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.NotificationPage>("/api/mobile/notifications", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // pageSize has a default value: 20
      pageSize: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/notifications/${param0}/read */
export async function markRead(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.markReadParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/notifications/${param0}/read`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}
