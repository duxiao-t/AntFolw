// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/mobile/events */
export async function events1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.SseEmitter>("/api/mobile/events", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/workflow/events */
export async function events(options?: { [key: string]: any }) {
  return request<AntFlowAPI.SseEmitter>("/api/workflow/events", {
    method: "GET",
    ...(options || {}),
  });
}
