import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { articles, generate, validateFiles, hash, topics } from './articles/dataset.mjs';
import { insertSql, cleanupSql, removeChildrenSql, validateOnline, scope, eventsSql } from './articles/sql.mjs';
import { sqlText } from './articles/mysql.mjs';
import { matchesDb, matchesEs } from './articles/workflow.mjs';

const base = JSON.parse(fs.readFileSync('benchmarks/config/articles.json', 'utf8'));
const online = { ...base, isolatedTestEnvironment: true, fixtures: { authorId: 1, actorIds: Array.from({ length: 16 }, (_, i) => i + 2),
  categories: Object.fromEntries(topics.map((t, i) => [t[0], i + 1])) } };
const digest = c => { const h = crypto.createHash('sha256'); for (const r of articles(c)) h.update(JSON.stringify(r)); return h.digest('hex'); };

test('10k dataset is byte deterministic; changed seed changes content', () => {
  assert.equal(digest(base), digest(base));
  assert.notEqual(digest(base), digest({ ...base, seed: base.seed + 1 }));
});
test('all supported production scales generate exact counts, unique slugs and legal field lengths', () => {
  for (const count of [10000, 50000, 100000]) {
    const seen = new Set(), cats = new Set(), variants = new Set(), lengths = new Set(); let n = 0;
    for (const r of articles({ ...base, count })) {
      assert.ok(r.title.length <= 120 && r.shortTitle.length <= 120 && r.summary.length <= 300 && r.slug.length <= 200);
      assert.ok(r.praiseCount <= 16 && r.collectionCount <= r.praiseCount);
      assert.ok(Date.parse(r.created) < Date.parse(base.referenceTime));
      seen.add(r.slug); cats.add(r.category); variants.add(r.variant); lengths.add(r.content.length); n++;
    }
    assert.equal(n, count); assert.equal(seen.size, count); assert.equal(cats.size, 5); assert.equal(variants.size, 6); assert.equal(lengths.size, 3);
  }
});
test('invalid counts and unsafe batch identifiers fail before side effects', () => {
  for (const overrides of [{ count: 999 }, { seed: -1 }, { batch: "x';DROP TABLE article;" }, { referenceTime: 'now' }]) {
    assert.throws(() => [...articles({ ...base, ...overrides })]);
  }
});
test('target guards refuse production namespaces, wildcard indices, remote hosts and real-user defaults', () => {
  validateOnline(online);
  assert.throws(() => validateOnline(base));
  for (const change of [{ mysql: { ...online.mysql, database: 'paicoding' } }, { es: { ...online.es, index: '*' } },
    { mysql: { ...online.mysql, host: '10.0.0.1' } }, { es: { ...online.es, url: 'http://u:secret@localhost:9200' } },
    { fixtures: { ...online.fixtures, actorIds: [2, 2] } }]) assert.throws(() => validateOnline({ ...online, ...change }));
});
test('explicit local paicoding authorization is scoped to that database and preserves ordinary default rejection', () => {
  const c = { ...online, isolatedTestEnvironment: false, existingLocalDatabase: 'paicoding', preserveExistingData: true,
    mysql: { ...online.mysql, database: 'paicoding' }, es: { ...online.es, index: 'paicoding_article' } };
  validateOnline(c);
  assert.throws(() => validateOnline({ ...c, preserveExistingData: false }));
  assert.throws(() => validateOnline({ ...c, mysql: { ...c.mysql, database: 'production' } }));
  assert.throws(() => validateOnline({ ...c, es: { ...c.es, index: '*' } }));
});
test('UTF-8 hex SQL literals do not interpolate quote, backslash, newline or hostile text', () => {
  const input = "中文'; DELETE FROM article; --\n\\$()`";
  const escaped = sqlText(input), match = /X'([0-9a-f]+)'/.exec(escaped);
  assert.equal(Buffer.from(match[1], 'hex').toString('utf8'), input);
  assert.ok(!escaped.includes('DELETE'));
});
test('batch inserts bind details and heat to exact slug/owner, with event in same transaction', () => {
  const rows = [...articles({ ...online, count: 100 })].slice(0, 2), sql = insertSql(online, rows, 'fixed-op');
  assert.ok(sql.indexOf('START TRANSACTION') < sql.indexOf('INSERT INTO article '));
  assert.ok(sql.indexOf('INSERT INTO domain_event_outbox') < sql.indexOf('COMMIT'));
  for (const table of ['article_detail', 'read_count', 'user_foot', 'domain_event_outbox']) assert.ok(sql.includes(`INSERT INTO ${table}`));
  assert.ok(sql.includes(scope(online)) && sql.includes('JOIN bench_stage'));
  assert.ok(!sql.includes('ARTICLE_ONLINE')); // avoids unwanted rank +10 side effects
  assert.throws(() => eventsSql(online, 'PRAISE', 'x'));
});
test('cleanup uses tombstones and scoped child deletion; never drops index, tables or article rows', () => {
  const rows = [...articles({ ...online, count: 100 })].slice(0, 2);
  const sql = cleanupSql(online, rows, 'x') + removeChildrenSql(online, rows);
  assert.ok(sql.includes('a.deleted=1,a.status=0,a.update_time=NOW()'));
  assert.ok(sql.includes('ARTICLE_DELETE'));
  assert.doesNotMatch(sql, /DROP|TRUNCATE|DELETE\s+(?:FROM\s+article\b|a\s+FROM)/i);
  assert.equal((sql.match(/DELETE [drf] FROM/g) ?? []).length, 3);
  assert.ok((sql.match(/a\.source_url=/g) ?? []).length >= 5);
});
test('verification rejects same-count wrong content/heat and ES errors', () => {
  const r = articles(base).next().value;
  const actual = { slug: r.slug, title: r.title, shortTitle: r.shortTitle, summary: r.summary, category: online.fixtures.categories[r.category],
    author: '1', deleted: 0, status: 1, created: r.created, official: r.officalStat, cream: r.creamStat, topping: r.toppingStat,
    contentHash: hash(r.content), details: 1, reads: r.readCount, praise: r.praiseCount, collections: r.collectionCount };
  assert.ok(matchesDb(online, r, actual));
  assert.ok(!matchesDb(online, r, { ...actual, contentHash: hash('different') }));
  assert.ok(!matchesDb(online, r, { ...actual, praise: actual.praise + 1 }));
  const doc = { found: true, _source: { ...r, urlSlug: r.slug, status: 1, deleted: 0,
    qualityScore: 1 + r.officalStat * 3 + r.creamStat * 5 + r.toppingStat * 2, createTime: r.created } };
  assert.ok(matchesEs(r, doc));
  assert.ok(matchesEs(r, { ...doc, _source: { ...doc._source, readCount: String(r.readCount), praiseCount: String(r.praiseCount), qualityScore: String(doc._source.qualityScore) } }));
  assert.ok(!matchesEs(r, { ...doc, _source: { ...doc._source, readCount: r.readCount + 1 } }));
  assert.ok(!matchesEs(r, { error: { type: 'unavailable' } }));
});
test('generation refuses overwrite and validator rejects tampered input before any online operation', async () => {
  const c = { ...base, count: 100 }, parent = path.resolve('benchmarks/results');
  fs.mkdirSync(parent, { recursive: true });
  const temp = path.join(parent, `unit-${crypto.randomUUID()}`);
  try {
    await generate(c, temp); await validateFiles(c, temp);
    await assert.rejects(generate(c, temp));
    fs.appendFileSync(path.join(temp, 'articles.jsonl'), '{}\n');
    await assert.rejects(validateFiles(c, temp), /hash mismatch/);
  } finally {
    // Known files only. Never recursively remove a supplied or computed directory.
    for (const f of ['articles.jsonl', 'manifest.json', 'queries.jsonl']) if (fs.existsSync(path.join(temp, f))) fs.unlinkSync(path.join(temp, f));
    fs.rmdirSync(temp);
  }
});
test('default online CLI exits nonzero with isolation guard and performs no import', () => {
  const p = spawnSync(process.execPath, ['benchmarks/scripts/articles.mjs', 'import', '--config', 'benchmarks/config/articles.json',
    '--data', 'benchmarks/data/not-present', '--report', 'benchmarks/results/must-not-exist'], { encoding: 'utf8' });
  assert.equal(p.status, 1); assert.match(p.stderr, /isolated test environment/);
  assert.ok(!fs.existsSync('benchmarks/results/must-not-exist'));
});
