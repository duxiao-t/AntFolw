/** 通讯录页的共享类型。放在一处，避免页面与 hook 各抄一份行结构。 */

/**
 * 部门。`contextOnly` / `canReadMembers` / `canManageDepartment` / `canManageUsers` 由后端按当前用户
 * 的能力算出来，前端只用不推导——权限口径只能有后端一个来源。
 */
export interface Dept {
  id: number;
  companyId: number;
  parentId: number | null;
  path: string;
  name: string;
  leaderId: number | null;
  leaderIds?: number[];
  sortOrder?: number;
  contextOnly?: boolean;
  canReadMembers?: boolean;
  canManageDepartment?: boolean;
  canManageUsers?: boolean;
}

/** 成员行，与 `/api/users/page` 的 records 一一对应。 */
export interface MemberItem {
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

export interface UserPage {
  records?: MemberItem[];
  total?: number;
}

export interface ManagerCandidate {
  id: number;
  displayName: string;
  employeeNo: string;
  deptId: number;
}

export interface CompanyOption {
  value: number;
  label: string;
}
