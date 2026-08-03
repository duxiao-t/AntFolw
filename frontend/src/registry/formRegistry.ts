import type { FieldType, SchemaNode } from './types';
import { TextField } from '../components/form-fields/TextField';
import { TextareaField } from '../components/form-fields/TextareaField';
import { DescriptionField } from '../components/form-fields/DescriptionField';
import { NumberField } from '../components/form-fields/NumberField';
import { MoneyField } from '../components/form-fields/MoneyField';
import { DateField } from '../components/form-fields/DateField';
import { DateRangeField } from '../components/form-fields/DateRangeField';
import { SelectField } from '../components/form-fields/SelectField';
import { MultiSelectField } from '../components/form-fields/MultiSelectField';
import { UserPickerField } from '../components/form-fields/UserPickerField';
import { DeptPickerField } from '../components/form-fields/DeptPickerField';
import { FileUploadField } from '../components/form-fields/FileUploadField';
import { ImageUploadField } from '../components/form-fields/ImageUploadField';
import { VideoUploadField } from '../components/form-fields/VideoUploadField';
import { SpanLayoutField } from '../components/form-fields/SpanLayoutField';
import { TableListField } from '../components/form-fields/TableListField';

export const formRegistry: Record<string, FieldType> = {
  text: TextField,
  textarea: TextareaField,
  description: DescriptionField,
  number: NumberField,
  money: MoneyField,
  date: DateField,
  date_range: DateRangeField,
  select: SelectField,
  multi_select: MultiSelectField,
  user_picker: UserPickerField,
  dept_picker: DeptPickerField,
  file_upload: FileUploadField,
  image_upload: ImageUploadField,
  video_upload: VideoUploadField,
  span_layout: SpanLayoutField,
  table_list: TableListField,
};

export type PaletteEntry = {
  type: string;
  label: string;
  icon: string;
  defaultProps: Record<string, any>;
};

export const paletteEntries = Object.entries(formRegistry).map(([type, ft]) => ({
  type,
  label: ft.label,
  icon: ft.icon,
  defaultProps: ft.defaultProps,
}));

export type PaletteGroup = {
  key: string;
  title: string;
  entries: PaletteEntry[];
};

export const paletteGroups: PaletteGroup[] = [
  {
    key: 'basic',
    title: '基础组件',
    entries: [
      'text',
      'textarea',
      'number',
      'money',
      'date',
      'date_range',
      'select',
      'multi_select',
      'user_picker',
      'dept_picker',
      'span_layout',
      'table_list',
    ]
      .map((type) => paletteEntries.find((entry) => entry.type === type))
      .filter((entry): entry is PaletteEntry => Boolean(entry)),
  },
  {
    key: 'media',
    title: '多媒体组件',
    entries: ['image_upload', 'video_upload', 'file_upload']
      .map((type) => paletteEntries.find((entry) => entry.type === type))
      .filter((entry): entry is PaletteEntry => Boolean(entry)),
  },
];

export function findById(nodes: SchemaNode[], id: string): SchemaNode | null {
  for (const n of nodes) {
    if (n.id === id) return n;
    const f = findById(n.children ?? [], id);
    if (f) return f;
  }
  return null;
}

export function updateAt(
  nodes: SchemaNode[],
  id: string,
  patch: Partial<SchemaNode>,
): SchemaNode[] {
  return nodes.map((n) => {
    if (n.id === id) return { ...n, ...patch };
    return n.children
      ? { ...n, children: updateAt(n.children, id, patch) }
      : n;
  });
}

export function removeAt(nodes: SchemaNode[], id: string): SchemaNode[] {
  return nodes
    .filter((n) => n.id !== id)
    .map((n) =>
      n.children ? { ...n, children: removeAt(n.children, id) } : n,
    );
}

export function insertAt(
  nodes: SchemaNode[],
  parentId: string | null,
  newNode: SchemaNode,
): SchemaNode[] {
  if (parentId == null) {
    return [...nodes, newNode];
  }
  return nodes.map((n) => {
    if (n.id === parentId) {
      return { ...n, children: [...(n.children ?? []), newNode] };
    }
    if (n.children) {
      return { ...n, children: insertAt(n.children, parentId, newNode) };
    }
    return n;
  });
}
