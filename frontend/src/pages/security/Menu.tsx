import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { request } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Space,
  Switch,
  Tag,
  Tree,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { PAGE_BY_KEY, PAGES } from '../registry';
import './Security.less';

type MenuNode = {
  nodeKey: string;
  type: 'DIR' | 'PAGE';
  pageKey: string | null;
  name: string | null;
  icon: string | null;
  requiredPermissions: string[];
  sortOrder: number;
  visible: boolean;
  children: MenuNode[];
};

type MenuDocument = { version: number; nodes: MenuNode[] };

/** 后端返回的树补齐可编辑字段与稳定 key。 */
function normalize(nodes: MenuNode[], prefix = 'n'): MenuNode[] {
  return nodes.map((node, index) => {
    const nodeKey = node.nodeKey || `${prefix}-${index}`;
    return {
      ...node,
      nodeKey,
      name: node.name ?? null,
      icon: node.icon ?? null,
      requiredPermissions: node.requiredPermissions ?? [],
      visible: node.visible ?? true,
      children: normalize(node.children ?? [], nodeKey),
    };
  });
}

function replaceNode(nodes: MenuNode[], nodeKey: string, patch: Partial<MenuNode>): MenuNode[] {
  return nodes.map((node) => {
    if (node.nodeKey === nodeKey) return { ...node, ...patch };
    if (node.children?.length) {
      return { ...node, children: replaceNode(node.children, nodeKey, patch) };
    }
    return node;
  });
}

function findNode(nodes: MenuNode[], nodeKey: string): MenuNode | null {
  for (const node of nodes) {
    if (node.nodeKey === nodeKey) return node;
    const found = findNode(node.children ?? [], nodeKey);
    if (found) return found;
  }
  return null;
}

function removeNode(nodes: MenuNode[], nodeKey: string): MenuNode[] {
  return nodes
    .filter((node) => node.nodeKey !== nodeKey)
    .map((node) => (node.children?.length
      ? { ...node, children: removeNode(node.children, nodeKey) }
      : node));
}

function collectPageKeys(nodes: MenuNode[]): string[] {
  return nodes.flatMap((node) => [
    ...(node.pageKey ? [node.pageKey] : []),
    ...collectPageKeys(node.children ?? []),
  ]);
}

export default function MenuPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [version, setVersion] = useState(0);
  const [nodes, setNodes] = useState<MenuNode[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState(false);
  // 新增目录的 key 生成器。原来用 Date.now()，双击（同一毫秒）会生成两个相同 key，
  // Tree/编辑面板随即错乱；自增计数器不可能撞。
  const dirSeq = useRef(0);
  // save() 在请求飞行期间看不到后续的 setNodes（闭包旧值），用 ref 镜像当前树来判断
  // 这轮响应回来时本地有没有被继续编辑。
  const nodesRef = useRef(nodes);
  nodesRef.current = nodes;

  const load = async () => {
    try {
      const menu = await request<MenuDocument>('/api/menu');
      setVersion(menu.version);
      setNodes(normalize(menu.nodes ?? []));
      setSelectedKey('');
      setLoadError(false);
    } catch {
      // 加载失败时 nodes 还是空数组、version 还是 0，此时放行编辑只会在保存时撞版本冲突
      // 或把空树写回去。改为锁死编辑并给出重试入口。
      setLoadError(true);
    }
  };

  useEffect(() => { load(); }, []);

  const selected = useMemo(
    () => (selectedKey ? findNode(nodes, selectedKey) : null),
    [nodes, selectedKey],
  );

  useEffect(() => {
    if (selected) {
      form.setFieldsValue({
        name: selected.name ?? '',
        icon: selected.icon ?? '',
        sortOrder: selected.sortOrder,
        visible: selected.visible,
        requiredPermissions: selected.requiredPermissions,
      });
    }
  }, [selected, form]);

  const usedPageKeys = useMemo(() => new Set(collectPageKeys(nodes)), [nodes]);
  const availablePages = useMemo(
    () => PAGES.filter((page) => !usedPageKeys.has(page.key)),
    [usedPageKeys],
  );

  const addDirectory = () => {
    const nodeKey = `new-dir-${++dirSeq.current}`;
    setNodes((rows) => [...rows, {
      nodeKey, type: 'DIR', pageKey: null, name: '新目录', icon: '', requiredPermissions: [],
      sortOrder: (rows.length + 1) * 10, visible: true, children: [],
    }]);
    setSelectedKey(nodeKey);
  };

  const addPage = (pageKey: string) => {
    const page = PAGES.find((item) => item.key === pageKey);
    if (!page) return;
    // 默认名用注册表里的中文名：早先直接写 page.key，保存后菜单栏显示的是
    // `settings.identity-providers` 这种内部标识，还得管理员手工改回来。
    const node: MenuNode = {
      nodeKey: page.key, type: 'PAGE', pageKey: page.key, name: page.label,
      icon: page.icon ?? '', requiredPermissions: page.readCapabilities,
      sortOrder: 999, visible: true, children: [],
    };
    setNodes((rows) => (selected?.type === 'DIR'
      ? replaceNode(rows, selected.nodeKey, {
        children: [...(findNode(rows, selected.nodeKey)?.children ?? []), node],
      })
      : [...rows, node]));
    setSelectedKey(page.key);
  };

  const move = (direction: -1 | 1) => {
    if (!selectedKey) return;
    setNodes((rows) => {
      const locate = (list: MenuNode[], parent: MenuNode | null): MenuNode[] | null => {
        const index = list.findIndex((node) => node.nodeKey === selectedKey);
        if (index >= 0) {
          const target = index + direction;
          if (target < 0 || target >= list.length) return rows;
          const cloned = [...list];
          [cloned[index], cloned[target]] = [cloned[target], cloned[index]];
          const reordered = cloned.map((node, order) => ({ ...node, sortOrder: (order + 1) * 10 }));
          return parent ? replaceNode(rows, parent.nodeKey, { children: reordered }) : reordered;
        }
        for (const node of list) {
          const found = locate(node.children ?? [], node);
          if (found) return found;
        }
        return null;
      };
      return locate(rows, null) ?? rows;
    });
  };

  const save = async () => {
    setSaving(true);
    const sentNodes = JSON.stringify(nodes);
    try {
      const saved = await request<MenuDocument>('/api/menu', {
        method: 'PUT',
        data: { version, nodes },
      });
      setVersion(saved.version);
      // 请求飞行期间用户可能继续编辑；此时若拿服务端回包覆盖整棵树，那些编辑会被静默丢弃。
      if (JSON.stringify(nodesRef.current) === sentNodes) {
        setNodes(normalize(saved.nodes ?? []));
      }
      message.success('菜单已保存，所有用户刷新后即生效');
      window.dispatchEvent(new Event('antflow:refresh-authz'));
    } finally {
      setSaving(false);
    }
  };

  const treeData = useMemo(() => {
    const build = (rows: MenuNode[]): Array<{ key: string; title: string; children: any[] }> =>
      rows.map((node) => {
        const fallback = node.pageKey ? PAGE_BY_KEY[node.pageKey]?.label : undefined;
        return {
          key: node.nodeKey,
          title: `${node.name ?? fallback ?? node.pageKey ?? '目录'}${
            node.type === 'PAGE' ? '' : '（目录）'
          }`,
          children: build(node.children ?? []),
        };
      });
    return build(nodes);
  }, [nodes]);

  return (
    <PageContainer title={false} className="security-page">
      <div className="security-workspace">
        <aside className="security-sidebar">
          <div className="security-sidebar__header">
            <div>
              <Typography.Title level={4}>菜单</Typography.Title>
              <Typography.Text type="secondary">版本 {version}</Typography.Text>
            </div>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={loadError}
              onClick={save}
            >
              保存
            </Button>
          </div>
          <Space wrap>
            <Button icon={<PlusOutlined />} disabled={loadError} onClick={addDirectory}>
              新增目录
            </Button>
            <Button
              icon={<ArrowUpOutlined />}
              onClick={() => move(-1)}
              disabled={loadError || !selectedKey}
            >
              上移
            </Button>
            <Button
              icon={<ArrowDownOutlined />}
              onClick={() => move(1)}
              disabled={loadError || !selectedKey}
            >
              下移
            </Button>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={loadError || !selectedKey}
              onClick={() => {
                setNodes((rows) => removeNode(rows, selectedKey));
                setSelectedKey('');
              }}
            >
              删除
            </Button>
          </Space>
          {treeData.length ? (
            <Tree
              treeData={treeData}
              selectedKeys={selectedKey ? [selectedKey] : []}
              onSelect={(keys) => setSelectedKey(String(keys[0] ?? ''))}
              defaultExpandAll
            />
          ) : <Empty description="暂无菜单" />}
        </aside>

        <main className="security-editor">
          {loadError && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              title="菜单加载失败，为避免覆盖线上配置已锁定编辑"
              action={<Button size="small" onClick={load}>重试</Button>}
            />
          )}
          <div className="security-permission-toolbar">
            <Typography.Title level={5}>可选页面</Typography.Title>
            <Typography.Text type="secondary">
              只列出当前前端版本已注册的页面；未注册的页面后端会拒绝保存。
            </Typography.Text>
          </div>
          <div className="security-permission-panel" style={{ minHeight: 'auto' }}>
            <Space wrap>
              {availablePages.length ? availablePages.map((page) => (
                <Button
                  key={page.key}
                  size="small"
                  disabled={loadError}
                  onClick={() => addPage(page.key)}
                >
                  + {page.label}
                </Button>
              )) : <Tag>全部页面已在菜单中</Tag>}
            </Space>
          </div>

          {!selected ? (
            <Empty description="选择左侧节点进行编辑" style={{ marginTop: 24 }} />
          ) : (
            <>
              <div className="security-permission-toolbar">
                <Typography.Title level={5}>
                  {selected.type === 'DIR' ? '编辑目录' : '编辑页面'}
                </Typography.Title>
                {selected.pageKey && <Tag>{selected.pageKey}</Tag>}
              </div>
              <Form form={form} layout="vertical" className="security-form">
                <div className="security-form__grid">
                  <Form.Item label="名称" name="name">
                    <Input
                      placeholder="菜单显示名称"
                      onChange={(event) => setNodes((rows) =>
                        replaceNode(rows, selected.nodeKey, { name: event.target.value }))}
                    />
                  </Form.Item>
                  <Form.Item label="图标" name="icon">
                    <Input
                      placeholder="例如：setting"
                      onChange={(event) => setNodes((rows) =>
                        replaceNode(rows, selected.nodeKey, { icon: event.target.value }))}
                    />
                  </Form.Item>
                </div>
                <div className="security-form__grid">
                  <Form.Item label="排序" name="sortOrder">
                    <InputNumber
                      style={{ width: '100%' }}
                      onChange={(value) => setNodes((rows) => replaceNode(rows, selected.nodeKey,
                        { sortOrder: Number(value) || 0 }))}
                    />
                  </Form.Item>
                  <Form.Item label="可见" name="visible" valuePropName="checked">
                    <Switch
                      onChange={(value) => setNodes((rows) =>
                        replaceNode(rows, selected.nodeKey, { visible: value }))}
                    />
                  </Form.Item>
                </div>
                <Form.Item
                  label="所需能力（全部满足才显示）"
                  extra="由页面注册表统一定义，菜单编辑器不能修改。"
                >
                  <Space wrap>
                    {selected.requiredPermissions.length
                      ? selected.requiredPermissions.map((code) => <Tag key={code}>{code}</Tag>)
                      : <Tag>仅需管理端入口</Tag>}
                  </Space>
                </Form.Item>
              </Form>
            </>
          )}
        </main>
      </div>
    </PageContainer>
  );
}
