import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { Mysql, sqlText as q, integer } from './mysql.mjs';
import { hash, json, readArticles, validateFiles } from './dataset.mjs';
import { validateOnline, scope, prefix, stageSql, stageRows, insertSql, eventsSql, cleanupSql, removeChildrenSql, existingLocal, fixtureUserPrefix, fixtureCategory } from './sql.mjs';

export async function* batches(dir, size) {
  let rows = [];
  for await (const row of readArticles(dir)) {
    rows.push(row);
    if (rows.length === size) { yield rows; rows = []; }
  }
  if (rows.length) yield rows;
}
async function es(c, route, body) {
  const key = process.env[c.es.apiKeyEnv];
  const response = await fetch(`${c.es.url.replace(/\/$/, '')}/${encodeURIComponent(c.es.index)}/${route}`, {
    method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(30000),
    headers: { 'content-type': 'application/json', ...(key ? { Authorization: `ApiKey ${key}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  if (!response.ok) throw Error(`ES ${route}: HTTP ${response.status}`);
  return response.json();
}
const dbRows = (db, c) => db.records(`SELECT JSON_OBJECT('id',CAST(a.id AS CHAR),'slug',a.url_slug,
 'title',a.title,'shortTitle',a.short_title,'summary',a.summary,'category',a.category_id,'author',CAST(a.user_id AS CHAR),
 'deleted',a.deleted,'status',a.status,'created',DATE_FORMAT(a.create_time,'%Y-%m-%dT%H:%i:%s.000Z'),
 'official',a.offical_stat,'cream',a.cream_stat,'topping',a.topping_stat,
 'contentHash',(SELECT SHA2(d.content,256) FROM article_detail d WHERE d.article_id=a.id AND d.version=1 AND d.deleted=0),
 'details',(SELECT COUNT(*) FROM article_detail d WHERE d.article_id=a.id),
 'reads',(SELECT cnt FROM read_count r WHERE r.document_id=a.id AND r.document_type=1),
 'praise',(SELECT COUNT(*) FROM user_foot f WHERE f.document_id=a.id AND f.document_type=1 AND f.praise_stat=1),
 'collections',(SELECT COUNT(*) FROM user_foot f WHERE f.document_id=a.id AND f.document_type=1 AND f.collection_stat=1))
 FROM article a JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)} ORDER BY s.ordinal;`);

export function matchesDb(c, expected, actual) {
  return actual && actual.slug === expected.slug && actual.title === expected.title && actual.shortTitle === expected.shortTitle &&
    actual.summary === expected.summary && String(actual.category) === String(c.fixtures.categories[expected.category]) &&
    actual.author === String(c.fixtures.authorId) && actual.deleted === 0 && actual.status === 1 && actual.created === expected.created &&
    actual.official === expected.officalStat && actual.cream === expected.creamStat && actual.topping === expected.toppingStat &&
    actual.contentHash === hash(expected.content) && actual.details === 1 && actual.reads === expected.readCount &&
    actual.praise === expected.praiseCount && actual.collections === expected.collectionCount;
}
export function matchesEs(expected, doc) {
  const s = doc?._source;
  const created = typeof s?.createTime === 'string' && /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d$/.test(s.createTime)
    ? s.createTime.replace(' ', 'T') + 'Z' : s?.createTime;
  return doc?.found === true && s?.urlSlug === expected.slug && s.title === expected.title && s.shortTitle === expected.shortTitle &&
    s.summary === expected.summary && hash(s.content ?? '') === hash(expected.content) && s.deleted === 0 && s.status === 1 &&
    String(s.readCount) === String(expected.readCount) && String(s.praiseCount) === String(expected.praiseCount) &&
    s.officalStat === expected.officalStat && s.creamStat === expected.creamStat && s.toppingStat === expected.toppingStat &&
    String(s.qualityScore) === String(1 + expected.officalStat * 3 + expected.creamStat * 5 + expected.toppingStat * 2) &&
    new Date(created).getTime() === Date.parse(expected.created);
}
async function preflight(c, db, reportDir) {
  await db.query("SET time_zone='+00:00'; SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;");
  const target = await db.records("SELECT JSON_OBJECT('database',DATABASE(),'version',VERSION(),'timeZone',@@session.time_zone,'autoIncrementIncrement',@@auto_increment_increment);");
  if (target[0].database !== c.mysql.database) throw Error('Wrong database');
  const lock = await db.query(`SELECT GET_LOCK(${q(`pai-bench:${c.mysql.database}`)},0);`);
  if (lock[0] !== '1') throw Error('Another benchmark owns this database');
  const tables = await db.records(`SELECT JSON_OBJECT('table',TABLE_NAME,'engine',ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('article','article_detail','read_count','user_foot','domain_event_outbox','user','user_info','category');`);
  if (tables.length !== 8 || tables.some(t => t.engine !== 'InnoDB')) throw Error('Existing migrated InnoDB schema required; tool never applies migrations');
  const ids = [...new Set([c.fixtures.authorId, ...c.fixtures.actorIds].map(integer))];
  const users = await db.records(`SELECT JSON_OBJECT('id',CAST(u.id AS CHAR),'name',i.user_name) FROM user u JOIN user_info i ON i.user_id=u.id WHERE u.id IN (${ids.join(',')}) AND u.deleted=0;`);
  if (users.length !== ids.length || new Set(users.map(u => u.id)).size !== ids.length || users.some(u => !u.name.startsWith(fixtureUserPrefix(c)))) throw Error('All fixture users must belong to this batch with one user_info row');
  for (const [topic, id] of Object.entries(c.fixtures.categories)) {
    const found = await db.records(`SELECT JSON_OBJECT('name',category_name) FROM category WHERE id=${integer(id)} AND status=1 AND deleted=0;`);
    if (found.length !== 1 || found[0].name !== fixtureCategory(c, topic)) throw Error(`Batch synthetic category ${topic} required`);
  }
  const mapping = await es(c, '_mapping');
  if (Object.keys(mapping).length !== 1 || Object.keys(mapping).some(n => existingLocal(c)
    ? !(n === c.es.index || n.startsWith(c.es.index + '_v')) : !/^bench_[a-z0-9_-]+$/.test(n))) throw Error('ES target must resolve to one expected physical index');
  const props = Object.values(mapping)[0]?.mappings?.properties;
  if (props?.urlSlug?.type !== 'keyword' || props?.title?.analyzer !== 'pai_zh_index' || props?.title?.fields?.pinyin?.analyzer !== 'pai_pinyin_index' || props?.readCount?.type !== 'long') throw Error('Actual Paicoding mapping required; no substitute mapping is installed');
  fs.writeFileSync(path.join(reportDir, 'preflight.json'), json({ mysql: target, tables, mapping, settings: await es(c, '_settings') }));
  await db.query(stageSql + `CREATE TEMPORARY TABLE bench_actors (n INT PRIMARY KEY,uid BIGINT) ENGINE=InnoDB;
 INSERT INTO bench_actors VALUES ${c.fixtures.actorIds.map((id, i) => `(${i + 1},${integer(id)})`).join(',')};`);
}
async function count(db, c) {
  const [r] = await db.records(`SELECT JSON_OBJECT('total',COUNT(*),'active',COALESCE(SUM(deleted=0 AND status=1),0)) FROM article a WHERE ${scope(c)};`);
  // Exact-length suffix prevents batch "foo" from matching batch "foo-bar".
  const e = await es(c, '_count', { query: { regexp: { urlSlug: `${prefix(c)}[0-9]{6}` } } });
  if (e._shards?.failed) throw Error('ES count has failed shards');
  return { databaseBatchCount: r.total, databaseActiveCount: Number(r.active), esBatchCount: e.count };
}
async function untouchedArticles(db, c) {
  // Compact guard for original article headers. Not a backup and not a checksum of every related table.
  const [r] = await db.records(`SELECT JSON_OBJECT('count',COUNT(*),'headerChecksum',CAST(BIT_XOR(CRC32(CONCAT_WS('|',id,user_id,title,short_title,url_slug,status,deleted,summary))) AS CHAR))
   FROM article a WHERE NOT (${scope(c)});`);
  return r;
}
async function waitCount(db, c, wanted, dir) {
  const start = performance.now();
  do {
    const counts = await count(db, c);
    fs.appendFileSync(path.join(dir, 'sync-polls.jsonl'), json({ at: new Date().toISOString(), ...counts }));
    if (counts.esBatchCount === wanted) return counts;
    await new Promise(resolve => setTimeout(resolve, c.pollIntervalMs));
  } while (performance.now() - start < c.syncTimeoutSeconds * 1000);
  throw Error('ES visibility deadline exceeded; Outbox SENT is not proof of indexing. Inspect failed events and use sync to replay');
}
async function verifyAll(db, c, dir, reportDir, cleaned = false) {
  let checked = 0, missing = 0, mismatch = 0;
  for await (const rows of batches(dir, c.batchSize)) {
    await db.query(stageRows(c, rows));
    const records = await dbRows(db, c), bySlug = new Map(records.map(r => [r.slug, r]));
    if (records.length !== bySlug.size) throw Error('Duplicate database slugs');
    const docs = records.length ? (await es(c, '_mget', { ids: records.map(r => r.id) })).docs : [];
    if (!Array.isArray(docs)) throw Error('Invalid ES mget response');
    const byId = new Map(docs.map(d => [String(d._id), d]));
    for (const r of rows) {
      const actual = bySlug.get(r.slug), doc = actual && byId.get(actual.id);
      // Cleanup may be requested for a partially imported batch. Missing rows are allowed only there.
      const ok = cleaned ? (!actual || (actual.deleted === 1 && actual.status === 0 && doc?.found === false && !doc.error)) : matchesDb(c, r, actual) && matchesEs(r, doc);
      if (!actual) missing++;
      if (!ok) mismatch++;
      fs.appendFileSync(path.join(reportDir, 'consistency.jsonl'), json({ ordinal: r.ordinal, slug: r.slug, id: actual?.id ?? null,
        databaseFound: !!actual, esFound: doc?.found ?? null,
        databaseFieldsMatch: cleaned ? null : !!matchesDb(c, r, actual), esFieldsMatch: cleaned ? null : !!matchesEs(r, doc), status: ok ? 'PASS' : 'FAIL' }));
      checked++;
    }
  }
  return { checked, missingDatabaseRows: missing, mismatches: mismatch };
}
export async function workflow(command, c, dir, reportDir) {
  validateOnline(c);
  const manifest = await validateFiles(c, dir);
  fs.mkdirSync(reportDir, { recursive: false });
  const started = new Date().toISOString(), op = crypto.randomUUID(), start = performance.now();
  const summary = { command, batch: c.batch, seed: c.seed, articleTotal: c.count, datasetSha256: manifest.sha256,
    started, status: 'RUNNING', concurrency: 1, transactionBatchSize: c.batchSize, importedThisRun: 0,
    mysqlImportSeconds: null, esSyncSeconds: null, importArticlesPerSecond: null, indexDocumentCount: null,
    note: 'SQL fixture import plus existing Outbox consumer, not article publish API performance', node: process.version };
  let db;
  try {
    db = new Mysql(c.mysql);
    await preflight(c, db, reportDir);
    summary.before = await count(db, c);
    summary.unownedArticlesBefore = await untouchedArticles(db, c);
    let mysqlMs = 0, firstCommitAt = null;
    if (['import', 'sync', 'cleanup'].includes(command)) {
      for await (const rows of batches(dir, c.batchSize)) {
        await db.query(stageRows(c, rows));
        // Refuse collision even if only slug matches and ownership marker does not.
        const collision = await db.query(`SELECT COUNT(*) FROM article a JOIN bench_stage s ON a.url_slug=s.slug WHERE NOT (${scope(c)});`);
        if (collision[0] !== '0') throw Error('Foreign slug collision; no mutation allowed');
        const existing = await dbRows(db, c);
        if (command === 'import' && existing.length) {
          if (existing.length !== rows.length || !rows.every((r, i) => matchesDb(c, r, existing[i]))) throw Error('Partial/divergent batch detected; preserve data and investigate or cleanup');
          fs.appendFileSync(path.join(reportDir, 'batches.jsonl'), json({ first: rows[0].ordinal, count: rows.length, status: 'ALREADY_COMMITTED' }));
          continue;
        }
        if (command === 'sync' && (existing.length !== rows.length || !rows.every((r, i) => matchesDb(c, r, existing[i])))) throw Error('sync requires exact active DB fixture');
        const sql = command === 'import' ? insertSql(c, rows, op) : command === 'cleanup' ? cleanupSql(c, rows, op) :
          `${stageRows(c, rows)} START TRANSACTION; ${eventsSql(c, 'ARTICLE_CREATE', op)} COMMIT;`;
        const t = performance.now(); await db.query(sql); const elapsedMs = performance.now() - t;
        mysqlMs += elapsedMs; firstCommitAt ??= new Date().toISOString();
        if (command === 'import') summary.importedThisRun += rows.length;
        fs.appendFileSync(path.join(reportDir, 'batches.jsonl'), json({ first: rows[0].ordinal, count: rows.length, elapsedMs, committedAt: new Date().toISOString(), status: 'COMMITTED' }));
      }
      if (command === 'import') {
        summary.mysqlImportSeconds = mysqlMs / 1000;
        summary.importArticlesPerSecond = summary.importedThisRun ? summary.importedThisRun / summary.mysqlImportSeconds : null;
      }
    }
    const syncStart = performance.now();
    summary.after = command === 'verify' ? await count(db, c) : await waitCount(db, c, command === 'cleanup' ? 0 : c.count, reportDir);
    summary.consistency = await verifyAll(db, c, dir, reportDir, command === 'cleanup');
    if (command !== 'verify') summary.esSyncSeconds = (performance.now() - syncStart) / 1000;
    summary.syncTimingDefinition = 'Tail visibility wait + full verification after SQL phase; consumer runs concurrently during import. Not isolated ES bulk indexing time.';
    summary.firstCommitAt = firstCommitAt;
    summary.indexDocumentCount = summary.after.esBatchCount;
    if (summary.consistency.mismatches || summary.after.esBatchCount !== (command === 'cleanup' ? 0 : c.count) ||
      (command !== 'cleanup' && (summary.after.databaseBatchCount !== c.count || summary.after.databaseActiveCount !== c.count)) ||
      (command === 'cleanup' && summary.after.databaseActiveCount !== 0)) throw Error('Full consistency check failed; inspect consistency.jsonl');
    if (command === 'cleanup') {
      for await (const rows of batches(dir, c.batchSize)) await db.query(removeChildrenSql(c, rows));
      const children = await db.records(`SELECT JSON_OBJECT('details',(SELECT COUNT(*) FROM article_detail d JOIN article a ON a.id=d.article_id WHERE ${scope(c)}),
       'readCounts',(SELECT COUNT(*) FROM read_count r JOIN article a ON a.id=r.document_id WHERE r.document_type=1 AND ${scope(c)}),
       'feet',(SELECT COUNT(*) FROM user_foot f JOIN article a ON a.id=f.document_id WHERE f.document_type=1 AND ${scope(c)}));`);
      summary.cleanup = { children: children[0], retained: 'Soft-deleted article tombstones and Outbox/consume audit rows; configured synthetic users/categories remain unchanged' };
      if (Object.values(children[0]).some(n => n !== 0)) throw Error('Child cleanup incomplete');
    }
    summary.unownedArticlesAfter = await untouchedArticles(db, c);
    if (JSON.stringify(summary.unownedArticlesAfter) !== JSON.stringify(summary.unownedArticlesBefore)) throw Error('Non-batch article headers changed during run; do not claim preservation (concurrent edits also trigger this check)');
    summary.status = 'PASS';
  } catch (e) {
    summary.status = 'FAIL'; summary.error = e.message;
    process.exitCode = 1;
  } finally {
    db?.close(); summary.finished = new Date().toISOString(); summary.totalSeconds = (performance.now() - start) / 1000;
    fs.writeFileSync(path.join(reportDir, 'summary.json'), json(summary));
  }
  return summary;
}
