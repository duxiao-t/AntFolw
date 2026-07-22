import type { MobileFile } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';

export type MobileFileDto = MobileFile;

export type MobilePickerUser = {
  id: number;
  displayName: string;
  username?: string;
};

export type MobilePickerDept = {
  id: number;
  name: string;
};

export async function searchMobileUsers(endpoint: string, keyword: string): Promise<MobilePickerUser[]> {
  return apiRequest<MobilePickerUser[]>(withKeyword(endpoint, keyword));
}

export async function searchMobileDepartments(endpoint: string, keyword: string): Promise<MobilePickerDept[]> {
  return apiRequest<MobilePickerDept[]>(withKeyword(endpoint, keyword));
}

export async function uploadMobileFile(endpoint: string, file: File): Promise<MobileFileDto> {
  const formData = new FormData();
  formData.set('file', file);
  return apiRequest<MobileFileDto>(endpoint, {
    method: 'POST',
    body: formData,
  });
}

function withKeyword(endpoint: string, keyword: string) {
  const trimmed = keyword.trim();
  if (!trimmed) {
    return endpoint;
  }
  const separator = endpoint.includes('?') ? '&' : '?';
  return `${endpoint}${separator}keyword=${encodeURIComponent(trimmed)}`;
}
