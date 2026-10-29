// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 POST /api/automation/webhook/test */
export async function test1(
  body: AntFlowAPI.TestRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.TestResult>("/api/automation/webhook/test", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
