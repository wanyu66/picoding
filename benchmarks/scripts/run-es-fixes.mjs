import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

// No dependency downloads, real database, or real ES access. Each run has a new output directory.
const out = path.resolve(process.argv[2] || '');
const allowed = path.resolve('benchmarks/results/fixes-20260927');
if (!out.startsWith(allowed + path.sep) || fs.existsSync(out)) throw Error('Use a new directory below benchmarks/results/fixes-20260927');
fs.mkdirSync(out, { recursive: true });
const build = path.join(out, 'build');
fs.mkdirSync(build);
const java = path.resolve(process.env.JAVA8_HOME || '.runtime/java8', 'bin');
const executable = name => path.join(java, name + (process.platform === 'win32' ? '.exe' : ''));
const deps = process.env.PAI_BENCH_CLASSPATH_FILE || 'paicoding-web/target/model-test-classpath.txt';
const cp = ['paicoding-api', 'paicoding-core', 'paicoding-service', 'paicoding-web'].map(p => path.resolve(p, 'target/classes'))
  .concat(fs.readFileSync(deps, 'utf8').trim().split(path.delimiter));
// Same cached BOM resolution as prepare-outbox.mjs: the old model classpath predates AMQP.
const repo = path.join(os.homedir(), '.m2/repository');
const bom = fs.readFileSync(path.join(repo, 'org/springframework/boot/spring-boot-dependencies/2.7.1/spring-boot-dependencies-2.7.1.pom'), 'utf8');
for (const [group, name, key] of [['org/springframework/amqp', 'spring-amqp', 'spring-amqp.version'],
  ['org/springframework/amqp', 'spring-rabbit', 'spring-amqp.version'], ['org/springframework/retry', 'spring-retry', 'spring-retry.version'],
  ['com/rabbitmq', 'amqp-client', 'rabbit-amqp-client.version']]) {
  const version = bom.match(new RegExp('<' + key + '>([^<]+)</' + key + '>'))?.[1];
  if (!version) throw Error('Missing cached BOM version: ' + key);
  cp.push(path.join(repo, group, name, version, name + '-' + version + '.jar'));
}
for (const p of cp) if (!fs.existsSync(p)) throw Error('Missing cached dependency: ' + p);
const serviceSource = 'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java';
// An optional saved source enables an honest pre-fix replay with the exact same current tests.
const sourceOverride = process.argv[3];
if (sourceOverride && (!path.resolve(sourceOverride).startsWith(allowed + path.sep) || !fs.existsSync(sourceOverride))) {
  throw Error('Baseline source must be an existing saved snapshot below the fixes results directory');
}
const inputFor = source => source === serviceSource && sourceOverride ? sourceOverride : source;
const sources = [serviceSource,
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/ArticleSearchDomainEventConsumer.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventConsumeService.java',
  'paicoding-service/src/test/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImplTest.java',
  'benchmarks/java/SearchFaultHarness.java',
  'benchmarks/java/es/SearchFailureRegressionTest.java',
  'benchmarks/java/es/EsRegressionRunner.java'];
const tracked = [...sources, 'benchmarks/scripts/run-es-fixes.mjs', 'benchmarks/config/es-logback.xml'];
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const sourceHashes = Object.fromEntries(tracked.map(p => [p, hash(inputFor(p))]));
for (const source of sources) {
  const dest = path.join(out, 'sources', source);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(inputFor(source), dest);
}
const commands = [];
function run(name, args, log) {
  const start = Date.now();
  const r = spawnSync(executable(name), args, { encoding: 'utf8', maxBuffer: 20e6 });
  fs.writeFileSync(path.join(out, log), (r.stdout || '') + (r.stderr || '') + (r.error ? String(r.error) : ''));
  commands.push({ executable: executable(name), args, exitCode: r.status, durationMs: Date.now() - start, log });
  return r.status;
}
const start = Date.now();
const javaStatus = run('java', ['-version'], 'java-version.txt');
if (javaStatus !== 0 || !fs.readFileSync(path.join(out, 'java-version.txt'), 'utf8').includes('1.8.0')) throw Error('Java 8 required');
const line = 'Class-Path: ' + cp.map(p => pathToFileURL(p).href + (fs.statSync(p).isDirectory() ? '/' : '')).join(' ');
let manifest = 'Manifest-Version: 1.0\r\n';
for (let i = 0; i < line.length; i += 68) manifest += (i ? ' ' : '') + line.slice(i, i + 68) + '\r\n';
const mf = path.join(build, 'MANIFEST.MF');
const cpJar = path.join(build, 'classpath.jar');
fs.writeFileSync(mf, manifest + '\r\n');
if (run('jar', ['cfm', cpJar, mf], 'jar.log') !== 0) throw Error('classpath JAR failed');
const compileArgs = ['-encoding', 'UTF-8', '-cp', cp.join(path.delimiter), '-d', build, ...sources.map(inputFor)];
const argfile = path.join(build, 'javac.args');
fs.writeFileSync(argfile, compileArgs.map(s => '"' + s.replaceAll('\\', '/') + '"').join('\n'));
let exitCode = run('javac', ['-J-Dfile.encoding=UTF-8', '@' + argfile], 'compile.log');
if (exitCode === 0) {
  const javaArgs = ['-Dfile.encoding=UTF-8', '-Dlogback.configurationFile=benchmarks/config/es-logback.xml', '-cp', build + path.delimiter + cpJar];
  const unitStatus = run('java', [...javaArgs, 'EsRegressionRunner', path.join(out, 'junit-results.json')], 'junit.log');
  const faultStatus = run('java', [...javaArgs, 'SearchFaultHarness', path.join(out, 'fault-results.json')], 'fault.log');
  exitCode = unitStatus === 0 && faultStatus === 0 ? 0 : 1;
}
fs.writeFileSync(path.join(out, 'manifest.json'), JSON.stringify({
  startedAt: new Date(start).toISOString(), finishedAt: new Date().toISOString(), durationMs: Date.now() - start,
  command: ['node', 'benchmarks/scripts/run-es-fixes.mjs', ...process.argv.slice(2)],
  implementationSource: inputFor(serviceSource),
  os: { type: os.type(), release: os.release(), arch: os.arch(), cpu: os.cpus()[0]?.model },
  javaVersion: fs.readFileSync(path.join(out, 'java-version.txt'), 'utf8'), nodeVersion: process.version,
  concurrency: 1, scope: 'local disposable HTTP endpoints and mocks only; no existing DB/index/Redis accessed',
  files: sourceHashes, commands, exitCode,
  cleanup: 'JVM finally blocks close every local HTTP server and ES client. Build files are local to this run; raw evidence retained.'
}, null, 2) + '\n');
console.log(JSON.stringify({ out, exitCode, durationMs: Date.now() - start }));
process.exitCode = exitCode;
