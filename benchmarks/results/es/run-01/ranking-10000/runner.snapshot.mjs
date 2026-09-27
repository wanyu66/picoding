import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { articles } from './articles/dataset.mjs';
const require=createRequire(import.meta.url);
const {buildReviewPage}=require('../../scripts/search-evaluation/review-page.js');
const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const write=(p,x)=>fs.writeFileSync(p,JSON.stringify(x,null,2)+'\n');
const append=(p,x)=>fs.appendFileSync(p,JSON.stringify(x)+'\n');
const clone=x=>structuredClone(x);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const fields=['title','shortTitle','summary','content'];
export function queryBody(contract,id,variant,origin,size=10,offset=0,after) {
  const body=clone(contract.queries[id]);
  if(!body) throw Error('Unknown query');
  body.size=size;
  if(variant==='A') {
    const keyword=body.query.function_score.query.bool.must[0].bool.should[0].match_phrase.title.query;
    body.query={bool:{must:[{multi_match:{query:keyword,fields,type:'best_fields',operator:'and'}}],
      filter:body.query.function_score.query.bool.filter}};
  } else if(variant==='B') {
    body.query.function_score.functions.find(f=>f.gauss).gauss.createTime.origin=origin;
  } else throw Error('Unknown strategy');
  if(after) body.search_after=after;
  else if(offset) body.from=offset;
  return body;
}
export function document(a) {
  const d={articleId:String(a.ordinal),authorId:'1',authorName:'benchmark synthetic author',urlSlug:a.slug,
    status:1,deleted:0,officalStat:a.officalStat,toppingStat:a.toppingStat,creamStat:a.creamStat,
    qualityScore:String(1+5*a.creamStat+3*a.officalStat+2*a.toppingStat),
    readCount:String(a.readCount),praiseCount:String(a.praiseCount),columnIds:[],createTime:a.created,updateTime:a.created};
  for(const f of fields) {d[f]=a[f];d[f+'Exact']=a[f];} return d;
}
export function statistics(rows,seconds) {
  const times=rows.map(x=>x.latencyMs).sort((a,b)=>a-b), ok=rows.filter(x=>x.ok);
  const percentile=p=>times.length?times[Math.ceil(p*times.length)-1]:null;
  return {samples:rows.length,successes:ok.length,errors:rows.length-ok.length,errorRate:rows.length?(rows.length-ok.length)/rows.length:null,
    seconds,qps:rows.length/seconds,successQps:ok.length/seconds,p50Ms:percentile(.5),p95Ms:percentile(.95),p99Ms:percentile(.99),
    meanMs:times.length?times.reduce((a,b)=>a+b,0)/times.length:null,
    meanEsTookMs:ok.length?ok.reduce((a,b)=>a+b.tookMs,0)/ok.length:null};
}
export async function main() {
  const [command,dirArg,countArg]=process.argv.slice(2);
  const dir=path.resolve(dirArg||'benchmarks/results/es/run-01');
  if(command==='init') {
    if(fs.existsSync(dir)) throw Error('Refuse overwrite existing run');
    fs.mkdirSync(dir,{recursive:true});
    const config=read('benchmarks/config/es-benchmark.json');
    write(path.join(dir,'config.json'),config);
    fs.copyFileSync('benchmarks/build/es/contract.json',path.join(dir,'contract.json'));
    const runId='bench_es_'+Date.now();
    const source='paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java';
    write(path.join(dir,'manifest.json'),{runId,createdAt:new Date().toISOString(),source,sourceSha256:sha(fs.readFileSync(source)),
      contractSha256:sha(fs.readFileSync(path.join(dir,'contract.json'))),configSha256:sha(fs.readFileSync(path.join(dir,'config.json'))),
      generatorSha256:sha(fs.readFileSync('benchmarks/scripts/articles/dataset.mjs')),runnerSha256:sha(fs.readFileSync(new URL(import.meta.url))),
      indices:config.sizes.map(n=>runId+'_'+n),alias:runId+'_alias',aliasIndices:[runId+'_old',runId+'_new'],
      environment:{platform:os.platform(),release:os.release(),arch:os.arch(),cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryBytes:os.totalmem(),node:process.version}});
  }
  const c=read(path.join(dir,'config.json')),m=read(path.join(dir,'manifest.json')),contract=read(path.join(dir,'contract.json'));
  if(!/^bench_es_\d+$/.test(m.runId)||m.indices.some((v,i)=>v!==m.runId+'_'+c.sizes[i])||m.alias!==m.runId+'_alias'||JSON.stringify(m.aliasIndices)!==JSON.stringify([m.runId+'_old',m.runId+'_new'])) throw Error('Invalid resource ownership manifest');
  const url=new URL(c.esUrl);
  if(!['127.0.0.1','localhost','[::1]'].includes(url.hostname)) throw Error('This runner only permits local ES');
  if(sha(fs.readFileSync(path.join(dir,'contract.json')))!==m.contractSha256 || sha(fs.readFileSync(path.join(dir,'config.json')))!==m.configSha256) throw Error('Frozen inputs changed');
  async function req(route,method='GET',body,timeout=c.requestTimeoutMs) {
    const r=await fetch(c.esUrl+route,{method,headers:{'content-type':typeof body==='string'?'application/x-ndjson':'application/json',
      ...(process.env.PAI_BENCH_ES_API_KEY?{authorization:'ApiKey '+process.env.PAI_BENCH_ES_API_KEY}:{})},
      body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),signal:AbortSignal.timeout(timeout)});
    const x=await r.json();
    if(!r.ok||x.error||x.errors||x.timed_out||x._shards?.failed) throw Error('ES_'+r.status+'_'+(x.error?.type|| (x.errors?'bulk_item_failure':x.timed_out?'timed_out':'failed_shards')));
    return x;
  }
  const es=await req('/');
  if(m.clusterUuid&&m.clusterUuid!==es.cluster_uuid) throw Error('Cluster changed');
  if(command==='init') {
    m.clusterUuid=es.cluster_uuid; write(path.join(dir,'manifest.json'),m);
    write(path.join(dir,'es-info.json'),es);
    write(path.join(dir,'nodes-info.json'),await req('/_nodes/os,jvm,process,plugins'));
    write(path.join(dir,'app-status.json'),await (await fetch('http://127.0.0.1:8080/search/api/status')).json());
    console.log('Initialized '+dir);return;
  }
  const count=Number(countArg),index=m.runId+'_'+count;
  if(['load','performance','ranking','relevance','probe'].includes(command)&&!c.sizes.includes(count)) throw Error('Invalid dataset size');
  const spec=n=>({count:n,seed:c.seed,batch:'es-s'+c.seed+'-n'+n,referenceTime:c.referenceTime});
  async function ownedCreate(name) {
    if(!m.indices.concat(m.aliasIndices).includes(name)) throw Error('Refuse unowned index');
    const definition=clone(contract.indexDefinition);
    definition.mappings._meta={benchmarkRun:m.runId,sourceSha256:m.sourceSha256};
    await req('/'+name,'PUT',definition); // existing index is an error
  }
  async function checkOwner(name) {
    const map=await req('/'+name+'/_mapping');
    if(map[name]?.mappings?._meta?.benchmarkRun!==m.runId) throw Error('Ownership mismatch');
  }
  async function bulkLoad(name,n,log) {
    const start=performance.now(),digest=crypto.createHash('sha256');let chunk=[],sent=0;
    async function flush() {
      const data=chunk.join('');digest.update(data);const t=performance.now();
      const r=await req('/'+name+'/_bulk','POST',data,120000);
      append(log,{index:name,completed:sent,documents:chunk.length/2,elapsedMs:performance.now()-t,tookMs:r.took,errors:r.errors});chunk=[];
    }
    for(const a of articles(spec(n))) {
      chunk.push(JSON.stringify({index:{_id:String(a.ordinal)}})+'\n',JSON.stringify(document(a))+'\n');sent++;
      if(chunk.length===c.bulkSize*2) await flush();
    }
    if(chunk.length) await flush();
    await req('/'+name+'/_refresh','POST');
    const actual=(await req('/'+name+'/_count')).count;
    if(actual!==n) throw Error('Index count mismatch');
    return {documents:n,indexCount:actual,seconds:(performance.now()-start)/1000,bulkSha256:digest.digest('hex')};
  }
  if(command==='load') {
    await ownedCreate(index);
    const result=await bulkLoad(index,count,path.join(dir,'load-'+count+'.jsonl'));
    write(path.join(dir,'load-'+count+'.json'),result);
    write(path.join(dir,'index-'+count+'.json'),{mapping:await req('/'+index+'/_mapping'),settings:await req('/'+index+'/_settings')});
    console.log(JSON.stringify(result));return;
  }
  const body=(id,v,size=10,offset=0,after)=>queryBody(contract,id,v,c.referenceTime,size,offset,after);
  const search=(idx,b)=>req('/'+idx+'/_search?request_cache=false','POST',b);
  if(command==='probe') {
    await checkOwner(index);const results=[];
    for(const [queryId,docId,branch] of [['pinyin','1','pinyin'],['typo','5','fuzzy']]) {
      const doc=(await req('/'+index+'/_doc/'+docId))._source;
      const query=c.queries.find(x=>x.id===queryId).query;
      const should=body(queryId,'B').query.function_score.query.bool.must[0].bool.should.filter(x=>x.match&&Object.keys(x.match)[0].endsWith('.pinyin')===(branch==='pinyin'));
      const probe={bool:{should,minimum_should_match:1}};
      const a=await req('/'+index+'/_explain/'+docId,'POST',{query:body(queryId,'A').query});
      const b=await req('/'+index+'/_explain/'+docId,'POST',{query:probe});
      const containsLiteral=fields.some(f=>doc[f].toLowerCase().includes(query.toLowerCase()));
      results.push({queryId,query,docId,branch,containsLiteral,baselineMatched:a.matched,isolatedBranchMatched:b.matched,
        pass:!containsLiteral&&!a.matched&&b.matched,source:doc,isolatedQuery:probe,explanation:b.explanation});
    }
    const file=path.join(dir,'recall-probes-'+count+'.json');
    fs.writeFileSync(file,JSON.stringify(results,null,2)+'\n',{flag:'wx'});
    if(results.some(x=>!x.pass))throw Error('Recall probe failed; preserve raw explanations');
    console.log('Nonliteral pinyin/fuzzy probes passed');return;
  }
  if(command==='performance'||command==='ranking') {
    await checkOwner(index);
    if((await req('/'+index+'/_count')).count!==count) throw Error('Wrong corpus size');
    const suffix=process.env.PAI_BENCH_ATTEMPT||'';
    if(suffix&&!/^-retry[1-9][0-9]*$/.test(suffix))throw Error('Invalid attempt suffix');
    const pd=path.join(dir,(command==='ranking'?'ranking-':'performance-')+count+suffix);fs.mkdirSync(pd); // never mix retries
    fs.copyFileSync(new URL(import.meta.url),path.join(pd,'runner.snapshot.mjs'));
    write(path.join(pd,'run-metadata.json'),{startedAt:new Date().toISOString(),balancedCompleteQueryCycles:true,runnerSha256:sha(fs.readFileSync(new URL(import.meta.url))),contractSha256:m.contractSha256,configSha256:m.configSha256});
    const raw=path.join(pd,'raw-results.csv');
    const columns=['cell','repetition','phase','dataset','variant','mode','concurrency','size','offset','queryId','startedAt','latencyMs','ok','tookMs','totalHits','returned','error'];
    fs.writeFileSync(raw,columns.join(',')+'\n');
    const summaries=[],cursors=new Map();
    // Walk cursor chain once; record the complete cost separately from pre-positioned cursor requests.
    let after,position=0;const prepStart=performance.now();
    const max=command==='ranking'?0:Math.max(...c.paginationDepths);
    while(position<max) {
      const size=Math.min(500,max-position,...c.paginationDepths.filter(x=>x>position).map(x=>x-position));
      const prep=body(c.paginationQuery,'B',size,0,after);delete prep.highlight;prep._source=false;
      const t=performance.now(),r=await search(index,prep);
      if(r.hits.hits.length!==size) throw Error('Pagination query does not have enough hits');
      after=r.hits.hits.at(-1).sort;position+=size;cursors.set(position,after);
      append(path.join(pd,'cursor-preparation.jsonl'),{position,size,latencyMs:performance.now()-t,tookMs:r.took,after});
    }
    write(path.join(pd,'cursor-preparation.json'),{seconds:(performance.now()-prepStart)/1000,method:'sequential search_after, pages up to 500, no highlights/source; excluded from positioned-cursor latency',positions:Object.fromEntries(cursors)});
    for(const offset of command==='ranking'?[]:c.paginationDepths) {
      const a=await search(index,body(c.paginationQuery,'B',10,offset));
      const b=await search(index,body(c.paginationQuery,'B',10,0,cursors.get(offset)));
      const ids=r=>r.hits.hits.map(x=>x._id);
      append(path.join(pd,'pagination-equivalence.jsonl'),{offset,from:ids(a),searchAfter:ids(b),pass:JSON.stringify(ids(a))===JSON.stringify(ids(b))});
      if(JSON.stringify(ids(a))!==JSON.stringify(ids(b))) throw Error('Pagination equivalence failed');
    }
    const cells=[];
    for(const concurrency of c.concurrency) for(const variant of ['A','B']) for(const size of c.resultSizes) cells.push({variant,concurrency,size,mode:'mixed',offset:0});
    for(const offset of command==='ranking'?[]:c.paginationDepths) for(const mode of ['from','search_after']) cells.push({variant:'B',concurrency:Math.max(...c.concurrency),size:10,mode,offset});
    let rng=c.seed; const rand=()=>{rng=(Math.imul(rng,1664525)+1013904223)>>>0;return rng;};
    for(let repetition=1;repetition<=c.repetitions;repetition++) {
      const order=cells.map((cell,i)=>({...cell,cell:i,repetition}));
      for(let i=order.length-1;i>0;i--){const j=rand()%(i+1);[order[i],order[j]]=[order[j],order[i]];}
      for(const cell of order) {
        const nodeLog=path.join(pd,'node-stats.jsonl');
        async function snapshot(label) {try{append(nodeLog,{at:new Date().toISOString(),cell:cell.cell,repetition,label,stats:await req('/_nodes/stats/os,process,jvm,indices')});}catch(e){append(nodeLog,{label,error:e.message});}}
        await snapshot('before');let tick=false;
        const timer=setInterval(async()=>{if(tick)return;tick=true;await snapshot('sample');tick=false;},1000);
        try {
          for(const [phase,seconds] of [['warmup',c.warmupSeconds],['measurement',c.durationSeconds]]) {
            const start=performance.now(),deadline=start+seconds*1000,rows=[];let sequence=0;
            await Promise.all(Array.from({length:cell.concurrency},async()=>{
              while(performance.now()<deadline || (cell.mode==='mixed' && sequence%c.queries.length!==0)) {
                const q=cell.mode==='mixed'?c.queries[sequence++%c.queries.length]:c.queries.find(q=>q.id===c.paginationQuery);
                const requestBody=body(q.id,cell.variant,cell.size,cell.mode==='from'?cell.offset:0,cell.mode==='search_after'?cursors.get(cell.offset):undefined);
                const at=new Date().toISOString(),t=performance.now();let result,error='';
                try{result=await search(index,requestBody);}catch(e){error=e.message;}
                const row={...cell,phase,dataset:count,queryId:q.id,startedAt:at,latencyMs:performance.now()-t,ok:!error,
                  tookMs:result?.took??'',totalHits:result?.hits.total.value??'',returned:result?.hits.hits.length??'',error};
                rows.push(row);fs.appendFileSync(raw,columns.map(k=>JSON.stringify(row[k]??'')).join(',')+'\n');
              }
            }));
            const elapsed=(performance.now()-start)/1000;
            if(cell.mode==='mixed' && c.queries.some(q=>rows.filter(r=>r.queryId===q.id).length!==rows.length/c.queries.length))throw Error('Unequal mixed-query frequencies');
            const summary={...cell,phase,dataset:count,requestedSeconds:seconds,...statistics(rows,elapsed)};
            summaries.push(summary);append(path.join(pd,'cells.jsonl'),summary);
            // Per-query counts/latencies prevent the cheap zero-hit query from concealing expensive queries.
            for(const q of c.queries) {const subset=rows.filter(r=>r.queryId===q.id);if(subset.length)append(path.join(pd,'per-query.jsonl'),{...cell,phase,dataset:count,queryId:q.id,...statistics(subset,elapsed),qpsNote:'share of mixed workload throughput'});}
          }
        } finally {clearInterval(timer);while(tick)await sleep(10);await snapshot('after');}
        console.log(JSON.stringify({dataset:count,cell:cell.cell,repetition,...summaries.at(-1)}));
      }
    }
    write(path.join(pd,'summary.json'),summaries);return;
  }
  if(command==='relevance') {
    await checkOwner(index);const rd=path.join(dir,'relevance-'+count);fs.mkdirSync(rd);
    const corpusSha256=read(path.join(dir,'load-'+count+'.json')).bulkSha256,experimentId=m.runId+'-'+count;
    const runs={A:[],B:[]},documents=new Map(),judgments=[];
    for(const q of c.queries) {
      for(const v of ['A','B']) {
        const b=body(q.id,v,20);b._source=true;
        append(path.join(rd,'requests.jsonl'),{queryId:q.id,variant:v,body:b});
        const r=await search(index,b);
        runs[v].push({query:q.query,query_id:q.id,variant:v,experimentId,corpusSha256,results:r.hits.hits.map(h=>h._id),scores:r.hits.hits.map(h=>h._score),total:r.hits.total.value,status:'ok'});
        for(const h of r.hits.hits)documents.set(h._id,{id:h._id,source:h._source});
      }
      const ids=[...new Set(['A','B'].flatMap(v=>runs[v].at(-1).results))].sort((a,b)=>Number(a)-Number(b));
      judgments.push({...q,experimentId,corpusSha256,intent:q.category,split:'test',relevance:Object.fromEntries(ids.map(id=>[id,null])),
        review:{status:'pending',source:'human',reviewer:null,reviewedAt:null,answerability:null,corpusReviewed:false,note:''}});
    }
    const manifest={experimentId,corpusSha256,documentCount:count,queryCount:c.queries.length,candidateScope:'pooled_top20',candidatePairs:judgments.reduce((n,q)=>n+Object.keys(q.relevance).length,0),variants:['A','B'],authorship:'assistant authored synthetic pilot; human grades pending'};
    write(path.join(rd,'manifest.json'),manifest);
    for(const [v,rows] of Object.entries(runs))fs.writeFileSync(path.join(rd,v+'.jsonl'),rows.map(JSON.stringify).join('\n')+'\n');
    fs.writeFileSync(path.join(rd,'judgments.pending.jsonl'),judgments.map(JSON.stringify).join('\n')+'\n');
    fs.writeFileSync(path.join(rd,'candidates.jsonl'),[...documents.values()].map(JSON.stringify).join('\n')+'\n');
    const reviewDocuments=[...documents.values()].map(d=>({...d,source:{...d.source,summary:d.source.summary+'\n[测试元数据] 阅读='+d.source.readCount+' 点赞='+d.source.praiseCount+' 发布时间='+d.source.createTime}}));
    let html=buildReviewPage({manifest,judgments,documents:reviewDocuments});
    html=html.replace('篇真实文章','篇合成测试文章').replace('条语料驱动查询','条人工待审查询');
    fs.writeFileSync(path.join(rd,'review.html'),html);console.log('Human judgments pending: '+rd);return;
  }
  if(command==='alias') {
    const ad=path.join(dir,'alias');fs.mkdirSync(ad);
    const [oldIndex,newIndex]=m.aliasIndices;
    await ownedCreate(oldIndex);await bulkLoad(oldIndex,10000,path.join(ad,'load-old.jsonl'));
    await req('/_aliases','POST',{actions:[{add:{index:oldIndex,alias:m.alias,is_write_index:true}}]});
    const fixture=path.join(ad,'rebuild-input.jsonl');
    const payload=[...articles(spec(10000))].map(a=>JSON.stringify(document(a))).join('\n')+'\n';
    fs.writeFileSync(fixture,payload);
    write(path.join(ad,'fixture-manifest.json'),{count:10000,sha256:sha(payload),generatorSpec:spec(10000)});
    const expected=(await search(m.alias,body('body','B',10))).hits.hits.map(h=>h._id);
    let running=true;const rows=[];
    const workers=Promise.all(Array.from({length:4},async()=>{while(running){const t=performance.now();let r,error='';try{r=await search(m.alias,body('body','B',10));if(r.hits.total.value!==10000||JSON.stringify(r.hits.hits.map(h=>h._id))!==JSON.stringify(expected))error='Unexpected count or top10 IDs';}catch(e){error=e.message;}
      const row={at:new Date().toISOString(),latencyMs:performance.now()-t,ok:!error,tookMs:r?.took,error,index:r?.hits.hits[0]?._index,returned:r?.hits.hits.length,total:r?.hits.total.value,ids:r?.hits.hits.map(h=>h._id)};rows.push(row);append(path.join(ad,'queries.jsonl'),row);await sleep(10);}}));
    const start=performance.now();let load,switchedAt;
    try {
      await sleep(1000);
      const log=fs.openSync(path.join(ad,'harness.log'),'wx');
      const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin',process.platform==='win32'?'java.exe':'java');
      try{await new Promise((resolve,reject)=>{
        const p=spawn(java,['-Dfile.encoding=UTF-8','-Dlogback.configurationFile=benchmarks/config/es-logback.xml','-cp',
          'benchmarks/build/es'+path.delimiter+'benchmarks/build/es/classpath.jar','SearchRebuildHarness',m.runId,c.esUrl,fixture,path.join(ad,'harness.json')],{stdio:['ignore',log,log]});
        p.on('error',reject);p.on('close',code=>code===0?resolve():reject(Error('Production rebuild harness failed: '+code)));
      });}finally{fs.closeSync(log);}
      load=read(path.join(ad,'harness.json'));
      switchedAt=new Date().toISOString();await sleep(2000);
    } finally {running=false;await workers;}
    const after=await req('/_alias/'+m.alias);
    const result={...statistics(rows,(performance.now()-start)/1000),concurrency:4,load,switchedAt,aliasAfter:after,expectedTop10:expected,
      oldHits:rows.filter(x=>x.index===oldIndex).length,newHits:rows.filter(x=>x.index===newIndex).length,
      countBefore:10000,countAfter:(await req('/'+newIndex+'/_count')).count,
      scope:'actual Java executeFullRebuild/bulkIndex/activateRebuiltIndex + real ES; DAO deterministic fixture; Redis mocked; no live MySQL writes'};
    result.pass=result.errors===0&&result.oldHits>0&&result.newHits>0&&result.countAfter===10000&&Object.keys(after).length===1&&Object.hasOwn(after,newIndex);
    write(path.join(ad,'result.json'),result);
    if(!result.pass)throw Error('Alias rebuild validation failed; see result.json');return;
  }
  if(command==='cleanup') {
    // No wildcard deletion. Validate all ownership before deleting any resource; skip nonexistent planned resources.
    const existing=await req('/_cat/indices?format=json&h=index');
    const names=m.indices.concat(m.aliasIndices).filter(n=>existing.some(x=>x.index===n));
    for(const n of names)await checkOwner(n);
    for(const n of names){const r=await req('/'+n,'DELETE');append(path.join(dir,'cleanup.jsonl'),{at:new Date().toISOString(),index:n,result:r});}
    console.log('Deleted only manifest-owned test indices: '+names.length);return;
  }
  throw Error('Commands: init/load/performance/ranking/relevance/probe/alias/cleanup');
}
if(process.argv[1]&&path.resolve(process.argv[1])===path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1'))) main().catch(e=>{console.error(e.message);process.exitCode=1;});
