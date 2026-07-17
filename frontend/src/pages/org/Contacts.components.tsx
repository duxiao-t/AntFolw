import { ProTable } from '@ant-design/pro-components';
import {
  DeleteOutlined, DownloadOutlined, ImportOutlined, UserAddOutlined,
} from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Tag } from 'antd';
import type { FormInstance } from 'antd';
import type { ChangeEvent, Key, RefObject } from 'react';
import { useMemo, useState } from 'react';
import { formatGender, normalizeGender } from './Contacts.utils';

export interface MemberListItem {
  id: number;
  username: string;
  displayName: string;
  email: string;
  phone: string;
  position: string;
  gender: string;
  deptId: number;
}

export function MemberGenderTag({ value }: { value?: string }) {
  const genderLabel = formatGender(value);
  if (genderLabel === '男') return <Tag color="blue">男</Tag>;
  if (genderLabel === '女') return <Tag color="pink">女</Tag>;
  return genderLabel || '-';
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
  selectedMemberIds,
  deptNameById,
  importInputRef,
  onSelectedMemberIdsChange,
  onAdd,
  onEdit,
  onRemove,
  onBulkRemove,
  onExport,
  onImport,
}: {
  breadcrumb: string;
  members: MemberListItem[];
  selectedMemberIds: Key[];
  deptNameById: Record<number, string>;
  importInputRef: RefObject<HTMLInputElement | null>;
  onSelectedMemberIdsChange: (keys: Key[]) => void;
  onAdd: () => void;
  onEdit: (member: MemberListItem) => void;
  onRemove: (id: number) => void;
  onBulkRemove: () => void;
  onExport: () => void;
  onImport: (event: ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <>
      <div className="ct-right-header">
        <h2>{breadcrumb} · {members.length}人</h2>
        <Space>
          <Button icon={<UserAddOutlined />} type="primary" onClick={onAdd}>添加成员</Button>
          <Popconfirm
            title={`确定删除选中的 ${selectedMemberIds.length} 名成员?`}
            disabled={!selectedMemberIds.length}
            onConfirm={onBulkRemove}
          >
            <Button danger icon={<DeleteOutlined />} disabled={!selectedMemberIds.length}>批量删除</Button>
          </Popconfirm>
          <Button icon={<ImportOutlined />} onClick={() => importInputRef.current?.click()}>批量导入</Button>
          <Button icon={<DownloadOutlined />} onClick={onExport}>导出</Button>
          <input ref={importInputRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={onImport} />
        </Space>
      </div>
      <ProTable<MemberListItem>
        rowKey="id"
        columns={[
          { title: '姓名', dataIndex: 'displayName' },
          { title: '账号', dataIndex: 'username' },
          { title: '手机', dataIndex: 'phone' },
          { title: '部门', dataIndex: 'deptId', render: (_, r) => deptNameById[r.deptId] ?? '-' },
          { title: '职务', dataIndex: 'position' },
          { title: '性别', dataIndex: 'gender', render: (_, r) => <MemberGenderTag value={r.gender} /> },
          { title: '操作', key: 'op', width: 160, render: (_, r) => (
            <Space>
              <a onClick={() => onEdit(r)}>编辑</a>
              <Popconfirm title="确定删除?" onConfirm={() => onRemove(r.id)}>
                <a style={{ color: '#ff4d4f' }}>删除</a>
              </Popconfirm>
            </Space>
          )},
        ]}
        dataSource={members}
        rowSelection={{
          selectedRowKeys: selectedMemberIds,
          onChange: onSelectedMemberIdsChange,
        }}
        search={false}
        options={false}
        pagination={{ pageSize: 15 }}
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
}: {
  open: boolean;
  editing: MemberListItem | null;
  form: FormInstance;
  saving: boolean;
  onOk: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal
      title={editing ? '编辑成员' : '添加成员'}
      open={open}
      confirmLoading={saving}
      width={520}
      onOk={onOk}
      onCancel={onCancel}
      destroyOnClose
    >
      <Form form={form} layout="vertical" preserve={false}
        initialValues={editing ? { ...editing, gender: normalizeGender(editing.gender) } : undefined}>
        <Form.Item label="姓名" name="displayName" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item label="账号" name="username" rules={[{ required: true }]}><Input disabled={!!editing} /></Form.Item>
        <Form.Item label="手机" name="phone"><Input /></Form.Item>
        <Form.Item label="邮箱" name="email"><Input /></Form.Item>
        <Form.Item label="职务" name="position"><Input /></Form.Item>
        <Form.Item label="性别" name="gender">
          <Select allowClear options={[{ value: 'M', label: '男' }, { value: 'F', label: '女' }]} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
