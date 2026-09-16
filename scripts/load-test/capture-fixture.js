'use strict'
const fs = require('node:fs')
const crypto = require('node:crypto')
const path = require('node:path')

async function get(route) {
  const response = await fetch('http://127.0.0.1:8080' + route, { signal: AbortSignal.timeout(10000) })
  const body = await response.json()
  if (!response.ok || body.status?.code !== 0) throw new Error(`Failed ${route}: ${JSON.stringify(body.status)}`)
  return { traceId: response.headers.get('x-trace-id'), result: body.result }
}

async function main() {
  const destination = process.argv[2] || 'docs/performance/fixed-search-fixture.json'
  if (fs.existsSync(destination)) throw new Error('Refusing to overwrite an existing fixed fixture; choose a new filename')
  const list = (await get('/article/api/list/data/category/0?page=1&size=10')).result
  const articles = []
  for (const article of list.list) {
    const detail = (await get(`/article/api/data/detail/${article.articleId}`)).result.article
    const fixed = { id: detail.articleId, title: detail.title, summary: detail.summary, content: detail.content, updated: detail.lastUpdateTime }
    articles.push({ id: detail.articleId, title: detail.title, updated: detail.lastUpdateTime,
      sha256: crypto.createHash('sha256').update(JSON.stringify(fixed)).digest('hex') })
  }
  const keywords = ['Java', '技术派', '分布式']
  const queries = []
  for (const keyword of keywords) {
    const data = await get(`/search/api/list?key=${encodeURIComponent(keyword)}&page=1&size=10`)
    queries.push({ keyword, traceId: data.traceId, state: data.result.searchState,
      results: [...data.result.html.matchAll(/data-article-id="(\d+)"/g)].map(m => m[1]) })
  }
  const status = (await get('/search/api/status')).result
  const fixture = { capturedAt: new Date().toISOString(), articles, listHasMore: list.hasMore, keywords, queries, searchStatus: status,
    note: 'Document fingerprints exclude read counts. Observed result IDs are a reproducibility snapshot, not human relevance judgments.' }
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.writeFileSync(destination, JSON.stringify(fixture, null, 2))
  console.log(JSON.stringify({ destination, articles: articles.length, queries: queries.length, state: status.state }))
}
main().catch(error => { console.error(error); process.exitCode = 1 })
