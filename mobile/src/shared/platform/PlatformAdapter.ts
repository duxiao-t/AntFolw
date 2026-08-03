import type { MobileFile } from '../api/types';

export interface PlatformEnvironment {
  standalone: boolean;
  userAgent: string;
}

export interface PlatformAdapter {
  readonly kind: 'browser' | 'wecom';
  trySilentLogin(): Promise<null>;
  openFile(file: MobileFile): Promise<void>;
  closePage(): void;
  getEnvironment(): PlatformEnvironment;
}
