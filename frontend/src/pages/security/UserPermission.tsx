import { EyeOutlined, KeyOutlined, SearchOutlined, SaveOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  App,
  Button,
  Checkbox,
  Empty,
  Input,
  List,
  Space,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import { request, useModel } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import './Security.less';
import { displayPermissionName } from './permissionLabels';

type Role = { id: number; code: string; name: string; enabled: boolean; builtin: boolean };
type Permission = { code: string; name: string; category: string; kind: 'PAGE' | 'ACTION'; riskLevel: 'NORMAL' | 'HIGH' | 'CRITICAL'; sortOrder: number };
type UserAssignment = {
  id: number; username: string; displayName: string; employeeNo: string; status: string;
  departmentId?: number; roleIds: number[]; authzVersion: number;
};
type EffectivePermission = {
  userId: number; roleCodes: string[]; permissions: string[]; departmentId?: number; admin: boolean;
};

function permissionTree(permissions: Permission[], checked: string[]): DataNode[] {
  const selected = new Set(checked);
  const groups = new Map<string, Permission[]>();
  permissions.forEach((permission) => {
    groups.set(permission.category, [
      ...(groups.get(permission.category) ?? []), permission,
    ]);
  });
  return [...groups.entries()].map(([category, items]) => ({
    key: `group:${category}`,
    title: category,
    disableCheckbox: true,
    children: items.sort((a, b) => a.sortOrder - b.sortOrder).map((permission) => ({
      key: permission.code,
      title: <span className="security-tree__item"><span><strong>{displayPermissionName(permission.code, permission.name)}</strong><code>{permission.code}</code></span>{permission.riskLevel !== 'NORMAL' && <Tag color={permission.riskLevel === 'CRITICAL' ? 'red' : 'orange'}>{permission.riskLevel === 'CRITICAL' ? '关键' : '高风险'}</Tag>}</span>,
      disabled: !selected.has(permission.code),
    })),
  }));
}

export default function UserPermissionPage() {
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [users, setUsers] = useState<UserAssignment[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [roleIds, setRoleIds] = useState<number[]>([]);
  const [effective, setEffective] = useState<EffectivePermission | null>(null);
  const [saving, setSaving] = useState(false);
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');

  const load = async () => {
    const [roleRows, permissionRows, userRows] = await Promise.all([
      request<Role[]>('/api/security/roles'),
      request<Permission[]>('/api/security/permissions'),
      request<UserAssignment[]>('/api/security/users'),
    ]);
    setRoles(roleRows); setPermissions(permissionRows); setUsers(userRows);
    if (selectedId === null && userRows[0]) setSelectedId(userRows[0].id);
  };
  useEffect(() => { if (isAdmin) load().catch(() => undefined); }, [isAdmin]);

  const filteredUsers = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return users;
    return users.filter((user) => `${user.displayName} ${user.username} ${user.employeeNo}`.toLowerCase().includes(query));
  }, [users, search]);
  const selectedUser = users.find((user) => user.id === selectedId) ?? null;
  const roleMap = useMemo(() => new Map(roles.map((role) => [role.id, role])), [roles]);
  const pagePermissions = permissions.filter((permission) => permission.kind === 'PAGE');
  const actionPermissions = permissions.filter((permission) => permission.kind === 'ACTION');

  useEffect(() => {
    if (!selectedUser) return;
    setRoleIds(selectedUser.roleIds);
    request<EffectivePermission>(`/api/security/effective/users/${selectedUser.id}`)
      .then(setEffective).catch(() => setEffective(null));
  }, [selectedUser]);

  const save = async () => {
    if (!selectedUser) return;
    setSaving(true);
    try {
      await request(`/api/users/${selectedUser.id}/roles`, { method: 'PUT', data: roleIds });
      message.success('用户角色已更新');
      await load();
    } finally {
      setSaving(false);
    }
  };

  if (!isAdmin) return <PageContainer title={false}><Empty description="仅管理员可以分配用户角色" /></PageContainer>;

  return (
    <PageContainer title={false} className="security-page">
      <div className="security-workspace security-user-workspace">
        <aside className="security-sidebar">
          <div className="security-sidebar__header">
            <div><Typography.Title level={4}>用户权限</Typography.Title><Typography.Text type="secondary">{users.length} 个用户</Typography.Text></div>
          </div>
          <Input allowClear prefix={<SearchOutlined />} placeholder="搜索姓名、账号或工号" value={search} onChange={(event) => setSearch(event.target.value)} />
          <List
            className="security-role-list"
            dataSource={filteredUsers}
            renderItem={(user) => (
              <List.Item className={`security-role-list__item${selectedId === user.id ? ' is-active' : ''}`} onClick={() => setSelectedId(user.id)}>
                <div><Typography.Text strong>{user.displayName}</Typography.Text><Typography.Text type="secondary" className="security-role-list__code">{user.username} · {user.employeeNo}</Typography.Text></div>
                <Tag color={user.status === 'ACTIVE' ? 'green' : 'default'}>{user.status === 'ACTIVE' ? '启用' : '停用'}</Tag>
              </List.Item>
            )}
          />
        </aside>

        <main className="security-editor">
          {!selectedUser ? <Empty description="选择一个用户查看权限" /> : (
            <>
              <header className="security-editor__header">
                <div><Typography.Title level={3}>{selectedUser.displayName}</Typography.Title><Typography.Text type="secondary">{selectedUser.username} · 授权版本 {selectedUser.authzVersion}</Typography.Text></div>
                <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>保存角色</Button>
              </header>
              <div className="security-user-summary">
                <div><span>当前角色</span><Space size={[4, 4]} wrap>{selectedUser.roleIds.map((id) => <Tag key={id}>{roleMap.get(id)?.name ?? `#${id}`}</Tag>)}</Space></div>
                <div><span>数据部门</span><Typography.Text>{selectedUser.departmentId ?? '未设置'}</Typography.Text></div>
                <div><span>管理员</span><Typography.Text>{effective?.admin ? '是' : '否'}</Typography.Text></div>
              </div>
              <section className="security-user-section">
                <div className="security-permission-toolbar"><div><Typography.Title level={5}><KeyOutlined /> 角色分配</Typography.Title><Typography.Text type="secondary">角色权限按并集生效，只有管理员可以修改</Typography.Text></div></div>
                <Checkbox.Group value={roleIds} onChange={(values) => setRoleIds(values as number[])}>
                  <div className="security-role-options">{roles.map((role) => <Checkbox key={role.id} value={role.id} disabled={!role.enabled}><span>{role.name}</span>{role.builtin && <Tag>内置</Tag>}</Checkbox>)}</div>
                </Checkbox.Group>
              </section>
              <section className="security-user-section">
                <div className="security-permission-toolbar"><div><Typography.Title level={5}><EyeOutlined /> 有效权限</Typography.Title><Typography.Text type="secondary">来自所有启用角色的权限并集</Typography.Text></div><Tag color="blue">{effective?.permissions.length ?? 0} 项</Tag></div>
                {effective && <div className="security-permission-grid">
                  <div className="security-permission-panel"><div className="security-permission-panel__title"><span>页面访问</span></div><Tree checkable checkStrictly checkedKeys={effective.permissions} treeData={permissionTree(pagePermissions, effective.permissions)} selectable={false} showLine={{ showLeafIcon: false }} /></div>
                  <div className="security-permission-panel"><div className="security-permission-panel__title"><span>操作权限</span></div><Tree checkable checkStrictly checkedKeys={effective.permissions} treeData={permissionTree(actionPermissions, effective.permissions)} selectable={false} showLine={{ showLeafIcon: false }} /></div>
                </div>}
              </section>
            </>
          )}
        </main>
      </div>
    </PageContainer>
  );
}
