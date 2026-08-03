import { apiRequest } from '../../shared/api/http';
import type { PublicBranding } from '../../shared/api/types';

export async function fetchPublicBranding(): Promise<PublicBranding | null> {
  try {
    return await apiRequest<PublicBranding>('/api/public/branding');
  } catch {
    return null;
  }
}
