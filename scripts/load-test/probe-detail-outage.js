'use strict'
const fs = require('node:fs')

async function main() {
  const results = await Promise.all([102, 101, 100].map(async id => {
    const started = performance.now()
    try {
      const response = await fetch(`http://127.0.0.1:8080/article/api/data/detail/${id}`, { signal: AbortSignal.timeout(15000) })
      const body = await response.json()
      return { id, http: response.status, status: body.status, articleId: body.result?.article?.articleId,
        traceId: response.headers.get('x-trace-id'), io: response.headers.get('x-request-io'), elapsedMs: performance.now() - started }
    } catch (error) { return { id, error: error.message, elapsedMs: performance.now() - started } }
  }))
  fs.writeFileSync(process.argv[2], JSON.stringify({ at: new Date().toISOString(), results }, null, 2))
  console.log(JSON.stringify(results))
  if (results.some(r => r.http !== 200 || r.status?.code !== 0 || String(r.id) !== String(r.articleId))) process.exitCode = 1
}
main().catch(error => { console.error(error); process.exitCode = 1 })
