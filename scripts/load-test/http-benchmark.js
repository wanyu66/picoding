'use strict'

// Local, closed-loop HTTP benchmark. No dependency installation required.
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { performance, monitorEventLoopDelay } = require('node:perf_hooks')
const { execFileSync } = require('node:child_process')

const base = new URL(process.env.BENCH_URL || 'http://127.0.0.1:8080')
if (!['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) || base.protocol !== 'http:') {
  throw new Error('This benchmark only permits local HTTP targets.')
}
const seconds = Number(process.env.BENCH_SECONDS || 10)
const warmup = Number(process.env.BENCH_WARMUP_SECONDS || 3)
const rounds = Number(process.env.BENCH_ROUNDS || 3)
const levels = (process.env.BENCH_CONCURRENCY || '1,8,32').split(',').map(Number)
if (![seconds, warmup, rounds, ...levels].every(n => Number.isFinite(n) && n > 0)) throw new Error('Invalid benchmark settings')
const out = path.resolve(process.env.BENCH_OUTPUT || `docs/performance/results/${new Date().toISOString().replace(/[:.]/g, '-')}`)
fs.mkdirSync(out, { recursive: true })
const timeoutMs = 10000
const listPath = '/article/api/list/data/category/0?page=1&size=10'
const keywords = ['Java', '技术派', '分布式']
const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(...levels), maxFreeSockets: Math.max(...levels) })

function request(urlPath, validate = () => true) {
  return new Promise(resolve => {
    const start = performance.now()
    let done = false
    let timer
    const finish = value => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve({ ...value, ms: performance.now() - start })
    }
    const req = http.get(new URL(urlPath, base), { agent, headers: { Accept: 'application/json', 'User-Agent': 'PaiCoding-local-benchmark/1.0' } }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', error => finish({ ok: false, error: error.code || error.message }))
      res.on('end', () => {
        const buffer = Buffer.concat(chunks)
        try {
          const body = JSON.parse(buffer.toString('utf8'))
          const ok = res.statusCode === 200 && body.status?.code === 0 && validate(body.result)
          finish({ ok, http: res.statusCode, code: body.status?.code, bytes: buffer.length, state: body.result?.searchState, body,
            error: ok ? undefined : `HTTP ${res.statusCode}, code ${body.status?.code}, invalid response or business error` })
        } catch (_) { finish({ ok: false, http: res.statusCode, bytes: buffer.length, error: 'Invalid JSON' }) }
      })
    })
    timer = setTimeout(() => req.destroy(new Error('Request deadline exceeded')), timeoutMs)
    req.on('error', error => finish({ ok: false, error: error.code || error.message }))
  })
}

function percentile(sorted, p) {
  return sorted.length ? sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)] : null
}

async function phase(scenario, concurrency, duration, record, round) {
  const lag = monitorEventLoopDelay({ resolution: 20 })
  lag.enable()
  const cpuStart = process.cpuUsage()
  const started = new Date().toISOString()
  const start = performance.now()
  const deadline = start + duration * 1000
  let counter = 0
  const samples = []
  const errors = {}
  const errorExamples = []
  const states = {}
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (performance.now() < deadline) {
      const index = counter++ % scenario.paths.length
      const result = await request(scenario.paths[index], data => scenario.validate(data, index))
      samples.push([result.ms, result.ok ? 1 : 0, result.bytes || 0, index, result.http || 0, result.code ?? null])
      if (!result.ok) errors[result.error] = (errors[result.error] || 0) + 1
      if (!result.ok && errorExamples.length < 3) errorExamples.push({ path: scenario.paths[index], status: result.body?.status, error: result.error })
      if (result.state) states[result.state] = (states[result.state] || 0) + 1
    }
  }))
  const elapsed = (performance.now() - start) / 1000
  const cpu = process.cpuUsage(cpuStart)
  lag.disable()
  const latencies = samples.filter(s => s[1]).map(s => s[0]).sort((a, b) => a - b)
  const success = latencies.length
  const summary = {
    scenario: scenario.name, concurrency, round, started, durationRequestedSeconds: duration, elapsedSeconds: elapsed,
    requests: samples.length, success, failures: samples.length - success, errorRate: (samples.length - success) / samples.length,
    successQps: success / elapsed, totalQps: samples.length / elapsed,
    meanMs: success ? latencies.reduce((a, b) => a + b, 0) / success : null,
    p50Ms: percentile(latencies, 0.5), p95Ms: percentile(latencies, 0.95), p99Ms: percentile(latencies, 0.99),
    maxMs: latencies.at(-1) ?? null, bytes: samples.reduce((a, b) => a + b[2], 0), errors, errorExamples, searchStates: states,
    clientCpuCorePercent: (cpu.user + cpu.system) / (elapsed * 10000), clientEventLoopP99Ms: lag.percentile(99) / 1e6,
    hostFreeMemoryGiB: os.freemem() / 2 ** 30
  }
  if (record) {
    fs.writeFileSync(path.join(out, `${scenario.name}-c${concurrency}-r${round}.json`), JSON.stringify({ summary, paths: scenario.paths,
      sampleColumns: ['elapsedMs', 'success', 'bytes', 'pathIndex', 'httpStatus', 'businessCode'], samples }))
    fs.appendFileSync(path.join(out, 'summary.jsonl'), JSON.stringify(summary) + '\n')
    console.log(JSON.stringify(summary))
  }
  if (summary.errorRate > 0.05) throw new Error(`Stopping: error rate above 5% in ${scenario.name}`)
  return summary
}

async function main() {
  const listing = await request(listPath, data => Array.isArray(data?.list) && data.list.length > 0)
  if (!listing.ok) throw new Error(`List preflight failed: ${listing.error}`)
  const articles = listing.body.result.list.map(a => ({ id: a.articleId, title: a.title }))
  const scenarios = [
    { name: 'list', paths: [listPath], validate: data => Array.isArray(data?.list) && data.list.length === articles.length },
    { name: 'detail', paths: articles.map(a => `/article/api/data/detail/${a.id}`),
      validate: (data, i) => String(data?.article?.articleId) === String(articles[i].id) && typeof data.article.content === 'string' && data.article.content.length > 0 },
    { name: 'search', paths: keywords.map(k => `/search/api/list?key=${encodeURIComponent(k)}&page=1&size=10`),
      validate: data => typeof data?.html === 'string' && data.html.includes('data-article-id=') }
  ]
  const preflight = []
  for (const scenario of scenarios) {
    for (let i = 0; i < scenario.paths.length; i++) {
      const result = await request(scenario.paths[i], data => scenario.validate(data, i))
      if (!result.ok) throw new Error(`Preflight failed: ${scenario.paths[i]}: ${result.error}`)
      preflight.push({ scenario: scenario.name, path: scenario.paths[i], bytes: result.bytes, ms: result.ms, state: result.state })
    }
  }
  const metadata = {
    started: new Date().toISOString(), base: base.href, seconds, warmup, rounds, levels, timeoutMs,
    serverRuntime: process.env.BENCH_SERVER_RUNTIME || null,
    model: 'Closed loop, no think time, HTTP/1.1 keep-alive, no retries, full response + JSON/semantic validation; drain included in elapsed time',
    latency: 'Client-observed elapsed time through full body receipt and validation; successful responses only; nearest-rank percentiles',
    host: { cpu: os.cpus()[0].model, logicalCpus: os.cpus().length, totalMemoryGiB: os.totalmem() / 2 ** 30, platform: os.platform(), release: os.release(), node: process.version },
    gitHead: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
    gitStatus: execFileSync('git', ['status', '--short'], { encoding: 'utf8' }),
    articles, listHasMore: listing.body.result.hasMore, keywords, preflight,
    searchStatusBefore: (await request('/search/api/status')).body
  }
  fs.writeFileSync(path.join(out, 'metadata.json'), JSON.stringify(metadata, null, 2))
  console.log(`OUTPUT ${out}`)
  for (let round = 1; round <= rounds; round++) {
    const orderedLevels = round % 2 ? levels : [...levels].reverse()
    const orderedScenarios = round % 2 ? scenarios : [...scenarios].reverse()
    for (const concurrency of orderedLevels) {
      for (const scenario of orderedScenarios) {
        await phase(scenario, concurrency, warmup, false, round)
        await phase(scenario, concurrency, seconds, true, round)
      }
    }
  }
  fs.writeFileSync(path.join(out, 'after.json'), JSON.stringify({ finished: new Date().toISOString(), searchStatusAfter: (await request('/search/api/status')).body }, null, 2))
}

main().catch(async error => {
  console.error(error)
  fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ stopped: new Date().toISOString(), error: error.message,
    searchStatusAfter: (await request('/search/api/status')).body }, null, 2))
  process.exitCode = 1
}).finally(() => agent.destroy())
