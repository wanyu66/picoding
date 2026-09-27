import { sqlText as q, integer } from './mysql.mjs';
import { hash, topics } from './dataset.mjs';
export const owner = c => `urn:paicoding:bench:${c.batch}`;
export const prefix = c => `bench-${c.batch}-`;
export const scope = (c, alias = 'a') => `${alias}.source_url=${q(owner(c))} AND LEFT(${alias}.url_slug,${prefix(c).length})=${q(prefix(c))}`;
export const date = value => value.slice(0, 19).replace('T', ' ');
export const stageSql = `CREATE TEMPORARY TABLE bench_stage (
 ordinal INT PRIMARY KEY, slug VARCHAR(200), category_id BIGINT, title VARCHAR(120), short_title VARCHAR(120),
 summary VARCHAR(300), content LONGTEXT, created DATETIME, read_cnt INT, praise INT, collections INT,
 official INT, cream INT, topping INT) ENGINE=InnoDB;`;
export function stageRows(c, rows) {
  return 'DELETE FROM bench_stage; INSERT INTO bench_stage VALUES ' + rows.map(r => `(${[
    r.ordinal, q(r.slug), integer(c.fixtures.categories[r.category]), q(r.title), q(r.shortTitle), q(r.summary),
    q(r.content), q(date(r.created)), r.readCount, r.praiseCount, r.collectionCount, r.officalStat, r.creamStat, r.toppingStat
  ].join(',')})`).join(',') + ';';
}
export function eventsSql(c, type, operation) {
  if (!['ARTICLE_CREATE', 'ARTICLE_DELETE'].includes(type)) throw Error('Unsupported fixture event');
  const eventPrefix = hash(`${c.batch}:${type}:${operation}`).slice(0, 24);
  return `INSERT INTO domain_event_outbox
 (event_id,event_type,aggregate_type,aggregate_id,payload,status,retry_count,next_retry_time,create_time,update_time)
 SELECT CONCAT('${eventPrefix}-',s.ordinal),'${type}','ARTICLE',a.id,
 JSON_OBJECT('eventId',CONCAT('${eventPrefix}-',s.ordinal),'eventType','${type}',
 'operatorId',a.user_id,'target',JSON_OBJECT('type','ARTICLE','id',a.id,'articleId',a.id,'ownerId',a.user_id),
 'occurredAt',CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS UNSIGNED),'version',1,'traceId',${q(`bench-${c.batch}`)},'content',NULL),
 'PENDING',0,NOW(),NOW(),NOW() FROM article a JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)};`;
}
export function insertSql(c, rows, operation) {
  const author = integer(c.fixtures.authorId);
  return `${stageRows(c, rows)} START TRANSACTION;
 INSERT INTO article (user_id,article_type,title,short_title,url_slug,summary,category_id,source,source_url,
 status,deleted,offical_stat,cream_stat,topping_stat,create_time,update_time)
 SELECT ${author},1,title,short_title,slug,summary,category_id,2,${q(owner(c))},1,0,official,cream,topping,created,created FROM bench_stage ORDER BY ordinal;
 INSERT INTO article_detail (article_id,version,content,deleted,create_time,update_time)
 SELECT a.id,1,s.content,0,s.created,s.created FROM article a JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)};
 INSERT INTO read_count (document_id,document_type,cnt,create_time,update_time)
 SELECT a.id,1,s.read_cnt,s.created,s.created FROM article a JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)};
 INSERT INTO user_foot (user_id,document_id,document_type,document_user_id,praise_stat,collection_stat,read_stat,create_time,update_time)
 SELECT u.uid,a.id,1,${author},1,IF(u.n<=s.collections,1,0),1,s.created,s.created
 FROM article a JOIN bench_stage s ON a.url_slug=s.slug JOIN bench_actors u ON u.n<=s.praise WHERE ${scope(c)};
 ${eventsSql(c, 'ARTICLE_CREATE', operation)} COMMIT;`;
}
export function cleanupSql(c, rows, operation) {
  return `${stageRows(c, rows)} START TRANSACTION;
 UPDATE article a JOIN bench_stage s ON a.url_slug=s.slug SET a.deleted=1,a.status=0,a.update_time=NOW() WHERE ${scope(c)};
 ${eventsSql(c, 'ARTICLE_DELETE', operation)} COMMIT;`;
}
export function removeChildrenSql(c, rows) {
  // Keep article tombstones and Outbox/consume audit rows for safe late delivery.
  return `${stageRows(c, rows)} START TRANSACTION;
 DELETE d FROM article_detail d JOIN article a ON a.id=d.article_id JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)} AND a.deleted=1;
 DELETE r FROM read_count r JOIN article a ON a.id=r.document_id JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)} AND a.deleted=1 AND r.document_type=1;
 DELETE f FROM user_foot f JOIN article a ON a.id=f.document_id JOIN bench_stage s ON a.url_slug=s.slug WHERE ${scope(c)} AND a.deleted=1 AND f.document_type=1;
 COMMIT;`;
}
export const existingLocal = c => ['paicoding', 'pai_coding'].includes(c.existingLocalDatabase) && c.mysql.database === c.existingLocalDatabase && c.preserveExistingData === true;
export const fixtureUserPrefix = c => `bench-${hash(c.batch).slice(0, 12)}-`;
export const fixtureCategory = (c, topic) => `bench-${topic}-${hash(c.batch).slice(0, 8)}`;
export function validateOnline(c, skipFixtures = false) {
  const existing = existingLocal(c);
  if (!existing && c.isolatedTestEnvironment !== true) throw Error('Configuration does not identify an isolated test environment or authorized existing local database');
  if (!existing && !/^bench_[a-z0-9_]+$/.test(c.mysql.database)) throw Error('Database must use bench_ namespace');
  if (!['127.0.0.1', 'localhost', '::1'].includes(c.mysql.host)) throw Error('Only local isolated MySQL allowed');
  const url = new URL(c.es.url);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || !['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw Error('ES must be a local origin without credentials');
  if (!(existing ? /^[a-z0-9][a-z0-9_-]+$/ : /^bench_[a-z0-9_-]+$/).test(c.es.index)) throw Error('ES target must be a literal permitted name; no wildcard');
  if (!Number.isInteger(c.batchSize) || c.batchSize < 1 || c.batchSize > 500) throw Error('batchSize must be 1..500');
  if (!Number.isInteger(c.syncTimeoutSeconds) || c.syncTimeoutSeconds < 1 || c.syncTimeoutSeconds > 7200 || !Number.isInteger(c.pollIntervalMs) || c.pollIntervalMs < 100) throw Error('Invalid polling bounds');
  if (skipFixtures) return;
  integer(c.fixtures.authorId);
  if (c.fixtures.actorIds.length !== 16 || new Set(c.fixtures.actorIds.map(String)).size !== 16) throw Error('Configure 16 distinct synthetic actor IDs');
  c.fixtures.actorIds.forEach(integer);
  topics.forEach(t => integer(c.fixtures.categories[t[0]]));
}
