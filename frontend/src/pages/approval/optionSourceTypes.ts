/**
 * 选项数据源的接口形状。抽成单独一份是因为列表页、详情抽屉、版本时间线、对比弹窗、
 * 生命周期弹窗都要用它——各自抄一份类型，改后端字段时必然漏改其中一处。
 */

export type SourceSummary = {
  id: number;
  code: string;
  name: string;
  status: string;
  /** 乐观锁版本号：每次改源（导入/发布/停用/授权/引用）都会递增。 */
  version: number;
  /** 源的最后一次改动时间；列表的「更新时间」列用它。 */
  updatedAt?: string;
  publishedVersionId?: number | null;
  /** 「最新」= 已发布且未停用里版本号最大的那个；全部下架时为 null。 */
  publishedVersionNo?: number | null;
  rowCount?: number | null;
  draftVersionId?: number | null;
  anyPublishedVersion: boolean;
  /** 有多少张表单的字段真的绑着它（≠ 源侧那份可撤销的引用清单）。 */
  inUseFormCount: number;
};

export type VersionView = {
  id: number;
  sourceId: number;
  versionNo: number;
  status: string;
  columns: string[];
  rowCount: number;
  originalName: string;
  createdAt?: string;
  publishedAt?: string | null;
  disabledAt?: string | null;
  /** 导入时写的变更说明；随版本不可变。 */
  note?: string | null;
  /** 发布人的显示名（后端只给 display_name，不下发登录账号）。 */
  publishedByName?: string | null;
};

export type FormRef = { id: number; code: string; name: string };
/** 一个版本被哪些表单的字段绑着。没出现在列表里的版本就是没人在用。 */
export type VersionUsage = { versionId: number; forms: FormRef[] };

export type SourceDetail = {
  source: SourceSummary;
  versions: VersionView[];
  userIds: number[];
  roleIds: number[];
  /** 源侧「可引用表单」清单（可撤销，≠ 真正在用的表单）。 */
  forms: FormRef[];
  versionUsage: VersionUsage[];
  deletable: boolean;
  deleteBlockedReason?: string | null;
};

export type VersionDiff = {
  versionNo: number;
  previousVersionNo?: number | null;
  columnChanges: string[];
  added: Array<Record<string, unknown>>;
  removed: Array<Record<string, unknown>>;
  addedTotal: number;
  removedTotal: number;
  truncated: boolean;
};

/** 版本状态的三态显示：待发布 / 已停用 / 已发布。 */
export function versionStateLabel(version: VersionView): string {
  if (version.status === 'DRAFT') return '待发布';
  return version.disabledAt ? '已停用' : '已发布';
}
