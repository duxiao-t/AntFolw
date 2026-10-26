import { request } from '@umijs/max';
import { useMutation, useQuery } from '@tanstack/react-query';
import { App, Form } from 'antd';
import { useMemo, useState } from 'react';
import type { Dept, ManagerCandidate, MemberItem } from './Contacts.types';
import { normalizeGender } from './Contacts.utils';

/**
 * 成员编辑弹窗 + 重置密码弹窗。两者的状态必须同一个宿主：编辑弹窗里点"重置密码"
 * 要先把编辑框关掉再把同一个成员交给密码框，分两个 hook 就得在页面壳里做接力。
 */
export function useContactsMemberDialogs({ selDeptId, canAdmin, refreshMembers }: {
  selDeptId: number | null;
  canAdmin: boolean;
  refreshMembers: () => Promise<void>;
}) {
  const { message: msg } = App.useApp();
  const [memberForm] = Form.useForm();
  const [passwordForm] = Form.useForm();
  const [memberOpen, setMemberOpen] = useState(false);
  const [memberEdit, setMemberEdit] = useState<MemberItem | null>(null);
  const [managerKeyword, setManagerKeyword] = useState('');
  const [passwordTarget, setPasswordTarget] = useState<MemberItem | null>(null);

  const managerDepartmentId = Form.useWatch('deptId', { form: memberForm, preserve: true });
  const memberDepartments = useQuery<Dept[]>({
    queryKey: ['depts'],
    queryFn: () => request('/api/departments'),
    enabled: memberOpen,
  });
  const { data: managerCandidates = [], isFetching: managerCandidatesLoading } = useQuery<ManagerCandidate[]>({
    queryKey: ['manager-candidates', managerDepartmentId, memberEdit?.id, managerKeyword],
    queryFn: () => request('/api/users/manager-candidates', { params: {
      deptId: managerDepartmentId,
      excludeUserId: memberEdit?.id,
      keyword: managerKeyword || undefined,
    } }),
    enabled: memberOpen && !!managerDepartmentId,
  });

  const managerOptions = useMemo(() => {
    const options = managerCandidates.map((candidate) => ({
      value: candidate.id,
      label: `${candidate.displayName}（${candidate.employeeNo}）`,
    }));
    if (memberEdit?.managerId && !options.some((option) => option.value === memberEdit.managerId)) {
      options.unshift({
        value: memberEdit.managerId,
        label: memberEdit.managerDisplayName ?? `用户 ${memberEdit.managerId}`,
      });
    }
    return options;
  }, [managerCandidates, memberEdit]);

  // --- member CRUD ---
  const memberCreate = useMutation({
    mutationFn: (body: any) => request('/api/users', { method: 'POST', data: body }),
    onSuccess: async () => { await refreshMembers(); msg.success('添加成功'); },
  });
  const memberUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/users/${id}`, { method: 'PUT', data: body }),
    onSuccess: async () => { await refreshMembers(); msg.success('已更新'); },
  });
  const memberPasswordReset = useMutation({
    mutationFn: ({ id, newPassword }: { id: number; newPassword: string }) =>
      request(`/api/users/${id}/password`, { method: 'PUT', data: { newPassword } }),
    onSuccess: () => { msg.success('密码已重置，用户的旧会话已失效'); },
  });

  const openAdd = () => {
    setMemberEdit(null);
    setManagerKeyword('');
    memberForm.resetFields();
    memberForm.setFieldsValue({ deptId: selDeptId });
    setMemberOpen(true);
  };
  const openEdit = (member: MemberItem) => {
    setMemberEdit(member);
    setManagerKeyword('');
    setMemberOpen(true);
    memberForm.setFieldsValue({ ...member, gender: normalizeGender(member.gender) });
  };
  const closeMember = () => { setMemberOpen(false); setMemberEdit(null); memberForm.resetFields(); };

  const handleMemberOk = async () => {
    try {
      const v = await memberForm.validateFields();
      const { confirmPassword: _confirmPassword, ...payload } = v;
      payload.managerId = payload.managerId ?? null;
      if (memberEdit) await memberUpdate.mutateAsync({ id: memberEdit.id, ...payload });
      else await memberCreate.mutateAsync(payload);
      setMemberOpen(false); setMemberEdit(null); memberForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };

  const openPasswordReset = (member: MemberItem) => { passwordForm.resetFields(); setPasswordTarget(member); };
  const closePassword = () => { setPasswordTarget(null); passwordForm.resetFields(); };
  // 编辑弹窗里的"重置密码"：先关编辑框，再把同一个成员交给密码框。
  const resetPasswordFromEditor = () => {
    if (!memberEdit) return;
    setMemberOpen(false);
    passwordForm.resetFields();
    setPasswordTarget(memberEdit);
  };

  const handlePasswordReset = async () => {
    if (!passwordTarget) return;
    try {
      const values = await passwordForm.validateFields();
      await memberPasswordReset.mutateAsync({
        id: passwordTarget.id,
        newPassword: values.newPassword,
      });
      setPasswordTarget(null);
      passwordForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };

  return {
    memberForm,
    memberOpen,
    memberEdit,
    managerKeyword,
    setManagerKeyword,
    managerOptions,
    managerCandidatesLoading,
    memberDepartments,
    memberDepartmentsReady: (memberDepartments.data ?? []).map((department) => ({
      id: department.id, pId: department.parentId ?? 0, value: department.id, title: department.name,
      selectable: !department.contextOnly && !!department.canManageUsers,
    })),
    saving: memberCreate.isPending || memberUpdate.isPending,
    openAdd,
    openEdit,
    closeMember,
    handleMemberOk,
    // 密码
    passwordForm,
    passwordTarget,
    passwordSaving: memberPasswordReset.isPending,
    openPasswordReset,
    resetPasswordFromEditor,
    closePassword,
    handlePasswordReset,
  };
}
