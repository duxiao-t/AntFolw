import { PageContainer, ProTable } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Input, Tree, Button, Dropdown, Modal, Form, Space, Tag, Popconfirm,
  Select, App,
} from 'antd';
import {
  PlusOutlined, SearchOutlined, MoreOutlined, UserAddOutlined,
  ImportOutlined, DeleteOutlined, EditOutlined, TeamOutlined, DownloadOutlined,
  ArrowUpOutlined, ArrowDownOutlined,
} from '@ant-design/icons';
import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import type { DataNode } from 'antd/es/tree';
import './Contacts.less';
import {
  buildMembersCsv,
  collectDepartmentIds,
  collectTreeKeys,
  formatGender,
  normalizeGender,
  parseMembersCsv,
  retainVisibleKeys,
  resolveDepartmentDropAction,
  summarizeSettledResults,
} from './Contacts.utils';

interface Dept {
  id: number; companyId: number; parentId: number | null;
  path: string; name: string; leaderId: number | null; leaderIds?: number[];
  sortOrder?: number;
}
interface UserItem {
  id: number; username: string; displayName: string; email: string;
  phone: string; position: string; gender: string; deptId: number;
}

export default function ContactsPage() {
  const [selDeptId, setSelDeptId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const qc = useQueryClient();
  const { message: msg, modal } = App.useApp();

  const [deptForm] = Form.useForm();
  const [memberForm] = Form.useForm();
  const [deptAddOpen, setDeptAddOpen] = useState(false);           // top "+"
  const [deptAddParentId, setDeptAddParentId] = useState<number | null>(null); // for sub-dept
  const [deptEditOpen, setDeptEditOpen] = useState(false);
  const [deptEditId, setDeptEditId] = useState<number | null>(null);
  const [leaderOpen, setLeaderOpen] = useState(false);
  const [leaderDeptId, setLeaderDeptId] = useState<number | null>(null);
  const [memberOpen, setMemberOpen] = useState(false);
  const [memberEdit, setMemberEdit] = useState<UserItem | null>(null);
  const [selectedMemberIds, setSelectedMemberIds] = useState<React.Key[]>([]);
  const importInputRef = useRef<HTMLInputElement>(null);

  // --- data ---
  const { data: companies } = useQuery({ queryKey: ['companies'], queryFn: () => request('/api/companies') });
  const companyId = (companies as any[])?.[0]?.id ?? 1;

  const { data: deptList = [] } = useQuery({
    queryKey: ['depts', companyId],
    queryFn: () => request(`/api/departments?companyId=${companyId}`),
    enabled: !!companyId,
  });

  const { data: selPath = [] } = useQuery({
    queryKey: ['dept-path', selDeptId],
    queryFn: () => request(`/api/departments/${selDeptId}/path`),
    enabled: !!selDeptId,
  });

  const { data: allUsers = [] } = useQuery({
    queryKey: ['all-users'],
    queryFn: () => request('/api/users'),
  });

  // --- tree data ---
  const treeData = useMemo(() => {
    const list = deptList as Dept[];
    const byParent: Record<number, DataNode[]> = {};
    for (const d of list) {
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
    for (const d of deptList as Dept[]) map[d.id] = d.parentId ?? null;
    return map;
  }, [deptList]);

  const deptNameById = useMemo(() => {
    const map: Record<number, string> = {};
    for (const d of deptList as Dept[]) map[d.id] = d.name;
    return map;
  }, [deptList]);

  const selectedDeptIds = useMemo(
    () => collectDepartmentIds(deptList as Dept[], selDeptId),
    [deptList, selDeptId],
  );

  const members = useMemo(() => {
    const idSet = new Set(selectedDeptIds);
    return (allUsers as UserItem[]).filter((u) => idSet.has(u.deptId));
  }, [allUsers, selectedDeptIds]);

  useEffect(() => {
    const visibleIds = new Set(members.map((m) => m.id));
    setSelectedMemberIds((prev) => retainVisibleKeys(prev, visibleIds));
  }, [members]);

  const filteredTree = useMemo(() => {
    if (!search.trim()) return treeData;
    const lower = search.toLowerCase();
    const filterNode = (nodes: DataNode[]): DataNode[] =>
      nodes.flatMap((n) => {
        const match = String(n.title).toLowerCase().includes(lower);
        const filteredChildren = n.children ? filterNode(n.children) : [];
        if (match || filteredChildren.length) return [{ ...n, children: filteredChildren }];
        return [];
      });
    const result = filterNode(treeData);
    return result;
  }, [treeData, search]);

  useEffect(() => {
    if (search.trim()) setExpandedKeys(collectTreeKeys(filteredTree));
  }, [filteredTree, search]);

  // --- dept CRUD ---
  const deptCreate = useMutation({
    mutationFn: (body: any) => request('/api/departments', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('部门已创建'); },
  });
  const deptUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/departments/${id}`, { method: 'PUT', data: body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['depts'] });
      qc.invalidateQueries({ queryKey: ['dept-path'] });
      msg.success('部门已更新');
    },
  });
  const deptMove = useMutation({
    mutationFn: ({ id, parentId }: { id: number; parentId: number | null }) =>
      request(`/api/departments/${id}`, { method: 'PUT', data: { parentId } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['depts'] });
      qc.invalidateQueries({ queryKey: ['dept-path'] });
      msg.success('部门已移动');
    },
  });
  const deptMoveOrder = useMutation({
    mutationFn: ({ id, direction }: { id: number; direction: 'UP' | 'DOWN' }) =>
      request(`/api/departments/${id}/order`, { method: 'PUT', data: { direction } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['depts'] });
      msg.success('排序已更新');
    },
  });
  const deptMovePosition = useMutation({
    mutationFn: ({ id, targetId, placement }: { id: number; targetId: number; placement: 'BEFORE' | 'AFTER' }) =>
      request(`/api/departments/${id}/position`, { method: 'PUT', data: { targetId, placement } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['depts'] });
      msg.success('排序已更新');
    },
  });
  const deptRemove = useMutation({
    mutationFn: (id: number) => request(`/api/departments/${id}`, { method: 'DELETE' }),
    onSuccess: () => { setSelDeptId(null); qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('已删除'); },
  });

  // --- member CRUD ---
  const memberCreate = useMutation({
    mutationFn: (body: any) => request('/api/users', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['all-users'] }); msg.success('添加成功'); },
  });
  const memberUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/users/${id}`, { method: 'PUT', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['all-users'] }); msg.success('已更新'); },
  });
  const memberRemove = useMutation({
    mutationFn: (id: number) => request(`/api/users/${id}`, { method: 'DELETE' }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['all-users'] }); msg.success('已删除'); },
  });

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

  // --- tree title render ---
  const titleRender = (node: DataNode) => {
    const currentDept = (deptList as Dept[]).find(x => x.id === node.key);
    const siblings = (deptList as Dept[]).filter(x => (x.parentId ?? null) === (currentDept?.parentId ?? null));
    const siblingIndex = siblings.findIndex(x => x.id === node.key);
    const canMoveUp = siblingIndex > 0;
    const canMoveDown = siblingIndex >= 0 && siblingIndex < siblings.length - 1;
    const items = [
      { key: 'add', label: '添加子部门', icon: <PlusOutlined />,
        onClick: () => { setDeptAddParentId(node.key as number); setDeptAddOpen(true); } },
      { key: 'edit', label: '修改名称', icon: <EditOutlined />,
        onClick: () => { setDeptEditId(node.key as number); setDeptEditOpen(true); } },
      { key: 'leader', label: '设置负责人', icon: <TeamOutlined />,
        onClick: () => { setLeaderDeptId(node.key as number); setLeaderOpen(true); } },
      { key: 'up', label: '上移', icon: <ArrowUpOutlined />, disabled: !canMoveUp,
        onClick: () => deptMoveOrder.mutate({ id: node.key as number, direction: 'UP' }) },
      { key: 'down', label: '下移', icon: <ArrowDownOutlined />, disabled: !canMoveDown,
        onClick: () => deptMoveOrder.mutate({ id: node.key as number, direction: 'DOWN' }) },
      { key: 'del', label: '删除部门', icon: <DeleteOutlined />, danger: true,
        onClick: () => {
          const d = (deptList as Dept[]).find(x => x.id === node.key);
          modal.confirm({
            title: `确认删除「${d?.name ?? node.key}」?`,
            content: '删除后不可恢复，该部门下不能有子部门',
            okType: 'danger',
            onOk: () => deptRemove.mutate(node.key as number),
          });
        } },
      { type: 'divider' as const },
      { key: 'dept-id', label: `部门ID：${node.key}`, disabled: true },
    ];
    return (
      <div className="ct-tree-node" onClick={() => setSelDeptId(node.key as number)}>
        <span className="ct-tree-node__name">{node.title as string}</span>
        <Dropdown menu={{ items }} trigger={['click']}>
          <Button type="text" size="small" icon={<MoreOutlined />} onClick={e => e.stopPropagation()} />
        </Dropdown>
      </div>
    );
  };

  // --- breadcrumb ---
  const breadcrumb = useMemo(() => {
    const parts = (selPath as Dept[]).map(d => d.name);
    return parts.join(' / ') || '请选择部门';
  }, [selPath]);

  // ---- dept form handlers ----
  const handleDeptAdd = async () => {
    try {
      const v = await deptForm.validateFields();
      await deptCreate.mutateAsync({ name: v.name, companyId, parentId: v.parentId ?? deptAddParentId ?? null });
      setDeptAddOpen(false); setDeptAddParentId(null); deptForm.resetFields();
    } catch (error: any) {
      if (!error?.errorFields) msg.error('部门创建失败');
    }
  };
  const handleDeptEdit = async () => {
    try {
      const v = await deptForm.validateFields();
      await deptUpdate.mutateAsync({ id: deptEditId, name: v.name });
      setDeptEditOpen(false); setDeptEditId(null); deptForm.resetFields();
    } catch (error: any) {
      if (!error?.errorFields) msg.error('部门更新失败');
    }
  };
  const handleLeaderSet = async (userIds: number[]) => {
    if (leaderDeptId === null) return;
    try {
      await deptUpdate.mutateAsync({ id: leaderDeptId, leaderIds: userIds });
      setLeaderOpen(false); setLeaderDeptId(null);
    } catch (_error) {
      msg.error('负责人设置失败');
    }
  };
  const handleMemberOk = async () => {
    try {
      const v = await memberForm.validateFields();
      if (memberEdit) await memberUpdate.mutateAsync({ id: memberEdit.id, ...v });
      else await memberCreate.mutateAsync({ ...v, deptId: selDeptId });
      setMemberOpen(false); setMemberEdit(null); memberForm.resetFields();
    } catch (error: any) {
      if (!error?.errorFields) msg.error(memberEdit ? '成员更新失败' : '成员添加失败');
    }
  };

  const handleBulkMemberRemove = async () => {
    const ids = selectedMemberIds.map(Number);
    if (!ids.length) return;
    const results = await Promise.allSettled(
      ids.map((id) => request(`/api/users/${id}`, { method: 'DELETE' })),
    );
    const { successCount, failedCount } = summarizeSettledResults(results);
    if (successCount) {
      setSelectedMemberIds([]);
      qc.invalidateQueries({ queryKey: ['all-users'] });
    }
    if (failedCount) msg.error(`批量删除完成：成功 ${successCount} 条，失败 ${failedCount} 条`);
    else msg.success(`已删除 ${successCount} 名成员`);
  };

  const handleExportMembers = () => {
    if (!members.length) { msg.warning('当前部门没有可导出的成员'); return; }
    const csv = `\uFEFF${buildMembersCsv(members as UserItem[])}`;
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    const deptName = breadcrumb.split(' / ').pop() || '部门成员';
    link.href = url;
    link.download = `${deptName}-成员.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const handleImportMembers = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !selDeptId) return;

    try {
      const result = parseMembersCsv(await file.text(), selDeptId);
      if (result.errors.length) {
        msg.error(result.errors.slice(0, 3).join('；'));
        return;
      }
      if (!result.rows.length) { msg.warning('CSV 中没有可导入的成员'); return; }

      const results = await Promise.allSettled(
        result.rows.map((row) => request('/api/users', { method: 'POST', data: row })),
      );
      const successCount = results.filter((r) => r.status === 'fulfilled').length;
      const failedCount = results.length - successCount;
      if (successCount) qc.invalidateQueries({ queryKey: ['all-users'] });
      if (failedCount) msg.error(`导入完成：成功 ${successCount} 条，失败 ${failedCount} 条`);
      else msg.success(`已导入 ${successCount} 名成员`);
    } catch (_error) {
      msg.error('批量导入失败');
    }
  };

  // ---- get edit dept name ----
  const editDeptName = deptEditId ? (deptList as Dept[]).find(d => d.id === deptEditId)?.name : '';

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

  return (
    <PageContainer breadcrumbRender={false}>
      <div className="ct-layout">
        {/* ===== LEFT ===== */}
        <aside className="ct-left">
          <div className="ct-left-top">
            <Input prefix={<SearchOutlined />} placeholder="搜索部门" allowClear
              value={search} onChange={e => setSearch(e.target.value)} />
            <Button icon={<PlusOutlined />} onClick={() => { setDeptAddParentId(null); setDeptAddOpen(true); }} />
          </div>
          <div className="ct-tree-wrap">
            <Tree
              showIcon
              treeData={filteredTree}
              expandedKeys={expandedKeys}
              onExpand={keys => setExpandedKeys(keys)}
              onSelect={keys => { if (keys[0]) setSelDeptId(keys[0] as number); }}
              selectedKeys={selDeptId ? [selDeptId] : []}
              titleRender={titleRender}
              draggable={{ icon: false }}
              blockNode
              onDrop={onDrop}
            />
          </div>
        </aside>

        {/* ===== RIGHT ===== */}
        <main className="ct-right">
          {selDeptId ? (
            <>
              <div className="ct-right-header">
                <h2>{breadcrumb} · {members.length}人</h2>
                <Space>
                  <Button icon={<UserAddOutlined />} type="primary" onClick={() => { setMemberEdit(null); memberForm.resetFields(); setMemberOpen(true); }}>添加成员</Button>
                  <Popconfirm
                    title={`确定删除选中的 ${selectedMemberIds.length} 名成员?`}
                    disabled={!selectedMemberIds.length}
                    onConfirm={handleBulkMemberRemove}
                  >
                    <Button danger icon={<DeleteOutlined />} disabled={!selectedMemberIds.length}>批量删除</Button>
                  </Popconfirm>
                  <Button icon={<ImportOutlined />} onClick={() => importInputRef.current?.click()}>批量导入</Button>
                  <Button icon={<DownloadOutlined />} onClick={handleExportMembers}>导出</Button>
                  <input ref={importInputRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImportMembers} />
                </Space>
              </div>
              <ProTable<UserItem>
                rowKey="id"
                columns={[
                  { title: '姓名', dataIndex: 'displayName' },
                  { title: '账号', dataIndex: 'username' },
                  { title: '手机', dataIndex: 'phone' },
                  { title: '部门', dataIndex: 'deptId', render: (_, r) => deptNameById[r.deptId] ?? '-' },
                  { title: '职务', dataIndex: 'position' },
                  { title: '性别', dataIndex: 'gender', render: (_, r) => {
                    const genderLabel = formatGender(r.gender);
                    if (genderLabel === '男') return <Tag color="blue">男</Tag>;
                    if (genderLabel === '女') return <Tag color="pink">女</Tag>;
                    return genderLabel || '-';
                  } },
                  { title: '操作', key: 'op', width: 160, render: (_, r) => (
                    <Space>
                      <a onClick={() => { setMemberEdit(r); setMemberOpen(true); memberForm.setFieldsValue({ ...r, gender: normalizeGender(r.gender) }); }}>编辑</a>
                      <Popconfirm title="确定删除?" onConfirm={() => memberRemove.mutate(r.id)}>
                        <a style={{ color: '#ff4d4f' }}>删除</a>
                      </Popconfirm>
                    </Space>
                  )},
                ]}
                dataSource={members}
                rowSelection={{
                  selectedRowKeys: selectedMemberIds,
                  onChange: (keys) => setSelectedMemberIds(keys),
                }}
                search={false}
                options={false}
                pagination={{ pageSize: 15 }}
              />
            </>
          ) : (
            <div className="ct-empty">请从左侧选择部门</div>
          )}
        </main>
      </div>

      {/* ===== Department Add Modal (top "+" or sub-dept) ===== */}
      <Modal
        title={deptAddParentId ? '添加子部门' : '新建部门'}
        open={deptAddOpen}
        confirmLoading={deptCreate.isPending}
        onOk={handleDeptAdd}
        onCancel={() => { setDeptAddOpen(false); setDeptAddParentId(null); deptForm.resetFields(); }}
        destroyOnClose
      >
        <Form form={deptForm} layout="vertical" preserve={false}>
          <Form.Item label="部门名称" name="name" rules={[{ required: true, message: '请输入' }]}>
            <Input autoFocus />
          </Form.Item>
          {!deptAddParentId && (
            <Form.Item label="所属部门" name="parentId">
              <Select allowClear placeholder="留空则为一级部门" options={
                (deptList as Dept[]).map(d => ({ value: d.id, label: d.name }))
              } />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* ===== Department Edit (name only) ===== */}
      <Modal
        title="修改部门"
        open={deptEditOpen}
        confirmLoading={deptUpdate.isPending}
        onOk={handleDeptEdit}
        onCancel={() => { setDeptEditOpen(false); setDeptEditId(null); deptForm.resetFields(); }}
        destroyOnClose
      >
        <Form form={deptForm} layout="vertical" preserve={false}
          initialValues={{ name: editDeptName }}>
          <Form.Item label="部门名称" name="name" rules={[{ required: true }]}>
            <Input autoFocus />
          </Form.Item>
        </Form>
      </Modal>

      {/* ===== Set Leader Modal ===== */}
      <Modal
        title="设置部门负责人"
        open={leaderOpen}
        onCancel={() => { setLeaderOpen(false); setLeaderDeptId(null); }}
        footer={null}
        width={600}
        confirmLoading={deptUpdate.isPending}
        destroyOnClose
      >
        <LeaderPicker
          users={(allUsers as UserItem[]).map(u => ({ id: u.id, name: u.displayName || u.username }))}
          currentLeaderIds={(deptList as Dept[]).find(d => d.id === leaderDeptId)?.leaderIds
            ?? ((deptList as Dept[]).find(d => d.id === leaderDeptId)?.leaderId
              ? [(deptList as Dept[]).find(d => d.id === leaderDeptId)?.leaderId as number]
              : [])}
          onOk={handleLeaderSet}
          onCancel={() => { setLeaderOpen(false); setLeaderDeptId(null); }}
          saving={deptUpdate.isPending}
        />
      </Modal>

      {/* ===== Member Modal ===== */}
      <Modal
        title={memberEdit ? '编辑成员' : '添加成员'}
        open={memberOpen}
        confirmLoading={memberCreate.isPending || memberUpdate.isPending}
        width={520}
        onOk={handleMemberOk}
        onCancel={() => { setMemberOpen(false); setMemberEdit(null); memberForm.resetFields(); }}
        destroyOnClose
      >
        <Form form={memberForm} layout="vertical" preserve={false}
          initialValues={memberEdit ? memberEdit : undefined}>
          <Form.Item label="姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="账号" name="username" rules={[{ required: true }]}><Input disabled={!!memberEdit} /></Form.Item>
          <Form.Item label="手机" name="phone"><Input /></Form.Item>
          <Form.Item label="邮箱" name="email"><Input /></Form.Item>
          <Form.Item label="职务" name="position"><Input /></Form.Item>
          <Form.Item label="性别" name="gender">
            <Select allowClear options={[{ value: 'M', label: '男' }, { value: 'F', label: '女' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}

/** Leader picker: list on left, selected on right */
function LeaderPicker({ users, currentLeaderIds, onOk, onCancel, saving }: {
  users: { id: number; name: string }[];
  currentLeaderIds: number[];
  onOk: (userIds: number[]) => void;
  onCancel: () => void;
  saving: boolean;
}) {
  const [selectedIds, setSelectedIds] = useState<number[]>(currentLeaderIds);
  const [keyword, setKeyword] = useState('');
  const filteredUsers = useMemo(() => {
    const lower = keyword.trim().toLowerCase();
    if (!lower) return users;
    return users.filter((u) => u.name.toLowerCase().includes(lower) || String(u.id).includes(lower));
  }, [keyword, users]);
  const selectedUsers = useMemo(
    () => selectedIds.map((id) => users.find((u) => u.id === id)).filter(Boolean) as { id: number; name: string }[],
    [selectedIds, users],
  );
  const toggleUser = (id: number) => {
    setSelectedIds((prev) => prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: 420 }}>
      <div style={{ display: 'flex', gap: 16, minHeight: 0, flex: 1 }}>
        <div style={{ flex: 1, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
          <Input placeholder="搜索成员" allowClear value={keyword} onChange={e => setKeyword(e.target.value)} style={{ marginBottom: 8 }} />
          {filteredUsers.map(u => (
            <div key={u.id} className={`ct-user-row${selectedIds.includes(u.id) ? ' ct-user-row--sel' : ''}`}
              onClick={() => toggleUser(u.id)}
              style={{ padding: '6px 8px', cursor: 'pointer', borderRadius: 4 }}>
              {u.name}
            </div>
          ))}
          {!filteredUsers.length && <div style={{ color: '#bbb', padding: '12px 8px' }}>无匹配成员</div>}
        </div>
        <div style={{ width: 200, border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, overflow: 'auto' }}>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>已选负责人</div>
          {selectedUsers.length ? selectedUsers.map((u) => (
            <Tag key={u.id} closable onClose={() => setSelectedIds((prev) => prev.filter((id) => id !== u.id))} style={{ marginBottom: 6 }}>
              {u.name}
            </Tag>
          )) : <span style={{ color: '#bbb' }}>未选择</span>}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button size="small" onClick={onCancel} disabled={saving} style={{ marginRight: 8 }}>取消</Button>
          <Button type="primary" size="small" loading={saving} onClick={() => onOk(selectedIds)}>确定</Button>
      </div>
    </div>
  );
}
