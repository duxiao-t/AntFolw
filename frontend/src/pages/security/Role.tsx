import {
  CheckOutlined,
  DeleteOutlined,
  LockOutlined,
  PlusOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
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
import { request, useModel } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import './Security.less';
import { displayPermissionName } from './permissionLabels';
import {
  mergePermissionTreeSelection,
  resolvePermissionSelection,
} from './permissionDependencies';

type Permission = {
  code: string;
  name: string;
  category: string;
  riskLevel: 'NORMAL' | 'HIGH' | 'CRITICAL';
  sortOrder: number;
  kind: 'PAGE' | 'ACTION';
  adminOnly: boolean;
  requiredPermissionCodes: string[];
};

type Role = {
  id: number;
  code: string;
  name: string;
  description?: string;
  dataScope: string;
  enabled: boolean;
  builtin: boolean;
  version: number;
  permissionCodes: string[];
  customDepartmentIds: number[];
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

const riskLabel = { NORMAL: '普通', HIGH: '高风险', CRITICAL: '关键' };
const riskColor = { NORMAL: 'default', HIGH: 'orange', CRITICAL: 'red' };

function buildPermissionTree(
  permissions: Permission[],
  disabled: boolean,
): DataNode[] {
  const groups = new Map<string, Permission[]>();
  permissions.forEach((permission) => {
    groups.set(permission.category, [
      ...(groups.get(permission.category) ?? []), permission,
    ]);
  });
  return [...groups.entries()].map(([category, items]) => ({
    key: `group:${category}`,
    title: <span className="security-tree__group">{category}</span>,
    disableCheckbox: disabled,
    children: items.sort((a, b) => a.sortOrder - b.sortOrder).map((permission) => ({
      key: permission.code,
      disabled: disabled || permission.adminOnly,
      title: (
        <span className="security-tree__item">
          <span>
            <strong>{displayPermissionName(permission.code, permission.name)}</strong>
            <code>{permission.code}</code>
          </span>
          {permission.adminOnly && <Tag icon={<LockOutlined />}>仅管理员</Tag>}
          {permission.riskLevel !== 'NORMAL' && (
            <Tag color={riskColor[permission.riskLevel]}>{riskLabel[permission.riskLevel]}</Tag>
          )}
        </span>
      ),
    })),
  }));
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
  const [selectedPermissions, setSelectedPermissions] = useState<Set<string>>(new Set());
  const [autoAddedPermissions, setAutoAddedPermissions] = useState<Set<string>>(new Set());
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const canWrite = isAdmin || (currentUser?.permissions ?? []).includes('security.role.write');
  const dataScope = Form.useWatch('dataScope', form);

  const load = async () => {
    const [roleRows, permissionRows] = await Promise.all([
      request<Role[]>('/api/security/roles'),
      request<Permission[]>('/api/security/permissions'),
    ]);
    setRoles(roleRows);
    setPermissions(permissionRows);
    if (canWrite) {
      const rows = await request<DepartmentCandidate[]>('/api/security/role-department-candidates');
      setDepartments(rows);
    }
  };

  useEffect(() => { load().catch(() => undefined); }, [canWrite]);

  const filteredRoles = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return roles;
    return roles.filter((role) => `${role.name} ${role.code}`.toLowerCase().includes(query));
  }, [roles, search]);
  const pagePermissions = useMemo(() => permissions.filter((permission) => permission.kind === 'PAGE'), [permissions]);
  const actionPermissions = useMemo(() => permissions.filter((permission) => permission.kind === 'ACTION'), [permissions]);
  const disabledEditor = !canWrite || !!editing?.builtin;

  const edit = (role?: Role) => {
    setDraft(!role);
    setEditing(role ?? null);
    const values = role ?? {
      code: '', name: '', description: '', dataScope: 'SELF', enabled: true,
      permissionCodes: [], customDepartmentIds: [],
    };
    form.setFieldsValue(values);
    setSelectedPermissions(new Set(values.permissionCodes));
    setAutoAddedPermissions(new Set());
  };

  const updatePermissionSelection = (checked: React.Key[], activePermissions: Permission[]) => {
    const all = [...pagePermissions, ...actionPermissions];
    const merged = mergePermissionTreeSelection(checked.map(String), selectedPermissions,
      new Set(activePermissions.map((permission) => permission.code)));
    const result = resolvePermissionSelection(merged, selectedPermissions,
      autoAddedPermissions, all);
    if (result.cascaded) {
      message.info('已同步取消依赖该权限的页面或操作');
    }
    setAutoAddedPermissions(result.autoAdded);
    setSelectedPermissions(result.selected);
    form.setFieldValue('permissionCodes', [...result.selected]);
  };

  const checkedTreeKeys = (checked: React.Key[] | { checked: React.Key[] }) => {
    const keys = Array.isArray(checked) ? checked : checked.checked;
    return keys as React.Key[];
  };

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = {
        ...values,
        permissionCodes: [...selectedPermissions],
        version: editing?.version,
      };
      await request(editing ? `/api/security/roles/${editing.id}` : '/api/security/roles', {
        method: editing ? 'PUT' : 'POST',
        data: payload,
      });
      message.success(editing ? '角色已更新' : '角色已创建');
      setDraft(false);
      await load();
      const saved = await request<Role[]>('/api/security/roles');
      const selected = saved.find((role) => role.code === values.code);
      if (selected) {
        setRoles(saved);
        setEditing(selected);
        form.setFieldsValue(selected);
        setSelectedPermissions(new Set(selected.permissionCodes));
      }
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

  const pageCheckedKeys = pagePermissions.filter((permission) => selectedPermissions.has(permission.code))
    .map((permission) => permission.code);
  const actionCheckedKeys = actionPermissions.filter((permission) => selectedPermissions.has(permission.code))
    .map((permission) => permission.code);
  const pageTree = buildPermissionTree(pagePermissions, disabledEditor);
  const actionTree = buildPermissionTree(actionPermissions, disabledEditor);

  return (
    <PageContainer title={false} className="security-page">
      <div className="security-workspace">
        <aside className="security-sidebar">
          <div className="security-sidebar__header">
            <div>
              <Typography.Title level={4}>角色</Typography.Title>
              <Typography.Text type="secondary">{roles.length} 个角色</Typography.Text>
            </div>
            {canWrite && <Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>新建</Button>}
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
                  <Typography.Text type="secondary" className="security-role-list__code">{role.code}</Typography.Text>
                </div>
                <Tag color={role.enabled ? 'green' : 'default'}>{role.enabled ? '启用' : '停用'}</Tag>
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
                  <Typography.Title level={3}>{editing ? editing.name : '新建角色'}</Typography.Title>
                  <Typography.Text type="secondary">
                    {editing?.code ?? '配置角色的页面访问与操作权限'}
                  </Typography.Text>
                </div>
                <Space>
                  {editing && !editing.builtin && canWrite && (
                    <Popconfirm title="删除该角色？" description="仅未分配成员的角色可删除。" onConfirm={() => remove(editing)}>
                      <Button danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                  )}
                  {canWrite && <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={disabledEditor} onClick={save}>保存更改</Button>}
                </Space>
              </header>

              <Form form={form} layout="vertical" requiredMark="optional" className="security-form">
                <div className="security-form__grid">
                  <Form.Item label="角色名称" name="name" rules={[{ required: true, message: '请输入角色名称' }]}>
                    <Input disabled={disabledEditor} maxLength={128} />
                  </Form.Item>
                  <Form.Item label="角色编码" name="code" rules={[{ required: true, message: '请输入角色编码' }]}>
                    <Input disabled={disabledEditor || !!editing} maxLength={64} placeholder="department_manager" />
                  </Form.Item>
                </div>
                <Form.Item label="说明" name="description">
                  <Input.TextArea disabled={disabledEditor} rows={2} maxLength={500} />
                </Form.Item>
                <div className="security-form__grid">
                  <Form.Item label="数据范围" name="dataScope" rules={[{ required: true }]}>
                    <Select disabled={disabledEditor} options={scopeOptions} />
                  </Form.Item>
                  <Form.Item label="状态" name="enabled" valuePropName="checked">
                    <Select disabled={disabledEditor} options={[{ value: true, label: '启用' }, { value: false, label: '停用' }]} />
                  </Form.Item>
                </div>
                {dataScope === 'CUSTOM' && (
                  <Form.Item label="指定部门" name="customDepartmentIds" rules={[{ required: true, message: '请选择部门' }]}>
                    <Select disabled={disabledEditor} mode="multiple" showSearch={{ optionFilterProp: 'label' }} options={departments.map((row) => ({ value: row.id, label: row.name }))} />
                  </Form.Item>
                )}
                <div className="security-permission-toolbar">
                  <div>
                    <Typography.Title level={5}>权限路径</Typography.Title>
                    <Typography.Text type="secondary">页面决定可见范围，操作决定可执行动作</Typography.Text>
                  </div>
                  <Space size={6}>
                    <Tag color="blue">页面 {pagePermissions.filter((item) => selectedPermissions.has(item.code)).length}</Tag>
                    <Tag>操作 {actionPermissions.filter((item) => selectedPermissions.has(item.code)).length}</Tag>
                  </Space>
                </div>
                <div className="security-permission-grid">
                  <section className="security-permission-panel">
                    <div className="security-permission-panel__title"><span>页面访问</span><Typography.Text type="secondary">菜单与路由</Typography.Text></div>
                    <Tree checkable checkedKeys={pageCheckedKeys} treeData={pageTree}
                      onCheck={(checked) => updatePermissionSelection(checkedTreeKeys(checked), pagePermissions)}
                      showLine={{ showLeafIcon: false }} />
                  </section>
                  <section className="security-permission-panel">
                    <div className="security-permission-panel__title"><span>操作权限</span><Typography.Text type="secondary">按钮与接口</Typography.Text></div>
                    <Tree checkable checkedKeys={actionCheckedKeys} treeData={actionTree}
                      onCheck={(checked) => updatePermissionSelection(checkedTreeKeys(checked), actionPermissions)}
                      showLine={{ showLeafIcon: false }} />
                  </section>
                </div>
                {editing?.builtin && (
                  <div className="security-readonly-note"><CheckOutlined /> 内置角色策略不可编辑</div>
                )}
              </Form>
            </>
          )}
        </main>
      </div>
    </PageContainer>
  );
}
