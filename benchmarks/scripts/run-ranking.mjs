import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import {spawn} from 'node:child_process';
const dir=path.resolve(process.argv[2]||'benchmarks/results/ranking/run-01');
if(fs.existsSync(dir))throw Error('Use a new result directory');fs.mkdirSync(dir,{recursive:true});
const run='bench-rank-'+Date.now(),env={...process.env,BENCH_RUN:run,BENCH_TEST_PASSWORD:crypto.randomBytes(24).toString('hex'),BENCH_RESULTS:dir};
const compose=['compose','-p',run,'-f','benchmarks/config/ranking-compose.yml'];
async function exec(cmd,args,file){const fd=fs.openSync(path.join(dir,file),'a');try{return await new Promise((resolve,reject)=>{const p=spawn(cmd,args,{env,stdio:['ignore',fd,fd]});p.on('error',reject);p.on('close',resolve);});}finally{fs.closeSync(fd);}}
fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({run,startedAt:new Date().toISOString(),concurrency:[10,50,100,200],operations:400,poolSize:220,redisCommandTimeoutMs:100,redisPersistence:'disabled',environment:{cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryBytes:os.totalmem(),os:os.release(),node:process.version},scope:'Actual transactional rank service, MyBatis mapper, MySQL and Redis; synthetic user profiles; no HTTP or Rabbit producer/consumer'},null,2));
let exit=1;
try{
 if(await exec('docker',[...compose,'up','-d','--wait','--wait-timeout','180'],'docker-start.log'))throw Error('Dedicated containers failed');
 await exec('docker',[...compose,'ps','--format','json'],'containers.jsonl');
 await exec('docker',['image','inspect','mysql:8.0','redis:8.0','--format','{{.Id}} {{json .RepoDigests}}'],'image-digests.txt');
 await exec('docker',['info','--format','{{json .}}'],'docker-environment.json');
 for(const f of ['provenance.json','compile.log','java-version.txt','RankingHarness.java'])fs.copyFileSync(path.join('benchmarks/build/ranking',f),path.join(dir,f));
 const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin',process.platform==='win32'?'java.exe':'java');
 exit=await exec(java,['-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Dlogback.configurationFile=benchmarks/config/es-logback.xml','-cp','benchmarks/build/ranking'+path.delimiter+'benchmarks/build/ranking/classpath.jar','RankingHarness'],'harness.log');
}catch(e){fs.writeFileSync(path.join(dir,'runner-error.json'),JSON.stringify({error:e.message}));}
finally{const cleanup=await exec('docker',[...compose,'down','--volumes'],'docker-cleanup.log');fs.writeFileSync(path.join(dir,'runner-status.json'),JSON.stringify({testExitCode:exit,cleanupExitCode:cleanup,finishedAt:new Date().toISOString()},null,2));if(cleanup)exit=1;}
console.log(JSON.stringify({run,dir,exit}));process.exitCode=exit;
