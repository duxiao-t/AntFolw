import { useEffect, useState } from 'react';
import {
  fetchMobileDepartment,
  fetchMobileUser,
  type MobilePickerDept,
  type MobilePickerUser,
} from '../files.api';

/** 按 id 并发补全人员名称；单个失败回落为「用户#id」，不阻塞整行渲染。 */
export function usePickerUsers(endpoint: string, ids: number[]) {
  const [users, setUsers] = useState<Record<number, MobilePickerUser>>({});
  useEffect(() => {
    if (ids.length === 0) return undefined;
    let active = true;
    void Promise.all(ids.map((id) => fetchMobileUser(endpoint, id).catch(() => fallbackUser(id))))
      .then((items) => {
        if (!active) return;
        setUsers((current) => ({ ...current, ...Object.fromEntries(items.map((item) => [item.id, item])) }));
      });
    return () => { active = false; };
  }, [endpoint, ids]);
  return ids.map((id) => users[id] ?? fallbackUser(id));
}

/** 部门同理：历史目标已删除时保留「部门 #id」，让确认页仍能核对原有编号。 */
export function usePickerDepartments(endpoint: string, ids: number[]) {
  const [departments, setDepartments] = useState<Record<number, MobilePickerDept>>({});
  useEffect(() => {
    if (ids.length === 0) return undefined;
    let active = true;
    void Promise.all(ids.map((id) => fetchMobileDepartment(endpoint, id)
      .catch(() => ({ id, name: `部门 #${id}` }))))
      .then((items) => {
        if (!active) return;
        setDepartments((current) => ({
          ...current,
          ...Object.fromEntries(items.map((item) => [item.id, item])),
        }));
      });
    return () => { active = false; };
  }, [endpoint, ids]);
  return ids.map((id) => departments[id] ?? { id, name: `部门 #${id}` });
}

export function fallbackUser(id: number): MobilePickerUser {
  return { id, displayName: `用户#${id}` };
}

export function identityMeta(user: MobilePickerUser, id: number) {
  const department = user.department || '未设置部门';
  const employeeNo = user.employeeNo || user.username || String(id);
  return `${department} · 工号 ${employeeNo}`;
}
