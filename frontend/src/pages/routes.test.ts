import { describe, expect, it } from 'vitest';
import routes from '../../config/routes';

type RouteNode = { path?: string; redirect?: string; routes?: RouteNode[] };

function collectPaths(nodes: RouteNode[], acc: string[] = []): string[] {
  nodes.forEach((node) => {
    if (node.path) acc.push(node.path);
    if (node.routes) collectPaths(node.routes, acc);
  });
  return acc;
}

/** 叶子路由（无子 routes）：父级路由组允许与其子项同路径，叶子不允许重名。 */
function collectLeafPaths(nodes: RouteNode[], acc: string[] = []): string[] {
  nodes.forEach((node) => {
    if (node.routes) {
      collectLeafPaths(node.routes, acc);
    } else if (node.path) {
      acc.push(node.path);
    }
  });
  return acc;
}

describe('route table', () => {
  const paths = collectPaths(routes as RouteNode[]);

  /**
   * 表单编辑入口回归：曾有一次改动把 `/approval/forms/:id/wizard` 误替换成别的路由，
   * 导致列表页「编辑」与 FormDesigner 的旧入口重定向全部落到兜底 404。
   * 这里把它们钉死，任何删除都会在单测阶段失败。
   */
  it('保留所有表单编辑入口', () => {
    expect(paths).toContain('/approval/forms/new');
    expect(paths).toContain('/approval/forms/:id/wizard');
    expect(paths).toContain('/designer/form/:id');
    expect(paths).toContain('/designer/process/:formDefId');
  });

  it('静态段必须先于同前缀的动态段声明', () => {
    // /approval/forms/new 若排在 /approval/forms/:id/wizard 之后，:id 会吃掉 "new"
    expect(paths.indexOf('/approval/forms/new')).toBeLessThan(
      paths.indexOf('/approval/forms/:id/wizard'),
    );
  });

  it('叶子路径不重复声明', () => {
    const leaves = collectLeafPaths(routes as RouteNode[]);
    const duplicated = leaves.filter((path, index) => leaves.indexOf(path) !== index);
    expect(duplicated).toEqual([]);
  });
});
