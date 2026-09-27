import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import { spawnSync } from 'node:child_process';

// Record old evidence before a fix, then verify it without rewriting old reports.
const [mode, directory] = process.argv.slice(2);
if (!['capture', 'verify'].includes(mode) || !directory) {
  throw Error('Usage: node benchmarks/scripts/check-fix-evidence.mjs capture|verify NEW_FIX_DIRECTORY');
}
const root = path.resolve('benchmarks/results'), out = path.resolve(directory);
const relative = path.relative(root, out);
if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw Error('Output must be within benchmarks/results');
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const write = (name, value) => fs.writeFileSync(path.join(out, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const capturePath = path.join(out, 'historical-evidence.json');
if (mode === 'capture') {
  fs.mkdirSync(out, { recursive: true });
  const files = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name);
      if (p === out || entry.isSymbolicLink()) continue;
      if (entry.isDirectory()) walk(p);
      else files.push({ path: path.relative(root, p).replaceAll('\\', '/'), sha256: sha(p), bytes: fs.statSync(p).size });
    }
  }
  walk(root);
  const git = spawnSync('git', ['status', '--porcelain'], { encoding: 'utf8', windowsHide: true });
  if (git.status !== 0) throw Error('Unable to record initial Git status');
  write('historical-evidence.json', { capturedAt: new Date().toISOString(), files });
  write('environment.json', { capturedAt: new Date().toISOString(), node: process.version, os: os.release(),
    platform: process.platform, cpu: os.cpus()[0]?.model, logicalCpus: os.cpus().length, memoryBytes: os.totalmem(),
    gitStatus: git.stdout, scope: 'Historical result hashes; no service connections or benchmark workload.' });
  console.log(JSON.stringify({ mode, files: files.length, output: capturePath }));
} else {
  const captured = JSON.parse(fs.readFileSync(capturePath, 'utf8'));
  const checks = captured.files.map(f => {
    const p = path.resolve(root, f.path), rel = path.relative(root, p);
    if (rel.startsWith('..') || path.isAbsolute(rel)) throw Error('Invalid captured path');
    return { path: f.path, status: !fs.existsSync(p) ? 'MISSING' : sha(p) === f.sha256 ? 'PASS' : 'CHANGED' };
  });
  const failures = checks.filter(r => r.status !== 'PASS');
  write('historical-evidence-check.json', { verifiedAt: new Date().toISOString(), files: checks.length,
    passed: checks.length - failures.length, failed: failures.length, checks });
  fs.writeFileSync(path.join(out, 'historical-evidence-check.csv'), 'path,status\n' +
    checks.map(r => `"${r.path.replaceAll('"', '""')}",${r.status}`).join('\n') + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ mode, checked: checks.length, failures }));
  if (failures.length) process.exitCode = 1;
}
