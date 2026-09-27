import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const root = path.resolve(process.argv[2] || 'benchmarks/results/fixes-20260927');
const read = p => JSON.parse(fs.readFileSync(path.join(root, p), 'utf8'));
const beforeEs = read('es-before-04/junit-results.json'), afterEs = read('es-after-02/junit-results.json');
const beforeFault = read('es-before-04/fault-results.json'), afterFault = read('es-after-02/fault-results.json');
const beforeRank = read('ranking-baseline-01/summary.json'), afterRank = read('ranking-fixture-fix-01/summary.json');
const chat = read('chat-stream-fixed-03/client-samples.json').filter(r => r.scenario !== 'warmup');
const foot = read('userfoot-fixed-01/summary.json'), oldFoot = read('userfoot-baseline-01/summary.json');
const articles = read('article-selftests/summary.json');
const count = (rows, key) => rows.filter(r => r[key]).length;
const records = [
  { phase: 'article-offline', scope: 'offline tool tests', passed: articles.passed, total: articles.tests, failed: articles.failed },
  { phase: 'es-junit', scope: 'service with HTTP/DAO/JDBC stubs', baselinePassed: beforeEs.passed, passed: afterEs.passed, total: afterEs.found, failed: afterEs.failed },
  { phase: 'es-network-fault', scope: 'loopback fault injection, mock DAO', baselinePassed: count(beforeFault, 'pass'), passed: count(afterFault, 'pass'), total: afterFault.length, failed: afterFault.filter(r => !r.pass).length },
  { phase: 'ranking', scope: 'real isolated MySQL/Redis; includes negative control and direct event boundary', baselinePassed: count(beforeRank, 'passed'), passed: count(afterRank, 'passed'), total: afterRank.length, failed: afterRank.filter(r => !r.passed).length },
  { phase: 'userfoot', scope: 'real isolated MySQL; favor service/mapper/outbox SQL', baselinePassed: count(oldFoot, 'passed'), passed: count(foot, 'passed'), total: foot.length, failed: foot.filter(r => !r.passed).length },
  { phase: 'chat-stream', scope: 'real STOMP with mock providers', baselinePassed: 207, passed: count(chat, 'passed'), total: chat.length, failed: chat.filter(r => !r.passed).length }
];
const cleanup = ['ranking-baseline-01', 'ranking-fixture-fix-01', 'userfoot-baseline-01', 'userfoot-fixed-01', 'chat-stream-fixed-03'].map(dir => ({ dir, ...read(dir + '/runner-status.json') }));
for (const r of cleanup) assert.equal(r.cleanupExitCode, 0, r.dir);
const business = [
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/mapper/UserFootMapper.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/dao/UserFootDao.java',
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/user/service/userfoot/UserFootServiceImpl.java',
  'paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/stomp/WsChatConfig.java'
];
const sources = Object.fromEntries(business.map(p => [p, crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]));
const oldManifest = read('userfoot-baseline-01/manifest.json'), newManifest = read('userfoot-fixed-01/manifest.json');
for (const p of business.filter(p => p.includes('/user/'))) assert.equal(sources[p], newManifest.sourceHashes[p], 'Untested source change: ' + p);
const history = read('historical-evidence-check.json'); assert.equal(history.failed, 0);
const result = { generatedAt: new Date().toISOString(), records, cleanup, businessSources: sources,
  historicalEvidence: { files: history.files, unchanged: history.passed, failed: history.failed },
  userfootBaselineSourceHashes: oldManifest.sourceHashes,
  rankingFailures: afterRank.filter(r => !r.passed).map(r => r.scenario),
  networkFailures: afterFault.filter(r => !r.pass).map(r => r.case),
  realModelCalls: 0, performanceImprovement: null,
  note: 'Different scopes and negative controls are not combined into a single business success rate. Old failures preserved.' };
fs.writeFileSync(path.join(root, 'verification-summary.json'), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
fs.writeFileSync(path.join(root, 'verification-summary.csv'), 'phase,baselinePassed,passed,total,failed\n' + records.map(r => [r.phase, r.baselinePassed ?? '', r.passed, r.total, r.failed].join(',')).join('\n') + '\n', { flag: 'wx' });
console.log(JSON.stringify({ records, historicalEvidence: result.historicalEvidence, cleanupPassed: true }));
