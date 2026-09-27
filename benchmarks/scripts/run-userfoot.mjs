import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
const [directory, mode = 'current', baselineRef = 'HEAD'] = process.argv.slice(2);
if (!directory || !['current', 'baseline'].includes(mode)) throw Error('Usage: node benchmarks/scripts/run-userfoot.mjs NEW_DIRECTORY [current|baseline] [BASELINE_REF]');
const revision = spawnSync('git', ['rev-parse', '--verify', '--end-of-options', (mode === 'baseline' ? baselineRef : 'HEAD') + '^{commit}'], { encoding: 'utf8', windowsHide: true });
if (revision.status !== 0) throw Error('Cannot resolve source revision');
const gitRevision = revision.stdout.trim();
const dir = path.resolve(directory), build = path.resolve('benchmarks/build/userfoot', mode);
if (fs.existsSync(dir)) throw Error('Use a new result directory');
fs.mkdirSync(dir, { recursive: true }); fs.mkdirSync(build, { recursive: true });
const java = path.resolve(process.env.JAVA8_HOME || '.runtime/java8', 'bin');
const exe = n => path.join(java, n + (process.platform === 'win32' ? '.exe' : ''));
const cp = ['paicoding-api', 'paicoding-core', 'paicoding-service'].map(p => path.resolve(p, 'target/classes'))
  .concat(fs.readFileSync(process.env.PAI_BENCH_CLASSPATH_FILE || 'paicoding-web/target/model-test-classpath.txt', 'utf8').trim().split(path.delimiter));
const sourcePaths = [
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/service/userfoot/UserFootServiceImpl.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/dao/UserFootDao.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/mapper/UserFootMapper.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventOutboxServiceImpl.java',
  'benchmarks/java/userfoot/UserFootConcurrencyHarness.java'];
const sources = sourcePaths.map(p => {
  if (mode !== 'baseline' || p.startsWith('benchmarks/')) return p;
  const r = spawnSync('git', ['show', gitRevision + ':' + p], { encoding: 'utf8', windowsHide: true });
  if (r.status !== 0) throw Error('Cannot read baseline: ' + p);
  const file = path.join(build, 'source', p); fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, r.stdout); return file;
});
function compile(n, args, log) {
  const r = spawnSync(exe(n), args, { encoding: 'utf8', windowsHide: true, maxBuffer: 8e6 });
  fs.writeFileSync(path.join(dir, log), (r.stdout || '') + (r.stderr || '')); if (r.status !== 0) throw Error(log);
}
compile('java', ['-version'], 'java-version.txt');
if (!fs.readFileSync(path.join(dir, 'java-version.txt'), 'utf8').includes('1.8.0')) throw Error('Java8 required');
const line = 'Class-Path: ' + cp.map(p => pathToFileURL(p).href + (fs.statSync(p).isDirectory() ? '/' : '')).join(' ');
let mf = 'Manifest-Version: 1.0\r\n'; for (let i = 0; i < line.length; i += 68) mf += (i ? ' ' : '') + line.slice(i, i + 68) + '\r\n';
fs.writeFileSync(path.join(build, 'MANIFEST.MF'), mf + '\r\n');
compile('jar', ['cfm', path.join(build, 'classpath.jar'), path.join(build, 'MANIFEST.MF')], 'jar.log');
fs.writeFileSync(path.join(build, 'javac.args'), ['-encoding', 'UTF-8', '-cp', cp.join(path.delimiter), '-d', build, ...sources].map(x => '"' + x.replaceAll('\\', '/') + '"').join('\n'));
compile('javac', ['-J-Dfile.encoding=UTF-8', '@' + path.join(build, 'javac.args')], 'compile.log');
const run = 'bench-foot-' + Date.now(), env = { ...process.env, BENCH_RUN: run, BENCH_RESULTS: dir, BENCH_TEST_PASSWORD: crypto.randomBytes(24).toString('hex') };
const compose = ['compose', '-p', run, '-f', 'benchmarks/config/userfoot-compose.yml'];
fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ mode, run, gitRevision, startedAt: new Date().toISOString(), concurrency: 32, callsPerScenario: 64,
  environment: { node: process.version, os: os.release(), cpu: os.cpus()[0].model, logicalCpus: os.cpus().length, memoryBytes: os.totalmem() },
  sourceHashes: Object.fromEntries(sources.map((p, i) => [sourcePaths[i], crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')])),
  scope: 'Actual UserFoot/MyBatis/Spring transaction/Outbox SQL on dedicated MySQL; no Rabbit or HTTP; no performance improvement claim.' }, null, 2));
async function execute(cmd, args, logfile) {
  const fd = fs.openSync(path.join(dir, logfile), 'a');
  try { return await new Promise((resolve, reject) => { const p = spawn(cmd, args, { env, windowsHide: true, stdio: ['ignore', fd, fd] }); p.on('error', reject); p.on('close', resolve); }); }
  finally { fs.closeSync(fd); }
}
let exit = 1;
try {
  if (await execute('docker', [...compose, 'up', '-d', '--wait', '--wait-timeout', '180'], 'docker-start.log')) throw Error('Dedicated MySQL failed');
  await execute('docker', [...compose, 'ps', '--format', 'json'], 'containers.jsonl');
  await execute('docker', ['image', 'inspect', 'mysql:8.0', '--format', '{{.Id}} {{json .RepoDigests}}'], 'image.txt');
  exit = await execute(exe('java'), ['-Dfile.encoding=UTF-8', '-Dlogback.configurationFile=benchmarks/config/es-logback.xml', '-cp', build + path.delimiter + path.join(build, 'classpath.jar'), 'UserFootConcurrencyHarness'], 'harness.log');
} catch (e) { fs.writeFileSync(path.join(dir, 'runner-error.json'), JSON.stringify({ error: e.message })); }
finally {
  const cleanup = await execute('docker', [...compose, 'down', '--volumes'], 'docker-cleanup.log');
  fs.writeFileSync(path.join(dir, 'runner-status.json'), JSON.stringify({ testExitCode: exit, cleanupExitCode: cleanup, finishedAt: new Date().toISOString() }, null, 2));
  if (cleanup) exit = 1;
}
if (fs.existsSync(path.join(dir, 'summary.json'))) {
  const rows = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'), 'utf8'));
  fs.writeFileSync(path.join(dir, 'metrics.csv'), 'scenario,concurrency,calls,successes,outboxEvents,expectedEvents,durationMs,passed\n' +
    rows.map(r => [r.scenario, r.concurrency, r.calls, r.successes, r.outboxEvents, r.expectedEvents, r.durationMs, r.passed].join(',')).join('\n') + '\n');
}
console.log(JSON.stringify({ dir, exit })); process.exitCode = exit;
