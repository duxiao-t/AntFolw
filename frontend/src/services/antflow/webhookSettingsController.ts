// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/settings/webhook */
export async function view(options?: { [key: string]: any }) {
  return request<AntFlowAPI.WebhookSettingsView>("/api/settings/webhook", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/settings/webhook */
export async function save(
  body: AntFlowAPI.WebhookSettingsRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.WebhookSettingsView>("/api/settings/webhook", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
