// @ts-ignore
/* eslint-disable */
import { request } from "@umijs/max";

/** 此处后端没有提供注释 GET /api/public/auth/oidc/${param0}/authorize */
export async function authorize1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.authorize1Params,
  options?: { [key: string]: any }
) {
  const { code: param0, ...queryParams } = params;
  return request<any>(`/api/public/auth/oidc/${param0}/authorize`, {
    method: "GET",
    params: {
      ...queryParams,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/public/auth/oidc/${param0}/callback */
export async function callback1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.callback1Params,
  options?: { [key: string]: any }
) {
  const { provider: param0, ...queryParams } = params;
  return request<any>(`/api/public/auth/oidc/${param0}/callback`, {
    method: "GET",
    params: {
      ...queryParams,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/public/auth/providers */
export async function publicProviders(options?: { [key: string]: any }) {
  return request<AntFlowAPI.PublicProvider[]>("/api/public/auth/providers", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/security/identity-providers */
export async function providers(options?: { [key: string]: any }) {
  return request<AntFlowAPI.ProviderDto[]>("/api/security/identity-providers", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/security/identity-providers */
export async function create3(
  body: AntFlowAPI.SaveProvider,
  options?: { [key: string]: any }
) {
  return request<AntFlowAPI.ProviderDto>("/api/security/identity-providers", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /api/security/identity-providers/${param0} */
export async function update2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.update2Params,
  body: AntFlowAPI.SaveProvider,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.ProviderDto>(
    `/api/security/identity-providers/${param0}`,
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

/** 此处后端没有提供注释 DELETE /api/security/identity-providers/${param0} */
export async function delete3(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.delete3Params,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/security/identity-providers/${param0}`, {
    method: "DELETE",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/security/identity-providers/${param0}/bindings */
export async function bindings(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.bindingsParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<AntFlowAPI.BindingDto[]>(
    `/api/security/identity-providers/${param0}/bindings`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 DELETE /api/security/identity-providers/${param0}/bindings/${param1} */
export async function unbind(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: AntFlowAPI.unbindParams,
  options?: { [key: string]: any }
) {
  const { providerId: param0, bindingId: param1, ...queryParams } = params;
  return request<any>(
    `/api/security/identity-providers/${param0}/bindings/${param1}`,
    {
      method: "DELETE",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}
