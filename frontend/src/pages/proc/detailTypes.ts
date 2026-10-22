// 详情页展示层的数据契约：流程快照树 + 后端返回的视图对象。
// 从 detailPresentation.ts 原样搬出，字段与可选性未做任何增减。

export type ProcessNode = {
  id: string;
  type: string;
  name?: string;
  label?: string;
  props?: Record<string, any>;
  children?: ProcessNode | null;
  branchs?: ProcessNode[];
};

export type NodeInstanceView = {
  id: number;
  nodeId: string;
  nodeType: string;
  roundNo: number;
  attemptNo: number;
  gatewayNodeInstanceId?: number | null;
  branchId?: string | null;
  status: string;
  startedAt?: string | null;
  completedAt?: string | null;
};

export type ApprovalRecordView = {
  id: string;
  taskId?: number | null;
  nodeId: string;
  nodeName: string;
  recordKind: string;
  nodeType?: string | null;
  parallelId?: string | null;
  branchId?: string | null;
  operationKind?: string | null;
  sourceOperatorName?: string | null;
  status: string;
  operatorName?: string | null;
  employeeNo?: string | null;
  department?: string | null;
  comment?: string | null;
  receivedAt: string;
  completedAt?: string | null;
  roundNo?: number | null;
};

export type HistoryView = {
  id: number;
  taskId?: number | null;
  fromNodeId?: string | null;
  toNodeId?: string | null;
  action: string;
  operatorId?: number | null;
  comment?: string | null;
  createdAt: string;
};

export type PersonView = {
  id: number;
  displayName?: string | null;
  username?: string | null;
  department?: string | null;
  employeeNo?: string | null;
};

export type ProgressStage = {
  id: string;
  label: string;
  detail: string;
  state: 'done' | 'current' | 'waiting' | 'error';
};

export type ActivityCategory = 'approval' | 'cc' | 'system';

export type ActivityItem = {
  id: string;
  category: ActivityCategory;
  title: string;
  status: string;
  state: 'done' | 'current' | 'error' | 'neutral';
  person: string;
  identity?: string;
  source?: string;
  comment: string;
  time: string;
  roundNo: number;
  context?: string;
};

export type InstancePresentation = {
  visibility: string;
  id: number;
  status: string;
  formName?: string | null;
  businessNo?: string | null;
  applicantName?: string | null;
  applicantEmployeeNo?: string | null;
  applicantDepartment?: string | null;
  startedAt?: string | null;
  currentNodeName?: string | null;
  approvalRecords?: ApprovalRecordView[] | null;
  history?: HistoryView[] | null;
};
