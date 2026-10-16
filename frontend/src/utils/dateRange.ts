import dayjs from 'dayjs';

/**
 * 报表/导出共用的时间范围。放在 utils 而非 report 页里：提交数据的导出弹窗也要用同一套预设与
 * 换算，页面之间不许互相 import（见 scripts/check-import-boundaries.mjs）。
 */
export const RANGE_PRESETS = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
];

/** 时间范围（含首尾）。报表一律带客户端时区：库里按 UTC 存，不换算"今天"会比用户看到的早 8 小时。 */
export function rangeParams(days: number, today = dayjs()): { from: string; to: string } {
  return {
    from: today.subtract(days - 1, 'day').format('YYYY-MM-DD'),
    to: today.format('YYYY-MM-DD'),
  };
}

export const tzOffsetMinutes = () => -new Date().getTimezoneOffset();
