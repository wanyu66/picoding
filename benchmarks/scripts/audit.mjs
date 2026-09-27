// Read-only source audit. The only writes are to a new benchmarks/results directory.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const relative = p => path.relative(root, p).split(path.sep).join('/');
const stamp = new Date().toISOString();
const run = path.join(root, 'benchmarks/results', `phase1-${stamp.replace(/[:.]/g, '-')}-${crypto.randomBytes(3).toString('hex')}`);
fs.mkdirSync(run, { recursive: false });
const write = (name, value) => fs.writeFileSync(path.join(run, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const git = (...args) => {
  try { return { status: 'PASS', output: execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim() }; }
  catch (e) { return { status: 'FAIL', exitCode: e.status, reason: 'Git command failed' }; }
};
write('environment.json', {
  capturedAt: stamp, scope: 'static-audit-only', platform: os.platform(), release: os.release(),
  architecture: os.arch(), cpuModel: os.cpus()[0]?.model ?? null, logicalCpuCount: os.cpus().length,
  totalMemoryBytes: os.totalmem(), node: process.version, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  workload: { dataSize: null, concurrency: null, durationSeconds: null, reason: 'NOT_RUN: phase 1 audit only' }
});
write('git-state.json', { head: git('rev-parse', 'HEAD'), status: git('status', '--porcelain=v1', '--untracked-files=normal') });

const paths = [];
const allowed = /\.(?:java|groovy|xml|ya?ml|sql|js|mjs|cjs|ps1|py|sh|md|html|json)$/i;
const skip = new Set(['target', 'node_modules', '.git', 'datasets', 'results', 'static']);
function walk(p) {
  if (!fs.existsSync(p)) return;
  const stat = fs.lstatSync(p);
  if (stat.isSymbolicLink()) return;
  if (stat.isDirectory()) {
    for (const item of fs.readdirSync(p).sort()) if (!skip.has(item)) walk(path.join(p, item));
  } else if (allowed.test(p)) paths.push(p);
}
for (const mod of ['paicoding-api', 'paicoding-core', 'paicoding-service', 'paicoding-web', 'paicoding-ui']) {
  walk(path.join(root, mod, 'pom.xml'));
  walk(path.join(root, mod, 'src'));
}
for (const item of ['pom.xml', 'AGENTS.md', 'README.md', 'docker-compose.canal-kibana.yml',
  'start-elasticsearch.sh', 'docker', 'scripts/load-test', 'scripts/search-evaluation', 'scripts/es-test', 'benchmarks/scripts']) walk(path.join(root, item));
const quote = s => `"${String(s).replaceAll('"', '""')}"`;
const rows = [...new Set(paths)].sort().map(p => [relative(p), fs.statSync(p).size, crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]);
fs.writeFileSync(path.join(run, 'source-manifest.csv'), 'path,bytes,sha256\n' + rows.map(row => row.map(quote).join(',')).join('\n') + '\n', { flag: 'wx' });

const docs = ['README.md', 'test-plan.md', 'metrics-definition.md', 'environment.md', 'results/README.md'];
const checks = docs.map(p => ({ check: `document:${p}`, status: fs.existsSync(path.join(root, 'benchmarks', p)) ? 'PASS' : 'FAIL' }));
const entries = ['SearchRestController.java', 'ArticleSearchServiceImpl.java', 'DomainEventOutboxPublisher.java',
  'DomainEventConsumeService.java', 'UserActivityRankServiceImpl.java', 'ChatRestController.java',
  'WsChatConfig.java', 'WsAnswerHelper.java', 'ChatStreamSessionService.java', 'LLMRouter.java',
  'OutboxRankFaultTest.java', 'ModelStreamFaultHarness.java', 'StatisticDomainEventConsumer.java'];
for (const entry of entries) {
  const matches = rows.filter(row => path.basename(row[0]) === entry).map(row => row[0]);
  checks.push({ check: `source:${entry}`, status: matches.length === 1 ? 'PASS' : 'FAIL', paths: matches });
}
for (const doc of docs) {
  const full = path.join(root, 'benchmarks', doc);
  if (!fs.existsSync(full)) continue;
  for (const match of fs.readFileSync(full, 'utf8').matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
    const target = match[1];
    if (/^(?:https?:|#)/.test(target)) continue;
    checks.push({ check: `link:${doc}:${target}`, status: fs.existsSync(path.resolve(path.dirname(full), target)) ? 'PASS' : 'FAIL' });
  }
}
const failed = checks.filter(c => c.status === 'FAIL').length;
const summary = { capturedAt: stamp, scope: 'Static artifact checks; no business tests or service requests',
  sourceFiles: rows.length, passed: checks.length - failed, failed, checks };
write('checks.json', summary);
console.log(JSON.stringify({ output: relative(run), sourceFiles: rows.length, passed: summary.passed, failed }, null, 2));
if (failed) process.exitCode = 1;
