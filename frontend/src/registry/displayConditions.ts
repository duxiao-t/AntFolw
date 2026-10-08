import type { DisplayCondition, SchemaNode } from './types';

/** 上传类字段：提交前要确认没有"还在传"或"服务端还没处理完"的文件。 */
const MEDIA_FIELD_TYPES = new Set(['image_upload', 'video_upload', 'file_upload', 'audio_upload']);

function hasPendingUpload(value: unknown) {
  return Array.isArray(value)
    && (value as unknown as Record<symbol, unknown>)[Symbol.for('antflowPendingUpload')] === true;
}

/** 一个文件项是不是"还没就绪"（服务端 READY 才允许被关联进提交）。 */
function notReady(file: unknown): string | null {
  if (!file || typeof file !== 'object') return null;
  const record = file as Record<string, unknown>;
  if (typeof record.id !== 'string') return null;
  if (record.status === 'FAILED') return '有文件处理失败，请移除后重新上传';
  if (record.status && record.status !== 'READY') return '有文件还在处理中（如视频加水印），请稍候';
  return null;
}

/**
 * 媒体字段的提交前检查。
 *
 * <p>要覆盖三层：字段自己的数组、**检查项每项的照片**（值在 `items[].images` 里，不是 children）、
 * 以及明细表行内的媒体。少一层就能在"照片还在传"时提交，附件被静默丢掉。
 *
 * <p>`rowVisible`（明细表用）是**该行**的可见字段集合：提交时 `collectVisibleValues` 会把按行
 * 条件隐藏的字段剔除，这里若扫全部原始键，恢复出"隐藏列上还带着上传标记"的草稿就永远提交不了。
 */
function pendingMediaReason(
  nodeType: string,
  value: unknown,
  rowVisible?: ReadonlySet<string>,
): string | null {
  if (hasPendingUpload(value)) return '仍有文件未完成上传';
  if (!Array.isArray(value)) return null;
  if (nodeType === 'checklist') {
    for (const entry of value) {
      const images = (entry as Record<string, unknown> | null)?.images
        ?? (entry as Record<string, unknown> | null)?.photos;
      if (hasPendingUpload(images)) return '仍有检查项照片未完成上传';
      if (Array.isArray(images)) {
        for (const file of images) {
          const reason = notReady(file);
          if (reason) return reason;
        }
      }
    }
    return null;
  }
  if (nodeType === 'table_list') {
    for (const row of value) {
      if (!row || typeof row !== 'object') continue;
      for (const [key, nested] of Object.entries(row as Record<string, unknown>)) {
        if (rowVisible && !rowVisible.has(key)) continue;
        if (hasPendingUpload(nested)) return '表格里仍有文件未完成上传';
        if (Array.isArray(nested)) {
          for (const file of nested) {
            const reason = notReady(file);
            if (reason) return reason;
          }
        }
      }
    }
    return null;
  }
  for (const file of value) {
    const reason = notReady(file);
    if (reason) return reason;
  }
  return null;
}

/**
 * 检查项的提交前校验（口径照移动端 `fieldRegistry.validateChecklist`）：逐项必选状态、按结果
 * 要求描述、照片张数上限，以及顶层必填时**至少有一项选了状态**。
 *
 * <p>最后那条是补漏：`props.required` 的通用判空只看"数组非空"，而检查项的数组里每个元素天生
 * 就存在（status 是空串），所以所有项一个都没选也能过。
 */
function checklistError(node: SchemaNode, value: unknown): string | null {
  const items = Array.isArray(node.props?.items) ? (node.props.items as any[]) : [];
  const entries = Array.isArray(value) ? (value as any[]) : [];
  const byId = new Map<string, any>();
  for (const entry of entries) {
    const id = String(entry?.id ?? entry?.itemId ?? '');
    if (id) byId.set(id, entry);
  }
  const allowDescription = node.props?.allowDescription !== false;
  const requiredByResult = node.props?.descriptionRequiredByResult;
  const photoMax = typeof node.props?.photoMaxCount === 'number' ? node.props.photoMaxCount : 9;
  for (const [index, raw] of items.entries()) {
    const id = String(raw?.id ?? `item-${index}`);
    const label = String(raw?.label ?? `检查项${index + 1}`);
    const entry = byId.get(id);
    const status = entry?.status ?? entry?.result ?? '';
    if (raw?.required === true && !status) return `请完成检查项「${label}」`;
    const images = Array.isArray(entry?.images) ? entry.images
      : Array.isArray(entry?.photos) ? entry.photos : [];
    if (images.length > photoMax) return `「${label}」最多上传 ${photoMax} 张照片`;
    if (allowDescription && status && requiredByResult?.[status] === true) {
      const description = String(entry?.description ?? entry?.remark ?? '').trim();
      if (!description && images.length === 0) return `「${label}」的描述必填`;
    }
  }
  if (node.props?.required === true
    && !entries.some((entry) => entry?.status ?? entry?.result)) {
    return '请完成检查项';
  }
  return null;
}

/** 明细表：行数上下限 + **逐行**校验（只算该行可见的字段，口径照移动端 `validateTableList`）。 */
function tableListError(node: SchemaNode, value: unknown): string | null {
  if (hasPendingUpload(value)) return '表格里仍有文件未完成上传';
  const rows = Array.isArray(value) ? value : [];
  // 0 行交给上面的通用必填判断（选填的明细表不该因为 `minRows` 的默认值 1 就永远提交不了）；
  // 这里管的是"填了一半"与"填多了"。
  if (rows.length === 0) return null;
  const minRows = typeof node.props?.minRows === 'number' ? node.props.minRows
    : node.props?.required === true ? 1 : 0;
  const maxRows = typeof node.props?.maxRows === 'number'
    ? node.props.maxRows : Number.POSITIVE_INFINITY;
  if (rows.length < minRows) return `请至少填写${minRows}行`;
  if (rows.length > maxRows) return `最多可填写${maxRows}行`;
  const children = node.children ?? [];
  for (const [index, row] of rows.entries()) {
    if (!row || typeof row !== 'object') return `第${index + 1}行: 请填写${node.label ?? node.id}`;
    const rowValues = row as Record<string, any>;
    // 逐行可见性：校验与媒体扫描共用一份结果（隐藏列既不该拦提交，也不该被当成"还在传"）。
    const rowVisible = visibleNodeIds(children, rowValues);
    const rowPending = pendingMediaReason('table_list', [rowValues], rowVisible);
    if (rowPending) return rowPending;
    const rowError = firstVisibleValidationErrorIn(children, rowValues, rowVisible);
    if (rowError) return `第${index + 1}行: ${rowError}`;
  }
  return null;
}

export function matchesDisplayCondition(
  condition: DisplayCondition | undefined,
  values: Record<string, any>,
) {
  if (!condition?.fieldId) return true;
  const sourceValue = values[condition.fieldId];
  const targetValue = condition.value;
  switch (condition.operator ?? 'eq') {
    case 'in':
      return Array.isArray(targetValue)
        && targetValue.some((item) => String(item) === String(sourceValue ?? ''));
    case 'ne':
    case '!=':
    case '!==':
      return String(sourceValue ?? '') !== String(targetValue ?? '');
    case 'contains':
      return Array.isArray(sourceValue)
        ? sourceValue.map(String).includes(String(targetValue ?? ''))
        : String(sourceValue ?? '').includes(String(targetValue ?? ''));
    case 'empty':
      return isEmptyValue(sourceValue);
    case 'notEmpty':
      return !isEmptyValue(sourceValue);
    case 'gt':
    case '>':
      return numberCompare(sourceValue, targetValue, (left, right) => left > right);
    case 'gte':
    case '>=':
      return numberCompare(sourceValue, targetValue, (left, right) => left >= right);
    case 'lt':
    case '<':
      return numberCompare(sourceValue, targetValue, (left, right) => left < right);
    case 'lte':
    case '<=':
      return numberCompare(sourceValue, targetValue, (left, right) => left <= right);
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
}

export function isVisibleNode(
  node: SchemaNode,
  values: Record<string, any>,
  visibleIds?: ReadonlySet<string>,
) {
  return node.props?.hidden !== true
    && (!node.props?.displayCondition?.fieldId
      || !visibleIds
      || visibleIds.has(node.props.displayCondition.fieldId))
    && matchesDisplayCondition(node.props?.displayCondition, values);
}

/**
 * 可见节点集合。**记忆化递归**，与后端 `FormDefinitionService.resolveVisible` 同一套口径：
 * 一个节点可见 = 父链可见 && 它的条件来源字段可见 && 条件成立。
 *
 * <p>以前是一遍 `forEach` 边走边判：条件引用**后面才声明**的字段时，那个字段还没被算过，
 * 于是"来源不可见"被当成"不可见"（或反之）——同一份数据在前后端能得出两套可见性。
 * 现在按 id 递归求值 + memo，声明顺序无关；`visiting` 防住互指条件（A 依赖 B、B 依赖 A）死循环。
 */
export function visibleNodeIds(nodes: SchemaNode[], values: Record<string, any>) {
  const all = flattenNodes(nodes);
  const byId = new Map(all.map((node) => [node.id, node]));
  const parentOf = new Map<string, string>();
  for (const node of all) {
    for (const child of node.children ?? []) parentOf.set(child.id, node.id);
  }
  const memo = new Map<string, boolean>();
  const visiting = new Set<string>();
  const resolve = (id: string): boolean => {
    const cached = memo.get(id);
    if (cached !== undefined) return cached;
    const node = byId.get(id);
    if (!node || !visiting.add(id)) return false;
    const parentId = parentOf.get(id);
    const sourceId = node.props?.displayCondition?.fieldId;
    const visible = (parentId === undefined || resolve(parentId))
      && (!sourceId || !byId.has(sourceId) || resolve(sourceId))
      && isVisibleNode(node, values);
    visiting.delete(id);
    memo.set(id, visible);
    return visible;
  };
  const visibleIds = new Set<string>();
  for (const node of all) {
    if (resolve(node.id)) visibleIds.add(node.id);
  }
  return visibleIds;
}

export function collectVisibleValues(nodes: SchemaNode[], values: Record<string, any>) {
  const output: Record<string, any> = {};
  const visibleIds = visibleNodeIds(nodes, values);
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.type === 'table_list') {
      output[node.id] = Array.isArray(values[node.id])
        ? values[node.id].map((row: any) => collectVisibleValues(node.children ?? [], row ?? {}))
        : [];
    } else if (node.children?.length) {
      collectVisibleValuesInto(node.children, values, output, visibleIds);
    } else if (node.type !== 'description' && Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
  return output;
}

export function firstVisibleValidationError(nodes: SchemaNode[], values: Record<string, any>): string | null {
  return firstVisibleValidationErrorIn(nodes, values, visibleNodeIds(nodes, values));
}

function firstVisibleValidationErrorIn(
  nodes: SchemaNode[],
  values: Record<string, any>,
  visibleIds: ReadonlySet<string>,
): string | null {
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.children?.length && node.type !== 'table_list') {
      const childError = firstVisibleValidationErrorIn(node.children, values, visibleIds);
      if (childError) return childError;
    }
    if (node.props?.required && isEmptyValue(values[node.id] ?? node.props.defaultValue)) {
      return String(node.props.validationMessage ?? `请填写${node.label ?? node.id}`);
    }
    const value = values[node.id] ?? node.props?.defaultValue;
    if (node.type === 'audio_upload') {
      if (hasPendingUpload(value)) {
        return '仍有录音未完成上传';
      }
      const maxCount = typeof node.props?.maxCount === 'number' ? node.props.maxCount : 3;
      if (Array.isArray(value) && value.length > maxCount) return `最多录制 ${maxCount} 段`;
    }
    // 图片/视频/附件/检查项照片：还在上传、或服务端还在处理（视频加水印）时不许提交——
    // 服务端的附件关联只接受 READY 文件，硬提交会被整单拒，或者干脆把附件丢掉。
    // 明细表走下面那条**逐行**的路径：它的媒体列要先按行条件筛可见性，不能拿全部原始键来扫。
    if (MEDIA_FIELD_TYPES.has(node.type) || node.type === 'checklist') {
      const pending = pendingMediaReason(node.type, value);
      if (pending) return pending;
    }
    if (node.type === 'checklist') {
      const error = checklistError(node, value);
      if (error) return error;
    }
    if (node.type === 'table_list') {
      const error = tableListError(node, value);
      if (error) return error;
    }
    if ((node.type === 'number' || node.type === 'money') && !isEmptyValue(value)) {
      const number = Number(value);
      if (!Number.isFinite(number)) return `${node.label ?? node.id}必须是数字`;
      if (typeof node.props?.min === 'number' && number < node.props.min) {
        return `${node.label ?? node.id}不能小于${node.props.min}`;
      }
      if (typeof node.props?.max === 'number' && number > node.props.max) {
        return `${node.label ?? node.id}不能大于${node.props.max}`;
      }
    }
  }
  return null;
}

function collectVisibleValuesInto(
  nodes: SchemaNode[],
  values: Record<string, any>,
  output: Record<string, any>,
  visibleIds: ReadonlySet<string>,
) {
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.type === 'table_list') {
      output[node.id] = Array.isArray(values[node.id])
        ? values[node.id].map((row: any) => collectVisibleValues(node.children ?? [], row ?? {}))
        : [];
    } else if (node.children?.length) {
      collectVisibleValuesInto(node.children, values, output, visibleIds);
    } else if (node.type !== 'description' && Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
}

function flattenNodes(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.flatMap((node) => [node, ...flattenNodes(node.children ?? [])]);
}

export function isEmptyValue(value: unknown) {
  return value == null
    || value === ''
    || (typeof value === 'string' && value.trim() === '')
    || (Array.isArray(value) && value.length === 0);
}

/**
 * 大小比较。**先拒空值再做数值转换**：`Number(null)`/`Number('')` 都是 0，于是"空数字字段"
 * 能满足 `gte 0` 之类的条件、把依赖它的字段显示出来，而后端 `compareNumbers`（BigDecimal 解析
 * 失败即 false）判它不成立——前后端可见性会对不上。
 */
function numberCompare(
  sourceValue: unknown,
  targetValue: unknown,
  compare: (left: number, right: number) => boolean,
) {
  if (isEmptyValue(sourceValue) || isEmptyValue(targetValue)) return false;
  const left = Number(sourceValue);
  const right = Number(targetValue);
  return Number.isFinite(left) && Number.isFinite(right) && compare(left, right);
}
