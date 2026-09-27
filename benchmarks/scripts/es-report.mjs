import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {statistics} from './es-benchmark.mjs';
const require=createRequire(import.meta.url);
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const lines=p=>fs.readFileSync(p,'utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const write=(p,x)=>fs.writeFileSync(p,typeof x==='string'?x:JSON.stringify(x,null,2)+'\n');
const fmt=x=>typeof x==='number'?x.toFixed(2):'N/A';
const table=(headers,rows)=>'|'+headers.join('|')+'|\n|'+headers.map(()=> '---').join('|')+'|\n'+rows.map(r=>'|'+r.join('|')+'|').join('\n')+'\n';
if(process.argv[2]==='relevance') {
  const rd=path.resolve(process.argv[3]),human=lines(process.argv[4]);
  const {evaluate}=require('../../scripts/search-evaluation/evaluate-search-ranking.js');
  const result={humanSource:path.basename(process.argv[4]),A:evaluate(human,lines(path.join(rd,'A.jsonl')),10),B:evaluate(human,lines(path.join(rd,'B.jsonl')),10)};
  fs.writeFileSync(path.join(rd,'relevance.metrics.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
  console.log('Validated human judgments; metrics saved.');
}else {
  const dir=path.resolve(process.argv[2]),out=path.dirname(dir),m=read(path.join(dir,'manifest.json')),c=read(path.join(dir,'config.json'));
  const allRows=[],cells=[],resources=[],completed=[],cursor=[],rawParts=[];
  let header;
  for(const n of c.sizes) {
    const candidates=fs.readdirSync(dir).filter(x=>new RegExp('^performance-'+n+'(-retry[1-9][0-9]*)?$').test(x)&&fs.existsSync(path.join(dir,x,'summary.json')));
    if(candidates.length>1)throw Error('Multiple completed attempts; choose an explicit dataset run before aggregating');
    if(!candidates.length)continue;
    const original=path.join(dir,candidates[0]),ranking=path.join(dir,'ranking-'+n);
    const ranked=fs.existsSync(path.join(ranking,'summary.json'));
    const isBalanced=read(path.join(original,'run-metadata.json')).balancedCompleteQueryCycles===true;
    if(ranked||isBalanced)completed.push(n);
    cursor.push({dataset:n,...read(path.join(original,'cursor-preparation.json'))});
    for(const pd of ranked?[original,ranking]:[original]) {
    const raw=fs.readFileSync(path.join(pd,'raw-results.csv'),'utf8').trim().split(/\r?\n/);
    header=raw.shift();
    // Writer uses JSON-encoded scalar cells (all labels/errors contain no quotes or linebreaks).
    const columns=header.split(',');
    const selectedRaw=raw.map(line=>({line,row:Object.fromEntries(JSON.parse('['+line+']').map((v,i)=>[columns[i],v]))})).filter(x=>!(ranked&&pd===original&&x.row.mode==='mixed'));
    rawParts.push(...selectedRaw.map(x=>x.line));
    const rows=selectedRaw.map(x=>x.row).filter(x=>x.phase==='measurement');
    allRows.push(...rows);
    const summaries=read(path.join(pd,'summary.json')).filter(x=>x.phase==='measurement'&&!(ranked&&pd===original&&x.mode==='mixed'));
    for(const id of [...new Set(summaries.map(x=>x.cell))]) {
      const selected=summaries.filter(x=>x.cell===id),matching=rows.filter(x=>x.cell===id);
      if(selected.length!==c.repetitions)throw Error('Incomplete repetitions');
      const first=selected[0];
      cells.push({dataset:n,cell:id,variant:first.variant,mode:first.mode,concurrency:first.concurrency,size:first.size,offset:first.offset,
        repetitions:selected.length,...statistics(matching,selected.reduce((a,b)=>a+b.seconds,0)),
        qpsByRepetition:selected.map(x=>x.qps),p95ByRepetition:selected.map(x=>x.p95Ms)});
    }
    const selectedCells=new Set(summaries.map(x=>x.cell));
    const snapshots=lines(path.join(pd,'node-stats.jsonl')).filter(x=>x.stats&&selectedCells.has(x.cell));
    for(const nodeId of Object.keys(snapshots[0].stats.nodes)) {
      const nodeSamples=snapshots.map(x=>x.stats.nodes[nodeId]).filter(Boolean);
      let cpuMs=0,wallMs=0;const gc={};
      for(const rep of Array.from({length:c.repetitions},(_,i)=>i+1))for(const cell of [...new Set(snapshots.map(x=>x.cell))]) {
        const before=snapshots.find(x=>x.cell===cell&&x.repetition===rep&&x.label==='before')?.stats.nodes[nodeId];
        const after=snapshots.find(x=>x.cell===cell&&x.repetition===rep&&x.label==='after')?.stats.nodes[nodeId];
        if(!before||!after)continue;
        cpuMs+=after.process.cpu.total_in_millis-before.process.cpu.total_in_millis;wallMs+=after.process.timestamp-before.process.timestamp;
        for(const [name,v]of Object.entries(after.jvm.gc.collectors)) {
          gc[name]??={count:0,timeMs:0};gc[name].count+=v.collection_count-before.jvm.gc.collectors[name].collection_count;
          gc[name].timeMs+=v.collection_time_in_millis-before.jvm.gc.collectors[name].collection_time_in_millis;
        }
      }
      resources.push({dataset:n,experiment:path.basename(pd),nodeId,samples:nodeSamples.length,scope:'entire ES node; selected cells warmup + measurement + resource polling; shared container',
        cpuCoreEquivalentPercent:cpuMs/wallMs*100,cpuMs,wallMs,
        sampledProcessCpuPercentMax:Math.max(...nodeSamples.map(n=>n.process.cpu.percent)),
        heapMaxBytes:nodeSamples[0].jvm.mem.heap_max_in_bytes,
        sampledHeapPeakBytes:Math.max(...nodeSamples.map(n=>n.jvm.mem.heap_used_in_bytes)),
        sampledCgroupMemoryPeakBytes:Math.max(...nodeSamples.map(n=>Number(n.os.cgroup?.memory?.usage_in_bytes)||0)),
        residentBytes:null,gc});
    }
    }
  }
  if(header)write(path.join(out,'raw-results.csv'),header+'\n'+rawParts.join('\n')+'\n');
  write(path.join(out,'cell-summary.json'),cells);write(path.join(out,'resource-summary.json'),resources);
  const totalSeconds=cells.reduce((a,x)=>a+x.seconds,0);
  const status=completed.length===c.sizes.length?'COMPLETE_PERFORMANCE_PILOT':'PARTIAL';
  write(path.join(out,'summary.json'),{status,run:m.runId,completedSizes:completed,requestedSizes:c.sizes,measurementSamples:allRows.length,measurementSeconds:totalSeconds,errors:allRows.filter(x=>!x.ok).length,config:c,cells,resources});
  const mixed=cells.filter(x=>x.mode==='mixed'&&x.concurrency===4).sort((a,b)=>a.dataset-b.dataset||a.size-b.size||a.variant.localeCompare(b.variant));
  const paging=cells.filter(x=>x.mode!=='mixed'&&[0,9990].includes(x.offset));
  let report=`# ES 检索测试汇总\n\n状态：${status}。原始运行目录：${path.basename(dir)}，runId=${m.runId}。\n\n`;
  report+='**这是 ES 引擎组件的本机短时实验，当前应用仍为 MYSQL_TITLE_FALLBACK，不能作为业务 HTTP QPS。综合策略的相关性收益尚待人工评测，不能把召回条数当作收益。**\n\n';
  const nodeInfo=Object.values(read(path.join(dir,'nodes-info.json')).nodes)[0];
  report+=`ES JVM=${nodeInfo.jvm.version}，heap上限=${fmt(nodeInfo.jvm.mem.heap_max_in_bytes/2**20)}MiB，插件=${nodeInfo.plugins.map(x=>x.name).join('/')}。测试Java harness使用Java8，与ES进程JVM不同。\n\n`;
  report+=`环境：${m.environment.cpu}，${m.environment.logicalCpus}逻辑CPU，主机内存${fmt(m.environment.memoryBytes/2**30)}GiB，${m.environment.platform} ${m.environment.release}，Node ${m.environment.node}。ES ${read(path.join(dir,'es-info.json')).version.number}，1 shard/0 replica，本地 Docker 共享节点。ES JVM/插件原始参数见 nodes-info.json；资源表的内存为 ES heap/cgroup，不是应用 JVM 或 RSS。\n\n`;
  report+=`数据规模：${completed.join('/')}。seed=${c.seed}，固定时间原点=${c.referenceTime}，独立 ES 测试索引，绕过 MySQL/Outbox造数。各规模内部 A/B 共用同一索引；50k/100k 不代表对应 MySQL 数量。\n\n`;
  report+=`每单元${c.repetitions}轮，预热${c.warmupSeconds}s/轮、计划测量${c.durationSeconds}s/轮，并发${c.concurrency.join('/')}，混合7查询、返回${c.resultSizes.join('/')}条；分页并发4、size10、offset=${c.paginationDepths.join('/')}。有效测量样本共${allRows.length}，实际测量墙钟累计${fmt(totalSeconds)}s（含请求排空），错误${allRows.filter(x=>!x.ok).length}。完整108/规模预热+测量记录见各目录 cells.jsonl。\n\n`;
  report+='基线 A=等权 best_fields/AND，B=当前 Java 导出的召回+quality/阅读/点赞/时间综合排序；两者保留相同高亮/source/sort/filter，共享 IK/同义词。B仅将now固定为语料参考日期。正式混合单元在时间窗口结束后完成整轮7查询，每条查询次数严格相等，精确次数和单查询耗时另见per-query.jsonl。客户端超时30s，不是生产1500ms。\n\n';
  report+='1万/5万初轮混合负载按时间直接截断，慢单元可能遗漏靠后的关键词：原始记录保留，但正式排序对照由 ranking-* 的完整循环补跑替换；原分页单元继续使用。raw-results.csv只合并正式选定的单元，不混合两次尝试。资源记录中分别标明实验目录。\n\n';
  report+=table(['篇数','策略','并发','返回数','样本','实际s','QPS','P50ms','P95ms','P99ms','平均ms','ES took均值ms','错误/错误率'],mixed.map(x=>[x.dataset,x.variant,x.concurrency,x.size,x.samples,fmt(x.seconds),fmt(x.qps),fmt(x.p50Ms),fmt(x.p95Ms),fmt(x.p99Ms),fmt(x.meanMs),fmt(x.meanEsTookMs),x.errors+'/'+fmt(x.errorRate*100)+'%']));
  report+='\n完整并发1/4和所有分页单元见 cell-summary.json；raw-results.csv 同时保留 warmup/measurement，统计须过滤 phase=measurement。P99样本量小于100时接近最大值，禁止用此短测宣布稳定SLA。\n\n';
  report+=table(['篇数','分页','offset','样本','QPS','P95ms','平均ms'],paging.map(x=>[x.dataset,x.mode,x.offset,x.samples,fmt(x.qps),fmt(x.p95Ms),fmt(x.meanMs)]));
  report+='\nsearch_after 数字为已定位游标后的请求；并非任意跳页耗时。起点由顺序扫描建立，不使用 from 伪造游标；所有测量深度的返回ID相等性记录在 pagination-equivalence.jsonl。应用实际最多50页，from深分页仅是ES API反事实基线。\n\n';
  report+=table(['篇数','游标准备s','说明'],cursor.map(x=>[x.dataset,fmt(x.seconds),'无高亮/source，顺序最多500条/批，成本未并入定位后请求']));
  report+='\n资源包含每单元预热和测量，CPU核心等效%=进程CPU时间/墙钟时间×100，可超过100；同机其它索引活动无法剥离。\n\n';
  report+=table(['实验','CPU核心等效%','heap峰值MiB','heap上限MiB','cgroup峰值MiB','GC计数/耗时ms'],resources.map(x=>[x.experiment,fmt(x.cpuCoreEquivalentPercent),fmt(x.sampledHeapPeakBytes/2**20),fmt(x.heapMaxBytes/2**20),fmt(x.sampledCgroupMemoryPeakBytes/2**20),Object.entries(x.gc).map(([k,v])=>`${k}:${v.count}/${v.timeMs}`).join('; ')]));
  report+='\n结论必须同时考虑成本与相关性：这里没有测得可直接宣称的“综合策略性能提升”；不同策略召回集合不同，B更慢不等于功能无价值。未完成人工评分前不能写相关性提升百分比。对照波动见各轮QPS/P95，不将最好一轮作为总结果。\n\n';
  const resume=cells.find(x=>x.dataset===100000&&x.mode==='mixed'&&x.variant==='B'&&x.concurrency===4&&x.size===10);
  report+='可用于简历的事实表述（需保留场景限定）：\n\n';
  report+=resume?`> 基于实际 Java DSL 搭建 ES 可复现测试，覆盖1万/5万/10万篇合成文章；在本机单分片、4并发、返回10条的7查询混合短测中，10万篇综合策略完成${resume.samples}次请求，QPS ${fmt(resume.qps)}、P95 ${fmt(resume.p95Ms)}ms、错误${resume.errors}，保留原始请求与JVM/GC记录。\n\n`:'> 已建立实际DSL和独立索引测试工具；规模未完整执行，不填写最终性能数字。\n\n';
  report+='禁止改写成线上容量、接口P95、稳定性SLA、相关性提升或search_after固定倍数优化。下一步：修复超时标志/分片失败处理需另开业务修改阶段；人工评分、独占环境长测、真实MySQL/Redis/HTTP故障演练。\n';
  const cleanup=path.join(dir,'cleanup-guard.json');
  if(fs.existsSync(cleanup)){const guard=read(cleanup);report+=`\n清理记录：${guard.status}，本轮独立测试索引按manifest精确删除；原业务alias一致=${guard.businessAliasUnchanged}、业务文档数一致=${guard.businessCountUnchanged}。原始证据：${path.basename(dir)}/cleanup-guard.json 与 cleanup.jsonl。上一阶段MySQL及业务alias中的1万篇数据未清理。\n`;}
  write(path.join(out,'benchmark-summary.md'),report);
  let relevance='# 相关性报告\n\n7条合成查询，覆盖精确标题/正文/拼音/错词/热门意图/新发布意图/无结果；A/B各Top20池化候选。查询不是人工流量日志，相关性评分必须由人完成。\n\n';
  for(const n of c.sizes) {
    const rd=path.join(dir,'relevance-'+n);if(!fs.existsSync(rd)){relevance+=`${n}篇：候选未采集，N/A。\n\n`;continue;}
    const manifest=read(path.join(rd,'manifest.json')),metrics=path.join(rd,'relevance.metrics.json');
    relevance+=`${n}篇：${manifest.queryCount}条查询、${manifest.candidatePairs}个候选对。`;
    relevance+=fs.existsSync(metrics)?`人工评测结果见 [relevance.metrics.json](${path.basename(dir)}/relevance-${n}/relevance.metrics.json)。\n\n`:`**人工标签待完成，MRR@10/NDCG@10/judged Recall@10 = N/A，收益 = N/A。**\n\n`;
    relevance+=`[人工评分页面](${path.basename(dir)}/relevance-${n}/review.html)，原始 A.jsonl/B.jsonl、requests.jsonl、candidates.jsonl、judgments.pending.jsonl 均保留。\n\n`;
  }
  relevance+='用户已选择本轮指标标 N/A、保留标注入口；不代填人工评分。0..3评分，>=2相关；仅对已判定可回答查询算MRR/NDCG。池化候选没有穷举语料，不输出全量Recall；无结果不能自动等于不相关。固定原点用于“新发布”意图。拼音/错词语料包含字面词，普通命中数不能代替召回能力验证。\n\n';
  const probes=path.join(dir,'recall-probes-10000.json');
  if(fs.existsSync(probes)) relevance+=table(['功能探针','原文含查询字面值','基础检索命中','隔离真实分支命中','结果'],read(probes).map(x=>[x.branch,x.containsLiteral,x.baselineMatched,x.isolatedBranchMatched,x.pass?'PASS':'FAIL']))+'\n功能探针用真实DSL中的pinyin/fuzzy分支在指定不含字面查询词的文章上 _explain；仅证明功能路径，不是人工相关性分数。原始 source/DSL/explanation 见 recall-probes-10000.json。\n\n';
  relevance+='通过 es-report.mjs relevance <候选目录> <真实人工评分文件> 计算，复用现有严格人审校验，不自动填标签。扩大真实查询集、盲审与多标注者一致性后再谈普遍收益。\n';
  write(path.join(out,'relevance-report.md'),relevance);
  const faultFile=process.argv[3]?path.resolve(process.argv[3]):path.join(out,'fault-harness-02.json');
  let fallback='# 降级与故障验证\n\n真实 SearchService/ReadService，Java8，单线程每故障3次搜索+1次读服务；本机随机端口 HTTP 故障桩。索引覆盖标志固定为已完成以触发实际网络查询；Redis模板缓存未命中、DAO空列表替身，syncOnlineKeyword禁用以避免副作用。**真实MySQL回源数据正确性/吞吐、业务HTTP故障下成功率与缓存熔断恢复 = N/A。**\n\n';
  if(fs.existsSync(faultFile)) {
    const tests=read(faultFile);
    fallback+=table(['用例','断言','注入确认','搜索耗时ms（3次）','DAO调用','实际底层原因'],tests.map(x=>[x.case,x.pass?'PASS':'FAIL',x.faultExercised?'是':'否',x.durationMs.map(fmt).join('/'),x.mysqlDaoCalls,x.runtimeStatus.lastFailure||'无异常']));
    fallback+=`\n${tests.filter(x=>x.pass).length}/${tests.length}断言通过。原始 JSON：${path.basename(faultFile)}；对应日志保留。\n\n`;
  }
  fallback+='客户端 socket1500ms 超时触发DAO降级；实测总延迟含客户端调度，不能直接声称接口1500ms完成。关闭监听端口在本机返回连接超时而非connection refused，严格注入类型断言FAIL，但DAO实际被调用；不伪造Connection refused通过。HTTP200 timed_out=true/failed shards 未被服务检查，DAO调用0，为确认的失败。缺游标/页码>50返回空且无DAO调用，为当前行为核对通过，不等价于高可用通过。\n\n早期 fault-harness-01 的 Redis未初始化导致NPE，stubRequests=0，属于无效故障注入，所有其表面PASS作废；修正测试设施后02才实际发起网络故障。首次 fault-harness.log 为 PowerShell未引用-D参数的启动失败，亦保留。未修改业务断言掩盖 timed_out/failed_shard 失败。\n';
  write(path.join(out,'fallback-report.md'),fallback);
  const aliasFile=path.join(dir,'alias/result.json');
  let alias='# 重建与 Alias 切换\n\n独立 bench_es_* alias；原有业务索引/alias不变。SearchRebuildHarness调用当前源码 executeFullRebuild → bulkIndexWithRetry → activateRebuiltIndex → switchAlias，真实ES读写；DAO提供固定10000篇内存语料、Redis模板替身，bulk=200、maxRetries=3、1shard/0replica。并发查询4，无预热（此处统计故障窗口全部请求）；每请求后暂停10ms。\n\n';
  if(fs.existsSync(aliasFile)) {
    const a=read(aliasFile);
    const during=lines(path.join(dir,'alias/queries.jsonl')).filter(x=>Date.parse(x.at)>=a.load.task.startTime&&Date.parse(x.at)<=a.load.task.finishTime);
    const taskWindow={selection:'requests completed between task.startTime and task.finishTime inclusive',startTime:a.load.task.startTime,finishTime:a.load.task.finishTime,...statistics(during,(a.load.task.finishTime-a.load.task.startTime)/1000)};
    write(path.join(out,'alias-task-window.json'),taskWindow);
    alias+=`严格重建任务窗口为${fmt(taskWindow.seconds)}s，按响应完成时间筛选，共${taskWindow.samples}次请求、${taskWindow.errors}次错误，成功率${fmt(taskWindow.successes/taskWindow.samples*100)}%。完整计算见 alias-task-window.json。下面的全观察窗口另包含Java进程启动和切换后观察，不能混称全部都发生在重建任务期间。\n\n`;
    alias+=table(['观测','实测'],[['总请求',a.samples],['成功/错误',`${a.successes}/${a.errors}`],['查询成功率',fmt(a.successes/a.samples*100)+'%'],['窗口秒',fmt(a.seconds)],['查询P95ms',fmt(a.p95Ms)],['旧/新索引返回次数',`${a.oldHits}/${a.newHits}`],['旧/新文档数',`${a.countBefore}/${a.countAfter}`],['生产重建任务',a.load.task.status],['重建耗时ms',fmt(a.load.elapsedMs)],['任务成功文档',a.load.task.succeeded]]);
    alias+=`\n生产任务细节/失败批次见 ${path.basename(dir)}/alias/harness.json；原始查询见 queries.jsonl，fixture-manifest.json含输入哈希，aliasAfter为实际服务端返回。\n\n`;
  }else alias+='本次任务结果未完成，成功率/耗时/切换结果 N/A。失败日志若存在保留。\n\n';
  alias+='这是实际Java重建方法+ES的组件集成，未验证真实MySQL扫描、Outbox双写并发更新、HTTP渲染/Redis跨进程失效或失败批重试恢复。不停止共享ES，不在现有业务alias上执行重建。清理命令只删除manifest精确命名且meta所有权匹配的测试索引。\n';
  write(path.join(out,'alias-rebuild-report.md'),alias);
  console.log(JSON.stringify({status,completedSizes:completed,samples:allRows.length,measurementSeconds:totalSeconds}));
}
