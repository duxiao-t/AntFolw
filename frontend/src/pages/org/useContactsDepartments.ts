import { request } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Form } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Dept, UserPage } from './Contacts.types';
import { departmentPathNames, resolveDepartmentDropAction } from './Contacts.utils';

/** 左树节点菜单的动作集合：左栏只负责"点哪一项"，落到哪个接口、清不清选中都在本 hook 里。 */
export interface DeptNodeActions {
  onAddChild: (deptId: number) => void;
  onRename: (deptId: number) => void;
  onSetLeader: (deptId: number) => void;
  onMoveUp: (deptId: number) => void;
  onMoveDown: (deptId: number) => void;
  onRemove: (deptId: number) => void;
}

/**
 * 部门域：列表查询、树与索引派生、六个写操作、拖拽落点，以及部门弹窗/负责人弹窗的状态。
 *
 * 写操作与弹窗必须在同一个 hook：建部门、改名、设负责人都落到 `PUT/POST /api/departments`，
 * 失效链（`['depts']`、`['dept-path']`）只写一份，拆开就会出现"一处刷新、另一处忘刷"。
 */
export function useContactsDepartments({ selDeptId, onSelectDept, onLeaderSaved }: {
  selDeptId: number | null;
  onSelectDept: (deptId: number | null) => void;
  /** 设置负责人后成员表的"负责人"标记要重算，原来顺手把成员页打回第 1 页。 */
  onLeaderSaved: () => void;
}) {
  const { message: msg, modal } = App.useApp();
  const qc = useQueryClient();

  const [deptForm] = Form.useForm();
  const [deptAddOpen, setDeptAddOpen] = useState(false);           // top "+"
  const [deptAddParentId, setDeptAddParentId] = useState<number | null>(null); // for sub-dept
  const [deptEditOpen, setDeptEditOpen] = useState(false);
  const [deptEditId, setDeptEditId] = useState<number | null>(null);
  const [leaderOpen, setLeaderOpen] = useState(false);
  const [leaderDeptId, setLeaderDeptId] = useState<number | null>(null);

  const { data: deptList = [], isError: departmentsError, refetch: refetchDepartments } = useQuery<Dept[]>({
    queryKey: ['depts'],
    queryFn: () => request('/api/departments'),
  });

  const { data: leaderResult } = useQuery<UserPage>({
    queryKey: ['leader-users'],
    queryFn: () => request('/api/users/page', { params: { page: 1, size: 100 } }),
    enabled: leaderOpen,
  });

  // --- tree data ---
  const treeData = useMemo(() => {
    const byParent: Record<number, DataNode[]> = {};
    for (const d of deptList) {
      const key = d.parentId ?? 0;
      if (!byParent[key]) byParent[key] = [];
      byParent[key].push({ title: d.name, key: d.id, children: [] });
    }
    const walk = (n: DataNode): DataNode => {
      n.children = (byParent[n.key as number] ?? []).map(walk);
      return n;
    };
    return (byParent[0] ?? []).map(walk);
  }, [deptList]);

  const deptParentById = useMemo(() => {
    const map: Record<number, number | null> = {};
    for (const d of deptList) map[d.id] = d.parentId ?? null;
    return map;
  }, [deptList]);

  const deptNameById = useMemo(() => {
    const map: Record<number, string> = {};
    for (const d of deptList) map[d.id] = d.name;
    return map;
  }, [deptList]);

  const canManageUsersByDept = useMemo(() => Object.fromEntries(
    deptList.map((department) => [department.id, !!department.canManageUsers]),
  ), [deptList]);

  const selectedDepartment = useMemo(
    () => deptList.find((department) => department.id === selDeptId),
    [deptList, selDeptId],
  );

  // 从**已授权的部门列表**推导，不再请求 /api/departments/{id}/path：那条只认
  // org:department:read，而左树是它与 org:user:read 的并集——树里点得到、面包屑却 403
  // （点跨部门搜索结果跳过去时最容易撞上）。祖先本来就带 contextOnly 在树里。
  const breadcrumb = useMemo(
    () => departmentPathNames(deptList, selDeptId).join(' / ') || '请选择部门',
    [deptList, selDeptId],
  );

  // --- dept CRUD ---
  const deptCreate = useMutation({
    mutationFn: (body: any) => request('/api/departments', { method: 'POST', data: body }),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('部门已创建'); },
  });
  const deptUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/departments/${id}`, { method: 'PUT', data: body }),
    onSuccess: async () => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['depts'] }),
        qc.invalidateQueries({ queryKey: ['dept-path'] }),
      ]);
      msg.success('部门已更新');
    },
  });
  const deptMove = useMutation({
    mutationFn: ({ id, parentId }: { id: number; parentId: number | null }) =>
      request(`/api/departments/${id}`, { method: 'PUT', data: { parentId } }),
    onSuccess: async () => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['depts'] }),
        qc.invalidateQueries({ queryKey: ['dept-path'] }),
      ]);
      msg.success('部门已移动');
    },
  });
  const deptMoveOrder = useMutation({
    mutationFn: ({ id, direction }: { id: number; direction: 'UP' | 'DOWN' }) =>
      request(`/api/departments/${id}/order`, { method: 'PUT', data: { direction } }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['depts'] });
      msg.success('排序已更新');
    },
  });
  const deptMovePosition = useMutation({
    mutationFn: ({ id, targetId, placement }: { id: number; targetId: number; placement: 'BEFORE' | 'AFTER' }) =>
      request(`/api/departments/${id}/position`, { method: 'PUT', data: { targetId, placement } }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['depts'] });
      msg.success('排序已更新');
    },
  });
  const deptRemove = useMutation({
    mutationFn: (id: number) => request(`/api/departments/${id}`, { method: 'DELETE' }),
    onSuccess: async () => { onSelectDept(null); await qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('已删除'); },
  });

  const removeDept = (deptId: number) => {
    const dept = deptList.find((item) => item.id === deptId);
    modal.confirm({
      title: `确认删除「${dept?.name ?? deptId}」?`,
      content: '删除前需先处理子部门及部门成员，删除后不可恢复',
      okType: 'danger',
      onOk: () => deptRemove.mutate(deptId),
    });
  };

  // --- tree drop ---
  const onDrop = useCallback((info: any) => {
    const dragId = Number(info.dragNode?.key ?? info.dragNode?.props?.eventKey);
    const dropId = Number(info.node?.key ?? info.node?.props?.eventKey);
    const dropNodeIndex = Number(String(info.node?.pos ?? '').split('-').pop() ?? 0);
    const relativeDropPosition = Number(info.dropPosition) - dropNodeIndex;
    const action = resolveDepartmentDropAction({
      dragId,
      dropId,
      dropToGap: !!info.dropToGap,
      relativeDropPosition,
      currentParentId: deptParentById[dragId] ?? null,
      parentById: deptParentById,
    });
    if (action.type === 'sort') {
      deptMovePosition.mutate({ id: dragId, targetId: action.targetId, placement: action.placement });
      return;
    }
    if (action.type === 'none') {
      return;
    }
    deptMove.mutate({ id: dragId, parentId: action.parentId });
  }, [deptMove, deptMovePosition, deptParentById]);

  // ---- dept form handlers ----
  const openAdd = (parentId: number | null) => { setDeptAddParentId(parentId); setDeptAddOpen(true); };
  const openEdit = (deptId: number) => { setDeptEditId(deptId); setDeptEditOpen(true); };
  const openLeader = (deptId: number) => { setLeaderDeptId(deptId); setLeaderOpen(true); };

  const handleDeptAdd = async () => {
    try {
      const v = await deptForm.validateFields();
      await deptCreate.mutateAsync({ name: v.name, parentId: v.parentId ?? deptAddParentId ?? null });
      setDeptAddOpen(false); setDeptAddParentId(null); deptForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };
  const handleDeptEdit = async () => {
    try {
      const v = await deptForm.validateFields();
      await deptUpdate.mutateAsync({ id: deptEditId, name: v.name });
      setDeptEditOpen(false); setDeptEditId(null); deptForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
    }
  };
  const handleLeaderSet = async (userIds: number[]) => {
    if (leaderDeptId === null) return;
    try {
      await deptUpdate.mutateAsync({ id: leaderDeptId, leaderIds: userIds });
      onLeaderSaved();
      await qc.invalidateQueries({ queryKey: ['users-page'] });
      setLeaderOpen(false); setLeaderDeptId(null);
    } catch {
      // The global request handler displays the backend business message.
    }
  };

  const editDeptName = deptEditId ? deptList.find(d => d.id === deptEditId)?.name : '';

  useEffect(() => {
    if (!deptAddOpen) return;
    deptForm.resetFields();
    deptForm.setFieldsValue({ name: undefined, parentId: deptAddParentId ?? undefined });
  }, [deptAddOpen, deptAddParentId, deptForm]);

  useEffect(() => {
    if (!deptEditOpen) return;
    deptForm.resetFields();
    deptForm.setFieldsValue({ name: editDeptName });
  }, [deptEditOpen, editDeptName, deptForm]);

  const actions: DeptNodeActions = {
    onAddChild: openAdd,
    onRename: openEdit,
    onSetLeader: openLeader,
    onMoveUp: (deptId) => deptMoveOrder.mutate({ id: deptId, direction: 'UP' }),
    onMoveDown: (deptId) => deptMoveOrder.mutate({ id: deptId, direction: 'DOWN' }),
    onRemove: removeDept,
  };

  return {
    deptList,
    departmentsError,
    refetchDepartments,
    treeData,
    deptParentById,
    deptNameById,
    canManageUsersByDept,
    selectedDepartment,
    breadcrumb,
    actions,
    onDrop,
    // 弹窗
    deptForm,
    deptAddOpen,
    deptAddParentId,
    deptEditOpen,
    editDeptName,
    leaderOpen,
    leaderDeptId,
    deptCreatePending: deptCreate.isPending,
    deptUpdatePending: deptUpdate.isPending,
    openAdd,
    handleDeptAdd,
    cancelDeptAdd: () => { setDeptAddOpen(false); setDeptAddParentId(null); deptForm.resetFields(); },
    handleDeptEdit,
    cancelDeptEdit: () => { setDeptEditOpen(false); setDeptEditId(null); deptForm.resetFields(); },
    leaderUsers: (leaderResult?.records ?? []).map(u => ({ id: u.id, name: u.displayName || u.username })),
    leaderCurrentIds: (deptList.find(d => d.id === leaderDeptId)?.leaderIds
      ?? (deptList.find(d => d.id === leaderDeptId)?.leaderId
        ? [deptList.find(d => d.id === leaderDeptId)?.leaderId as number]
        : [])),
    handleLeaderSet,
    cancelLeader: () => { setLeaderOpen(false); setLeaderDeptId(null); },
  };
}
