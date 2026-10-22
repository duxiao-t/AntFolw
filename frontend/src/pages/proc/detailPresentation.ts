// 详情页展示逻辑的统一入口（兼容层）。实现已按职责拆到同目录的四个模块：
//   detailTypes      —— 数据契约（快照树 / 视图对象）
//   detailTree       —— 快照解析、树导航、节点命名
//   detailFormatting —— 状态文案与时间格式化
//   detailProgress   —— 进度轨构建
//   detailActivity   —— 流转轨迹构建
//
// 这里只做**显式** re-export：上游 `./detailPresentation` 的 10 处 import 一行不用改，
// 而对外的名字仍是拆前的 7 个函数 + 9 个类型，不多不少。
// 新增代码请直接引上面的具体模块，不要再往这个文件里加实现。
export type {
  ActivityCategory,
  ActivityItem,
  ApprovalRecordView,
  HistoryView,
  InstancePresentation,
  NodeInstanceView,
  PersonView,
  ProcessNode,
  ProgressStage,
} from './detailTypes';
export { formatDateTime, formatDuration, instanceStatus } from './detailFormatting';
export { buildProgressStages } from './detailProgress';
export { buildActivityItems } from './detailActivity';
export { nodeLabel, parseProcessSnapshot } from './detailTree';
