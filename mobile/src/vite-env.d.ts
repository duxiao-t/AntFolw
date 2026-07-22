/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_TELEMETRY_ENDPOINT?: string;
  readonly VITE_TELEMETRY_DISABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
