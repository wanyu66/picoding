'use strict'

const fs = require('node:fs')
const path = require('node:path')
const directory = path.resolve(process.argv[2])
const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'metadata.json'), 'utf8'))
const phases = fs.readdirSync(directory).filter(f => /-c\d+-r\d+\.json$/.test(f))
  .map(f => JSON.parse(fs.readFileSync(path.join(directory, f), 'utf8')))
const groups = new Map()
for (const phase of phases) {
  const key = `${phase.summary.scenario}-${phase.summary.concurrency}`
  if (!groups.has(key)) groups.set(key, [])
  groups.get(key).push(phase)
}
const quantile = (xs, p) => xs[Math.max(0, Math.ceil(xs.length * p) - 1)] ?? null
const summaries = [...groups.values()].map(group => {
  const samples = group.flatMap(p => p.samples)
  const latencies = samples.filter(s => s[1]).map(s => s[0]).sort((a, b) => a - b)
  const seconds = group.reduce((sum, p) => sum + p.summary.elapsedSeconds, 0)
  const qps = group.map(p => p.summary.successQps)
  return {
    scenario: group[0].summary.scenario, concurrency: group[0].summary.concurrency, rounds: group.length,
    requests: samples.length, success: latencies.length, failures: samples.length - latencies.length,
    elapsedSeconds: seconds, successQps: latencies.length / seconds,
    roundQpsMin: Math.min(...qps), roundQpsMax: Math.max(...qps),
    meanMs: latencies.length ? latencies.reduce((a, b) => a + b, 0) / latencies.length : null,
    p50Ms: quantile(latencies, 0.5), p95Ms: quantile(latencies, 0.95), p99Ms: quantile(latencies, 0.99),
    meanBytes: samples.reduce((sum, s) => sum + s[2], 0) / samples.length,
    clientCpuCorePercentMax: Math.max(...group.map(p => p.summary.clientCpuCorePercent)),
    clientEventLoopP99MsMax: Math.max(...group.map(p => p.summary.clientEventLoopP99Ms))
  }
}).sort((a, b) => a.scenario.localeCompare(b.scenario) || a.concurrency - b.concurrency)
fs.writeFileSync(path.join(directory, 'aggregate.json'), JSON.stringify(summaries, null, 2))
const fields = Object.keys(summaries[0])
fs.writeFileSync(path.join(directory, 'aggregate.csv'), fields.join(',') + '\n' + summaries.map(s => fields.map(k => s[k]).join(',')).join('\n') + '\n')
const names = { list: '文章列表', detail: '文章详情', search: '搜索列表（MySQL 回退）' }
const n = value => value == null ? '—' : value.toFixed(2)
const lines = [
  '# PaiCoding 本机 HTTP 压测报告', '',
  `测试开始时间：${metadata.started}；数据目录：\`${path.basename(directory)}\`。`, '',
  '## 测试条件', '',
  `- 目标：\`${metadata.base}\`，匿名请求，客户端和服务端同机。`,
  `- 主机：${metadata.host.cpu}，${metadata.host.logicalCpus} 逻辑处理器，${n(metadata.host.totalMemoryGiB)} GiB 内存，${metadata.host.platform} ${metadata.host.release}。压测客户端 ${metadata.host.node}。`,
  `- 服务端运行时：${metadata.serverRuntime || '未自动检测'}。脚本不重编译或重启应用，不能据此认定源码与运行产物完全一致。`,
  `- 工作区 Git HEAD：\`${metadata.gitHead}\`；存在未提交启动修复，详见 metadata.json。`,
  `- 首页可见公开文章 ${metadata.articles.length} 篇，hasMore=${metadata.listHasMore}；这不是整个数据库的行数统计。详情均匀轮询这些文章。`,
  `- 搜索关键词：${metadata.keywords.join('、')}；均为有结果的第一页查询。搜索状态见 metadata.json、after.json 和逐轮 searchStates。`,
  `- 并发：${metadata.levels.join('/')}；每档每接口 ${metadata.rounds} 轮，每轮预热 ${metadata.warmup}s、计时 ${metadata.seconds}s；相邻轮反转执行顺序。预热不计入结果。`,
  '- 闭环压测：每个并发连接收到完整响应后立即发送下一请求，无思考时间；HTTP/1.1 keep-alive；不重试；10 秒请求截止时间。',
  '- 校验 HTTP 200、业务 status.code=0，并检查非空文章列表、匹配文章 ID 与正文、搜索结果 HTML 中存在文章。HTTP 200 中的业务异常也计入失败。',
  '- QPS = 成功请求总数 / 各轮实际耗时之和（含最后一批请求收尾）；P50/P95/P99 由已完成各轮成功请求原始延迟合并后按 nearest-rank 计算，不是分位数的平均值。失败请求不进入延迟分位数，应结合错误率解释。',
  '- 延迟为客户端从发起请求到完整响应接收并校验完成的耗时，包含客户端调度开销。短时闭环测试不代表开放到达流量下的延迟或生产最大容量。',
  '- 未清空 Redis、未造数、未禁用限流、未修改业务代码。详情访问会增加阅读计数，搜索会产生查询/统计副作用。', '',
  '## 实测结果', '',
  '| 接口 | 并发 | 完成轮数 | 成功数 | 失败数 | 成功 QPS | 单轮 QPS 范围 | 平均 ms | P50 ms | P95 ms | P99 ms |',
  '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|',
  ...summaries.map(s => `| ${names[s.scenario]} | ${s.concurrency} | ${s.rounds} | ${s.success} | ${s.failures} | ${n(s.successQps)} | ${n(s.roundQpsMin)}–${n(s.roundQpsMax)} | ${n(s.meanMs)} | ${n(s.p50Ms)} | ${n(s.p95Ms)} | ${n(s.p99Ms)} |`), '',
  `实测完成 ${phases.length}/${metadata.rounds * metadata.levels.length * 3} 个计时阶段。若未完成，说明测试中止；不得当作完整稳定性测试。`, '',
  '## 能否宣称“性能提升”', '',
  '不能据本次测试给出优化前后提升百分比：本次只有当前运行版本，没有同数据、同环境、同负载的优化前基线。不同并发档的 QPS 差异属于负载变化，不是代码优化收益。此次结果可作为后续优化的基线。', '',
  '不能给出搜索准确率提升：仓库 scripts/search-evaluation 中的文章 ID 和 sample-run 为模板示例，不能冒充真实标注与线上结果；本次也未建立人工相关性标注集。接口成功率与搜索准确率不是同一指标。', '',
  '## 结论的适用范围', '',
  '- 仅适用于本机、当前小数据集、热门第一页及匿名访问。没有验证大数据量、深分页、登录用户、混合流量、持续稳定性或多机部署。',
  '- 搜索当前回退 MySQL，不能将本结果用于宣称 Elasticsearch 优化收益。先恢复 ES 索引覆盖，再在同一数据快照与查询集合下分别测量。',
  '- 同机负载、IDE、其他容器、JIT/GC 和缓存温度会影响结果。每轮原始样本、客户端 CPU/事件循环延迟已保存；如有 server-process-samples.jsonl，可进一步核对部分时段服务端进程状态。',
  '- 源码显示列表逐篇补全标签、统计和作者等信息（ArticleReadServiceImpl.fillArticleRelatedInfo）。这是后续 SQL/Redis 调用分析的候选点，尚未经性能剖析证明为瓶颈。', '',
  '## 可复现命令', '',
  '```powershell',
  'node scripts/load-test/http-benchmark.js',
  `node scripts/load-test/summarize.js docs/performance/results/${path.basename(directory)}`,
  '```', '',
  '可使用 BENCH_URL、BENCH_SECONDS、BENCH_WARMUP_SECONDS、BENCH_ROUNDS、BENCH_CONCURRENCY、BENCH_OUTPUT 调整配置。目标仅允许本机 HTTP。复测请使用新的输出目录。', '',
  '原始证据：metadata.json（环境、数据、预检）、*-c*-r*.json（每请求延迟/结果/返回字节数）、summary.jsonl（逐轮摘要）、aggregate.json/csv（合并指标）、after.json（结束状态）、server-process-samples.jsonl（进程采样）。', ''
]
fs.writeFileSync(path.join(directory, 'report.md'), lines.join('\n'))
console.log(JSON.stringify(summaries, null, 2))
