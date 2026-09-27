import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
const out=path.resolve('benchmarks/build/chat-stream');fs.mkdirSync(out,{recursive:true});
const java=path.resolve(process.env.JAVA8_HOME||'.runtime/java8','bin');
function run(exe,args,log){const r=spawnSync(path.join(java,exe+(process.platform==='win32'?'.exe':'')),args,{encoding:'utf8',maxBuffer:8e6});fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||''));if(r.status!==0)throw Error(log);}
run('java',['-version'],'java-version.txt');if(!fs.readFileSync(path.join(out,'java-version.txt'),'utf8').includes('1.8.0'))throw Error('Java8 required');
const cp=['paicoding-api','paicoding-core','paicoding-service','paicoding-web'].map(p=>path.resolve(p,'target/classes')).concat(fs.readFileSync(process.env.PAI_BENCH_CLASSPATH_FILE||'paicoding-web/target/model-test-classpath.txt','utf8').trim().split(path.delimiter));
const line='Class-Path: '+cp.map(p=>pathToFileURL(p).href+(fs.statSync(p).isDirectory()?'/':'')).join(' ');
let manifest='Manifest-Version: 1.0\r\n';for(let i=0;i<line.length;i+=68)manifest+=(i?' ':'')+line.slice(i,i+68)+'\r\n';fs.writeFileSync(path.join(out,'MANIFEST.MF'),manifest+'\r\n');run('jar',['cfm',path.join(out,'classpath.jar'),path.join(out,'MANIFEST.MF')],'jar.log');
const svc='paicoding-service/src/main/java/com/github/paicoding/forum/service/',web='paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/',api='paicoding-api/src/main/java/com/github/paicoding/forum/api/model/vo/chat/';
const sources=['ChatStreamEvent.java','ChatStreamRequest.java'].map(p=>api+p).concat(
 ['llm/LLMRouter.java','llm/LLMRouteProperties.java','llm/LLMUsageGuard.java','chatai/ChatFacade.java','chatai/service/ChatServiceFactory.java','chatai/service/AbsChatService.java','chatai/service/ChatGenerationControl.java','chatai/service/ChatContextBudgeter.java','chatai/service/impl/deepseek/DeepSeekIntegration.java','chatai/service/impl/deepseek/DeepSeekChatServiceImpl.java'].map(p=>svc+p),
 ['rest/ChatRestController.java','helper/WsAnswerHelper.java','helper/ChatStreamSessionService.java','stomp/WsChatConfig.java','stomp/WsChatProperties.java','stomp/WsConnectionRegistry.java','stomp/AuthInChannelInterceptor.java','stomp/AuthOutChannelInterceptor.java'].map(p=>web+p),
 'paicoding-core/src/main/java/com/github/paicoding/forum/core/cache/RedisClient.java','paicoding-core/src/main/java/com/github/paicoding/forum/core/ws/WebSocketResponseUtil.java','benchmarks/java/chat/ChatStreamBenchServer.java');
fs.writeFileSync(path.join(out,'javac.args'),['-encoding','UTF-8','-cp',cp.join(path.delimiter),'-d',out,...sources].map(s=>'"'+s.replaceAll('\\','/')+'"').join('\n'));
run('javac',['-J-Dfile.encoding=UTF-8','@'+path.join(out,'javac.args')],'compile.log');
const evidence=sources.concat('paicoding-ui/src/main/resources/templates/views/chat-home/index.html');
fs.writeFileSync(path.join(out,'provenance.json'),JSON.stringify({sources:Object.fromEntries(evidence.map(p=>[p,crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]))},null,2));console.log('Compiled actual chat/STOMP/router sources with Java8');
