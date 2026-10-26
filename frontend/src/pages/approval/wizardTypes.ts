import type { GrantDepartment, GrantUser } from './FormGrantUserPicker';

export type ProcessDefinition = {
  id: number;
  formDefId: number;
  status: string;
  version: number;
  process?: any;
};

export type PublishCheckStatus = 'success' | 'warning' | 'error';

export type PublishCheckItem = {
  key: string;
  status: PublishCheckStatus;
  title: string;
  description: string;
};

export type FormGrant = {
  version: number;
  userIds: number[];
  roleIds: number[];
  departmentIds: number[];
  users: GrantUser[];
  roles: { id: number; code: string; name: string }[];
  departments: GrantDepartment[];
};

export type FormGrantCandidates = {
  companies: { id: number; name: string }[];
  roles: { id: number; code: string; name: string }[];
  departments: GrantDepartment[];
};

export type FormMaintainers = {
  version: number;
  userIds: number[];
  users: GrantUser[];
};
