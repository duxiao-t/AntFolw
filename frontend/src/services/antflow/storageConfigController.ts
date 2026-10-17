// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/settings/storage */
export async function view(options?: { [key: string]: any }) {
  return request<AntFlowAPI.StorageConfigView>("/api/settings/storage", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/settings/storage/test */
export async function test(options?: { [key: string]: any }) {
  return request<AntFlowAPI.StorageProbeResult>("/api/settings/storage/test", {
    method: "POST",
    ...(options || {}),
  });
}
