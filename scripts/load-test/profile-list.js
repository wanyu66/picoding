'use strict'
const fs = require('node:fs')

function summarize(io) {
  const total = name => {
    const match = io.match(new RegExp(`${name}=\\{([^}]*)\\}`))
    if (!match) throw new Error(`Missing ${name} diagnostics`)
    return [...match[1].matchAll(/=(\d+)/g)].reduce((sum, item) => sum + Number(item[1]), 0)
  }
  const acquisitions = io.match(/redisAcquisitions=(\d+)/)
  if (!acquisitions) throw new Error('Missing connection acquisition diagnostics')
  return { sql: total('sql'), redisCommands: total('redisCommands'), redisAcquisitions: Number(acquisitions[1]) }
}

async function main() {
  const observations = []
  const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:8080'
  // Warm the category cache before collecting the first sample.
  const warmup = await fetch(`${baseUrl}/article/api/list/data/category/0?page=1&size=8`, {
    signal: AbortSignal.timeout(10000)
  })
  const warmBody = await warmup.json()
  if (!warmup.ok || warmBody.status?.code !== 0) throw new Error('Warmup failed')
  for (const size of [1, 4, 8]) {
    for (let round = 1; round <= 3; round++) {
      const response = await fetch(`${baseUrl}/article/api/list/data/category/0?page=1&size=${size}`, {
        signal: AbortSignal.timeout(10000)
      })
      const body = await response.json()
      const io = response.headers.get('x-request-io')
      if (!response.ok || body.status?.code !== 0 || !io) throw new Error('Request failed or request IO diagnostics are not active')
      observations.push({ size, round, articles: body.result.list.length,
        distinctAuthors: new Set(body.result.list.map(article => article.author)).size,
        articleIds: body.result.list.map(article => article.articleId),
        hasMore: body.result.hasMore, totals: summarize(io),
        traceId: response.headers.get('x-trace-id'), io })
    }
  }
  const destination = process.argv[2] || 'docs/performance/list-io-profile.json'
  fs.writeFileSync(destination, JSON.stringify({ capturedAt: new Date().toISOString(),
    scope: 'Request-thread MyBatis executed statements plus instrumented outbox insert; Redis template commands, excluding asynchronous/background tasks and Lua subcommands.', observations }, null, 2))
  console.log(JSON.stringify(observations, null, 2))
}
main().catch(error => { console.error(error); process.exitCode = 1 })
