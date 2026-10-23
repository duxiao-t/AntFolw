import { request, useModel } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';

export type CompanyOption = { id: number; name: string };

/**
 * 公司归属：有部门的账号由服务端按身份推导；**没有部门的全局管理员**必须显式选一个，
 * 否则后端会以 `BAD_COMPANY` 拒绝（导入与发起流程同一套规则）。
 *
 * <p>判定口径与 `pages/runtime/form/Fill.tsx` 保持一致——两处如果写法不同，
 * 会出现"填单能提交、导入却被拒"这种很难解释的现象。
 */
export function useCompanyAttribution() {
  const currentUser = useModel('@@initialState')?.initialState?.currentUser as
    | { departmentId?: unknown }
    | undefined;
  const departmentId = Number(currentUser?.departmentId);
  const needsCompany = currentUser != null && !(Number.isInteger(departmentId) && departmentId > 0);

  const { data: companies = [], isError } = useQuery<CompanyOption[]>({
    queryKey: ['companies'],
    queryFn: () => request('/api/companies').then((r: any) => r ?? []),
    enabled: needsCompany,
    retry: 0,
  });

  return { needsCompany, companies, loadFailed: isError };
}
