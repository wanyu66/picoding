'use strict'
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { evaluate, aggregate, readJsonLines, mean, round } = require('./evaluate-search-ranking')
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')

// Resample intent families, not correlated spelling variants independently.
function pairedBootstrap(left, right, field, iterations = 2000) {
  const byId = new Map(right.map(r => [r.id, r])), groups = new Map()
  for (const row of left) {
    if (row[field] === null) continue
    if (!groups.has(row.intent)) groups.set(row.intent, [])
    groups.get(row.intent).push(byId.get(row.id)[field] - row[field])
  }
  if (!groups.size) return null
  const values = [...groups.values()], samples = []
  let state = 20260923
  const random = () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 2 ** 32 }
  for (let i = 0; i < iterations; i++) {
    const deltas = []
    for (let n = 0; n < values.length; n++) deltas.push(...values[Math.floor(random() * values.length)])
    samples.push(mean(deltas))
  }
  samples.sort((a, b) => a - b)
  return { delta: round(mean(values.flat())), intentGroups: groups.size, samples: iterations,
    percentile95: [round(samples[Math.floor(iterations * 0.025)]), round(samples[Math.floor(iterations * 0.975)])] }
}

function compare(directory, judgmentFile, k = 10) {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8'))
  if (hash(path.join(directory, 'corpus.jsonl')) !== manifest.corpusSha256 || hash(path.join(directory, 'queries.jsonl')) !== manifest.querySha256) throw Error('Frozen corpus/query hash mismatch')
  const queries = readJsonLines(path.join(directory, 'queries.jsonl')), judgments = readJsonLines(judgmentFile)
  const corpus = new Set(readJsonLines(path.join(directory, 'corpus.jsonl')).map(d => d.id))
  if (judgments.length !== queries.length || new Set(judgments.map(q => q.id)).size !== judgments.length) throw Error('Incomplete/duplicate human query set')
  const originals = new Map(queries.map(q => [q.id, q]))
  for (const q of judgments) {
    const original = originals.get(q.id)
    if (!original || ['query', 'category', 'intent', 'split'].some(f => q[f] !== original[f])) throw Error(`Query metadata changed: ${q.id}`)
    if (q.experimentId !== manifest.experimentId || q.corpusSha256 !== manifest.corpusSha256) throw Error('Judgments belong to another snapshot')
    if (Object.keys(q.relevance).some(id => !corpus.has(id))) throw Error(`Unknown article ID in ${q.id}`)
    if (manifest.candidateScope === 'entire_corpus' && Object.keys(q.relevance).length !== corpus.size) throw Error(`Incomplete whole-corpus labeling: ${q.id}`)
    if (q.review.answerability === 'no_answer' && manifest.candidateScope !== 'entire_corpus') throw Error('No-answer claims require whole-corpus review')
  }
  const results = {}
  for (const variant of manifest.variants) {
    if (!manifest.runSha256?.[variant] || hash(path.join(directory, variant + '.jsonl')) !== manifest.runSha256[variant]) throw Error(`Frozen run hash mismatch: ${variant}`)
    const runs = readJsonLines(path.join(directory, variant + '.jsonl'))
    if (runs.some(r => r.variant !== variant || r.results.some(id => !corpus.has(String(id))))) throw Error('Run variant/document mismatch')
    const evaluated = evaluate(judgments, runs, k)
    results[variant] = { ...evaluated, byCategory: {}, bySplit: {} }
    for (const category of new Set(queries.map(q => q.category))) results[variant].byCategory[category] = aggregate(evaluated.perQuery.filter(q => q.category === category), k)
    for (const split of ['dev', 'test']) results[variant].bySplit[split] = aggregate(evaluated.perQuery.filter(q => q.split === split), k)
  }
  const deltas = {}
  for (const [a, b] of [['a_fulltext', 'b_recall'], ['b_recall', 'c_heat_decay']]) {
    deltas[b + '_minus_' + a] = {}
    for (const split of ['all', 'dev', 'test']) {
      const subset = rows => rows.filter(r => split === 'all' || r.split === split)
      deltas[b + '_minus_' + a][split] = Object.fromEntries(['ndcg', 'reciprocalRank', 'recall'].map(field => [field,
        pairedBootstrap(subset(results[a].perQuery), subset(results[b].perQuery), field)]))
    }
  }
  return { experimentId: manifest.experimentId, judgmentSha256: hash(judgmentFile), k, scope: manifest.candidateScope,
    documentCount: manifest.documentCount, limitation: 'Pilot corpus; intent bootstrap intervals do not establish production traffic improvement',
    results, pairedDeltas: deltas }
}

if (require.main === module) {
  try {
    if (!process.argv[2] || !process.argv[3]) throw Error('Usage: node compare-search-variants.js DATASET_DIR judgments.reviewed.jsonl [K]')
    console.log(JSON.stringify(compare(path.resolve(process.argv[2]), path.resolve(process.argv[3]), Number(process.argv[4] || 10)), null, 2))
  } catch (e) { console.error(e.message); process.exitCode = 1 }
}
module.exports = { compare, pairedBootstrap }
