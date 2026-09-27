import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileHash, json } from './articles/dataset.mjs';
const dir = process.argv[2];
if (!dir) throw Error('Usage: node benchmarks/scripts/check-articles.mjs NEW_REPORT_DIRECTORY');
fs.mkdirSync(path.dirname(dir), { recursive: true });
fs.mkdirSync(dir, { recursive: false });
const started = new Date().toISOString();
const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', 'benchmarks/scripts/articles.test.mjs'], { encoding: 'utf8' });
fs.writeFileSync(path.join(dir, 'tests.tap'), result.stdout ?? '');
fs.writeFileSync(path.join(dir, 'stderr.txt'), result.stderr ?? '');
const files = ['benchmarks/scripts/articles.mjs', 'benchmarks/scripts/cleanup-articles.mjs', 'benchmarks/scripts/articles.test.mjs',
  ...fs.readdirSync('benchmarks/scripts/articles').map(f => `benchmarks/scripts/articles/${f}`)];
const sources = {};
for (const file of files) sources[file] = await fileHash(file);
const summary = { started, finished: new Date().toISOString(), status: result.status === 0 ? 'PASS' : 'FAIL', exitCode: result.status,
  tests: Number(/^# tests (\d+)/m.exec(result.stdout)?.[1] ?? 0), passed: Number(/^# pass (\d+)/m.exec(result.stdout)?.[1] ?? 0),
  failed: Number(/^# fail (\d+)/m.exec(result.stdout)?.[1] ?? 0), node: process.version, os: os.release(),
  cpu: os.cpus()[0]?.model, logicalCpus: os.cpus().length, memoryBytes: os.totalmem(), sources,
  scope: 'Offline generator/SQL guards/verification tests, not online database or ES performance' };
fs.writeFileSync(path.join(dir, 'summary.json'), json(summary));
console.log(JSON.stringify(summary, null, 2));
process.exitCode = result.status === 0 ? 0 : 1;
