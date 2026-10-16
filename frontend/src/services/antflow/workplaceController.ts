// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/workplace/overview */
export async function overview(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.overviewParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.Overview>("/api/workplace/overview", {
    method: "GET",
    params: {
      // pendingPage has a default value: 1
      pendingPage: "1",
      // pendingSize has a default value: 8
      pendingSize: "8",
      // recentPage has a default value: 1
      recentPage: "1",
      // recentSize has a default value: 8
      recentSize: "8",
      ...params,
    },
    ...(options || {}),
  });
}
