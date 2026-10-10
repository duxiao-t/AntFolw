// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/security/effective/users/${param0} */
export async function effective(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.effectiveParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.EffectivePermissionDto>(
    `/api/security/effective/users/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/security/permissions */
export async function permissions(options?: { [key: string]: any }) {
  return request<AntFlowAPI.PermissionDto[]>("/api/security/permissions", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/security/role-department-candidates */
export async function roleDepartmentCandidates(options?: {
  [key: string]: any;
}) {
  return request<AntFlowAPI.DepartmentCandidate[]>(
    "/api/security/role-department-candidates",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/security/roles */
export async function roles(options?: { [key: string]: any }) {
  return request<AntFlowAPI.RoleDto[]>("/api/security/roles", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/security/roles */
export async function create2(
  body: AntFlowAPI.RoleWriteRequest,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.RoleDto>("/api/security/roles", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/security/roles/${param0} */
export async function role(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.roleParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.RoleDto>(`/api/security/roles/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/security/roles/${param0} */
export async function update1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.update1Params,
  body: AntFlowAPI.RoleWriteRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.RoleDto>(`/api/security/roles/${param0}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /api/security/roles/${param0} */
export async function delete2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.delete2Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/security/roles/${param0}`, {
    method: "DELETE",
    params: {
      ...queryParams,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/security/users */
export async function users(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.usersParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.UserRolePage>("/api/security/users", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 50
      size: "50",
      ...params,
    },
    ...(options || {}),
  });
}
