import {
  DeleteOutlined,
  LockOutlined,
  PlusOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { request, useModel } from '@umijs/max';
import {
  App,
  Button,
  Empty,
  Form,
  Input,
  List,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tree,
  Typography,
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useEffect, useMemo, useState } from 'react';
import './Security.less';

type Permission = {
  code: string;
  name: string;
  domain: string;
  domainLabel: string;
  riskLevel: 'NORMAL' | 'HIGH' | 'CRITICAL';
  sortOrder: number;
  adminOnly: boolean;
  scopeable: boolean;
  defaultScope: string | null;
};

type Grant = { code: string; scopeOverride: string | null; departmentIds: number[] };

type Role = {
  id: number;
  code: string;
  name: string;
  description?: string;
  enabled: boolean;
  builtin: boolean;
  version: number;
  permissions: Grant[];
  userCount: number;
};

type DepartmentCandidate = { id: number; name: string };

const scopeOptions = [
  { value: 'SELF', label: '仅本人' },
  { value: 'DEPARTMENT', label: '本部门' },
  { value: 'DEPARTMENT_AND_DESCENDANTS', label: '本部门及下级' },
  { value: 'CUSTOM', label: '指定部门' },
  { value: 'ALL', label: '全部数据' },
];

const scopeLabels: Record<string, string> = Object.fromEntries(
  scopeOptions.map((option) => [option.value, option.label]),
);
const riskLabel = { NORMAL: '普通', HIGH: '高风险', CRITICAL: '关键' };
const riskColor = { NORMAL: 'default', HIGH: 'orange', CRITICAL: 'red' };

/** 把能力点按域分组，域顺序即目录顺序。 */
function groupByDomain(permissions: Permission[]): Array<[string, Permission[]]> {
  const groups = new Map<string, Permission[]>();
  permissions.forEach((permission) => {
    groups.set(permission.domainLabel, [...(groups.get(permission.domainLabel) ?? []), permission]);
  });
  return [...groups.entries()];
}

export default function RolePage() {
  const [form] = Form.useForm();
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [departments, setDepartments] = useState<DepartmentCandidate[]>([]);
  const [editing, setEditing] = useState<Role | null>(null);
  const [draft, setDraft] = useState(false);
  const [search, setSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [grants, setGrants] = useState<Map<string, Grant>>(new Map());
  const currentUser = initialState?.currentUser as
    | { roles?: string[]; permissions?: string[] }
    | undefined;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const canWrite = isAdmin || (currentUser?.permissions ?? []).includes('security:role:manage');

  const load = async () => {
    const [roleRows, permissionRows] = await Promise.all([
      request<Role[]>('/api/security/roles'),
      request<Permission[]>('/api/security/permissions'),
    ]);
    setRoles(roleRows);
    setPermissions(permissionRows);
    if (canWrite) {
      setDepartments(
        await request<DepartmentCandidate[]>('/api/security/role-department-candidates'),
      );
    }
  };

  useEffect(() => { load().catch(() => undefined); }, [canWrite]);

  const filteredRoles = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return roles;
    return roles.filter((role) => `${role.name} ${role.code}`.toLowerCase().includes(query));
  }, [roles, search]);

  const disabledEditor = !canWrite || !!editing?.builtin;
  const permissionByCode = useMemo(
    () => new Map(permissions.map((permission) => [permission.code, permission])),
    [permissions],
  );

  const edit = (role?: Role) => {
    setDraft(!role);
    setEditing(role ?? null);
    form.setFieldsValue(role ?? { code: '', name: '', description: '', enabled: true });
    setGrants(new Map((role?.permissions ?? []).map((grant) => [grant.code, grant])));
  };

  const updateGrant = (code: string, patch: Partial<Grant>) => {
    setGrants((current) => {
      const next = new Map(current);
      const existing = next.get(code) ?? { code, scopeOverride: null, departmentIds: [] };
      next.set(code, { ...existing, ...patch });
      return next;
    });
  };

  const permissionTree = useMemo((): DataNode[] => {
    return groupByDomain(permissions).map(([domainLabel, items]) => ({
      key: `group:${domainLabel}`,
      title: <span className="security-tree__group">{domainLabel}</span>,
      disableCheckbox: true,
      selectable: false,
      children: items.sort((a, b) => a.sortOrder - b.sortOrder).map((permission) => {
        const grant = grants.get(permission.code);
        return {
          key: permission.code,
          disabled: disabledEditor || (!isAdmin && permission.adminOnly),
          title: (
            <span className="security-tree__item">
              <span>
                <strong>{permission.name}</strong>
                <code>{permission.code}</code>
              </span>
              <Space size={6} onClick={(event) => event.stopPropagation()}>
                {permission.adminOnly && <Tag icon={<LockOutlined />}>超管专属</Tag>}
                {permission.riskLevel !== 'NORMAL' && (
                  <Tag color={riskColor[permission.riskLevel]}>
                    {riskLabel[permission.riskLevel]}
                  </Tag>
                )}
                {permission.scopeable && grant && (
                  <Select
                    size="small"
                    style={{ width: 150 }}
                    disabled={disabledEditor}
                    value={grant.scopeOverride ?? 'DEFAULT'}
                    onChange={(value) => updateGrant(permission.code, {
                      scopeOverride: value === 'DEFAULT' ? null : value,
                      departmentIds: value === 'CUSTOM' ? grant.departmentIds : [],
                    })}
                    options={[
                      {
                        value: 'DEFAULT',
                        label: `默认（${scopeLabels[permission.defaultScope ?? ''] ?? '不限制'}）`,
                      },
                      ...scopeOptions,
                    ]}
                  />
                )}
              </Space>
            </span>
          ),
        };
      }),
    }));
  }, [permissions, grants, disabledEditor, isAdmin]);

  const customGrants = useMemo(
    () => [...grants.values()].filter((grant) => grant.scopeOverride === 'CUSTOM'),
    [grants],
  );

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = {
        ...values,
        version: editing?.version,
        permissions: [...grants.values()].map((grant) => ({
          code: grant.code,
          scopeOverride: grant.scopeOverride,
          departmentIds: grant.departmentIds,
        })),
      };
      await request(editing ? `/api/security/roles/${editing.id}` : '/api/security/roles', {
        method: editing ? 'PUT' : 'POST',
        data: payload,
      });
      message.success(editing ? '角色已更新' : '角色已创建');
      setDraft(false);
      const saved = await request<Role[]>('/api/security/roles');
      setRoles(saved);
      const selected = saved.find((role) => role.code === values.code);
      if (selected) {
        setEditing(selected);
        form.setFieldsValue(selected);
        setGrants(new Map(selected.permissions.map((grant) => [grant.code, grant])));
      }
      window.dispatchEvent(new Event('antflow:refresh-authz'));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (role: Role) => {
    await request(`/api/security/roles/${role.id}`, {
      method: 'DELETE', params: { version: role.version },
    });
    message.success('角色已删除');
    setEditing(null);
    await load();
  };

  return (
    <PageContainer title={false} className="security-page">
      <div className="security-workspace">
        <aside className="security-sidebar">
          <div className="security-sidebar__header">
            <div>
              <Typography.Title level={4}>角色</Typography.Title>
              <Typography.Text type="secondary">{roles.length} 个角色</Typography.Text>
            </div>
            {canWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>新建</Button>
            )}
          </div>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索角色"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <List
            className="security-role-list"
            dataSource={filteredRoles}
            renderItem={(role) => (
              <List.Item
                className={`security-role-list__item${editing?.id === role.id ? ' is-active' : ''}`}
                onClick={() => edit(role)}
              >
                <div>
                  <Space size={6}>
                    <Typography.Text strong>{role.name}</Typography.Text>
                    {role.builtin && <SafetyCertificateOutlined className="security-muted-icon" />}
                  </Space>
                  <Typography.Text type="secondary" className="security-role-list__code">
                    {role.code}
                  </Typography.Text>
                </div>
                <Tag color={role.enabled ? 'green' : 'default'}>
                  {role.enabled ? '启用' : '停用'}
                </Tag>
              </List.Item>
            )}
          />
        </aside>

        <main className="security-editor">
          {!editing && !draft ? (
            <Empty description="选择一个角色开始编辑" />
          ) : (
            <>
              <header className="security-editor__header">
                <div>
                  <Typography.Title level={3}>
                    {editing ? editing.name : '新建角色'}
                  </Typography.Title>
                  <Typography.Text type="secondary">
                    {editing?.code ?? '勾选能力，并只在需要例外时调整数据范围'}
                  </Typography.Text>
                </div>
                <Space>
                  {editing && !editing.builtin && canWrite && (
                    <Popconfirm
                      title="删除该角色？"
                      description="仅未分配成员的角色可删除。"
                      onConfirm={() => remove(editing)}
                    >
                      <Button danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                  )}
                  {canWrite && !editing?.builtin && (
                    <Button
                      type="primary"
                      icon={<SaveOutlined />}
                      loading={saving}
                      onClick={save}
                    >
                      保存
                    </Button>
                  )}
                </Space>
              </header>

              <Form form={form} layout="vertical" className="security-form" disabled={disabledEditor}>
                <div className="security-form__grid">
                  <Form.Item label="角色名称" name="name" rules={[{ required: true, message: '请输入角色名称' }]}>
                    <Input placeholder="例如：表单流程管理员" />
                  </Form.Item>
                  <Form.Item label="角色编码" name="code" rules={[{ required: true, message: '请输入角色编码' }]}>
                    <Input disabled={!!editing} placeholder="小写字母、数字、下划线" />
                  </Form.Item>
                </div>
                <div className="security-form__grid">
                  <Form.Item label="说明" name="description">
                    <Input placeholder="可选" />
                  </Form.Item>
                  <Form.Item label="状态" name="enabled">
                    <Select
                      options={[{ value: true, label: '启用' }, { value: false, label: '停用' }]}
                    />
                  </Form.Item>
                </div>
              </Form>

              {editing?.builtin && (
                <div className="security-readonly-note">内置角色的能力策略不可修改，仅可查看。</div>
              )}

              <div className="security-permission-toolbar">
                <Typography.Title level={5}>能力</Typography.Title>
                <Typography.Text type="secondary">
                  按域勾选。带范围选择的能力可覆盖默认范围，未覆盖即使用默认值。
                </Typography.Text>
              </div>

              <div className="security-permission-panel">
                <Tree
                  checkable
                  selectable={false}
                  disabled={disabledEditor}
                  checkedKeys={[...grants.keys()]}
                  onCheck={(checked) => {
                    const keys = (Array.isArray(checked) ? checked : checked.checked)
                      .map(String)
                      .filter((key) => !key.startsWith('group:'));
                    setGrants((current) => {
                      const next = new Map<string, Grant>();
                      keys.forEach((code) => {
                        next.set(code, current.get(code) ?? {
                          code, scopeOverride: null, departmentIds: [],
                        });
                      });
                      return next;
                    });
                  }}
                  treeData={permissionTree}
                />
              </div>

              {customGrants.length > 0 && (
                <>
                  <div className="security-permission-toolbar">
                    <Typography.Title level={5}>指定部门</Typography.Title>
                    <Typography.Text type="secondary">
                      以下能力选择了「指定部门」，请为每项选择部门。
                    </Typography.Text>
                  </div>
                  <div className="security-permission-panel">
                    <Space direction="vertical" style={{ width: '100%' }}>
                      {customGrants.map((grant) => (
                        <div key={grant.code} className="security-tree__item">
                          <span>
                            <strong>{permissionByCode.get(grant.code)?.name ?? grant.code}</strong>
                            <code>{grant.code}</code>
                          </span>
                          <Select
                            mode="multiple"
                            allowClear
                            style={{ minWidth: 320 }}
                            placeholder="选择部门"
                            disabled={disabledEditor}
                            value={grant.departmentIds}
                            options={departments.map((department) => ({
                              value: department.id, label: department.name,
                            }))}
                            onChange={(value) => updateGrant(grant.code, { departmentIds: value })}
                          />
                        </div>
                      ))}
                    </Space>
                  </div>
                </>
              )}
            </>
          )}
        </main>
      </div>
    </PageContainer>
  );
}
