import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import {spawn} from 'node:child_process';
const dir=path.resolve(process.argv[2]||'benchmarks/results/outbox/run-01');
if(fs.existsSync(dir))throw Error('Use a new result directory; never overwrite fault evidence');
fs.mkdirSync(dir,{recursive:true});
const run='bench-ob-'+Date.now(),env={...process.env,BENCH_RUN:run,BENCH_TEST_PASSWORD:crypto.randomBytes(24).toString('hex'),BENCH_RESULTS:dir};
const compose=['compose','-p',run,'-f','benchmarks/config/outbox-compose.yml'];
async function exec(cmd,args,file){const fd=fs.openSync(path.join(dir,file),'a');try{return await new Promise((resolve,reject)=>{const p=spawn(cmd,args,{env,stdio:['ignore',fd,fd]});p.on('error',reject);p.on('close',code=>resolve(code));});}finally{fs.closeSync(fd);}}
const manifest={run,startedAt:new Date().toISOString(),schema:'bench_outbox',mysqlPort:13416,rabbitPort:15782,consumerConcurrency:4,prefetch:1,seed:20260925,eventsPerScenario:{normal:32,rollback:8,send_failure:8,rabbit_outage:8,ack_crash:8,duplicate:32,dlq:8},environment:{cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryBytes:os.totalmem(),os:os.release(),node:process.version},scope:'real isolated MySQL/Rabbit; actual Outbox/publisher/consumer and retry factory; instrumented JDBC business fixture, not full HTTP/notification/rank projections'};
fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
let exit=1;
try{
  if(await exec('docker',[...compose,'up','-d','--wait','--wait-timeout','180'],'docker-start.log'))throw Error('Dedicated containers failed to start');
  await exec('docker',[...compose,'ps','--format','json'],'containers.jsonl');
  for(const f of ['provenance.json','compile.log','java-version.txt'])fs.copyFileSync(path.join('benchmarks/build/outbox',f),path.join(dir,f));
  const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin',process.platform==='win32'?'java.exe':'java');
  exit=await exec(java,['-Dfile.encoding=UTF-8','-Dlogback.configurationFile=benchmarks/config/es-logback.xml','-cp','benchmarks/build/outbox'+path.delimiter+'benchmarks/build/outbox/classpath.jar','OutboxReliabilityHarness'],'harness.log');
}catch(e){fs.writeFileSync(path.join(dir,'runner-error.json'),JSON.stringify({error:e.message})+'\n');}
finally{
  if(exit)await exec('docker',[...compose,'logs','--no-color','--tail','150'],'dedicated-container-diagnostics.log');
  // Only this newly generated Compose project; no global docker stop/prune, no shared volumes.
  const cleanup=await exec('docker',[...compose,'down','--volumes'],'docker-cleanup.log');
  fs.writeFileSync(path.join(dir,'runner-status.json'),JSON.stringify({testExitCode:exit,cleanupExitCode:cleanup,finishedAt:new Date().toISOString()},null,2)+'\n');
  if(cleanup)exit=1;
}
console.log(JSON.stringify({run,result:dir,exitCode:exit}));process.exitCode=exit;
