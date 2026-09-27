import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const dir = path.resolve(process.argv[2] || 'benchmarks/results/fixes-20260927/chat-backpressure-fixed');
const baselineRef = process.argv.find(arg => arg.startsWith('--baseline-ref='))?.slice('--baseline-ref='.length) || 'HEAD';
const baseline = process.argv.includes('--baseline') || process.argv.some(arg => arg.startsWith('--baseline-ref='));
if (fs.existsSync(dir)) throw Error('Use a new result directory');
fs.mkdirSync(dir, {recursive: true});
const build = path.join(dir, 'build');
fs.mkdirSync(build);
const java = path.resolve(process.env.JAVA8_HOME || '.runtime/java8', 'bin');
const source = 'paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/stomp/WsChatConfig.java';
const harness = 'benchmarks/java/chat/ChatInboundBackpressureCheck.java';
const run = (command, args, log) => {
  const r = spawnSync(command, args, {encoding: 'utf8', maxBuffer: 8e6});
  fs.writeFileSync(path.join(dir, log), (r.stdout || '') + (r.stderr || '') + (r.error?.stack || ''));
  return r.status ?? 1;
};
const executable = name => path.join(java, name + (process.platform === 'win32' ? '.exe' : ''));
if (run(executable('java'), ['-version'], 'java-version.txt') !== 0 || !fs.readFileSync(path.join(dir, 'java-version.txt'), 'utf8').includes('1.8.0')) throw Error('Java 8 required');
let configSource = fs.readFileSync(source, 'utf8');
let revision = null;
if (baseline) {
  const r = spawnSync('git', ['show', baselineRef + ':' + source], {encoding: 'utf8'});
  if (r.status !== 0) throw Error('Cannot read baseline from Git HEAD');
  configSource = r.stdout;
  revision = spawnSync('git', ['rev-parse', baselineRef], {encoding: 'utf8'}).stdout.trim();
}
const snapshot = path.join(build, 'WsChatConfig.java');
fs.writeFileSync(snapshot, configSource);
const cp = ['paicoding-api', 'paicoding-core', 'paicoding-service', 'paicoding-web'].map(p => path.resolve(p, 'target/classes')).concat(fs.readFileSync(process.env.PAI_BENCH_CLASSPATH_FILE || 'paicoding-web/target/model-test-classpath.txt', 'utf8').trim().split(path.delimiter));
const line = 'Class-Path: ' + cp.map(p => pathToFileURL(p).href + (fs.statSync(p).isDirectory() ? '/' : '')).join(' ');
let manifest = 'Manifest-Version: 1.0\r\n';
for (let i = 0; i < line.length; i += 68) manifest += (i ? ' ' : '') + line.slice(i, i + 68) + '\r\n';
fs.writeFileSync(path.join(build, 'MANIFEST.MF'), manifest + '\r\n');
if (run(executable('jar'), ['cfm', path.join(build, 'classpath.jar'), path.join(build, 'MANIFEST.MF')], 'jar.log') !== 0) throw Error('Classpath jar failed');
const argsFile = path.join(build, 'javac.args');
fs.writeFileSync(argsFile, ['-encoding', 'UTF-8', '-cp', cp.join(path.delimiter), '-d', build, snapshot, source.replace('WsChatConfig.java', 'WsChatProperties.java'), harness].map(s => '"' + s.replaceAll('\\', '/') + '"').join('\n'));
if (run(executable('javac'), ['-J-Dfile.encoding=UTF-8', '@' + argsFile], 'compile.log') !== 0) throw Error('Compile failed; inspect compile.log');
const hash = contents => crypto.createHash('sha256').update(contents).digest('hex');
const startedAt = new Date().toISOString();
const exitCode = run(executable('java'), ['-Dfile.encoding=UTF-8', '-Dlogback.configurationFile=benchmarks/config/es-logback.xml', '-cp', build + path.delimiter + path.join(build, 'classpath.jar'), 'ChatInboundBackpressureCheck', dir], 'test.log');
fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({mode: baseline ? 'git-head-baseline' : 'working-tree-fixed', revision, source, sourceSha256: hash(configSource), harness, harnessSha256: hash(fs.readFileSync(harness)), startedAt, finishedAt: new Date().toISOString(), environment: {cpu: os.cpus()[0].model, logicalCpus: os.cpus().length, memoryBytes: os.totalmem(), os: os.release(), node: process.version}, realModelCalls: 0, testExitCode: exitCode, cleanup: 'Executor shut down in finally; no external services, database or model API'}, null, 2));
console.log(JSON.stringify({dir, baseline, exitCode}));
process.exitCode = exitCode;
