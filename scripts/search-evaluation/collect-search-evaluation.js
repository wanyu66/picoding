'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { variants, fields, buildQuery } = require('./search-variants')
const { buildReviewPage } = require('./review-page')
const sha = value => crypto.createHash('sha256').update(value).digest('hex')
const lines = rows => rows.map(r => JSON.stringify(r)).join('\n') + '\n'

async function collect(destination) {
  if (fs.existsSync(destination)) throw new Error('Choose a new output directory; frozen experiments are never overwritten')
  const base = process.env.ES_URL || 'http://127.0.0.1:9200'
  const index = process.env.ES_INDEX || 'paicoding_article'
  const queries = fs.readFileSync(path.join(__dirname, 'queries.jsonl'), 'utf8').trim().split(/\r?\n/).map(JSON.parse)
    .map(q => ({ ...q, split: parseInt(sha(q.intent).slice(0, 8), 16) % 4 === 0 ? 'test' : 'dev', source: 'authored_from_local_corpus_not_traffic_logs' }))
  if (queries.length < 50 || queries.length > 100 || new Set(queries.map(q => q.query)).size !== queries.length) throw new Error('Invalid query set')
  async function request(route, method = 'GET', body) {
    const response = await fetch(base + route, { method, headers: { 'content-type': 'application/json',
      ...(process.env.ES_API_KEY ? { authorization: 'ApiKey ' + process.env.ES_API_KEY } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
    const value = await response.json()
    if (!response.ok || value.error) throw new Error(`${method} ${route}: ${response.status} ${JSON.stringify(value.error)}`)
    if (value.timed_out || value._shards?.failed) throw new Error('Incomplete ES result (timeout or failed shards)')
    return value
  }
  const mappings = await request('/' + encodeURIComponent(index) + '/_mapping')
  const settings = await request('/' + encodeURIComponent(index) + '/_settings')
  const indexNames = Object.keys(mappings)
  if (indexNames.length !== 1) throw new Error('Resolve the alias to one concrete index before evaluation')
  const concreteIndex = indexNames[0]
  const properties = mappings[concreteIndex].mappings.properties
  for (const field of fields) if (!properties[field]?.fields?.pinyin || !properties[field + 'Exact']) throw new Error(`Missing recall fields for ${field}`)
  for (const field of ['readCount', 'praiseCount', 'createTime', 'articleId']) if (!properties[field]) throw new Error(`Missing ${field}`)
  const origin = new Date().toISOString()
  let pit = (await request('/' + encodeURIComponent(concreteIndex) + '/_pit?keep_alive=5m', 'POST')).id
  const documents = [], runs = Object.fromEntries(variants.map(v => [v, []])), requests = []
  try {
    let after
    do {
      const response = await request('/_search', 'POST', { size: 500, pit: { id: pit, keep_alive: '5m' },
        query: { bool: { filter: [{ term: { deleted: 0 } }, { term: { status: 1 } }] } },
        sort: ['_shard_doc'], ...(after ? { search_after: after } : {}) })
      pit = response.pit_id || pit
      if (!response.hits.hits.length) break
      documents.push(...response.hits.hits.map(h => ({ id: String(h._source.articleId), esId: h._id, source: h._source })))
      after = response.hits.hits.at(-1).sort
    } while (true)
    documents.sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }))
    if (!documents.length || new Set(documents.map(d => d.id)).size !== documents.length) throw new Error('Empty corpus or duplicate article IDs')
    const corpusSha256 = sha(lines(documents))
    const querySha256 = sha(lines(queries))
    const experimentId = sha(corpusSha256 + querySha256 + origin).slice(0, 24)
    for (const q of queries) for (const variant of variants) {
      const body = buildQuery(q.query, variant, origin)
      requests.push({ query_id: q.id, variant, body })
      const result = await request('/_search', 'POST', { ...body, pit: { id: pit, keep_alive: '5m' } })
      pit = result.pit_id || pit
      runs[variant].push({ query: q.query, query_id: q.id, variant, experimentId, corpusSha256,
        results: result.hits.hits.map(h => String(h._source.articleId)), scores: result.hits.hits.map(h => h._score),
        total: result.hits.total.value, tookMs: result.took, status: 'ok' })
    }
    const exhaustive = documents.length <= 100
    const judgments = queries.map(q => {
      const candidates = exhaustive ? documents.map(d => d.id)
        : [...new Set(variants.flatMap(v => runs[v].find(r => r.query_id === q.id).results))]
      return { ...q, experimentId, corpusSha256, relevance: Object.fromEntries(candidates.map(id => [id, null])),
        review: { status: 'pending', source: 'human', reviewer: null, reviewedAt: null, answerability: null, corpusReviewed: false, note: '' } }
    })
    const manifest = { schemaVersion: 1, experimentId, capturedAt: origin, fixedDecayOrigin: origin,
      corpusSha256, querySha256, sourceIndex: concreteIndex, documentCount: documents.length,
      queryCount: queries.length, intentCount: new Set(queries.map(q => q.intent)).size,
      queryAuthorship: 'Assistant-authored from corpus contents; not human search logs; requires curator review',
      runSha256: Object.fromEntries(variants.map(v => [v, sha(lines(runs[v]))])),
      requestsSha256: sha(lines(requests)),
      judgmentsComplete: false, relevanceMetrics: null, candidateScope: exhaustive ? 'entire_corpus' : 'pooled_top20',
      candidatePairs: judgments.reduce((n, q) => n + Object.keys(q.relevance).length, 0),
      variants, limits: ['Human relevance judgments pending', 'Authored queries, not sampled traffic',
        'Small local corpus is a pilot, not evidence of community-wide improvement',
        'C adds only heat and time decay; production qualityScore is intentionally omitted',
        'All variants share the existing Chinese and synonym analyzers'],
      queryBuilderSha256: sha(fs.readFileSync(path.join(__dirname, 'search-variants.js'))),
      sourceServiceSha256: sha(fs.readFileSync(path.join(__dirname, '../../paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java'))),
      operational: Object.fromEntries(variants.map(v => [v, { queries: runs[v].length,
        emptyResults: runs[v].filter(r => !r.results.length).length,
        note: 'Result availability only; not relevance quality or measured user CTR' }])) }
    fs.mkdirSync(destination, { recursive: true })
    const write = (name, value) => fs.writeFileSync(path.join(destination, name), value)
    write('manifest.json', JSON.stringify(manifest, null, 2) + '\n')
    write('corpus.jsonl', lines(documents)); write('queries.jsonl', lines(queries))
    write('judgments.pending.jsonl', lines(judgments)); write('requests.jsonl', lines(requests))
    write('index-mapping.json', JSON.stringify(mappings, null, 2))
    write('index-settings.json', JSON.stringify(settings, null, 2))
    for (const variant of variants) write(variant + '.jsonl', lines(runs[variant]))
    write('review.html', buildReviewPage({ manifest, judgments, documents }))
    console.log(JSON.stringify({ destination, ...manifest }, null, 2))
  } finally { await request('/_pit', 'DELETE', { id: pit }) }
}

if (require.main === module) collect(path.resolve(process.argv[2] || path.join(__dirname, 'datasets', new Date().toISOString().replace(/[:.]/g, '-'))))
  .catch(e => { console.error(e.message); process.exitCode = 1 })
module.exports = { collect, sha, lines }
