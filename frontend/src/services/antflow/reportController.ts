// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/reports/approval-summary */
export async function approvalSummary(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.approvalSummaryParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.ApprovalSummary>("/api/reports/approval-summary", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/reports/form-data-count */
export async function formDataCount(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.formDataCountParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/reports/form-data-count", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
