#!/usr/bin/env node
/**
 * Enterprise mobile bundle budget gate.
 * - Initial entry JS (gzip) <= 250 KiB
 * - Each lazy/async JS chunk (gzip) <= 180 KiB
 */
import { createGzip } from 'node:zlib';
import { createReadStream, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { Writable } from 'node:stream';

const __dirname = dirname(fileURLToPath(import.meta.url));
const mobileRoot = resolve(__dirname, '..');
const distDir = resolve(mobileRoot, 'dist');

const ENTRY_BUDGET_KIB = 250;
const LAZY_BUDGET_KIB = 180;
const KIB = 1024;

async function gzipSize(filePath) {
  let size = 0;
  const counter = new Writable({
    write(chunk, _enc, cb) {
      size += chunk.length;
      cb();
    },
  });
  await pipeline(createReadStream(filePath), createGzip({ level: 9 }), counter);
  return size;
}

function loadManifest() {
  const candidates = [
    join(distDir, '.vite', 'manifest.json'),
    join(distDir, 'manifest.json'),
  ];
  for (const path of candidates) {
    if (existsSync(path)) {
      return { path, data: JSON.parse(readFileSync(path, 'utf8')) };
    }
  }
  return null;
}

function collectJsFromAssets() {
  const assetsDir = join(distDir, 'assets');
  if (!existsSync(assetsDir)) {
    return [];
  }
  return readdirSync(assetsDir)
    .filter((name) => name.endsWith('.js'))
    .map((name) => ({
      file: `assets/${name}`,
      isEntry: name.startsWith('index-') || name.includes('index'),
      isDynamicEntry: !name.startsWith('index-'),
    }));
}

function collectFromManifest(manifest) {
  return Object.values(manifest)
    .filter((entry) => entry && typeof entry.file === 'string' && entry.file.endsWith('.js'))
    .map((entry) => ({
      file: entry.file,
      isEntry: Boolean(entry.isEntry),
      isDynamicEntry: Boolean(entry.isDynamicEntry),
      name: entry.name ?? entry.src ?? entry.file,
    }));
}

async function main() {
  if (!existsSync(distDir)) {
    console.error('[check:bundle] dist/ missing — run `npm run build` first');
    process.exit(1);
  }

  const manifest = loadManifest();
  const entries = manifest ? collectFromManifest(manifest.data) : collectJsFromAssets();
  if (entries.length === 0) {
    console.error('[check:bundle] no JS assets found under dist/');
    process.exit(1);
  }

  const report = {
    generatedAt: new Date().toISOString(),
    budgets: {
      entryGzipKiB: ENTRY_BUDGET_KIB,
      lazyGzipKiB: LAZY_BUDGET_KIB,
    },
    assets: [],
    violations: [],
  };

  let entryTotal = 0;
  const entryFiles = entries.filter((e) => e.isEntry);
  const lazyFiles = entries.filter((e) => !e.isEntry && (e.isDynamicEntry || !e.isEntry));

  // If Vite emits a single non-entry chunk as the only JS, treat largest as entry.
  const jsFiles = entries.length > 0 ? entries : [];
  const classified = {
    entry: entryFiles.length > 0 ? entryFiles : [jsFiles.sort((a, b) => a.file.localeCompare(b.file))[0]].filter(Boolean),
    lazy: entryFiles.length > 0
      ? entries.filter((e) => !e.isEntry)
      : jsFiles.slice(1),
  };

  for (const item of classified.entry) {
    const abs = join(distDir, item.file);
    if (!existsSync(abs)) continue;
    const raw = statSync(abs).size;
    const gzip = await gzipSize(abs);
    entryTotal += gzip;
    report.assets.push({
      file: item.file,
      role: 'entry',
      rawBytes: raw,
      gzipBytes: gzip,
      gzipKiB: Number((gzip / KIB).toFixed(2)),
    });
  }

  for (const item of classified.lazy) {
    const abs = join(distDir, item.file);
    if (!existsSync(abs)) continue;
    const raw = statSync(abs).size;
    const gzip = await gzipSize(abs);
    report.assets.push({
      file: item.file,
      role: 'lazy',
      rawBytes: raw,
      gzipBytes: gzip,
      gzipKiB: Number((gzip / KIB).toFixed(2)),
    });
    if (gzip > LAZY_BUDGET_KIB * KIB) {
      report.violations.push(
        `lazy chunk ${item.file} gzip ${(gzip / KIB).toFixed(1)} KiB exceeds ${LAZY_BUDGET_KIB} KiB`,
      );
    }
  }

  if (entryTotal > ENTRY_BUDGET_KIB * KIB) {
    report.violations.push(
      `entry JS gzip total ${(entryTotal / KIB).toFixed(1)} KiB exceeds ${ENTRY_BUDGET_KIB} KiB`,
    );
  }

  const outDir = join(mobileRoot, 'test-results');
  try {
    if (!existsSync(outDir)) {
      // avoid mkdir dependency issues
      const { mkdirSync } = await import('node:fs');
      mkdirSync(outDir, { recursive: true });
    }
    writeFileSync(join(outDir, 'bundle-budget.json'), JSON.stringify(report, null, 2));
  } catch {
    // non-fatal
  }

  console.log('[check:bundle] entry gzip total:', `${(entryTotal / KIB).toFixed(2)} KiB / ${ENTRY_BUDGET_KIB} KiB`);
  for (const asset of report.assets) {
    console.log(`  - ${asset.role.padEnd(5)} ${asset.gzipKiB.toFixed(2)} KiB  ${asset.file}`);
  }

  if (report.violations.length > 0) {
    console.error('[check:bundle] FAILED');
    for (const v of report.violations) console.error('  •', v);
    process.exit(1);
  }

  console.log('[check:bundle] OK');
}

main().catch((error) => {
  console.error('[check:bundle] error', error);
  process.exit(1);
});
