import { ProTable } from '@ant-design/pro-components';
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, ImportOutlined, KeyOutlined,
  LockOutlined, UnlockOutlined, UserAddOutlined,
} from '@ant-design/icons';
import { Button, Form, Grid, Input, Modal, Popconfirm, Select, Space, Tag, Tooltip } from 'antd';
import type { FormInstance } from 'antd';
import type { ChangeEvent, Key, RefObject } from 'react';
import { useMemo, useState } from 'react';
import { formatGender, normalizeGender } from './Contacts.utils';

export interface MemberListItem {
  id: number;
  employeeNo: string;
  username: string;
  displayName: string;
  email: string;
  phone: string;
  position: string;
  gender: string;
  deptId: number;
  managerId?: number | null;
  managerDisplayName?: string | null;
  status: string;
  wecomMapped?: boolean;
  wecomStatus?: number | null;
  wecomDirectoryPresent?: boolean;
  departmentLeader?: boolean;
}

export function MemberGenderTag({ value }: { value?: string }) {
  const genderLabel = formatGender(value);
  if (genderLabel === '男') return <Tag color="blue">男</Tag>;
  if (genderLabel === '女') return <Tag color="pink">女</Tag>;
  return genderLabel || '-';
}

function wecomStatus(member: MemberListItem) {
  if (!member.wecomMapped) return { label: '本地账号', color: undefined };
  if (!member.wecomDirectoryPresent) return { label: '已移出通讯录', color: 'default' };
  switch (member.wecomStatus) {
    case 1: return { label: '已激活', color: 'green' };
    case 2: return { label: '已禁用', color: 'red' };
    case 4: return { label: '未激活', color: 'gold' };
    case 5: return { label: '已退出', color: 'default' };
    case null:
    case undefined: return { label: '待同步', color: 'default' };
    default: return { label: `未知状态（${member.wecomStatus}）`, color: 'default' };
  }
}

function loginControlDisabledReason(member: MemberListItem) {
  if (!member.wecomDirectoryPresent) return '该成员已移出企业微信通讯录，不能修改登录权限';
  if (member.wecomStatus == null) return '请先同步企业微信状态';
  if (member.wecomStatus !== 1 && member.wecomStatus !== 4) {
    return '当前企业微信状态不允许修改登录权限';
  }
  return '';
}

export function MemberAccountStatus({ member }: { member: MemberListItem }) {
  const source = wecomStatus(member);
  const loginEnabled = member.status === 'ACTIVE';
  return (
    <Space className="ct-account-status" size={[4, 4]} wrap>
      <Tag color={source.color}>{source.label}</Tag>
      <Tag color={loginEnabled ? 'green' : 'red'}>
        {loginEnabled ? '可登录' : '禁止登录'}
      </Tag>
    </Space>
  );
}

export function LeaderPicker({ users, currentLeaderIds, onOk, onCancel, saving }: {
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

export function MembersSection({
  breadcrumb,
  members,
  total,
  currentPage,
  selectedMemberIds,
  deptNameById,
  importInputRef,
  onSelectedMemberIdsChange,
  onPageChange,
  onAdd,
  onEdit,
  onRemove,
  onBulkRemove,
  onExport,
  onImport,
  canAdd,
  canResetPassword,
  canControlLogin,
  canManageUsersByDept,
  onResetPassword,
  onSetLoginAccess,
  loginAccessLoadingId,
}: {
  breadcrumb: string;
  members: MemberListItem[];
  total: number;
  currentPage: number;
  selectedMemberIds: Key[];
  deptNameById: Record<number, string>;
  importInputRef: RefObject<HTMLInputElement | null>;
  onSelectedMemberIdsChange: (keys: Key[]) => void;
  onPageChange: (page: number) => void;
  onAdd: () => void;
  onEdit: (member: MemberListItem) => void;
  onRemove: (id: number) => void;
  onBulkRemove: () => void;
  onExport: () => void;
  onImport: (event: ChangeEvent<HTMLInputElement>) => void;
  canAdd?: boolean;
  canResetPassword?: boolean;
  canControlLogin?: boolean;
  canManageUsersByDept?: Record<number, boolean>;
  onResetPassword?: (member: MemberListItem) => void;
  onSetLoginAccess?: (member: MemberListItem, enabled: boolean) => void;
  loginAccessLoadingId?: number;
}) {
  const screens = Grid.useBreakpoint();
  const fixedColumns = !!screens.xl;
  return (
    <>
      <div className="ct-right-header">
        <h2>{breadcrumb} · {total}人</h2>
        <Space wrap>
          <Button icon={<UserAddOutlined />} type="primary" onClick={onAdd} disabled={!canAdd}>添加成员</Button>
          <Popconfirm
            title={`确定删除选中的 ${selectedMemberIds.length} 名成员?`}
            disabled={!selectedMemberIds.length}
            onConfirm={onBulkRemove}
          >
            <Button danger icon={<DeleteOutlined />} disabled={!selectedMemberIds.length}>批量删除</Button>
          </Popconfirm>
          <Button icon={<ImportOutlined />} disabled={!canAdd} onClick={() => importInputRef.current?.click()}>批量导入</Button>
          <Button icon={<DownloadOutlined />} onClick={onExport}>导出</Button>
          <input ref={importInputRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={onImport} />
        </Space>
      </div>
      <ProTable<MemberListItem>
        rowKey="id"
        columns={[
          { title: '姓名', dataIndex: 'displayName', width: 180,
            fixed: fixedColumns ? 'left' : undefined, render: (_, r) => (
            <span className="ct-member-name">
              <span className="ct-member-name__text" title={r.displayName}>{r.displayName}</span>
              {r.departmentLeader && <Tag className="ct-leader-tag">负责人</Tag>}
            </span>
          ) },
          { title: '工号', dataIndex: 'employeeNo', width: 100, ellipsis: true },
          { title: '账号', dataIndex: 'username', width: 100, ellipsis: true },
          { title: '手机', dataIndex: 'phone', width: 130, ellipsis: true },
          { title: '部门', dataIndex: 'deptId', width: 150, ellipsis: true,
            render: (_, r) => deptNameById[r.deptId] ?? '-' },
          { title: '职务', dataIndex: 'position', width: 180, ellipsis: true },
          { title: '直属上级', dataIndex: 'managerDisplayName', width: 110, ellipsis: true,
            render: (value) => value || '-' },
          { title: '账号状态', key: 'accountStatus', width: 150,
            fixed: fixedColumns ? 'right' : undefined,
            render: (_, r) => <MemberAccountStatus member={r} /> },
          { title: '性别', dataIndex: 'gender', width: 70,
            render: (_, r) => <MemberGenderTag value={r.gender} /> },
          { title: '操作', key: 'op', width: 300,
            fixed: fixedColumns ? 'right' : undefined, render: (_, r) => {
            const canManage = !!canManageUsersByDept?.[r.deptId];
            const loginEnabled = r.status === 'ACTIVE';
            const loginControlReason = loginControlDisabledReason(r);
            return (
              <Space size={2}>
                <Button type="text" size="small" icon={<EditOutlined />} disabled={!canManage} onClick={() => onEdit(r)}>编辑</Button>
                {canResetPassword && (
                  <Button type="text" size="small" icon={<KeyOutlined />} onClick={() => onResetPassword?.(r)}>重置密码</Button>
                )}
                {canControlLogin && r.wecomMapped && (loginControlReason ? (
                  <Tooltip title={loginControlReason}>
                    <span>
                      <Button type="text" size="small" disabled icon={<LockOutlined />}>登录控制</Button>
                    </span>
                  </Tooltip>
                ) : (
                  <Popconfirm
                    title={`确定${loginEnabled ? '禁止' : '允许'} ${r.displayName} 登录？`}
                    description={loginEnabled
                      ? '该用户已有的登录会话将立即失效'
                      : '允许后可使用账号密码登录 AntFlow'}
                    okText="确定"
                    cancelText="取消"
                    onConfirm={() => onSetLoginAccess?.(r, !loginEnabled)}
                  >
                    <Button
                      type="text"
                      size="small"
                      danger={loginEnabled}
                      loading={loginAccessLoadingId === r.id}
                      icon={loginEnabled ? <LockOutlined /> : <UnlockOutlined />}
                    >
                      {loginEnabled ? '禁止登录' : '允许登录'}
                    </Button>
                  </Popconfirm>
                ))}
                <Popconfirm title="确定删除?" disabled={!canManage} onConfirm={() => onRemove(r.id)}>
                  <Button type="text" danger size="small" icon={<DeleteOutlined />} disabled={!canManage} aria-label={`删除 ${r.displayName}`} />
                </Popconfirm>
              </Space>
            );
          } },
        ]}
        dataSource={members}
        rowSelection={{
          fixed: fixedColumns,
          columnWidth: 40,
          selectedRowKeys: selectedMemberIds,
          onChange: onSelectedMemberIdsChange,
          getCheckboxProps: (record) => ({ disabled: !canManageUsersByDept?.[record.deptId] }),
        }}
        search={false}
        options={false}
        scroll={{ x: 1510 }}
        pagination={{ current: currentPage, pageSize: 15, total, showSizeChanger: false,
          onChange: onPageChange }}
      />
    </>
  );
}

export function MemberFormModal({
  open,
  editing,
  form,
  saving,
  onOk,
  onCancel,
  canResetPassword,
  onResetPassword,
  managerOptions,
  managerLoading,
  onManagerSearch,
}: {
  open: boolean;
  editing: MemberListItem | null;
  form: FormInstance;
  saving: boolean;
  onOk: () => void;
  onCancel: () => void;
  canResetPassword?: boolean;
  onResetPassword?: () => void;
  managerOptions: Array<{ value: number; label: string }>;
  managerLoading?: boolean;
  onManagerSearch: (keyword: string) => void;
}) {
  return (
    <Modal
      title={editing ? '编辑成员' : '添加成员'}
      open={open}
      confirmLoading={saving}
      width={600}
      onOk={onOk}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Form className="ct-member-form" form={form} layout="vertical" preserve={false}
        initialValues={editing ? { ...editing, gender: normalizeGender(editing.gender) } : undefined}>
        <div className="ct-member-form__grid">
          <Form.Item label="姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="工号" name="employeeNo" rules={[{ pattern: /^\S{1,64}$/, message: '工号必须为 1 至 64 位且不能包含空白字符' }]} extra="留空时自动生成；企业微信成员使用企微账号"><Input maxLength={64} /></Form.Item>
          <Form.Item label="账号" name="username" rules={[{ required: true }]}><Input disabled={!!editing} /></Form.Item>
          <Form.Item label="手机" name="phone"><Input /></Form.Item>
          <Form.Item label="邮箱" name="email"><Input /></Form.Item>
          <Form.Item label="职务" name="position"><Input /></Form.Item>
          <Form.Item label="直属上级" name="managerId">
            <Select
              allowClear
              showSearch
              filterOption={false}
              loading={managerLoading}
              options={managerOptions}
              placeholder="搜索姓名、账号或工号"
              onSearch={onManagerSearch}
            />
          </Form.Item>
          {!editing && (
            <>
              <Form.Item label="初始密码" name="password" rules={[
                { required: true, message: '请设置初始密码' },
                { min: 8, max: 64, message: '密码长度为 8 到 64 位' },
              ]}>
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item label="确认密码" name="confirmPassword" dependencies={['password']} rules={[
                { required: true, message: '请再次输入密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    return !value || getFieldValue('password') === value
                      ? Promise.resolve()
                      : Promise.reject(new Error('两次输入的密码不一致'));
                  },
                }),
              ]}>
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </>
          )}
          <Form.Item label="性别" name="gender">
            <Select allowClear options={[{ value: 'M', label: '男' }, { value: 'F', label: '女' }]} />
          </Form.Item>
        </div>
        {editing && canResetPassword && (
          <div className="ct-member-form__security">
            <div>
              <strong>登录密码</strong>
              <span>重置后，该用户需要使用新密码重新登录</span>
            </div>
            <Button icon={<KeyOutlined />} onClick={onResetPassword}>重置密码</Button>
          </div>
        )}
      </Form>
    </Modal>
  );
}
