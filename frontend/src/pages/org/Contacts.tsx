import { PageContainer, ProTable } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Input, Tree, Button, Dropdown, Modal, Form, Space, Tag, Popconfirm,
  Select, App,
} from 'antd';
import {
  PlusOutlined, SearchOutlined, MoreOutlined, UserAddOutlined,
  ImportOutlined, DeleteOutlined, EditOutlined, TeamOutlined,
} from '@ant-design/icons';
import { useState, useMemo } from 'react';
import type { DataNode } from 'antd/es/tree';
import './Contacts.less';

interface Dept {
  id: number; companyId: number; parentId: number | null;
  path: string; name: string; leaderId: number | null;
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

  const { data: members = [], refetch: refetchMembers } = useQuery({
    queryKey: ['members', selDeptId],
    queryFn: () => request(`/api/users?deptId=${selDeptId}&_t=${Date.now()}`),
    enabled: !!selDeptId,
  });

  const { data: allUsers = [] } = useQuery({
    queryKey: ['all-users'],
    queryFn: () => request('/api/users'),
    enabled: leaderOpen,
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
    const collectKeys = (nodes: DataNode[]): React.Key[] =>
      nodes.flatMap((n) => [n.key, ...(n.children ? collectKeys(n.children) : [])]);
    if (search) setExpandedKeys(collectKeys(result));
    return result;
  }, [treeData, search]);

  // --- dept CRUD ---
  const deptCreate = useMutation({
    mutationFn: (body: any) => request('/api/departments', { method: 'POST', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('部门已创建'); },
  });
  const deptUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/departments/${id}`, { method: 'PUT', data: body }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('部门已更新'); },
  });
  const deptRemove = useMutation({
    mutationFn: (id: number) => request(`/api/departments/${id}`, { method: 'DELETE' }),
    onSuccess: () => { setSelDeptId(null); qc.invalidateQueries({ queryKey: ['depts'] }); msg.success('已删除'); },
  });

  // --- member CRUD ---
  const memberCreate = useMutation({
    mutationFn: (body: any) => request('/api/users', { method: 'POST', data: body }),
    onSuccess: () => { refetchMembers(); msg.success('添加成功'); },
  });
  const memberUpdate = useMutation({
    mutationFn: ({ id, ...body }: any) => request(`/api/users/${id}`, { method: 'PUT', data: body }),
    onSuccess: () => { refetchMembers(); msg.success('已更新'); },
  });
  const memberRemove = useMutation({
    mutationFn: (id: number) => request(`/api/users/${id}`, { method: 'DELETE' }),
    onSuccess: () => { refetchMembers(); msg.success('已删除'); },
  });

  // --- tree drop ---
  const onDrop = (info: any) => {
    const dragId = info.dragNode?.key ?? info.dragNode?.props?.eventKey;
    const dropId = info.node?.key ?? info.node?.props?.eventKey;
    if (dragId && dropId && !info.dropToGap) {
      deptUpdate.mutate({ id: Number(dragId), parentId: Number(dropId) });
    }
  };

  // --- tree title render ---
  const titleRender = (node: DataNode) => {
    const items = [
      { key: 'add', label: '添加子部门', icon: <PlusOutlined />,
        onClick: () => { setDeptAddParentId(node.key as number); setDeptAddOpen(true); } },
      { key: 'edit', label: '修改名称', icon: <EditOutlined />,
        onClick: () => { setDeptEditId(node.key as number); setDeptEditOpen(true); } },
      { key: 'leader', label: '设置负责人', icon: <TeamOutlined />,
        onClick: () => { setLeaderDeptId(node.key as number); setLeaderOpen(true); } },
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
  const handleDeptAdd = () => {
    const v = deptForm.getFieldsValue();
    if (!v?.name) { msg.error('请输入部门名称'); return; }
    deptCreate.mutate({ name: v.name, companyId, parentId: deptAddParentId ?? null });
    setDeptAddOpen(false); setDeptAddParentId(null); deptForm.resetFields();
  };
  const handleDeptEdit = () => {
    const v = deptForm.getFieldsValue();
    if (!v?.name) { msg.error('请输入部门名称'); return; }
    deptUpdate.mutate({ id: deptEditId, name: v.name });
    setDeptEditOpen(false); setDeptEditId(null); deptForm.resetFields();
  };
  const handleLeaderSet = (userId: number | null) => {
    if (leaderDeptId && userId) {
      deptUpdate.mutate({ id: leaderDeptId, leaderId: userId });
    }
    setLeaderOpen(false); setLeaderDeptId(null);
  };
  const handleMemberOk = () => {
    const v = memberForm.getFieldsValue();
    if (memberEdit) { memberUpdate.mutate({ id: memberEdit.id, ...v }); }
    else { memberCreate.mutate({ ...v, deptId: selDeptId }); }
    setMemberOpen(false); setMemberEdit(null); memberForm.resetFields();
  };

  // ---- get edit dept name ----
  const editDeptName = deptEditId ? (deptList as Dept[]).find(d => d.id === deptEditId)?.name : '';

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
              treeData={filteredTree}
              expandedKeys={expandedKeys}
              onExpand={keys => setExpandedKeys(keys)}
              onSelect={keys => { if (keys[0]) setSelDeptId(keys[0] as number); }}
              selectedKeys={selDeptId ? [selDeptId] : []}
              titleRender={titleRender}
              draggable
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
                  <Button icon={<UserAddOutlined />} type="primary" onClick={() => { setMemberEdit(null); setMemberOpen(true); }}>添加成员</Button>
                  <Button icon={<ImportOutlined />}>批量导入/导出</Button>
                </Space>
              </div>
              <ProTable<UserItem>
                rowKey="id"
                columns={[
                  { title: '姓名', dataIndex: 'displayName' },
                  { title: '账号', dataIndex: 'username' },
                  { title: '手机', dataIndex: 'phone' },
                  { title: '部门', dataIndex: 'deptId', render: () => breadcrumb.split(' / ').pop() },
                  { title: '职务', dataIndex: 'position' },
                  { title: '性别', dataIndex: 'gender', render: (_, r) =>
                    r.gender === 'M' ? <Tag color="blue">男</Tag> : r.gender === 'F' ? <Tag color="pink">女</Tag> : r.gender || '-',
                  },
                  { title: '操作', key: 'op', width: 160, render: (_, r) => (
                    <Space>
                      <a onClick={() => { setMemberEdit(r); setMemberOpen(true); memberForm.setFieldsValue(r); }}>编辑</a>
                      <Popconfirm title="确定删除?" onConfirm={() => memberRemove.mutate(r.id)}>
                        <a style={{ color: '#ff4d4f' }}>删除</a>
                      </Popconfirm>
                    </Space>
                  )},
                ]}
                dataSource={members}
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
        destroyOnClose
      >
        <LeaderPicker
          users={(allUsers as UserItem[]).map(u => ({ id: u.id, name: u.displayName || u.username }))}
          currentLeaderId={(deptList as Dept[]).find(d => d.id === leaderDeptId)?.leaderId ?? null}
          onOk={handleLeaderSet}
          onCancel={() => { setLeaderOpen(false); setLeaderDeptId(null); }}
        />
      </Modal>

      {/* ===== Member Modal ===== */}
      <Modal
        title={memberEdit ? '编辑成员' : '添加成员'}
        open={memberOpen}
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
            <Select options={[{ value: '男', label: '男' }, { value: '女', label: '女' }, { value: 'M', label: '男' }, { value: 'F', label: '女' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}

/** Leader picker: list on left, selected on right */
function LeaderPicker({ users, currentLeaderId, onOk, onCancel }: {
  users: { id: number; name: string }[];
  currentLeaderId: number | null;
  onOk: (userId: number | null) => void;
  onCancel: () => void;
}) {
  const [sel, setSel] = useState<number | null>(currentLeaderId);
  return (
    <div style={{ display: 'flex', gap: 16, height: 360 }}>
      <div style={{ flex: 1, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
        <Input placeholder="搜索成员" style={{ marginBottom: 8 }} />
        {users.map(u => (
          <div key={u.id} className={`ct-user-row${sel === u.id ? ' ct-user-row--sel' : ''}`}
            onClick={() => setSel(u.id)}
            style={{ padding: '6px 8px', cursor: 'pointer', borderRadius: 4 }}>
            {u.name}
          </div>
        ))}
      </div>
      <div style={{ width: 160, border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
        <div style={{ fontWeight: 600, marginBottom: 8 }}>已选负责人</div>
        {sel ? <Tag closable onClose={() => setSel(null)}>{users.find(u => u.id === sel)?.name}</Tag> : <span style={{ color: '#bbb' }}>未选择</span>}
        <div style={{ marginTop: 16, textAlign: 'right' }}>
          <Button size="small" onClick={onCancel} style={{ marginRight: 8 }}>取消</Button>
          <Button type="primary" size="small" onClick={() => onOk(sel)}>确定</Button>
        </div>
      </div>
    </div>
  );
}
