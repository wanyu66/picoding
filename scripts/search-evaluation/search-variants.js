'use strict'

const fields = ['title', 'shortTitle', 'summary', 'content']
const boosts = [8, 5, 2, 1]
const variants = ['a_fulltext', 'b_recall', 'c_heat_decay']

function buildQuery(query, variant, origin, size = 20) {
  if (!variants.includes(variant)) throw new Error(`Unknown variant ${variant}`)
  const filter = [{ term: { deleted: 0 } }, { term: { status: 1 } }]
  let must
  if (variant === 'a_fulltext') {
    // All versions share the existing Chinese/synonym analyzers. A has equal field weights.
    must = [{ multi_match: { query, fields, type: 'best_fields', operator: 'and' } }]
  } else {
    const should = fields.flatMap((field, i) => {
      const boost = boosts[i]
      const wildcard = { value: '*' + query.replace(/[\\*?]/g, '\\$&') + '*', case_insensitive: true }
      if (boost * 0.35 > 1) wildcard.boost = boost * 0.35
      return [
        { match_phrase: { [field]: { query, slop: 0, boost: boost * 2 } } },
        { match_phrase_prefix: { [field]: { query, max_expansions: 50, boost: boost * 1.4 } } },
        { match: { [field]: { query, operator: 'and', fuzziness: 'AUTO', prefix_length: 1, max_expansions: 50, boost } } },
        { match: { [field + '.pinyin']: { query, operator: 'and', boost: boost * 0.8 } } },
        { wildcard: { [field + 'Exact']: wildcard } }
      ]
    })
    must = [{ bool: { should, minimum_should_match: 1 } }]
  }
  let search = { bool: { must, filter } }
  if (variant === 'c_heat_decay') {
    if (!origin || !Number.isFinite(Date.parse(origin))) throw new Error('A fixed time origin is required')
    // Deliberately omit production qualityScore: C isolates heat + recency, as requested.
    search = { function_score: { query: search, functions: [
      { field_value_factor: { field: 'readCount', modifier: 'log1p', factor: 0.08, missing: 0 }, weight: 0.8 },
      { field_value_factor: { field: 'praiseCount', modifier: 'log1p', factor: 0.3, missing: 0 }, weight: 1.2 },
      { gauss: { createTime: { origin, scale: '30d', offset: '7d', decay: 0.5 } }, weight: 1.5 }
    ], score_mode: 'sum', boost_mode: 'sum', max_boost: 20 } }
  }
  // Same tie-breaking for all three; no implicit recency tie-break in the baseline.
  return { size, track_total_hits: true, query: search, _source: ['articleId'],
    sort: [{ _score: 'desc' }, { articleId: 'desc' }] }
}

module.exports = { fields, variants, buildQuery }
