import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const dir=path.resolve(process.argv[2]||'benchmarks/results/chat-stream/run-03');const read=f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
const samples=read('client-samples.json'),summary=read('client-summary.json'),provider=read('provider-final.json'),router=read('router-results.json'),status=read('runner-status.json'),ui=read('ui-results.json');
assert.equal(samples.length,212);assert.equal(router.length,40);assert.equal(summary.length,10);assert.equal(status.cleanupExitCode,0);
const full=Array.from({length:6},(_,i)=>`chunk${String(i+1).padStart(2,'0')}|`).join(''),checks=[];
for(const s of samples){
 const partial=['cancel','timeout'].includes(s.scenario),expected=partial?full.slice(0,24):full;
 assert.equal(s.expectedAnswer,expected);const ids=new Set(s.events.map(x=>x.event.seq));
 const missing=Array.from({length:partial?5:8},(_,i)=>i+1).filter(n=>!ids.has(n)).length;assert.equal(s.messageLoss,missing);
 const calls=provider.calls.filter(x=>x.question===s.question);assert.equal(s.modelCalls,calls.length);
 const history=provider.history.filter(x=>x.question===s.question);const successfulGeneration=calls.some(x=>x.done);
 if(s.passed){assert.equal(s.answer,expected);assert.equal(missing,0);assert.equal(s.errors.length,0);assert.equal(s.modelCalls,1);assert.equal(s.terminal,partial?(s.scenario==='cancel'?'cancelled':'timeout'):'completed');}
 if(s.scenario==='duplicate_done'){assert.equal(history.length,1);assert.equal(s.terminalMessages,1);}
 if(s.scenario==='cancel'||s.scenario==='timeout')assert.equal(history.length,0);
 if(s.scenario==='reconnect'&&s.passed){assert.equal(s.connections,2);assert.equal(history.length,1);}
 checks.push({scenario:s.scenario,user:s.user,expectedEventGaps:missing,historyWrites:history.length,upstreamCompleted:successfulGeneration,passed:s.passed});
}
for(const r of router){assert.equal(r.primaryCalls,2);assert.equal(r.backupCalls,r.mode==='circuit'?3:1);assert.equal(r.results.length,r.mode==='circuit'?3:1);assert.ok(r.results.every(x=>x===(r.mode==='template'?'template:MOCK_TEMPLATE':'backup:MOCK_OK')));}
assert.equal(ui.status,'PASS');assert.equal(ui.checks.length,10);assert.ok(ui.checks.every(x=>x.duplicateDisplayCount===0&&x.answer===full));
const hashFiles=['benchmarks/scripts/chat-stream-client.mjs','benchmarks/scripts/chat-stream-ui.cjs','benchmarks/scripts/run-chat-stream.mjs','benchmarks/scripts/check-chat-stream.mjs','benchmarks/scripts/chat-stream-report.mjs'];
const result={artifactChecksPassed:true,streamingFixtureChecks:checks.length,businessFailures:samples.filter(x=>!x.passed).map(x=>x.chat),routerChecks:router.length,uiChecks:ui.checks.length,realModelCalls:0,scriptsSha256:Object.fromEntries(hashFiles.map(p=>[p,crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')])),checks};
fs.writeFileSync(path.join(dir,'evidence-check.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,checks:undefined,scriptsSha256:undefined}));
