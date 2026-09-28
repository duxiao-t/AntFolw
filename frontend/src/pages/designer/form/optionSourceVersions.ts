/**
 * 版本下拉的候选收敛。
 *
 * 接口按「每个已发布版本一行」返回（一个源有 20 个版本就是 20 行），直接铺出来这个下拉
 * 就没法用了。所以这里按数据源分组，默认每个源只留最新版——**除非**它是当前已绑定的那个，
 * 否则选择框会空掉、管理员会以为绑定丢了。
 */

export type BindableSource = {
  id: number;
  code: string;
  name: string;
  versionId: number;
  versionNo: number;
  columns: string[];
  rowCount: number;
  latestVersionNo: number;
};

export type VersionOptionGroup = {
  label: string;
  options: Array<{ value: number; label: string }>;
};

function versionLabel(source: BindableSource): string {
  const latest = source.versionNo === source.latestVersionNo ? '最新' : undefined;
  return [`v${source.versionNo}`, latest, `${source.rowCount} 行`].filter(Boolean).join(' · ');
}

export function versionOptionGroups(
  sources: BindableSource[],
  { boundVersionId, showHistory }: { boundVersionId?: number; showHistory: boolean },
): VersionOptionGroup[] {
  const bySource = new Map<number, BindableSource[]>();
  sources.forEach((source) => {
    const list = bySource.get(source.id);
    if (list) list.push(source);
    else bySource.set(source.id, [source]);
  });

  return [...bySource.values()].map((list) => {
    const sorted = [...list].sort((left, right) => right.versionNo - left.versionNo);
    const shown = showHistory
      ? sorted
      : sorted.filter((source) => source.versionNo === source.latestVersionNo
        || source.versionId === boundVersionId);
    return {
      label: sorted[0].name,
      options: shown.map((source) => ({ value: source.versionId, label: versionLabel(source) })),
    };
  });
}
