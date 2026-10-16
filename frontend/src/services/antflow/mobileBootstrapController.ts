// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/mobile/bootstrap */
export async function bootstrap(options?: { [key: string]: any }) {
  return request<AntFlowAPI.MobileBootstrapDto>("/api/mobile/bootstrap", {
    method: "GET",
    ...(options || {}),
  });
}
