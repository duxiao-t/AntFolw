// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/roles */
export async function all(options?: { [key: string]: any }) {
  return request<AntFlowAPI.RoleDto[]>("/api/roles", {
    method: "GET",
    ...(options || {}),
  });
}
