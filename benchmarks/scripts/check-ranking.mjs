import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const dir=path.resolve(process.argv[2]||'benchmarks/results/ranking/run-02');
const read=f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
const rows=read('summary.json');assert.equal(rows.length,40);
const evidence=[];
for(const r of rows){
 const lat=read(r.scenario+'-latencies.json');assert.equal(lat.length,r.operations);assert.equal(new Set(lat.map(x=>x.index)).size,r.operations);
 assert.equal(lat.filter(x=>x.ok).length/r.operations,r.updateSuccessRate);
 if(!fs.existsSync(path.join(dir,r.scenario+'-fixture.json')))continue;
 const phases=read(r.scenario+'-fixture.json'),seen=new Set(),state=new Map();
 // Separate declarative oracle: derive final active business keys from fixture only.
 for(const x of phases.flat()){if(seen.has(x.id))continue;seen.add(x.id);state.set([x.uid,x.article,x.action].join(':'),x.active);}
 const expected={};for(const [key,active] of state)if(active){const uid=key.split(':')[0];expected[uid]=(expected[uid]||0)+2;}
 assert.deepEqual(r.expected,expected);assert.deepEqual(read(r.scenario+'-expected.json'),expected);
 const diff=actual=>new Set([...Object.keys(expected),...Object.keys(actual)].filter(k=>(expected[k]||0)!==(actual[k]||0))).size;
 assert.equal(r.scoreDifferenceCount,diff(r.beforeScores));assert.equal(r.monthScoreDifferenceCount,diff(r.beforeMonthScores));
 assert.equal(read(r.scenario+'-mysql-ledger.json').length,r.ledgerRows);
 const expectedOrder=Object.keys(expected).sort((a,b)=>expected[b]-expected[a]||Number(a)-Number(b)).map(Number);assert.deepEqual(r.expectedOrder,expectedOrder);
 evidence.push({scenario:r.scenario,independentOracleValid:true,passed:r.passed});
}
const result={artifactChecksPassed:true,independentFixtureOracles:evidence.length,businessFailures:rows.filter(x=>!x.passed&&!x.scenario.includes('baseline')).map(x=>x.scenario),checks:evidence};
fs.writeFileSync(path.join(dir,'evidence-check.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
