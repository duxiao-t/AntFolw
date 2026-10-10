// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/mobile/drafts */
export async function drafts(options?: { [key: string]: any }) {
  return request<AntFlowAPI.MobileDraftDto[]>("/api/mobile/drafts", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/drafts */
export async function createDraft(
  body: AntFlowAPI.MobileDraftRequest,
  options?: { [key: string]: any }
) {
  return request<number>("/api/mobile/drafts", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/drafts/${param0} */
export async function draft(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.draftParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileDraftDto>(`/api/mobile/drafts/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/mobile/drafts/${param0} */
export async function updateDraft(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.updateDraftParams,
  body: AntFlowAPI.MobileDraftRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileDraftDto>(`/api/mobile/drafts/${param0}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/mobile/drafts/${param0} */
export async function deleteDraft(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.deleteDraftParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/drafts/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/forms/${param0} */
export async function form(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.formParams,
  options?: { [key: string]: any }
) {
  const { code: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileFormDto>(`/api/mobile/forms/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/forms/${param0}/approval-preview */
export async function approvalPreview(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.approvalPreviewParams,
  body: AntFlowAPI.ApprovalPreviewRequest,
  options?: { [key: string]: any }
) {
  const { code: param0, ...queryParams } = params;
  return request<AntFlowAPI.ApprovalPreviewDto>(
    `/api/mobile/forms/${param0}/approval-preview`,
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

/** 此处后端没有提供注释 GET /api/mobile/initiated */
export async function initiated(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.initiatedParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobilePageDtoMobileInitiatedDto>(
    "/api/mobile/initiated",
    {
      method: "GET",
      params: {
        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",

        ...params,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/mobile/instances */
export async function instances(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.instancesParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobilePageDtoMobileInstanceDto>(
    "/api/mobile/instances",
    {
      method: "GET",
      params: {
        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",

        ...params,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/mobile/instances */
export async function start(
  body: AntFlowAPI.StartMobileInstanceRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobileStartResult>("/api/mobile/instances", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/instances/${param0} */
export async function instance(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.instanceParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileInstanceDetailDto>(
    `/api/mobile/instances/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/mobile/instances/${param0}/withdraw */
export async function withdraw1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.withdraw1Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/instances/${param0}/withdraw`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/rework-tasks/${param0} */
export async function reworkTask(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.reworkTaskParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ReworkTaskDto>(
    `/api/mobile/rework-tasks/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 PUT /api/mobile/rework-tasks/${param0} */
export async function saveReworkTask(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.saveReworkTaskParams,
  body: AntFlowAPI.ReworkTaskRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ReworkTaskDto>(
    `/api/mobile/rework-tasks/${param0}`,
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

/** 此处后端没有提供注释 POST /api/mobile/rework-tasks/${param0}/resubmit */
export async function resubmitReworkTask(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.resubmitReworkTaskParams,
  body: AntFlowAPI.ReworkTaskRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ReworkResult>(
    `/api/mobile/rework-tasks/${param0}/resubmit`,
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

/** 此处后端没有提供注释 POST /api/mobile/submissions */
export async function submitDirect(
  body: AntFlowAPI.DirectSubmitRequest,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/mobile/submissions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/submissions/${param0} */
export async function submission(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.submissionParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileDirectSubmissionDetailDto>(
    `/api/mobile/submissions/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/mobile/tasks */
export async function tasks(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.tasksParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobilePageDtoMobileTaskDto>("/api/mobile/tasks", {
    method: "GET",
    params: {
      // view has a default value: pending
      view: "pending",
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/mobile/tasks/${param0} */
export async function task(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.taskParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobileTaskDetailDto>(
    `/api/mobile/tasks/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/mobile/tasks/${param0}/approve */
export async function approve1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.approve1Params,
  body: AntFlowAPI.MobileTaskActionRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/tasks/${param0}/approve`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/tasks/${param0}/read */
export async function markTaskRead(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.markTaskReadParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/tasks/${param0}/read`, {
    method: "POST",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/mobile/tasks/${param0}/reject */
export async function reject1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.reject1Params,
  body: AntFlowAPI.MobileTaskActionRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/mobile/tasks/${param0}/reject`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}
