import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const [beforeDir,afterDir]=process.argv.slice(2).map(p=>path.resolve(p));
if(!beforeDir||!afterDir)throw Error('Usage: node benchmarks/scripts/compare-ranking.mjs BASELINE RESULTS');
const read=(dir,file)=>JSON.parse(fs.readFileSync(path.join(dir,file),'utf8'));
const before=read(beforeDir,'summary.json'),after=read(afterDir,'summary.json');
assert.equal(before.length,40);assert.equal(after.length,40);
const beforeSources=read(beforeDir,'provenance.json').sources,afterSources=read(afterDir,'provenance.json').sources;
for(const file of Object.keys(beforeSources).filter(f=>!f.startsWith('benchmarks/')))assert.equal(afterSources[file],beforeSources[file],`Business source changed: ${file}`);
const stripFixture=(rows,scenario)=>rows.map(phase=>phase.map(({time,...x})=>({...x,id:x.id.replace(scenario+':',''),original:x.original?.replace(scenario+':','')??null})));
const eventOwners=new Map();
const rows=after.map(candidate=>{
 const baseline=before.find(r=>r.scenario===candidate.scenario);assert.ok(baseline);
 for(const key of ['concurrency','operations','expected','expectedOrder','expectedScore'])assert.deepEqual(candidate[key],baseline[key],candidate.scenario+': '+key);
 const fixture=candidate.scenario+'-fixture.json';
 if(fs.existsSync(path.join(beforeDir,fixture))){
  const inputs=read(afterDir,fixture);
  assert.deepEqual(stripFixture(read(beforeDir,fixture),candidate.scenario),stripFixture(inputs,candidate.scenario),candidate.scenario+': fixture differs beyond ID namespace/time');
  for(const input of inputs.flat()){
   if(eventOwners.has(input.id))assert.equal(eventOwners.get(input.id),candidate.scenario,`Event ID reused between scenarios: ${input.id}`);
   eventOwners.set(input.id,candidate.scenario);
  }
 }
 const errors=dir=>read(dir,candidate.scenario+'-latencies.json').filter(x=>!x.ok);
 const a=errors(beforeDir),b=errors(afterDir);
 return {scenario:candidate.scenario,concurrency:candidate.concurrency,operations:candidate.operations,beforePassed:baseline.passed,afterPassed:candidate.passed,beforeErrors:a.length,afterErrors:b.length,beforeDeadlocks:a.filter(x=>x.error?.includes('Deadlock')).length,afterDeadlocks:b.filter(x=>x.error?.includes('Deadlock')).length,beforeTPS:baseline.attemptTPS,afterTPS:candidate.attemptTPS,beforeDurationMs:baseline.durationMs,afterDurationMs:candidate.durationMs};
});
const result={before:path.relative(process.cwd(),beforeDir),after:path.relative(process.cwd(),afterDir),businessSourceHashesEqual:true,fixturesEquivalentExceptScenarioEventIdsAndWallClock:true,rows};
fs.writeFileSync(path.join(afterDir,'comparison.json'),JSON.stringify(result,null,2));
const fields=Object.keys(rows[0]);fs.writeFileSync(path.join(afterDir,'comparison.csv'),fields.join(',')+'\n'+rows.map(r=>fields.map(k=>r[k]).join(',')).join('\n')+'\n');
console.log(JSON.stringify({comparable:true,beforeDeadlocks:rows.reduce((n,r)=>n+r.beforeDeadlocks,0),afterDeadlocks:rows.reduce((n,r)=>n+r.afterDeadlocks,0),afterFailures:rows.filter(r=>!r.afterPassed).map(r=>r.scenario)}));
