import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
const out=path.resolve('benchmarks/build/outbox');fs.mkdirSync(out,{recursive:true});
const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin');
const run=(exe,args,log)=>{const r=spawnSync(path.join(java,exe+(process.platform==='win32'?'.exe':'')),args,{encoding:'utf8',maxBuffer:8e6});fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||''));if(r.status!==0)throw Error(exe+' failed: '+log);};
run('java',['-version'],'java-version.txt');if(!fs.readFileSync(path.join(out,'java-version.txt'),'utf8').includes('1.8.0'))throw Error('Java8 required');
const cp=['paicoding-api','paicoding-core','paicoding-service'].map(p=>path.resolve(p,'target/classes')).concat(fs.readFileSync(process.env.PAI_BENCH_CLASSPATH_FILE||'paicoding-web/target/model-test-classpath.txt','utf8').trim().split(path.delimiter));
// The previously exported model classpath predates AMQP; resolve its four additions from the cached project BOM.
const repo=path.join(os.homedir(),'.m2/repository');
const bom=fs.readFileSync(path.join(repo,'org/springframework/boot/spring-boot-dependencies/2.7.1/spring-boot-dependencies-2.7.1.pom'),'utf8');
const version=key=>{const v=bom.match(new RegExp('<'+key+'>([^<]+)</'+key+'>'));if(!v)throw Error('Missing BOM version '+key);return v[1];};
for(const [group,name,key] of [['org/springframework/amqp','spring-amqp','spring-amqp.version'],['org/springframework/amqp','spring-rabbit','spring-amqp.version'],['org/springframework/retry','spring-retry','spring-retry.version'],['com/rabbitmq','amqp-client','rabbit-amqp-client.version']]){
  const v=version(key);const p=path.join(repo,group,name,v,name+'-'+v+'.jar');if(!fs.existsSync(p))throw Error('Missing cached dependency: '+name+':'+v);cp.push(p);
}
const line='Class-Path: '+cp.map(p=>pathToFileURL(p).href+(fs.statSync(p).isDirectory()?'/':'')).join(' ');
let manifest='Manifest-Version: 1.0\r\n';for(let i=0;i<line.length;i+=68)manifest+=(i?' ':'')+line.slice(i,i+68)+'\r\n';
fs.writeFileSync(path.join(out,'MANIFEST.MF'),manifest+'\r\n');run('jar',['cfm',path.join(out,'classpath.jar'),path.join(out,'MANIFEST.MF')],'jar.log');
const base='paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/';
const sources=['service/impl/DomainEventOutboxServiceImpl.java','service/impl/DomainEventOutboxPublisher.java','service/impl/DomainEventConsumeService.java','config/RabbitMqAutoConfig.java','config/RabbitMdcTraceAdvice.java','monitor/DomainEventMessageMonitor.java'].map(p=>base+p).concat('benchmarks/java/outbox/OutboxReliabilityHarness.java');
fs.writeFileSync(path.join(out,'javac.args'),['-encoding','UTF-8','-cp',cp.join(path.delimiter),'-d',out,...sources].map(s=>'"'+s.replaceAll('\\','/')+'"').join('\n'));
run('javac',['-J-Dfile.encoding=UTF-8','@'+path.join(out,'javac.args')],'compile.log');
fs.writeFileSync(path.join(out,'provenance.json'),JSON.stringify({javaVersion:fs.readFileSync(path.join(out,'java-version.txt'),'utf8'),sources:Object.fromEntries(sources.map(p=>[p,crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]))},null,2)+'\n');
console.log('Compiled actual Outbox/Rabbit/consume classes with Java8.');
