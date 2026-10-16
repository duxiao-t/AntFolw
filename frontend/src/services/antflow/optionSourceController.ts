// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/forms/${param0}/option-sources */
export async function bindable(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.bindableParams,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.BindableSource[]>(
    `/api/forms/${param0}/option-sources`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/option-sources */
export async function list3(options?: { [key: string]: any }) {
  return request<AntFlowAPI.SourceSummary[]>("/api/option-sources", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/option-sources */
export async function create4(
  body: AntFlowAPI.SourceWrite,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.SourceDetail>("/api/option-sources", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/option-sources/${param0} */
export async function detail(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.detailParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.SourceDetail>(`/api/option-sources/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/option-sources/${param0} */
export async function delete4(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.delete4Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/option-sources/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/disable */
export async function disable(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.disableParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.SourceSummary>(
    `/api/option-sources/${param0}/disable`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/enable */
export async function enable(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.enableParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.SourceSummary>(
    `/api/option-sources/${param0}/enable`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 PUT /api/option-sources/${param0}/forms */
export async function forms(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.formsParams,
  body: AntFlowAPI.FormRefWrite,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.SourceDetail>(
    `/api/option-sources/${param0}/forms`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 PUT /api/option-sources/${param0}/grants */
export async function grants(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.grantsParams,
  body: AntFlowAPI.GrantWrite,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.SourceDetail>(
    `/api/option-sources/${param0}/grants`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 DELETE /api/option-sources/${param0}/versions/${param1} */
export async function discardVersion(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.discardVersionParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<any>(`/api/option-sources/${param0}/versions/${param1}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/option-sources/${param0}/versions/${param1}/diff */
export async function diff(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.diffParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.VersionDiff>(
    `/api/option-sources/${param0}/versions/${param1}/diff`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/versions/${param1}/disable */
export async function disableVersion(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.disableVersionParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.VersionView>(
    `/api/option-sources/${param0}/versions/${param1}/disable`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/versions/${param1}/enable */
export async function enableVersion(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.enableVersionParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.VersionView>(
    `/api/option-sources/${param0}/versions/${param1}/enable`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/versions/${param1}/publish */
export async function publish1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.publish1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.VersionView>(
    `/api/option-sources/${param0}/versions/${param1}/publish`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/option-sources/${param0}/versions/${param1}/rows */
export async function rows(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.rowsParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<Record<string, any>[]>(
    `/api/option-sources/${param0}/versions/${param1}/rows`,
    {
      method: "GET",
      params: {
        // limit has a default value: 20
        limit: "20",
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/versions/${param1}/unpublish */
export async function unpublish(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.unpublishParams,
  options?: { [key: string]: any }
) {
  const { id: param0, versionId: param1, ...queryParams } = params;
  return request<AntFlowAPI.VersionView>(
    `/api/option-sources/${param0}/versions/${param1}/unpublish`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/${param0}/versions/import */
export async function importDraft(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.importDraftParams,
  body: {},
  file?: File,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
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

  return request<AntFlowAPI.VersionView>(
    `/api/option-sources/${param0}/versions/import`,
    {
      method: "POST",
      params: {
        ...queryParams,
      },
      data: formData,
      requestType: "form",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/option-sources/inspect */
export async function inspect(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.inspectParams,
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

  return request<AntFlowAPI.ImportPreview>("/api/option-sources/inspect", {
    method: "POST",
    params: {
      ...params,
    },
    data: formData,
    requestType: "form",
    ...(options || {}),
  });
}
