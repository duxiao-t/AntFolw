export type ValidationNode = {
  id: string;
  type: string;
  label?: string;
  props?: Record<string, unknown>;
  rules?: Record<string, unknown>;
};

export function validationProps(node: ValidationNode): Record<string, unknown> {
  return { ...node.props, ...node.rules };
}

export function emptyValue(value: unknown): boolean {
  return value == null || value === '' || (typeof value === 'string' && !value.trim())
    || (Array.isArray(value) && value.length === 0);
}

/** Pure submit-time checks shared by both clients; server authorization remains authoritative. */
export function basicFieldError(node: ValidationNode, value: unknown): string | null {
  const props = validationProps(node);
  const label = node.label ?? node.id;
  if (props.required === true && emptyValue(value)) {
    return String(props.validationMessage ?? `请填写${label}`);
  }
  if (emptyValue(value)) return null;
  const invalid = `${label}格式不正确`;
  const scalarOption = (item: unknown) => typeof item === 'string' || typeof item === 'number';
  const pickerId = (item: unknown) =>
    typeof item === 'number' && Number.isSafeInteger(item) && item > 0;
  if (['text', 'textarea', 'scan_code', 'date', 'time'].includes(node.type)
    && typeof value !== 'string') return invalid;
  if (node.type === 'select' && !scalarOption(value)) return invalid;
  if (node.type === 'multi_select'
    && (!Array.isArray(value) || !value.every(scalarOption))) return invalid;
  if (node.type === 'date_range' && (!Array.isArray(value) || value.length !== 2
    || !value.every((item) => typeof item === 'string' && item.trim()))) return invalid;
  if (node.type === 'checklist' && (!Array.isArray(value)
    || value.some((item) => !item || typeof item !== 'object' || Array.isArray(item)))) return invalid;
  if (node.type === 'location') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid;
    const location = value as Record<string, unknown>;
    if (typeof location.latitude !== 'number' || !Number.isFinite(location.latitude)
      || Math.abs(location.latitude) > 90 || typeof location.longitude !== 'number'
      || !Number.isFinite(location.longitude) || Math.abs(location.longitude) > 180
      || (location.accuracy != null && (typeof location.accuracy !== 'number'
        || !Number.isFinite(location.accuracy) || location.accuracy < 0))
      || (location.coordinateSystem != null
        && !['WGS84', 'GCJ02'].includes(String(location.coordinateSystem)))) return invalid;
  }
  if (['user_picker', 'dept_picker'].includes(node.type)
    && !(props.multiple === true
      ? pickerId(value) || (Array.isArray(value) && value.every(pickerId))
      : pickerId(value))) return invalid;
  if (['number', 'money'].includes(node.type)) {
    const number = typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN;
    if (!Number.isFinite(number)) return `${label}必须是数字`;
    if (typeof props.min === 'number' && number < props.min) return `${label}不能小于${props.min}`;
    if (typeof props.max === 'number' && number > props.max) return `${label}不能大于${props.max}`;
    if (typeof props.precision === 'number') {
      const [coefficient = '', exponent = '0'] = String(number).toLowerCase().split('e');
      const decimals = Math.max(0, (coefficient.split('.')[1]?.length ?? 0) - Number(exponent));
      if (decimals > props.precision) return `${label}最多保留${props.precision}位小数`;
    }
  }
  if (typeof value === 'string') {
    if (typeof props.minLength === 'number' && value.length < props.minLength) return invalid;
    if (typeof props.maxLength === 'number' && value.length > props.maxLength) return invalid;
    if (props.pattern) {
      try {
        if (!new RegExp(String(props.pattern)).test(value)) return invalid;
      } catch {
        return `${label}校验规则无效`;
      }
    }
  }
  if (Array.isArray(value)) {
    const max = props.maxChecked ?? props.maxSelected;
    if (typeof props.minChecked === 'number' && value.length < props.minChecked) return invalid;
    if (typeof max === 'number' && value.length > max) return invalid;
    if (['user_picker', 'dept_picker'].includes(node.type)
      && typeof props.maxCount === 'number' && value.length > props.maxCount) return invalid;
  }
  if (['file_upload', 'image_upload', 'video_upload', 'audio_upload'].includes(node.type)) {
    if (!Array.isArray(value) || value.some((item) => !item || typeof item !== 'object'
      || typeof item.id !== 'string'
      || (item.status != null && item.status !== 'READY'))) return invalid;
    const max = props.maxCount ?? (node.type === 'audio_upload' ? 3 : undefined);
    if (typeof max === 'number' && value.length > max) return invalid;
    if (typeof props.maxDuration === 'number' && value.some((item) =>
      item.durationSeconds != null && (typeof item.durationSeconds !== 'number'
        || !Number.isFinite(item.durationSeconds) || item.durationSeconds < 0
        || item.durationSeconds > (props.maxDuration as number)))) return invalid;
  }
  return null;
}
