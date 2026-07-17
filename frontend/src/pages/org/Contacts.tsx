import { PageContainer } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Input, Tree, Button, Dropdown, Modal, Form, Space, Tag, Popconfirm,
  Select, App,
} from 'antd';
import {
  PlusOutlined, SearchOutlined, MoreOutlined, UserAddOutlined,
  ImportOutlined, DeleteOutlined, EditOutlined,
} from '@ant-design/icons';
import { useState, useMemo, useCallback } from 'react';
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
  const [deptModal, setDeptModal] = useState<{ open: boolean; parentId?: number; edit?: Dept }>({ open: false });
  const [memberModal, setMemberModal] = useState<{ open: boolean; edit?: UserItem }>({ open: false });
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const qc = useQueryClient();
  const { message: msg } = App.useApp();

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
    queryFn: () => request(`/api/users?deptId=${selDeptId}`),
    enabled: !!selDeptId,
  });

  // --- tree data ---
  const treeData = useMemo(() => {
    const list = deptList as Dept[];
    const byParent: Record<number, DataNode[]> = {};
    const nodeMap: Record<number, DataNode> = {};
    for (const d of list) {
      const node: DataNode = { title: d.name, key: d.id, children: [] };
      nodeMap[d.id] = node;
      const key = d.parentId ?? 0;
      if (!byParent[key]) byParent[key] = [];
      byParent[key].push(node);
    }
    const walk = (n: DataNode) => {
      n.children = byParent[n.key as number]?.map(walk) ?? [];
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
    // expand all matching results
    const collectKeys = (nodes: DataNode[]): React.Key[] =>
      nodes.flatMap((n) => [n.key, ...(n.children ? collectKeys(n.children) : [])]);
    if (search) setExpandedKeys(collectKeys(result));
    return result;
  }, [treeData, search]);

  // --- dept CRUD ---
  const deptMutations = {
    create: useMutation({ mutationFn: (body: any) => request('/api/departments', { method: 'POST', data: body }), onSuccess: () => qc.invalidateQueries({ queryKey: ['depts'] }) }),
    update: useMutation({ mutationFn: ({ id, ...body }: any) => request(`/api/departments/${id}`, { method: 'PUT', data: body }), onSuccess: () => qc.invalidateQueries({ queryKey: ['depts'] }) }),
    remove: useMutation({ mutationFn: (id: number) => request(`/api/departments/${id}`, { method: 'DELETE' }), onSuccess: () => { setSelDeptId(null); qc.invalidateQueries({ queryKey: ['depts'] }); } }),
  };

  // --- tree drop ---
  const onDrop = useCallback((info: any) => {
    const dragKey = info.dragNode.key as number;
    const dropKey = info.node.key as number;
    const dropToGap = info.dropToGap;
    if (!dropToGap) {
      // drop onto node = move under it
      deptMutations.update.mutate({ id: dragKey, parentId: dropKey });
    }
  }, []);

  // --- member CRUD ---
  const memberMutations = {
    create: useMutation({ mutationFn: (body: any) => request('/api/users', { method: 'POST', data: { ...body, deptId: selDeptId } }), onSuccess: () => { refetchMembers(); msg.success('添加成功'); } }),
    update: useMutation({ mutationFn: ({ id, ...body }: any) => request(`/api/users/${id}`, { method: 'PUT', data: body }), onSuccess: () => { refetchMembers(); msg.success('更新成功'); } }),
    remove: useMutation({ mutationFn: (id: number) => request(`/api/users/${id}`, { method: 'DELETE' }), onSuccess: () => { refetchMembers(); msg.success('已删除'); } }),
  };

  // --- tree title render ---
  const titleRender = useCallback((node: DataNode) => {
    const items = [
      { key: 'add', label: '添加子部门', icon: <PlusOutlined />, onClick: () => setDeptModal({ open: true, parentId: node.key as number }) },
      {
        key: 'edit', label: '修改名称', icon: <EditOutlined />,
        onClick: () => { const d = (deptList as Dept[]).find(x => x.id === node.key); if (d) setDeptModal({ open: true, edit: d }); }
      },
      { key: 'leader', label: '设置负责人', icon: <UserAddOutlined />, onClick: () => setDeptModal({ open: true, edit: (deptList as Dept[]).find(x => x.id === node.key) }) },
      { key: 'del', label: '删除部门', icon: <DeleteOutlined />, danger: true, onClick: () => deptMutations.remove.mutate(node.key as number) },
    ];
    return (
      <div className="ct-tree-title"
        onClick={() => { setSelDeptId(node.key as number); }}>
        <span>{node.title as string}</span>
        <Dropdown menu={{ items }} trigger={['click']}>
          <Button type="text" size="small" icon={<MoreOutlined />} onClick={e => e.stopPropagation()} />
        </Dropdown>
      </div>
    );
  }, [deptList]);

  // --- breadcrumb ---
  const breadcrumb = useMemo(() => {
    const parts: string[] = [];
    for (const d of (selPath as Dept[])) parts.push(d.name);
    return parts.join(' / ') || '请选择部门';
  }, [selPath]);

  return (
    <PageContainer breadcrumbRender={false}>
      <div className="ct-layout">
        {/* ===== LEFT: Department Tree ===== */}
        <aside className="ct-left">
          <div className="ct-left-top">
            <Input prefix={<SearchOutlined />} placeholder="搜索部门" allowClear
              value={search} onChange={e => setSearch(e.target.value)} />
            <Button type="primary" size="small" icon={<PlusOutlined />}
              onClick={() => setDeptModal({ open: true })} />
          </div>
          <div className="ct-tree-wrap">
            <Tree.DirectoryTree
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

        {/* ===== RIGHT: Member List ===== */}
        <main className="ct-right">
          {selDeptId ? (
            <>
              <div className="ct-right-header">
                <h2>{breadcrumb} · {members.length}人</h2>
                <Space>
                  <Button icon={<UserAddOutlined />} type="primary"
                    onClick={() => setMemberModal({ open: true })}>添加成员</Button>
                  <Button icon={<ImportOutlined />}>批量导入/导出</Button>
                </Space>
              </div>
              <ProTable<UserItem>
                rowKey="id"
                columns={[
                  { title: '姓名', dataIndex: 'displayName', key: 'displayName' },
                  { title: '账号', dataIndex: 'username', key: 'username' },
                  { title: '手机', dataIndex: 'phone', key: 'phone' },
                  { title: '部门', dataIndex: 'deptId', key: 'deptId', render: () => breadcrumb },
                  { title: '职务', dataIndex: 'position', key: 'position' },
                  {
                    title: '性别', dataIndex: 'gender', key: 'gender',
                    render: (_, r) => r.gender === '男' ? <Tag color="blue">男</Tag> : r.gender === '女' ? <Tag color="pink">女</Tag> : '-',
                  },
                  {
                    title: '操作', key: 'op', width: 160,
                    render: (_, r) => (
                      <Space>
                        <a onClick={() => setMemberModal({ open: true, edit: r })}>编辑</a>
                        <Popconfirm title="确定删除?" onConfirm={() => memberMutations.remove.mutate(r.id)}>
                          <a style={{ color: '#ff4d4f' }}>删除</a>
                        </Popconfirm>
                      </Space>
                    ),
                  },
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

      {/* ===== Department Modal ===== */}
      <Modal
        title={deptModal.edit ? '编辑部门' : '新建部门'}
        open={deptModal.open}
        onCancel={() => setDeptModal({ open: false })}
        onOk={() => {
          const form = (document.getElementById('dept-form') as HTMLFormElement);
          const data = Object.fromEntries(new FormData(form as any) as any);
          if (deptModal.edit) {
            deptMutations.update.mutate({ id: deptModal.edit.id, ...data, leaderId: data.leaderId ? Number(data.leaderId) : null });
          } else {
            deptMutations.create.mutate({ ...data, companyId, parentId: deptModal.parentId ?? null, leaderId: data.leaderId ? Number(data.leaderId) : null });
          }
          setDeptModal({ open: false }); msg.success('OK');
        }}
        destroyOnClose
      >
        <Form id="dept-form" layout="vertical">
          <Form.Item label="部门名称" name="name" initialValue={deptModal.edit?.name}>
            <Input required />
          </Form.Item>
          <Form.Item label="负责人 ID" name="leaderId" initialValue={deptModal.edit?.leaderId}>
            <Input type="number" />
          </Form.Item>
        </Form>
      </Modal>

      {/* ===== Member Modal ===== */}
      <Modal
        title={memberModal.edit ? '编辑成员' : '添加成员'}
        open={memberModal.open}
        width={520}
        onCancel={() => setMemberModal({ open: false })}
        onOk={() => {
          const form = (document.getElementById('member-form') as HTMLFormElement);
          const data = Object.fromEntries(new FormData(form as any) as any);
          if (memberModal.edit) {
            memberMutations.update.mutate({ id: memberModal.edit.id, ...data });
          } else {
            memberMutations.create.mutate(data);
          }
          setMemberModal({ open: false });
        }}
        destroyOnClose
      >
        <Form id="member-form" layout="vertical">
          <Form.Item label="姓名" name="displayName" initialValue={memberModal.edit?.displayName}><Input required /></Form.Item>
          <Form.Item label="账号" name="username" initialValue={memberModal.edit?.username}><Input required /></Form.Item>
          <Form.Item label="手机" name="phone" initialValue={memberModal.edit?.phone}><Input /></Form.Item>
          <Form.Item label="邮箱" name="email" initialValue={memberModal.edit?.email}><Input /></Form.Item>
          <Form.Item label="职务" name="position" initialValue={memberModal.edit?.position}><Input /></Form.Item>
          <Form.Item label="性别" name="gender" initialValue={memberModal.edit?.gender}>
            <Select options={[{ value: '男', label: '男' }, { value: '女', label: '女' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
