import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {spawn} from 'node:child_process';
import {runSuite} from './chat-stream-client.mjs';
const dir=path.resolve(process.argv[2]||'benchmarks/results/chat-stream/run-01');
if(fs.existsSync(dir))throw Error('Use a new result directory');fs.mkdirSync(dir,{recursive:true});
const run='bench-chat-'+Date.now(),env={...process.env,BENCH_RUN:run,BENCH_RESULTS:dir,BENCH_MOCK_ONLY:'true'};
// Real-provider settings are never used or passed to the isolated JVM.
for(const k of Object.keys(env))if(/API_KEY|API_TOKEN|PAI_MODEL_TEST|OPENAI|ANTHROPIC/.test(k))delete env[k];
const compose=['compose','-p',run,'-f','benchmarks/config/chat-stream-compose.yml'];
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function exec(cmd,args,file){const fd=fs.openSync(path.join(dir,file),'a');try{return await new Promise((resolve,reject)=>{const p=spawn(cmd,args,{env,stdio:['ignore',fd,fd]});p.on('error',reject);p.on('close',resolve);});}finally{fs.closeSync(fd);}}
fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({run,startedAt:new Date().toISOString(),mode:'mock-only',realModelCalls:0,concurrency:[1,10,50,100],faultSessions:10,environment:{cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryBytes:os.totalmem(),os:os.release(),node:process.version}},null,2));
let exit=1,server,serverExit=null,log;
try{
 if(await exec('docker',[...compose,'up','-d','--wait','--wait-timeout','60'],'docker-start.log'))throw Error('Dedicated Redis failed');
 await exec('docker',['image','inspect','redis:8.0','--format','{{.Id}} {{json .RepoDigests}}'],'redis-image.txt');
 for(const f of ['provenance.json','compile.log','java-version.txt'])fs.copyFileSync(path.join('benchmarks/build/chat-stream',f),path.join(dir,f));
 const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin',process.platform==='win32'?'java.exe':'java');
 log=fs.openSync(path.join(dir,'server.log'),'a');server=spawn(java,['-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Dlogback.configurationFile=benchmarks/config/es-logback.xml','-cp','benchmarks/build/chat-stream'+path.delimiter+'benchmarks/build/chat-stream/classpath.jar','ChatStreamBenchServer'],{env,stdio:['pipe',log,log]});server.on('exit',code=>serverExit=code);server.on('error',e=>{serverExit=-1;fs.writeFileSync(path.join(dir,'spawn-error.txt'),e.message);});
 const deadline=Date.now()+60000;while(!fs.existsSync(path.join(dir,'server-ready.json'))){if(serverExit!==null||Date.now()>deadline)throw Error('Server not ready; inspect server.log');await delay(250);}
 exit=(await runSuite(dir)).passed?0:2;
}catch(e){fs.writeFileSync(path.join(dir,'runner-error.json'),JSON.stringify({error:e.stack},null,2));}
finally{
 if(server&&serverExit===null){server.stdin.end('stop\n');for(let i=0;i<80&&serverExit===null;i++)await delay(250);if(serverExit===null){server.kill();exit=1;}}
 if(log!==undefined)fs.closeSync(log);
 const cleanup=await exec('docker',[...compose,'down','--volumes'],'docker-cleanup.log');fs.writeFileSync(path.join(dir,'runner-status.json'),JSON.stringify({testExitCode:exit,serverExitCode:serverExit,cleanupExitCode:cleanup,finishedAt:new Date().toISOString()},null,2));if(cleanup)exit=1;
}
console.log(JSON.stringify({run,dir,exit}));process.exitCode=exit;
