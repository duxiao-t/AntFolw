import type { MobileFile } from '../api/types';

export interface PlatformEnvironment {
  standalone: boolean;
  userAgent: string;
}

export type PlatformLocation = {
  latitude: number;
  longitude: number;
  accuracy?: number;
  coordinateSystem?: 'WGS84' | 'GCJ02';
  name?: string;
  address?: string;
};

export type PlatformAudio = {
  durationSeconds: number;
  file?: File;
  uploaded?: MobileFile;
};

export interface PlatformAdapter {
  readonly kind: 'browser' | 'wecom';
  trySilentLogin(): Promise<null>;
  chooseImages?(maxCount: number, source?: 'camera' | 'album' | 'both'): Promise<MobileFile[]>;
  recordAudio?(): Promise<PlatformAudio>;
  startAudioRecording?(): Promise<void>;
  stopAudioRecording?(): Promise<PlatformAudio>;
  scanCode?(): Promise<string | null>;
  getLocation?(): Promise<PlatformLocation>;
  openLocation?(location: PlatformLocation): Promise<void>;
  openFile(file: MobileFile): Promise<void>;
  closePage(): void;
  getEnvironment(): PlatformEnvironment;
}
