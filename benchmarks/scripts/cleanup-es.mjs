import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
const dir=path.resolve(process.argv[2]),c=JSON.parse(fs.readFileSync(path.join(dir,'config.json'),'utf8'));
const file=path.join(dir,'cleanup-guard.json');
if(fs.existsSync(file))throw Error('Cleanup guard already exists; do not overwrite history');
async function snapshot() {
  const fetchJson=async route=>{const r=await fetch(c.esUrl+route,{headers:process.env.PAI_BENCH_ES_API_KEY?{authorization:'ApiKey '+process.env.PAI_BENCH_ES_API_KEY}:{}});if(!r.ok)throw Error('Protected alias check failed: '+r.status);return r.json();};
  return {at:new Date().toISOString(),businessAlias:await fetchJson('/_alias/paicoding_article'),businessCount:await fetchJson('/paicoding_article/_count')};
}
const before=await snapshot();fs.writeFileSync(file,JSON.stringify({before,status:'STARTED'},null,2)+'\n',{flag:'wx'});
const r=spawnSync(process.execPath,['benchmarks/scripts/es-benchmark.mjs','cleanup',dir],{encoding:'utf8'});
fs.writeFileSync(path.join(dir,'cleanup-console.log'),(r.stdout||'')+(r.stderr||''));
const after=await snapshot();
const businessAliasUnchanged=JSON.stringify(before.businessAlias)===JSON.stringify(after.businessAlias);
const businessCountUnchanged=before.businessCount.count===after.businessCount.count;
const pass=r.status===0&&businessAliasUnchanged&&businessCountUnchanged;
fs.writeFileSync(file,JSON.stringify({before,after,exitCode:r.status,businessAliasUnchanged,businessCountUnchanged,status:pass?'PASS':'FAIL'},null,2)+'\n');
console.log(JSON.stringify({cleanupExitCode:r.status,businessAliasUnchanged,businessCountUnchanged,businessDocuments:after.businessCount.count}));
if(!pass)process.exitCode=1;
