import {
  AuditOutlined,
  BankOutlined,
  BarChartOutlined,
  CloudOutlined,
  ContactsOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  ExportOutlined,
  FileSearchOutlined,
  FormOutlined,
  FundOutlined,
  HomeOutlined,
  IdcardOutlined,
  KeyOutlined,
  MenuOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  TeamOutlined,
  WechatOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';

/**
 * 菜单图标键 → 真正的图标。
 *
 * registry.ts 与后端 t_menu 里存的是键字符串（"home" / "team" / …），而 ProLayout 的
 * getIcon 只认 URL、图片和 icon- 前缀，其余会把字符串**原样渲染成文本**——菜单里就会
 * 冒出「home 工作台」这种中英混排。所以映射必须在这里做。
 *
 * 未知键一律返回 null：宁可这个菜单项没图标，也不要漏出英文。
 * 键取自 registry 的 16 个页面图标 + 后端菜单额外提供的 4 个目录图标
 * （team/audit/barChart/setting，它们只存在于 t_menu.icon_override）。
 */
const MENU_ICONS: Record<string, ReactNode> = {
  home: <HomeOutlined />,
  team: <TeamOutlined />,
  audit: <AuditOutlined />,
  barChart: <BarChartOutlined />,
  setting: <SettingOutlined />,
  contacts: <ContactsOutlined />,
  form: <FormOutlined />,
  search: <SearchOutlined />,
  dashboard: <DashboardOutlined />,
  fund: <FundOutlined />,
  export: <ExportOutlined />,
  idcard: <IdcardOutlined />,
  key: <KeyOutlined />,
  fileSearch: <FileSearchOutlined />,
  menu: <MenuOutlined />,
  bank: <BankOutlined />,
  cloud: <CloudOutlined />,
  wechat: <WechatOutlined />,
  safetyCertificate: <SafetyCertificateOutlined />,
  database: <DatabaseOutlined />,
};

export function menuIcon(key?: string | null): ReactNode {
  return key ? (MENU_ICONS[key] ?? null) : null;
}
