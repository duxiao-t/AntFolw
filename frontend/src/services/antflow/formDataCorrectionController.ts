// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/forms/data/${param0}/correction-form */
export async function correctionForm(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.correctionFormParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.CorrectionFormView>(
    `/api/forms/data/${param0}/correction-form`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/forms/data/${param0}/corrections */
export async function history1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.history1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.CorrectionView[]>(
    `/api/forms/data/${param0}/corrections`,
    {
      method: "GET",
      params: {
        // limit has a default value: 50
        limit: "50",
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/forms/data/${param0}/corrections */
export async function correct(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.correctParams,
  body: AntFlowAPI.CorrectionRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.CorrectionResult>(
    `/api/forms/data/${param0}/corrections`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/forms/data/${param0}/corrections/${param1}/review */
export async function review(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.reviewParams,
  body: AntFlowAPI.ReviewRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, correctionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.CorrectionResult>(
    `/api/forms/data/${param0}/corrections/${param1}/review`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}
