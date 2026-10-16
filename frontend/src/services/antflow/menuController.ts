// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/menu */
export async function menu(options?: { [key: string]: any }) {
  return request<AntFlowAPI.MenuDocument>("/api/menu", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/menu */
export async function replace(
  body: AntFlowAPI.MenuDocument,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MenuDocument>("/api/menu", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/navigation */
export async function navigation(options?: { [key: string]: any }) {
  return request<AntFlowAPI.NavNode[]>("/api/navigation", {
    method: "GET",
    ...(options || {}),
  });
}
