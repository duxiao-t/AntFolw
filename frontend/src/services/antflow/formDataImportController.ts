// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 POST /api/forms/data/import */
export async function importData(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.importDataParams,
  body: {},
  file?: File,
  options?: { [key: string]: any }
) {
  const formData = new FormData();

  if (file) {
    formData.append("file", file);
  }

  Object.keys(body).forEach((ele) => {
    const item = (body as any)[ele];

    if (item !== undefined && item !== null) {
      if (typeof item === "object" && !(item instanceof File)) {
        if (item instanceof Array) {
          item.forEach((f) => formData.append(ele, f || ""));
        } else {
          formData.append(
            ele,
            new Blob([JSON.stringify(item)], { type: "application/json" })
          );
        }
      } else {
        formData.append(ele, item);
      }
    }
  });

  return request<AntFlowAPI.ImportOutcome>("/api/forms/data/import", {
    method: "POST",
    params: {
      ...params,
    },
    data: formData,
    requestType: "form",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/forms/data/import/inspect */
export async function inspect1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.inspect1Params,
  body: {},
  file?: File,
  options?: { [key: string]: any }
) {
  const formData = new FormData();

  if (file) {
    formData.append("file", file);
  }

  Object.keys(body).forEach((ele) => {
    const item = (body as any)[ele];

    if (item !== undefined && item !== null) {
      if (typeof item === "object" && !(item instanceof File)) {
        if (item instanceof Array) {
          item.forEach((f) => formData.append(ele, f || ""));
        } else {
          formData.append(
            ele,
            new Blob([JSON.stringify(item)], { type: "application/json" })
          );
        }
      } else {
        formData.append(ele, item);
      }
    }
  });

  return request<AntFlowAPI.ImportInspection>(
    "/api/forms/data/import/inspect",
    {
      method: "POST",
      params: {
        ...params,
      },
      data: formData,
      requestType: "form",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/forms/data/import/manual */
export async function manual(
  body: AntFlowAPI.ManualImportRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.ImportOutcome>("/api/forms/data/import/manual", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/forms/data/import/template */
export async function template(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.templateParams,
  options?: { [key: string]: any }
) {
  return request<string>("/api/forms/data/import/template", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
