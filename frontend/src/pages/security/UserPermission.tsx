import { EyeOutlined, KeyOutlined, SearchOutlined, SaveOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  App,
  Button,
  Checkbox,
  Empty,
  Input,
  List,
  Pagination,
  Space,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import { request, useModel } from '@umijs/max';
import { useCallback, useEffect, useMemo, useState } from 'react';
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
  permissionScopes: Record<string, { modes: string[]; departmentIds: number[]; all: boolean }>;
};
type UserPage = { records: UserAssignment[]; total: number; page: number; size: number };

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
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [roleIds, setRoleIds] = useState<number[]>([]);
  const [effective, setEffective] = useState<EffectivePermission | null>(null);
  const [saving, setSaving] = useState(false);
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');

  const loadCatalog = useCallback(async () => {
    const [roleRows, permissionRows] = await Promise.all([
      request<Role[]>('/api/security/roles'),
      request<Permission[]>('/api/security/permissions'),
    ]);
    setRoles(roleRows); setPermissions(permissionRows);
  }, []);
  const loadUsers = useCallback(async () => {
    const result = await request<UserPage>('/api/security/users', {
      params: { page, size: 50, keyword: keyword || undefined },
    });
    setUsers(result.records);
    setTotal(result.total);
    setSelectedId((current) => result.records.some((user: UserAssignment) => user.id === current)
      ? current : result.records[0]?.id ?? null);
  }, [keyword, page]);
  useEffect(() => { if (isAdmin) void loadCatalog(); }, [isAdmin, loadCatalog]);
  useEffect(() => { if (isAdmin) void loadUsers(); }, [isAdmin, loadUsers]);
  useEffect(() => {
    const timer = window.setTimeout(() => { setKeyword(search.trim()); setPage(1); }, 300);
    return () => window.clearTimeout(timer);
  }, [search]);
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
      await loadUsers();
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
            <div><Typography.Title level={4}>用户权限</Typography.Title><Typography.Text type="secondary">共 {total} 个用户</Typography.Text></div>
          </div>
          <Input allowClear prefix={<SearchOutlined />} placeholder="搜索姓名、账号或工号" value={search} onChange={(event) => setSearch(event.target.value)} />
          <List
            className="security-role-list"
            dataSource={users}
            renderItem={(user) => (
              <List.Item className={`security-role-list__item${selectedId === user.id ? ' is-active' : ''}`} onClick={() => setSelectedId(user.id)}>
                <div><Typography.Text strong>{user.displayName}</Typography.Text><Typography.Text type="secondary" className="security-role-list__code">{user.username} · {user.employeeNo}</Typography.Text></div>
                <Tag color={user.status === 'ACTIVE' ? 'green' : 'default'}>{user.status === 'ACTIVE' ? '启用' : '停用'}</Tag>
              </List.Item>
            )}
          />
          <Pagination simple current={page} pageSize={50} total={total}
            onChange={setPage} style={{ marginTop: 12, textAlign: 'center' }} />
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
                {effective && (
                  <div style={{ marginTop: 16 }}>
                    <Typography.Title level={5}>权限数据范围</Typography.Title>
                    <Space size={[6, 6]} wrap>
                      {effective.permissions.map((code) => {
                        const scope = effective.permissionScopes?.[code];
                        if (!scope) return null;
                        const label = scope.all ? '全部部门' : scope.modes.includes('SELF') && !scope.departmentIds.length
                          ? '本人' : `${scope.modes.join(' / ')}${scope.departmentIds.length ? ` · ${scope.departmentIds.length} 个部门` : ''}`;
                        return <Tag key={code}>{displayPermissionName(code, code)}：{label}</Tag>;
                      })}
                    </Space>
                  </div>
                )}
              </section>
            </>
          )}
        </main>
      </div>
    </PageContainer>
  );
}
