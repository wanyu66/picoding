'use strict'

const fs = require('node:fs')
const path = require('node:path')

function readJsonLines(file) {
  return fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim()).map((line, index) => {
    try { return JSON.parse(line) } catch (error) {
      throw new Error(`${file}:${index + 1}: ${error.message}`)
    }
  })
}

function dcg(grades, k) {
  return grades.slice(0, k).reduce((sum, grade, index) =>
    sum + (Math.pow(2, grade) - 1) / Math.log2(index + 2), 0)
}

const mean = values => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null
const round = n => n === null ? null : Number(n.toFixed(6))

function validate(judgments, runs, k) {
  if (!Number.isInteger(k) || k < 1) throw Error('K must be a positive integer')
  if (!judgments.length) throw Error('Empty judgment set')
  for (const [name, rows] of [['judgments', judgments], ['runs', runs]]) {
    if (new Set(rows.map(r => r.query)).size !== rows.length) throw Error(`Duplicate query in ${name}`)
  }
  const expected = new Set(judgments.map(q => q.query))
  if (runs.length !== expected.size || runs.some(r => !expected.has(r.query))) throw Error('Run query coverage differs from judgments; missing runs are not zero-result queries')
  const byQuery = new Map(runs.map(r => [r.query, r]))
  for (const q of judgments) {
    const review = q.review || {}
    if (review.status !== 'human_reviewed' || review.source !== 'human' || !review.reviewer?.trim() || !Number.isFinite(Date.parse(review.reviewedAt))) throw Error(`Human review pending or invalid: ${q.id || q.query}`)
    if (!review.corpusReviewed || !['answerable', 'no_answer'].includes(review.answerability)) throw Error(`Answerability review missing: ${q.query}`)
    const grades = Object.values(q.relevance || {})
    if (grades.some(g => !Number.isInteger(g) || g < 0 || g > 3)) throw Error(`Unjudged/invalid grade: ${q.query}`)
    if ((review.answerability === 'answerable') !== grades.some(g => g >= 2)) throw Error(`Answerability contradicts grades: ${q.query}`)
    const run = byQuery.get(q.query)
    if (run.status !== 'ok') throw Error(`Run failed: ${q.query}`)
    if (!Array.isArray(run.results)) throw Error(`Missing results array: ${q.query}`)
    if (new Set(run.results.map(String)).size !== run.results.length) throw Error(`Duplicate result IDs: ${q.query}`)
    if (!q.experimentId || !q.corpusSha256 || q.experimentId !== run.experimentId || q.corpusSha256 !== run.corpusSha256) throw Error(`Snapshot mismatch: ${q.query}`)
    for (const id of run.results) if (!Object.hasOwn(q.relevance, String(id))) throw Error(`Unjudged result ${id}: ${q.query}`)
  }
}

function aggregate(rows, k) {
  const answered = rows.filter(q => q.answerable), unanswered = rows.filter(q => !q.answerable)
  return { queries: rows.length, answerableQueries: answered.length, noAnswerQueries: unanswered.length,
    [`ndcg@${k}`]: round(mean(answered.map(q => q.ndcg))),
    [`mrr@${k}`]: round(mean(answered.map(q => q.reciprocalRank))),
    [`judged_recall@${k}`]: round(mean(answered.map(q => q.recall))),
    zero_result_rate: round(mean(rows.map(q => Number(q.empty)))),
    answerable_zero_result_rate: round(mean(answered.map(q => Number(q.empty)))),
    no_answer_false_positive_rate: round(mean(unanswered.map(q => Number(q.falsePositive)))),
    click_through_rate: null, clickNote: 'No measured impressions/clicks in offline runs; CTR unavailable', relevanceThreshold: 2 }
}

function evaluate(judgments, runs, k = 10) {
  validate(judgments, runs, k)
  const runByQuery = new Map(runs.map(r => [r.query, r]))
  const perQuery = judgments.map(q => {
    const run = runByQuery.get(q.query), results = run.results.map(String).slice(0, k)
    const grades = results.map(id => q.relevance[id]), answerable = q.review.answerability === 'answerable'
    const ideal = Object.values(q.relevance).sort((a, b) => b - a)
    const first = grades.findIndex(g => g >= 2), relevant = ideal.filter(g => g >= 2).length
    return { id: q.id, query: q.query, category: q.category, intent: q.intent, split: q.split, answerable,
      ndcg: answerable ? dcg(grades, k) / dcg(ideal, k) : null,
      reciprocalRank: answerable ? (first < 0 ? 0 : 1 / (first + 1)) : null,
      recall: answerable ? grades.filter(g => g >= 2).length / relevant : null,
      empty: run.results.length === 0, falsePositive: !answerable && run.results.length > 0 }
  })
  return { ...aggregate(perQuery, k), perQuery }
}

if (require.main === module) {
  try {
    const judgments = path.resolve(process.argv[2] || path.join(__dirname, 'judgments.jsonl'))
    if (!process.argv[3]) throw Error('Usage: node evaluate-search-ranking.js judgments.reviewed.jsonl actual-run.jsonl [K]')
    console.log(JSON.stringify(evaluate(readJsonLines(judgments), readJsonLines(process.argv[3]), Number(process.argv[4] || 10)), null, 2))
  } catch (e) { console.error(e.message); process.exitCode = 1 }
}

module.exports = { readJsonLines, dcg, evaluate, aggregate, validate, mean, round }
