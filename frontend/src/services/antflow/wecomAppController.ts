// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/integrations/wecom/access-info */
export async function accessInfo(options?: { [key: string]: any }) {
  return request<AntFlowAPI.AccessInfo>("/api/integrations/wecom/access-info", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/integrations/wecom/message-status */
export async function messageStatus(options?: { [key: string]: any }) {
  return request<AntFlowAPI.DeliveryStatus>(
    "/api/integrations/wecom/message-status",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/integrations/wecom/retry-messages */
export async function retryMessages(options?: { [key: string]: any }) {
  return request<any>("/api/integrations/wecom/retry-messages", {
    method: "POST",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/integrations/wecom/test-message */
export async function testMessage(options?: { [key: string]: any }) {
  return request<any>("/api/integrations/wecom/test-message", {
    method: "POST",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/wecom/js-sdk-config */
export async function jsSdkConfig(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.jsSdkConfigParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.JsSdkConfig>("/api/mobile/wecom/js-sdk-config", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/wecom/media/import */
export async function importMedia(
  body: AntFlowAPI.MediaRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobileFileDto>("/api/mobile/wecom/media/import", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/public/auth/wecom/authorize */
export async function authorize(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.authorizeParams,
  options?: { [key: string]: any }
) {
  return request<any>("/api/public/auth/wecom/authorize", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/public/auth/wecom/callback */
export async function callback(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.callbackParams,
  options?: { [key: string]: any }
) {
  return request<any>("/api/public/auth/wecom/callback", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/public/auth/wecom/status */
export async function status1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Status>("/api/public/auth/wecom/status", {
    method: "GET",
    ...(options || {}),
  });
}
