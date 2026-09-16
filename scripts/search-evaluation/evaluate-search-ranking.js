'use strict'

const fs = require('fs')
const path = require('path')

function readJsonLines(file) {
  return fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map((line, index) => {
    try { return JSON.parse(line) } catch (error) {
      throw new Error(`${file}:${index + 1}: ${error.message}`)
    }
  })
}

function dcg(grades, k) {
  return grades.slice(0, k).reduce((sum, grade, index) =>
    sum + (Math.pow(2, grade) - 1) / Math.log2(index + 2), 0)
}

function evaluate(judgments, runs, k) {
  const runByQuery = new Map(runs.map(item => [item.query, item]))
  let ndcg = 0
  let reciprocalRank = 0
  let zeroResults = 0
  let clickedQueries = 0
  let judgedQueries = 0

  for (const judgment of judgments) {
    const run = runByQuery.get(judgment.query) || { results: [], clicked: [] }
    const results = (run.results || []).map(String)
    const clicked = new Set((run.clicked || []).map(String))
    const relevance = judgment.relevance || {}
    const grades = results.map(id => Number(relevance[id] || 0))
    const ideal = Object.values(relevance).map(Number).sort((a, b) => b - a)
    const idealDcg = dcg(ideal, k)
    ndcg += idealDcg === 0 ? 0 : dcg(grades, k) / idealDcg
    const firstRelevant = grades.findIndex(grade => grade > 0)
    reciprocalRank += firstRelevant < 0 ? 0 : 1 / (firstRelevant + 1)
    if (results.length === 0) zeroResults++
    if (results.some(id => clicked.has(id))) clickedQueries++
    judgedQueries++
  }

  const divisor = Math.max(judgedQueries, 1)
  return {
    queries: judgedQueries,
    [`ndcg@${k}`]: Number((ndcg / divisor).toFixed(6)),
    mrr: Number((reciprocalRank / divisor).toFixed(6)),
    zero_result_rate: Number((zeroResults / divisor).toFixed(6)),
    click_through_rate: Number((clickedQueries / divisor).toFixed(6))
  }
}

if (require.main === module) {
  const baseDir = __dirname
  const judgmentsFile = path.resolve(process.argv[2] || path.join(baseDir, 'judgments.jsonl'))
  const runsFile = path.resolve(process.argv[3] || path.join(baseDir, 'sample-run.jsonl'))
  const k = Math.max(1, Number(process.argv[4] || 10))
  console.log(JSON.stringify(evaluate(readJsonLines(judgmentsFile), readJsonLines(runsFile), k), null, 2))
}

module.exports = { dcg, evaluate }
