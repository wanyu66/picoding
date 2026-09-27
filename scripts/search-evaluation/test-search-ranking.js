'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const { evaluate, dcg, readJsonLines } = require('./evaluate-search-ranking')
const { pairedBootstrap, compare } = require('./compare-search-variants')
const { buildQuery } = require('./search-variants')
const { buildReviewPage } = require('./review-page')

// Synthetic unit fixtures only. Never exported as actual human judgments.
const judgment = (query, relevance, answerability = 'answerable') => ({ id: query, query, intent: query,
  category: 'unit_fixture', split: 'test', experimentId: 'unit', corpusSha256: 'unit', relevance,
  review: { source: 'human', status: 'human_reviewed', reviewer: 'SYNTHETIC_UNIT_FIXTURE',
    reviewedAt: '2026-09-23T00:00:00Z', corpusReviewed: true, answerability } })
const run = (query, results) => ({ query, results, status: 'ok', experimentId: 'unit', corpusSha256: 'unit' })

test('perfect ranking and empty answerable result use the same denominator', () => {
  const result = evaluate([judgment('a', { 1: 3, 2: 2 }), judgment('b', { 1: 3 })], [run('a', [1, 2]), run('b', [])], 10)
  assert.equal(result['ndcg@10'], 0.5); assert.equal(result['mrr@10'], 0.5)
  assert.equal(result['judged_recall@10'], 0.5); assert.equal(result.answerable_zero_result_rate, 0.5)
  assert.equal(result.click_through_rate, null)
})
test('MRR is bounded by K and weak mentions do not count as an answer', () => {
  const q = judgment('a', { 1: 1, 2: 0, 3: 3 }), r = run('a', [1, 2, 3])
  assert.equal(evaluate([q], [r], 2)['mrr@2'], 0)
  assert.equal(evaluate([q], [r], 3)['mrr@3'], 0.333333)
  assert.equal(evaluate([q], [r], 2)['judged_recall@2'], 0)
})
test('NDCG uses graded gains and an independently sorted ideal ranking', () => {
  const q = judgment('a', { 1: 3, 2: 1, 3: 2 }), r = run('a', [2, 3, 1])
  assert.equal(evaluate([q], [r], 3)['ndcg@3'], Number((dcg([1, 2, 3], 3) / dcg([3, 2, 1], 3)).toFixed(6)))
})
test('no-answer queries have separate false-positive metrics, not zero NDCG', () => {
  const result = evaluate([judgment('a', { 1: 0 }, 'no_answer'), judgment('b', { 1: 1 }, 'no_answer')], [run('a', []), run('b', [1])], 10)
  assert.equal(result['ndcg@10'], null); assert.equal(result['mrr@10'], null)
  assert.equal(result.no_answer_false_positive_rate, 0.5)
  assert.equal(result.answerable_zero_result_rate, null)
})
test('missing run is rejected rather than counted as a zero-result query', () => {
  assert.throws(() => evaluate([judgment('a', { 1: 3 })], [], 10), /coverage/)
})
test('duplicate run queries, judgment queries and result IDs are rejected', () => {
  const q = judgment('a', { 1: 3 }), r = run('a', [1])
  assert.throws(() => evaluate([q], [r, r]), /Duplicate query/)
  assert.throws(() => evaluate([q, q], [r]), /Duplicate query/)
  assert.throws(() => evaluate([q], [run('a', [1, '1'])]), /Duplicate result/)
})
test('unjudged results including beyond K are not silently assigned zero', () => {
  assert.throws(() => evaluate([judgment('a', { 1: 3 })], [run('a', [1, 2])], 1), /Unjudged result/)
})
test('null, negative, fractional and string grades are rejected', () => {
  for (const grade of [null, -1, 4, 1.5, '3']) assert.throws(() => evaluate([judgment('a', { 1: grade })], [run('a', [1])]), /grade/)
})
test('pending and model-only judgments cannot produce human relevance metrics', () => {
  const q = judgment('a', { 1: 3 })
  q.review.status = 'pending'; assert.throws(() => evaluate([q], [run('a', [1])]), /Human review/)
  q.review.status = 'human_reviewed'; q.review.source = 'model'; assert.throws(() => evaluate([q], [run('a', [1])]), /Human review/)
})
test('failed run and mismatched snapshot cannot be scored', () => {
  const q = judgment('a', { 1: 3 }), r = run('a', [1])
  r.status = 'error'; assert.throws(() => evaluate([q], [r]), /Run failed/)
  r.status = 'ok'; r.corpusSha256 = 'other'; assert.throws(() => evaluate([q], [r]), /Snapshot mismatch/)
})
test('answerability must agree with grades and actual review', () => {
  assert.throws(() => evaluate([judgment('a', { 1: 3 }, 'no_answer')], [run('a', [1])]), /contradicts/)
  const q = judgment('a', { 1: 3 }); q.review.corpusReviewed = false
  assert.throws(() => evaluate([q], [run('a', [1])]), /review missing/)
})
test('invalid K fails instead of producing NaN metrics', () => {
  for (const k of [0, -1, 1.5, NaN]) assert.throws(() => evaluate([judgment('a', { 1: 3 })], [run('a', [1])], k), /K must/)
})
test('paired confidence intervals resample intent groups deterministically', () => {
  const rows = [{ id: 'a', intent: 'same', ndcg: 0.3 }, { id: 'b', intent: 'same', ndcg: 0.6 }]
  const result = pairedBootstrap(rows, rows, 'ndcg', 100)
  assert.equal(result.intentGroups, 1); assert.deepEqual(result.percentile95, [0, 0]); assert.equal(result.delta, 0)
})
test('C changes only heat and decay, while B shares the same retrieval query', () => {
  const origin = '2026-09-23T00:00:00Z'
  const a = buildQuery('缓存', 'a_fulltext', origin), b = buildQuery('缓存', 'b_recall', origin), c = buildQuery('缓存', 'c_heat_decay', origin)
  assert.deepEqual(c.query.function_score.query, b.query)
  assert.equal(c.query.function_score.functions.length, 3)
  assert.equal(c.query.function_score.functions[2].gauss.createTime.origin, origin)
  assert.equal(JSON.stringify(c).includes('qualityScore'), false)
  assert.deepEqual(a.sort, b.sort); assert.deepEqual(b.sort, c.sort)
  assert.equal(a.query.bool.must[0].multi_match.fields.some(f => f.includes('^')), false)
  assert.throws(() => buildQuery('a', 'c_heat_decay'), /fixed time/)
})
test('query family split has no leakage and all five query types are covered', () => {
  const queries = readJsonLines(path.join(__dirname, 'datasets/local-2026-09-23/queries.jsonl'))
  assert.equal(queries.length, 64)
  const families = new Map()
  for (const q of queries) { if (families.has(q.intent)) assert.equal(families.get(q.intent), q.split); families.set(q.intent, q.split) }
  assert.equal(new Set(queries.map(q => q.category)).size, 5)
})
test('actual frozen pilot refuses to score while real human labels are pending', () => {
  const dir = path.join(__dirname, 'datasets/local-2026-09-23')
  assert.throws(() => compare(dir, path.join(dir, 'judgments.pending.jsonl')), /Human review pending/)
  const pending = readJsonLines(path.join(dir, 'judgments.pending.jsonl'))
  assert.equal(pending.flatMap(q => Object.values(q.relevance)).filter(g => g !== null).length, 0)
})
test('frozen real runs cover every query and match saved DSL and source hashes', () => {
  const crypto = require('node:crypto'), dir = path.join(__dirname, 'datasets/local-2026-09-23')
  const hash = name => crypto.createHash('sha256').update(fs.readFileSync(path.join(dir, name))).digest('hex')
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'))
  const queries = readJsonLines(path.join(dir, 'queries.jsonl'))
  const corpus = new Set(readJsonLines(path.join(dir, 'corpus.jsonl')).map(d => d.id))
  assert.equal(hash('corpus.jsonl'), manifest.corpusSha256)
  assert.equal(hash('queries.jsonl'), manifest.querySha256)
  assert.equal(hash('requests.jsonl'), manifest.requestsSha256)
  const requests = readJsonLines(path.join(dir, 'requests.jsonl'))
  assert.equal(requests.length, queries.length * 3)
  for (const variant of manifest.variants) {
    const runs = readJsonLines(path.join(dir, variant + '.jsonl'))
    assert.equal(hash(variant + '.jsonl'), manifest.runSha256[variant])
    assert.deepEqual(runs.map(r => r.query_id), queries.map(q => q.id))
    assert.equal(runs.filter(r => !r.results.length).length, manifest.operational[variant].emptyResults)
    for (const r of runs) {
      assert.equal(r.status, 'ok'); assert.equal(r.experimentId, manifest.experimentId)
      assert.equal(r.corpusSha256, manifest.corpusSha256)
      assert.equal(new Set(r.results).size, r.results.length)
      assert.ok(r.results.every(id => corpus.has(id)))
      assert.deepEqual(requests.find(d => d.variant === variant && d.query_id === r.query_id).body,
        buildQuery(r.query, variant, manifest.fixedDecayOrigin))
    }
  }
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname, 'search-variants.js'))).digest('hex'), manifest.queryBuilderSha256)
})
test('blind page escapes document HTML and strips popularity and timestamps', () => {
  const html = buildReviewPage({ manifest: {}, judgments: [], documents: [{ id: '1', source: { title: '</script><script>malicious()', readCount: 99, createTime: 'secret-time', content: '<img onerror=bad()>' } }] })
  assert.equal(html.includes('</script><script>malicious()'), false)
  const payload = JSON.parse(html.match(/id="data">([\s\S]*?)<\/script>/)[1])
  assert.equal(payload.documents[0].source.readCount, undefined)
  assert.equal(payload.documents[0].source.createTime, undefined)
})

test('complete synthetic review compares all versions and rejects altered artifacts', () => {
  const os = require('node:os'), crypto = require('node:crypto')
  const tempRoot = path.resolve(os.tmpdir()), dir = fs.mkdtempSync(path.join(tempRoot, 'search-evaluation-unit-'))
  const write = (name, rows) => fs.writeFileSync(path.join(dir, name), rows.map(r => JSON.stringify(r)).join('\n') + '\n')
  const hash = name => crypto.createHash('sha256').update(fs.readFileSync(path.join(dir, name))).digest('hex')
  try {
    write('corpus.jsonl', [{ id: '1', source: { title: 'UNIT FIXTURE' } }])
    const corpusSha256 = hash('corpus.jsonl')
    const q = { ...judgment('unit', { 1: 3 }), corpusSha256 }
    write('queries.jsonl', [q]); write('review.jsonl', [q])
    const variants = ['a_fulltext', 'b_recall', 'c_heat_decay']
    for (const variant of variants) write(variant + '.jsonl', [{ ...run('unit', [1]), corpusSha256, variant }])
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify({ experimentId: 'unit', corpusSha256,
      querySha256: hash('queries.jsonl'), candidateScope: 'entire_corpus', documentCount: 1, variants,
      runSha256: Object.fromEntries(variants.map(v => [v, hash(v + '.jsonl')])) }))
    const result = compare(dir, path.join(dir, 'review.jsonl'))
    assert.equal(result.results.a_fulltext['ndcg@10'], 1)
    assert.equal(result.pairedDeltas.b_recall_minus_a_fulltext.all.ndcg.delta, 0)
    fs.appendFileSync(path.join(dir, 'b_recall.jsonl'), '\n')
    assert.throws(() => compare(dir, path.join(dir, 'review.jsonl')), /run hash mismatch/)
    write('b_recall.jsonl', [{ ...run('unit', [1]), corpusSha256, variant: 'b_recall' }])
    write('review.jsonl', [{ ...q, category: 'tampered' }])
    assert.throws(() => compare(dir, path.join(dir, 'review.jsonl')), /metadata changed/)
    write('review.jsonl', [q]); fs.appendFileSync(path.join(dir, 'corpus.jsonl'), '\n')
    assert.throws(() => compare(dir, path.join(dir, 'review.jsonl')), /hash mismatch/)
  } finally {
    if (path.dirname(path.resolve(dir)) !== tempRoot || !path.basename(dir).startsWith('search-evaluation-unit-')) throw Error('Unsafe temporary path')
    fs.rmSync(dir, { recursive: true })
  }
})
