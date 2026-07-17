import { Button, Input, Tag } from 'antd';
import { useMemo, useState } from 'react';
import { formatGender } from './Contacts.utils';

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
