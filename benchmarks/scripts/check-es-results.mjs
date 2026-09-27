import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const dir=path.resolve(process.argv[2]),out=path.dirname(dir);
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const lines=p=>fs.readFileSync(p,'utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const m=read(path.join(dir,'manifest.json')),summary=read(path.join(out,'summary.json'));
const config=read(path.join(dir,'config.json')),mixedCells=2*config.concurrency.length*config.resultSizes.length;
const checks=[];
function check(name,fn){try{fn();checks.push({name,status:'PASS'});}catch(e){checks.push({name,status:'FAIL',message:e.message});}}
check('source contract unchanged',()=>assert.equal(crypto.createHash('sha256').update(fs.readFileSync(m.source)).digest('hex'),m.sourceSha256));
check('all requested sizes completed',()=>assert.deepEqual(summary.completedSizes,[10000,50000,100000]));
for(const n of [10000,50000,100000]) {
  check(n+' exact index count',()=>{const r=read(path.join(dir,'load-'+n+'.json'));assert.equal(r.documents,n);assert.equal(r.indexCount,n);});
  const perf=fs.readdirSync(dir).find(p=>new RegExp('^performance-'+n+'(-retry[1-9][0-9]*)?$').test(p)&&fs.existsSync(path.join(dir,p,'summary.json')));
  check(n+' pagination equivalence',()=>{const a=lines(path.join(dir,perf,'pagination-equivalence.jsonl'));assert.equal(a.length,5);for(const r of a){assert.equal(r.pass,true);assert.deepEqual(r.from,r.searchAfter);}});
  const ranked=fs.existsSync(path.join(dir,'ranking-'+n,'summary.json'))?'ranking-'+n:perf;
  check(n+' every mixed window has exactly balanced queries',()=>{
    const p=lines(path.join(dir,ranked,'per-query.jsonl')).filter(x=>x.mode==='mixed');
    for(const rep of Array.from({length:config.repetitions},(_,i)=>i+1))for(let cell=0;cell<mixedCells;cell++)for(const phase of ['warmup','measurement']) {
      const a=p.filter(x=>x.repetition===rep&&x.cell===cell&&x.phase===phase);
      assert.equal(a.length,config.queries.length);assert.ok(a.every(x=>x.samples>0&&x.samples===a[0].samples));
    }
  });
  check(n+' all cells, repetitions and numeric metrics',()=>{
    const cells=summary.cells.filter(x=>x.dataset===n);assert.equal(cells.length,mixedCells+2*config.paginationDepths.length);
    for(const x of cells){assert.equal(x.repetitions,config.repetitions);assert.equal(x.qps,x.samples/x.seconds);assert.equal(x.errors,x.samples-x.successes);assert.ok(x.samples>0&&x.seconds>=config.durationSeconds*config.repetitions&&x.p99Ms>=x.p95Ms&&x.p95Ms>=x.p50Ms);}
  });
  check(n+' human judgments remain explicitly pending',()=>{
    const judgments=lines(path.join(dir,'relevance-'+n,'judgments.pending.jsonl'));assert.equal(judgments.length,7);
    assert.ok(judgments.every(x=>x.review.status==='pending'&&Object.values(x.relevance).every(v=>v===null)));
  });
}
check('combined raw samples reproduce summary',()=>{
  const raw=fs.readFileSync(path.join(out,'raw-results.csv'),'utf8').trim().split(/\r?\n/);const cols=raw.shift().split(',');
  const rows=raw.map(x=>Object.fromEntries(JSON.parse('['+x+']').map((v,i)=>[cols[i],v]))).filter(x=>x.phase==='measurement');
  assert.equal(rows.length,summary.measurementSamples);assert.equal(rows.filter(x=>!x.ok).length,summary.errors);
  for(const c of summary.cells) assert.equal(rows.filter(x=>x.dataset===c.dataset&&x.cell===c.cell).length,c.samples);
});
check('alias task and observed results',()=>{
  const r=read(path.join(dir,'alias/result.json'));assert.equal(r.pass,true);assert.equal(r.load.task.status,'SUCCEEDED');
  assert.equal(r.countAfter,10000);assert.equal(r.errors,0);assert.ok(r.oldHits>0&&r.newHits>0);
});
check('nonliteral recall probes',()=>assert.ok(read(path.join(dir,'recall-probes-10000.json')).every(x=>x.pass)));
const fault=read(path.join(out,'fault-harness-02.json'));
check('reported defects were actually injected, not hidden by Redis exceptions',()=>{
  for(const id of ['es_timed_out','failed_shard']) {const x=fault.find(x=>x.case===id);assert.equal(x.faultExercised,true);assert.equal(x.mysqlDaoCalls,0);assert.equal(x.pass,false);}
});
const result={at:new Date().toISOString(),checks,integrityStatus:checks.every(x=>x.status==='PASS')?'PASS':'FAIL',
  businessFaultStatus:fault.every(x=>x.pass)?'PASS':'FAIL',businessFaultPasses:fault.filter(x=>x.pass).length,businessFaultCases:fault.length};
fs.writeFileSync(path.join(out,'integrity-check.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));if(result.integrityStatus==='FAIL')process.exitCode=1;
