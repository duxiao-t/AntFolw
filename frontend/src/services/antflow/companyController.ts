// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/companies */
export async function all1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.Company[]>("/api/companies", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/companies */
export async function create6(
  body: AntFlowAPI.Company,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.Company>("/api/companies", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
