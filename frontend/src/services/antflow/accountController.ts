// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 POST /api/account/password */
export async function changePassword(
  body: AntFlowAPI.PasswordChange,
  options?: { [key: string]: any }
) {
  return request<any>("/api/account/password", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/account/profile */
export async function profile(options?: { [key: string]: any }) {
  return request<AntFlowAPI.AccountProfile>("/api/account/profile", {
    method: "GET",
    ...(options || {}),
  });
}
