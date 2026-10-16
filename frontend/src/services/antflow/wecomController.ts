// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/integrations/wecom/settings */
export async function settings2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.settings2Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.SettingsDto>("/api/integrations/wecom/settings", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/integrations/wecom/settings */
export async function save(
  body: AntFlowAPI.SaveSettingsRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.SettingsDto>("/api/integrations/wecom/settings", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/integrations/wecom/sync-jobs */
export async function start1(
  body: AntFlowAPI.StartJobRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.JobDto>("/api/integrations/wecom/sync-jobs", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/integrations/wecom/sync-jobs/${param0} */
export async function job(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.jobParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.JobDto>(
    `/api/integrations/wecom/sync-jobs/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}
