// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 POST /api/runtime/form-options/preview/${param0} */
export async function preview(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.previewParams,
  body: AntFlowAPI.PreviewQuery,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.OptionPage>(
    `/api/runtime/form-options/preview/${param0}`,
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

/** 此处后端没有提供注释 POST /api/runtime/form-options/query */
export async function query(
  body: AntFlowAPI.OptionQuery,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.OptionPage>("/api/runtime/form-options/query", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
