// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/pickers/departments */
export async function departments(options?: { [key: string]: any }) {
  return request<AntFlowAPI.MobilePickerDepartmentDto[]>(
    "/api/pickers/departments",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/pickers/roles */
export async function roles1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.roles1Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.PickerRoleDto[]>("/api/pickers/roles", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/pickers/roles/selected */
export async function selectedRoles(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.selectedRolesParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.PickerRoleDto[]>("/api/pickers/roles/selected", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/pickers/users */
export async function users1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.users1Params,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.RuntimePickerUserDto[]>("/api/pickers/users", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/pickers/users/${param0} */
export async function user(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.userParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.MobilePickerUserDto>(
    `/api/pickers/users/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/pickers/users/selected */
export async function selectedUsers(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.selectedUsersParams,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.MobilePickerUserDto[]>(
    "/api/pickers/users/selected",
    {
      method: "GET",
      params: {
        ...params,
      },
      ...(options || {}),
    }
  );
}
