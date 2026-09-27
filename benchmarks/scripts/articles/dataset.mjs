import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import { once } from 'node:events';

export const topics = [
  ['Java', '并发编程', 'java虚拟机', 'jvm', 'bingfabiancheng', 'jvaa'],
  ['Spring', '依赖注入', '控制反转', 'dependency injection', 'yilaizhuru', 'srping'],
  ['Redis', '缓存', '缓存', 'cache', 'huancun', 'reids'],
  ['MySQL', '事务隔离', '数据库', 'db', 'shujuku', 'myslq'],
  ['Elasticsearch', '全文检索', '搜索引擎', 'elasticsearch', 'quanwenjiansuo', 'elastcisearch']
];
export const hash = value => crypto.createHash('sha256').update(value).digest('hex');
export const json = value => JSON.stringify(value) + '\n';
export function validateDataset(c) {
  if (![100, 10000, 50000, 100000].includes(c.count)) throw Error('count must be 100 (smoke), 10000, 50000 or 100000');
  if (!Number.isInteger(c.seed) || c.seed < 0 || c.seed > 0xffffffff) throw Error('seed must be uint32');
  if (!/^[a-z0-9][a-z0-9-]{2,47}$/.test(c.batch)) throw Error('batch must be 3..48 lowercase letters/digits/hyphens');
  if (!/^\d{4}-\d\d-\d\dT00:00:00\.000Z$/.test(c.referenceTime) || !Number.isFinite(Date.parse(c.referenceTime))) throw Error('referenceTime must be fixed UTC midnight');
}
export function* articles(c) {
  validateDataset(c);
  let state = c.seed >>> 0;
  const random = n => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state % n; };
  for (let i = 0; i < c.count; i++) {
    const t = topics[i % topics.length], variant = Math.floor(i / topics.length) % 6;
    const keywords = [t[1], t[3], `${t[1]} ${t[3]}`, t[2], t[4], t[5]];
    const title = `${t[0]} ${keywords[variant]}${['', '实践与问题排查', '从工作原理到并发场景下的性能分析与恢复验证'][i % 3]} ${String(i + 1).padStart(6, '0')}`;
    const paragraph = `${t[0]} ${keywords[variant]}：案例${random(10000)}讨论请求处理、边界条件和数据一致性。\n` +
      `Example ${random(10000)}: measure latency, inspect queries and validate recovery. 不把模拟结果当成线上指标。\n`;
    const length = [240, 1200, 6000][Math.floor(i / 7) % 3];
    const content = (`# ${title}\n\n` + paragraph.repeat(Math.ceil(length / paragraph.length))).slice(0, length);
    const reads = [0, random(100), 1000 + random(99000), 100000 + random(900000)][i % 4];
    const praise = [0, 1, 4, 16][Math.floor(i / 3) % 4];
    const created = new Date(Date.parse(c.referenceTime) - random(731) * 86400000 - random(86400) * 1000).toISOString();
    yield { ordinal: i + 1, slug: `bench-${c.batch}-${String(i + 1).padStart(6, '0')}`, category: t[0],
      variant: ['zh', 'en', 'mixed', 'synonym', 'pinyin', 'typo'][variant], title, shortTitle: title.slice(0, 35),
      summary: `${t[0]} ${keywords[variant]}。${paragraph}`.slice(0, 280), content, created,
      readCount: reads, praiseCount: praise, collectionCount: Math.floor(praise / 2),
      officalStat: i % 7 === 0 ? 1 : 0, creamStat: i % 11 === 0 ? 1 : 0, toppingStat: i % 29 === 0 ? 1 : 0 };
  }
}
export function datasetSpec(c) {
  return { version: 1, count: c.count, seed: c.seed, batch: c.batch, referenceTime: c.referenceTime };
}
export async function fileHash(file) {
  const sha = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) sha.update(chunk);
  return sha.digest('hex');
}
export async function* readArticles(dir) {
  const input = fs.createReadStream(path.join(dir, 'articles.jsonl'));
  const lines = readline.createInterface({ input, crlfDelay: Infinity });
  try { for await (const line of lines) if (line) yield JSON.parse(line); }
  finally { lines.close(); input.destroy(); }
}
export async function generate(c, dir) {
  validateDataset(c);
  fs.mkdirSync(dir, { recursive: false }); // refuse overwriting any previous batch
  const output = fs.createWriteStream(path.join(dir, 'articles.jsonl'), { flags: 'wx' });
  const done = once(output, 'finish');
  const sha = crypto.createHash('sha256'), distribution = { category: {}, variant: {}, contentLength: {} };
  let bytes = 0;
  for (const row of articles(c)) {
    const line = json(row); sha.update(line); bytes += Buffer.byteLength(line);
    for (const [key, value] of [['category', row.category], ['variant', row.variant], ['contentLength', row.content.length]]) {
      distribution[key][value] = (distribution[key][value] || 0) + 1;
    }
    if (!output.write(line)) await once(output, 'drain');
  }
  output.end(); await done;
  const manifest = { ...datasetSpec(c), sha256: sha.digest('hex'), bytes, distribution,
    source: 'deterministic synthetic fixture, not real users/articles', generatorSha256: await fileHash(new URL(import.meta.url)) };
  fs.writeFileSync(path.join(dir, 'manifest.json'), json(manifest), { flag: 'wx' });
  fs.writeFileSync(path.join(dir, 'queries.jsonl'), topics.flatMap(t => [t[1], t[2], t[3], t[4], t[5]].map(query => json({ category: t[0], query, relevance: null }))).join(''), { flag: 'wx' });
  return manifest;
}
export async function validateFiles(c, dir) {
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
  if (json(datasetSpec(c)) !== json(datasetSpec(manifest))) throw Error('Dataset/config mismatch');
  if (await fileHash(path.join(dir, 'articles.jsonl')) !== manifest.sha256) throw Error('Dataset hash mismatch');
  // Recompute deterministic records as well: editing the manifest cannot authorize foreign rows.
  const expected = articles(c); let count = 0;
  for await (const row of readArticles(dir)) {
    if (json(row) !== json(expected.next().value)) throw Error('Dataset is not the expected deterministic fixture');
    count++;
  }
  if (count !== c.count) throw Error('Dataset count mismatch');
  return manifest;
}
