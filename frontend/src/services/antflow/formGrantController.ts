// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/forms/${param0}/grants */
export async function get1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.get1Params,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormGrantDto>(`/api/forms/${param0}/grants`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/forms/${param0}/grants */
export async function replace1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.replace1Params,
  body: AntFlowAPI.FormGrantWriteRequest,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormGrantDto>(`/api/forms/${param0}/grants`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/forms/${param0}/grants/candidates */
export async function candidates(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.candidatesParams,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormGrantCandidates>(
    `/api/forms/${param0}/grants/candidates`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/forms/${param0}/grants/user-candidates */
export async function userCandidates(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.userCandidatesParams,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.GrantUserPage>(
    `/api/forms/${param0}/grants/user-candidates`,
    {
      method: "GET",
      params: {
        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",

        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/forms/${param0}/maintainers */
export async function maintainers(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.maintainersParams,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormMaintainerDto>(
    `/api/forms/${param0}/maintainers`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 PUT /api/forms/${param0}/maintainers */
export async function replaceMaintainers(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.replaceMaintainersParams,
  body: AntFlowAPI.FormMaintainerWriteRequest,
  options?: { [key: string]: any }
) {
  const { formId: param0, ...queryParams } = params;
  return request<AntFlowAPI.FormMaintainerDto>(
    `/api/forms/${param0}/maintainers`,
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

/** 此处后端没有提供注释 GET /api/forms/grant-candidates */
export async function candidates1(options?: { [key: string]: any }) {
  return request<AntFlowAPI.FormGrantCandidates>(
    "/api/forms/grant-candidates",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/forms/grant-user-candidates */
export async function userCandidates1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.userCandidates1Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.GrantUserPage>("/api/forms/grant-user-candidates", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}
