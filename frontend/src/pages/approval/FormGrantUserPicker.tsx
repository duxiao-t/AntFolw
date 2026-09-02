import { UserAddOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Empty, Input, Modal, Space, Table, Tag, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { request } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import {
  buildGrantDepartmentTree,
  updateGrantUserSelection,
} from './formGrantUserSelection';

export type GrantUser = {
  id: number;
  username: string;
  displayName: string;
  employeeNo?: string;
  departmentId?: number;
  departmentName?: string;
};

export type GrantDepartment = { id: number; parentId?: number; name: string };

type GrantUserPage = { items: GrantUser[]; total: number; page: number; size: number };

type Props = {
  value?: number[];
  onChange?: (value: number[]) => void;
  users?: GrantUser[];
  departments: GrantDepartment[];
  endpoint: string;
};

export default function FormGrantUserPicker({
  value = [], onChange, users = [], departments, endpoint,
}: Props) {
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [departmentId, setDepartmentId] = useState<number>();
  const [knownUsers, setKnownUsers] = useState<Map<number, GrantUser>>(new Map());
  const [draft, setDraft] = useState<Map<number, GrantUser>>(new Map());

  useEffect(() => {
    setKnownUsers((current) => {
      const next = new Map(current);
      users.forEach((user) => {
        next.set(user.id, user);
      });
      return next;
    });
  }, [users]);

  const { data, isFetching } = useQuery<GrantUserPage>({
    queryKey: ['form-grant-user-candidates', endpoint, page, size, keyword, departmentId],
    queryFn: () => request(endpoint, {
      params: { page, size, keyword: keyword || undefined, departmentId },
    }),
    enabled: open,
  });

  useEffect(() => {
    if (!data?.items) return;
    setKnownUsers((current) => {
      const next = new Map(current);
      data.items.forEach((user) => {
        next.set(user.id, user);
      });
      return next;
    });
  }, [data?.items]);

  const openPicker = () => {
    setDraft(new Map(value.map((id) => [id, knownUsers.get(id) ?? { id, username: '', displayName: `用户 #${id}` }])));
    setOpen(true);
  };
  const selectedUsers: GrantUser[] = value.map((id) => knownUsers.get(id) ?? {
    id, username: '', displayName: `用户 #${id}`,
  });
  const treeData = useMemo<DataNode[]>(() => buildGrantDepartmentTree(departments), [departments]);

  return (
    <div>
      <Space size={[6, 6]} wrap>
        {selectedUsers.slice(0, 5).map((user) => (
          <Tag key={user.id} closable onClose={() => onChange?.(value.filter((id) => id !== user.id))}>
            {user.displayName}{user.employeeNo ? ` · ${user.employeeNo}` : ''}
          </Tag>
        ))}
        {selectedUsers.length > 5 && <Tag>另有 {selectedUsers.length - 5} 人</Tag>}
        <Button icon={<UserAddOutlined />} onClick={openPicker}>
          {selectedUsers.length ? `管理指定人员（${selectedUsers.length}）` : '选择指定人员'}
        </Button>
      </Space>

      <Modal
        title="选择可见人员"
        open={open}
        width={920}
        onCancel={() => setOpen(false)}
        onOk={() => {
          setKnownUsers((current) => new Map([...current, ...draft]));
          onChange?.([...draft.keys()]);
          setOpen(false);
        }}
        okText={`确认选择（${draft.size}）`}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 16, minHeight: 420 }}>
          <div style={{ borderRight: '1px solid #f0f0f0', paddingRight: 12 }}>
            <Typography.Text strong>按部门筛选</Typography.Text>
            <Button type="link" size="small" onClick={() => { setDepartmentId(undefined); setPage(1); }}>
              全部部门
            </Button>
            {treeData.length ? (
              <Tree
                treeData={treeData}
                selectedKeys={departmentId ? [departmentId] : []}
                defaultExpandAll
                onSelect={(keys) => {
                  setDepartmentId(keys.length ? Number(keys[0]) : undefined);
                  setPage(1);
                }}
              />
            ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无可选部门" />}
          </div>
          <div>
            <Input.Search
              allowClear
              placeholder="搜索姓名、账号或工号"
              onSearch={(text) => { setKeyword(text.trim()); setPage(1); }}
              style={{ marginBottom: 12 }}
            />
            <Table<GrantUser>
              rowKey="id"
              size="small"
              loading={isFetching}
              dataSource={data?.items ?? []}
              pagination={{
                current: page,
                pageSize: size,
                total: data?.total ?? 0,
                showSizeChanger: true,
                pageSizeOptions: [20, 50],
                showTotal: (total) => `共 ${total} 人`,
                onChange: (nextPage, nextSize) => {
                  setPage(nextSize === size ? nextPage : 1);
                  setSize(nextSize);
                },
              }}
              rowSelection={{
                preserveSelectedRowKeys: true,
                selectedRowKeys: [...draft.keys()],
                onSelect: (record, selected) => setDraft((current) =>
                  updateGrantUserSelection(current, [record], selected)),
                onSelectAll: (selected, _rows, changedRows) => setDraft((current) =>
                  updateGrantUserSelection(current, changedRows, selected)),
              }}
              columns={[
                { title: '姓名', dataIndex: 'displayName' },
                { title: '工号', dataIndex: 'employeeNo', width: 130, render: (text) => text || '-' },
                { title: '账号', dataIndex: 'username', width: 150 },
                { title: '部门', dataIndex: 'departmentName', width: 160, render: (text) => text || '未设置' },
              ]}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
