import fs from 'node:fs';
import path from 'node:path';
const dir=path.resolve(process.argv[2]),root=path.dirname(dir);
const read=p=>JSON.parse(fs.readFileSync(path.join(dir,p),'utf8'));
const lines=p=>fs.readFileSync(path.join(dir,p),'utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const write=(p,x)=>fs.writeFileSync(path.join(root,p),typeof x==='string'?x:JSON.stringify(x,null,2)+'\n');
const csv=(columns,rows)=>columns.join(',')+'\n'+rows.map(r=>columns.map(k=>{const v=r[k]??'';return typeof v==='number'?String(v):'"'+String(v).replaceAll('"','""')+'"';}).join(',')).join('\n')+'\n';
const f=n=>Number.isFinite(n)?n.toFixed(2):'N/A';
const table=(h,r)=>'|'+h.join('|')+'|\n|'+h.map(()=> '---').join('|')+'|\n'+r.map(x=>'|'+x.join('|')+'|').join('\n')+'\n';
const status=read('harness-status.json');
if(!status.rawSnapshotsComplete)throw Error('Raw snapshots incomplete: cannot publish success metrics from this attempt');
const manifest=read('manifest.json'),outbox=read('domain_event_outbox.json'),consumes=read('domain_event_consume.json'),sources=read('bench_source.json'),effects=read('bench_effect.json'),attempts=read('bench_attempt.json'),checks=read('checks.json'),meters=read('meters.json');
const dead=lines('dlq-messages.jsonl'),recoveries=lines('recoveries.jsonl').map(r=>({...r,recoveryMs:r.finishMs-r.startMs}));
const before=read('07-before-manual-compensation.json'),after=read('08-final.json');
const distinct=a=>new Set(a).size;
const eventRows=sources.map(s=>{
  const e=effects.filter(x=>x.event_id===s.event_id),a=attempts.filter(x=>x.event_id===s.event_id),o=outbox.find(x=>x.event_id===s.event_id);
  const c=consumes.filter(x=>x.event_id===s.event_id&&x.consumer_name==='bench-reliability');
  const finished=a.filter(x=>x.outcome==='COMMITTED').map(x=>Number(x.finished_ms));
  const deliveryCount=distinct(a.map(x=>x.delivery_key));
  return {eventId:s.event_id,scenario:s.scenario,createdMs:s.created_ms,outboxStatus:o?.status||'MISSING',publishRetries:o?.retry_count??null,
    listenerAttempts:a.length,brokerDeliveries:deliveryCount,consumerRetryInvocations:a.length-deliveryCount,duplicateDeliveries:Math.max(0,deliveryCount-1),
    brokerRedeliveries:distinct(a.filter(x=>x.redelivered).map(x=>x.delivery_key)),deduplicatedInvocations:a.filter(x=>x.outcome==='COMMITTED'&&!x.handler_entered).length,
    businessWrites:e.length,duplicateBusinessWrites:Math.max(0,e.length-1),consumeTokens:c.length,businessSucceeded:e.length>=1&&c.length===1,
    exactlyOnceEffect:e.length===1&&c.length===1,deadLetterArrivals:dead.filter(x=>x.eventId===s.event_id).length,
    endToEndMs:finished.length?Math.min(...finished)-Number(s.created_ms):null};
});
const sum=k=>eventRows.reduce((n,r)=>n+(Number(r[k])||0),0),success=eventRows.filter(x=>x.businessSucceeded).length;
const idem=eventRows.filter(x=>['duplicate','ack_crash'].includes(x.scenario));
const metric=name=>meters.filter(x=>x.name===name&&x.statistic==='COUNT').reduce((n,x)=>n+x.value,0);
const stats=rows=>{const a=rows.map(x=>x.endToEndMs).filter(x=>x!==null).sort((a,b)=>a-b);return {meanMs:a.length?a.reduce((n,x)=>n+x,0)/a.length:null,p50Ms:a.length?a[Math.ceil(a.length*.5)-1]:null,p95Ms:a.length?a[Math.ceil(a.length*.95)-1]:null,maxMs:a.at(-1)??null};};
const scenarios=Object.keys(manifest.eventsPerScenario).map(s=>{const a=eventRows.filter(x=>x.scenario===s);return {scenario:s,attempted:manifest.eventsPerScenario[s],committed:a.length,success:a.filter(x=>x.businessSucceeded).length,
  listenerAttempts:a.reduce((n,x)=>n+x.listenerAttempts,0),brokerDeliveries:a.reduce((n,x)=>n+x.brokerDeliveries,0),duplicateDeliveries:a.reduce((n,x)=>n+x.duplicateDeliveries,0),
  duplicateBusinessWrites:a.reduce((n,x)=>n+x.duplicateBusinessWrites,0),deadLetterArrivals:a.reduce((n,x)=>n+x.deadLetterArrivals,0),...stats(a)};});
const integrity=[
  {name:'business sources and Outbox identifiers agree',pass:distinct(outbox.map(x=>x.event_id))===sources.length&&sources.every(s=>outbox.some(o=>o.event_id===s.event_id))},
  {name:'96 committed events; 8 deliberately rolled back excluded',pass:eventRows.length===96&&sources.every(s=>s.scenario!=='rollback')},
  {name:'raw effects match final checkpoint',pass:effects.length===after.businessWrites&&success===after.successEvents},
  {name:'retry instrumentation agrees with production monitor',pass:sum('consumerRetryInvocations')===metric('paicoding.rabbitmq.domain.retries')},
  {name:'actual DLQ x-death rejection captured',pass:dead.length===8&&dead.every(x=>x.xDeath.includes('rejected')&&x.xDeath.includes('paicoding.domain.notification.queue'))},
  {name:'DLQ is not counted as success before compensation',pass:before.dlqReady===8&&before.successEvents===88&&before.outboxEvents===96},
  {name:'40 events in committed duplicate/crash scenarios',pass:idem.length===40},
  {name:'duplicate and crash scenarios have real repeat deliveries',pass:idem.every(x=>x.brokerDeliveries>=2)},
  {name:'all effects belong to committed source events',pass:effects.every(e=>sources.some(s=>s.event_id===e.event_id))}
];
const summary={run:manifest.run,harnessPass:status.pass,rawIntegrityPass:integrity.every(x=>x.pass),assertionsPassed:checks.filter(x=>x.pass).length,assertionsTotal:checks.length,
  attemptedEventCreation:read('fixtures.json').length,committedEvents:outbox.length,rolledBackEvents:8,finalBusinessSucceeded:success,
  automaticBusinessSucceeded:before.successEvents,automaticSuccessRate:before.successEvents/before.outboxEvents,
  assistedFinalSuccessRate:success/sources.length,unprocessedBeforeDlqReplay:before.outboxEvents-before.successEvents,finalUnprocessed:sources.length-success,
  publishRetryCount:sum('publishRetries'),consumerRetryCount:sum('consumerRetryInvocations'),brokerDeliveries:sum('brokerDeliveries'),duplicateDeliveries:sum('duplicateDeliveries'),brokerRedeliveries:sum('brokerRedeliveries'),deduplicatedInvocations:sum('deduplicatedInvocations'),
  businessWrites:effects.length,duplicateBusinessWrites:sum('duplicateBusinessWrites'),idempotencyEvents:idem.length,idempotencyCorrectEvents:idem.filter(x=>x.exactlyOnceEffect).length,idempotencyCorrectRate:idem.filter(x=>x.exactlyOnceEffect).length/idem.length,
  deadLetterArrivals:dead.length,deadLetterRemaining:after.dlqReady,monitorConsumerRetries:metric('paicoding.rabbitmq.domain.retries'),
  durationMs:status.finishedMs-status.startedMs,endToEnd:stats(eventRows),recoveries,scenarios,integrity};
write('summary.json',summary);write('events.csv',csv(Object.keys(eventRows[0]),eventRows));write('events.json',eventRows);
write('attempts.csv',csv(Object.keys(attempts[0]),attempts));write('recoveries.csv',csv(['scenario','mode','startMs','finishMs','recoveryMs'],recoveries));write('scenarios.csv',csv(Object.keys(scenarios[0]),scenarios));
let md='# Outbox / RabbitMQ 故障测试结果\n\n';
md+=`主结果：${path.basename(dir)}（${manifest.run}）。真实独立MySQL/RabbitMQ、当前源码编译、4消费者/prefetch1，${f(summary.durationMs/1000)}秒故障矩阵，未预热；功能断言${summary.assertionsPassed}/${summary.assertionsTotal}、原始数据核对${integrity.filter(x=>x.pass).length}/${integrity.length}。不是容量测试或线上SLA。\n\n`;
md+='范围：真实Outbox写入、发布确认、状态/重试、Spring事务消费包装、Rabbit监听工厂及DLQ；业务效果为无event_id唯一约束的专用JDBC表。**没有证明文章HTTP接口、通知推送、排行榜Redis、统计Redis、ES投影全部业务成功。**\n\n';
md+=table(['指标','实测'],[['尝试创建/主动回滚/实际提交事件',`${summary.attemptedEventCreation}/${summary.rolledBackEvents}/${summary.committedEvents}`],['自动恢复后业务成功率',`${before.successEvents}/${before.outboxEvents} = ${f(summary.automaticSuccessRate*100)}%`],['显式DLQ补偿后最终业务成功率',`${success}/${sources.length} = ${f(summary.assistedFinalSuccessRate*100)}%`],['幂等正确率（重复+崩溃事件）',`${summary.idempotencyCorrectEvents}/${summary.idempotencyEvents} = ${f(summary.idempotencyCorrectRate*100)}%`],['发布重试/消费重试',`${summary.publishRetryCount}/${summary.consumerRetryCount}`],['Broker投递/额外重复投递',`${summary.brokerDeliveries}/${summary.duplicateDeliveries}`],['Broker redelivered标记投递/幂等跳过调用',`${summary.brokerRedeliveries}/${summary.deduplicatedInvocations}`],['已提交业务效果/重复业务写入',`${summary.businessWrites}/${summary.duplicateBusinessWrites}`],['累计进入DLQ/最终DLQ残留',`${summary.deadLetterArrivals}/${summary.deadLetterRemaining}`],['人工补偿前/后未处理',`${summary.unprocessedBeforeDlqReplay}/${summary.finalUnprocessed}`],['端到端平均/P50/P95/最大(ms)',[summary.endToEnd.meanMs,summary.endToEnd.p50Ms,summary.endToEnd.p95Ms,summary.endToEnd.maxMs].map(f).join('/')]]);
md+=`\n**DLQ不是成功：**补偿前已提交事件${before.outboxEvents}个，业务成功${before.successEvents}个，DLQ待处理${before.dlqReady}个；Outbox各状态保留在07检查点。现有项目没有通用自动DLQ重放；最终${f(summary.assistedFinalSuccessRate*100)}%包含本次测试明确触发的管理员式重放，不能写成“现有系统自动100%恢复”。\n\n`;
md+=table(['故障','恢复方式','实测恢复ms'],recoveries.map(x=>[x.scenario,x.mode,x.recoveryMs]));
md+='\n恢复计时始于恢复操作，包含重连/自然退避到期；显式调用生产publishPending，不包含其定时调度等待。端到端起点为event创建时间，终点为首个成功消费事务返回后的审计时间。重复投递按连接/信道/deliveryTag去重计算，stateless listener重试不是新的Broker投递。\n\n';
md+=table(['场景','尝试/提交','最终成功','Broker投递','重复投递','重复写入','DLQ累计','端到端P95ms'],scenarios.map(x=>[x.scenario,`${x.attempted}/${x.committed}`,x.success,x.brokerDeliveries,x.duplicateDeliveries,x.duplicateBusinessWrites,x.deadLetterArrivals,f(x.p95Ms)]));
md+='\nDLQ一行的“最终成功”发生在第8步显式重放之后；第7步该场景成功数为0。SQL效果插入后失败的24次尝试全部回滚，不属于已提交业务写入。\n\n';
md+='证据：events.csv/attempts.csv/recoveries.csv由数据库逐行快照计算；'+path.basename(dir)+'/ 下包含Outbox/consume/source/effect/attempt全量JSON、8个检查点、真实子进程崩溃日志、Broker x-death、Micrometer读数、镜像/构建哈希和清理日志。所有初轮记录保留，未修改结果以制造成功率。\n\n';
md+='运行命令见 [计划与操作](../../outbox-test-plan.md)。主机='+manifest.environment.cpu+'、逻辑CPU='+manifest.environment.logicalCpus+'、Node='+manifest.environment.node+'；数据库13416和Broker15782均为临时独立容器，固定镜像digest见config/outbox-compose.yml。生产配置/接口/表结构未改。\n\n';
md+='历史尝试说明见 [阶段执行记录](phase-close.md)；主结果以本报告开头标明的目录和原始状态为准。\n\n';
md+='适合简历的限定表述：\n\n';
if(summary.harnessPass&&summary.rawIntegrityPass)md+=`> 构建8类Outbox/RabbitMQ隔离故障测试，覆盖${sources.length}个已提交事件和${summary.duplicateDeliveries}次额外重复投递；${summary.idempotencyCorrectEvents}/${idem.length}个重复/提交后崩溃事件仅产生一次业务效果，重复写入${summary.duplicateBusinessWrites}次。验证Broker恢复补偿，并区分${before.successEvents}个自动完成事件与${dead.length}个需显式DLQ重放的事件。\n\n`;
else md+='本轮验证未通过，不提供成功率简历表述，请检查原始失败记录。\n\n';
md+='剩余：真实业务各投影端到端成功率、5分钟SENDING租约过期、多发布者争抢、Broker节点/磁盘崩溃和网络分区、长时间多轮统计N/A。下一阶段应先补齐真实通知/Redis/ES失败状态与补偿验收，再增加独立重复轮次；本轮不声称exactly-once消息投递。\n';
write('benchmark-summary.md',md);
console.log(JSON.stringify(summary,null,2));
if(!summary.harnessPass||!summary.rawIntegrityPass)process.exitCode=1;
