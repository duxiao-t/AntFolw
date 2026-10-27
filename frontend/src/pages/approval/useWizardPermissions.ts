import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Form, message } from 'antd';
import type { FormInstance } from 'antd';
import { useEffect } from 'react';
import type { MutableRefObject } from 'react';
import type { GrantUser } from './FormGrantUserPicker';
import type { FormGrant, FormGrantCandidates, FormMaintainers } from './wizardTypes';

type Params = {
  form: FormInstance;
  formId: number | null;
  isNew: boolean;
  isAdmin: boolean;
  canManageGrants: boolean;
  /** `@@initialState` 的 currentUser 没有生成类型，壳里也是 `as any`；这里保持同一口径。 */
  currentUser?: any;
  /** 回填哨兵：放在壳里是因为它们必须跨步骤切换存活，随步骤组件卸载会重跑回填。 */
  syncedGrantKey: MutableRefObject<string | null>;
  syncedMaintainerFormId: MutableRefObject<number | null>;
  grantVersionRef: MutableRefObject<number | null>;
};

const sameIds = (left?: number[], right?: number[]) =>
  JSON.stringify([...(left ?? [])].sort((a, b) => a - b))
    === JSON.stringify([...(right ?? [])].sort((a, b) => a - b));

/**
 * 表单使用范围与维护人员：服务端 → Form 的一次性回填、未保存状态、以及两处保存。
 *
 * <p>回填只做一次（用 ref 记住"已经回填过哪张表单"），因为服务端刷新会换出新的 data 对象，
 * 按对象依赖重跑会把用户没保存的授权改动冲回旧值。
 */
export function useWizardPermissions({
  form,
  formId,
  isNew,
  isAdmin,
  canManageGrants,
  currentUser,
  syncedGrantKey,
  syncedMaintainerFormId,
  grantVersionRef,
}: Params) {
  const qc = useQueryClient();
  const watchedAllCompany = Form.useWatch('allCompany', form);
  const watchedUserIds = Form.useWatch('userIds', form);
  const watchedRoleIds = Form.useWatch('roleIds', form);
  const watchedDepartmentIds = Form.useWatch('departmentIds', form);

  const { data: formGrant } = useQuery<FormGrant>({
    queryKey: ['form-management-grant', formId],
    queryFn: () => request<FormGrant>(`/api/forms/${formId}/grants`),
    enabled: !!formId && canManageGrants,
  });

  const { data: formMaintainers } = useQuery<FormMaintainers>({
    queryKey: ['form-management-maintainers', formId],
    queryFn: () => request<FormMaintainers>(`/api/forms/${formId}/maintainers`),
    enabled: !!formId && canManageGrants,
  });

  const { data: grantCandidates } = useQuery<FormGrantCandidates>({
    queryKey: ['form-management-grant-candidates', formId ?? 'new'],
    queryFn: () =>
      request<FormGrantCandidates>(
        formId
          ? `/api/forms/${formId}/grants/candidates`
          : '/api/forms/grant-candidates',
      ),
    enabled: canManageGrants,
  });
  const allCompanyRoleId = grantCandidates?.roles.find((role) => role.code === 'employee')?.id;
  const visibilitySummary = allCompanyRoleId && formGrant?.roleIds.includes(allCompanyRoleId)
    ? '全体在职员工'
    : [
        formGrant?.departmentIds.length ? `${formGrant.departmentIds.length} 个部门` : '',
        formGrant?.roleIds.length ? `${formGrant.roleIds.length} 个角色` : '',
        formGrant?.userIds.length ? `${formGrant.userIds.length} 名人员` : '',
      ].filter(Boolean).join('、') || '仅创建人';
  // 「全体员工」是个开关，看着像点一下就生效，但这一整段要按「保存使用范围」才落库。
  // 没有提示的话，切走页面就会被当成"打开了又自己变回去"。所以把未保存状态写出来。
  const grantDirty = !!formGrant && (
    !sameIds(watchedUserIds, formGrant.userIds)
    || !sameIds(watchedRoleIds, formGrant.roleIds.filter((id) => id !== allCompanyRoleId))
    || !sameIds(watchedDepartmentIds, formGrant.departmentIds)
    || (!!allCompanyRoleId
      && watchedAllCompany !== formGrant.roleIds.includes(allCompanyRoleId))
  );
  const initialGrantUsers: GrantUser[] =
    formGrant?.users ??
    (currentUser?.id
      ? [
          {
            id: currentUser.id,
            username: currentUser.username ?? '',
            displayName:
              currentUser.displayName ??
              currentUser.name ??
              currentUser.username,
            employeeNo: currentUser.employeeNo,
            departmentId: currentUser.departmentId,
          },
        ]
      : []);
  const initialMaintainerUsers: GrantUser[] =
    formMaintainers?.users ?? initialGrantUsers;

  // 保存授权用的乐观锁版本由壳持有（换表单时要一起清空），这里只读不建。
  useEffect(() => {
    if (formGrant && !grantDirty) grantVersionRef.current = formGrant.version;
  }, [formGrant, grantDirty, grantVersionRef]);

  // ⚠ 这一段只在「换表单」或「这一张表单还没回填过」时跑，绝不能因为数据刷新就重跑：
  // 使用范围的四个字段（人员/角色/部门/全体员工）要按「保存使用范围」才落库，中间是用户的
  // 未保存编辑。而保存维护人员与保存使用范围共用同一个版本计数器（saveBasic 里断言两者
  // version 必须相等），所以任何一次保存、以及 react-query 默认的 refetchOnWindowFocus 都会
  // 换出新的 data 对象——用对象当依赖会让回填重跑，把「全体员工」这类改动冲回服务端旧值，
  // 看起来就是"打开了又自己变回未启用"。
  const grantInitKey = `${formId ?? 'new'}:${allCompanyRoleId ?? ''}`;

  useEffect(() => {
    if (canManageGrants && formGrant && syncedGrantKey.current !== grantInitKey) {
      // 用户已经改过就不覆盖：宁可让保存时报版本冲突，也不要悄悄丢掉他填的东西。
      if (syncedGrantKey.current === null || !grantDirty) {
        syncedGrantKey.current = grantInitKey;
        form.setFieldsValue({
          userIds: formGrant.userIds,
          // 非管理员也按服务端回填：早先这里给非管理员写死 []，于是 grantDirty 永远为真
          // （[] 对比服务端的角色清单），页面上恒显「有未保存的修改」，而且一旦点保存会把
          // 现有角色授权整片抹掉。候选接口与 canManageGrants 同门禁，非管理员同样拿得到
          // employee 角色 id（表示全体员工），过滤逻辑不需要分角色。
          roleIds: formGrant.roleIds.filter((id) => id !== allCompanyRoleId),
          allCompany: !!allCompanyRoleId && formGrant.roleIds.includes(allCompanyRoleId),
          departmentIds: formGrant.departmentIds,
        });
      }
    }
    // 维护人员同理：只回填一次，否则刷新一下就把没保存的人员选择冲回去。
    if (canManageGrants && formMaintainers && syncedMaintainerFormId.current !== formId) {
      syncedMaintainerFormId.current = formId;
      form.setFieldValue('maintainerIds', formMaintainers.userIds);
    } else if (
      canManageGrants &&
      isNew &&
      (form.getFieldValue('userIds') === undefined ||
        form.getFieldValue('maintainerIds') === undefined)
    ) {
      form.setFieldsValue({
        userIds: currentUser?.id ? [currentUser.id] : [],
        maintainerIds: currentUser?.id ? [currentUser.id] : [],
        roleIds: [],
        allCompany: false,
        departmentIds: [],
      });
    }
  }, [
    allCompanyRoleId,
    canManageGrants,
    currentUser?.id,
    form,
    formGrant,
    formMaintainers,
    formId,
    grantDirty,
    grantInitKey,
    isNew,
    syncedGrantKey,
    syncedMaintainerFormId,
  ]);

  const saveUsageScope = useMutation({
    mutationFn: async () => {
      if (!formId || !formGrant) throw new Error('请先保存表单属性');
      const values = await form.validateFields(['userIds', 'roleIds', 'departmentIds', 'allCompany']);
      let roleIds = Array.isArray(values.roleIds) ? values.roleIds : formGrant.roleIds;
      if (isAdmin && allCompanyRoleId) {
        roleIds = values.allCompany
          ? [...new Set([...roleIds, allCompanyRoleId])]
          : roleIds.filter((roleId: number) => roleId !== allCompanyRoleId);
      }
      return request<FormGrant>(`/api/forms/${formId}/grants`, {
        method: 'PUT',
        data: {
          // 用快照版本：有未保存编辑时它就是"我读到的那一版"，服务端凭它判冲突，
          // 而不是拿刚被焦点刷新过的新版本去覆盖别人的改动。
          version: grantVersionRef.current ?? formGrant.version,
          userIds: Array.isArray(values.userIds) ? values.userIds : formGrant.userIds,
          roleIds,
          departmentIds: Array.isArray(values.departmentIds)
            ? values.departmentIds : formGrant.departmentIds,
        },
      });
    },
    onSuccess: async () => {
      message.success('使用范围已保存，无需重新发布表单');
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['form-management-grant', formId] }),
        qc.invalidateQueries({ queryKey: ['form-management-maintainers', formId] }),
      ]);
    },
    onError: (error: any) => message.error(error?.message ?? '使用范围保存失败'),
  });

  const saveMaintainers = useMutation({
    mutationFn: async () => {
      if (!formId || !formMaintainers) throw new Error('请先保存表单属性');
      const values = await form.validateFields(['maintainerIds']);
      return request<FormMaintainers>(`/api/forms/${formId}/maintainers`, {
        method: 'PUT',
        data: { version: formMaintainers.version, userIds: values.maintainerIds },
      });
    },
    onSuccess: async () => {
      message.success('维护人员已保存');
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['form-management-grant', formId] }),
        qc.invalidateQueries({ queryKey: ['form-management-maintainers', formId] }),
      ]);
    },
    onError: (error: any) => message.error(error?.message ?? '维护人员保存失败'),
  });

  return {
    allCompanyRoleId,
    allCompany: !!watchedAllCompany,
    grantCandidates,
    grantDirty,
    formGrant,
    initialGrantUsers,
    initialMaintainerUsers,
    maintainerCount: formMaintainers?.userIds.length ?? 1,
    saveMaintainers,
    saveUsageScope,
    visibilitySummary,
  };
}

export type WizardPermissions = ReturnType<typeof useWizardPermissions>;
