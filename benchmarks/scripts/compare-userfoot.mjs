import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const [baseline, current, output] = process.argv.slice(2);
if (!output) throw Error('Usage: node benchmarks/scripts/compare-userfoot.mjs BASELINE CURRENT NEW_OUTPUT_DIRECTORY');
const read = (dir, file) => JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
const before = read(baseline, 'summary.json'), after = read(current, 'summary.json');
assert.equal(before.length, 7); assert.equal(after.length, 7);
const compared = [];
for (let i = 0; i < before.length; i++) {
  const a = before[i], b = after[i]; assert.equal(a.scenario, b.scenario);
  if (b.scenario === 'rollback') { assert.equal(a.passed, true); assert.equal(b.passed, true); continue; }
  for (const r of [a, b]) {
    assert.equal(r.concurrency, 32); assert.equal(r.calls, 64); assert.equal(r.expectedEvents, 1);
    const dir = r === a ? baseline : current, raw = read(dir, r.scenario + '-requests.json');
    assert.equal(raw.length, 64); assert.equal(new Set(raw.map(x => x.index)).size, 64);
    assert.equal(raw.filter(x => x.ok).length, r.successes);
    assert.equal(r.passed, r.successes === 64 && r.outboxEvents === 1 && r.state === r.expectedState);
  }
  assert.equal(a.expectedState, b.expectedState);
  compared.push({ scenario: b.scenario, calls: 64, concurrency: 32, baselineEvents: a.outboxEvents,
    currentEvents: b.outboxEvents, baselineErrors: 64 - a.successes, currentErrors: 64 - b.successes,
    baselinePassed: a.passed, currentPassed: b.passed });
}
for (const dir of [baseline, current]) {
  const rows = read(dir, 'summary.json'), outbox = read(dir, 'outbox-rows.json');
  assert.equal(outbox.length, rows.reduce((n, r) => n + (r.outboxEvents ?? 0), 0));
  assert.equal(new Set(outbox.map(r => r.event_id)).size, outbox.length);
  assert.ok(outbox.every(r => r.status === 'PENDING' && Number(r.aggregate_id) !== 44));
  assert.ok(read(dir, 'foot-rows.json').every(r => Number(r.document_id) !== 44));
  assert.equal(read(dir, 'runner-status.json').cleanupExitCode, 0);
}
fs.mkdirSync(output, { recursive: false });
const result = { checkedAt: new Date().toISOString(), baseline, current, evidenceChecksPassed: true,
  baselinePassed: before.filter(r => r.passed).length, currentPassed: after.filter(r => r.passed).length,
  totalCases: 7, comparisons: compared,
  scope: 'Same state transitions and assertions; real isolated MySQL service/mapper/outbox. No HTTP/Rabbit delivery or throughput gain claim.' };
fs.writeFileSync(path.join(output, 'comparison.json'), JSON.stringify(result, null, 2) + '\n');
const keys = Object.keys(compared[0]);
fs.writeFileSync(path.join(output, 'comparison.csv'), keys.join(',') + '\n' + compared.map(r => keys.map(k => r[k]).join(',')).join('\n') + '\n');
console.log(JSON.stringify(result));
if (after.some(r => !r.passed)) process.exitCode = 1;
