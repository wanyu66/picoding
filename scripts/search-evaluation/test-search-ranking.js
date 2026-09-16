'use strict'

const assert = require('assert')
const { evaluate } = require('./evaluate-search-ranking')

const judgments = [
  { query: 'perfect', relevance: { 1: 3, 2: 1 } },
  { query: 'empty', relevance: { 3: 3 } }
]
const runs = [
  { query: 'perfect', results: [1, 2], clicked: [1] },
  { query: 'empty', results: [], clicked: [] }
]
const metrics = evaluate(judgments, runs, 10)

assert.strictEqual(metrics.queries, 2)
assert.strictEqual(metrics['ndcg@10'], 0.5)
assert.strictEqual(metrics.mrr, 0.5)
assert.strictEqual(metrics.zero_result_rate, 0.5)
assert.strictEqual(metrics.click_through_rate, 0.5)
console.log('search ranking evaluator tests passed')
