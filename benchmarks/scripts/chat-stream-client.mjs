import fs from 'node:fs';
import path from 'node:path';
import {performance} from 'node:perf_hooks';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const expected=Array.from({length:6},(_,i)=>`chunk0${i+1}|`).join('');
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1]:null;
export async function runSuite(dir){
 const config=JSON.parse(fs.readFileSync(path.join(dir,'server-ready.json'),'utf8'));
 if(config.mockOnly!==true)throw Error('Mock-only server required');
 const model=`http://127.0.0.1:${config.modelPort}`, rows=[],rawFrames=[],samples=[];let user=10000;
 const get=async p=>{const r=await fetch(model+p);if(!r.ok)throw Error('Fixture HTTP '+r.status);return r.json();};
 const wait=async(test,label,ms=20000)=>{const end=performance.now()+ms;while(!test()){if(performance.now()>end)throw Error('Timed out: '+label);await delay(5);}};
 function client(uid,chat){
  const c={uid,chat,events:[],seq:0,generation:null,accepted:[],suppressed:0,errors:[],connections:0};
  // Production stompClient.send uses an empty header object; match its String controller contract.
  c.send=(body)=>c.ws.send('SEND\ndestination:/app/chat/me/'+chat+'\n\n'+JSON.stringify(body)+'\0');
  c.open=async()=>{
   c.connections++;let connected=false,heartbeat=false,buffer='';
   const ws=new WebSocket(`ws://127.0.0.1:${config.wsPort}/bench/ws/${uid}`,['v12.stomp']);c.ws=ws;
   ws.addEventListener('open',()=>ws.send('CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0'));
   ws.addEventListener('error',()=>c.errors.push('WebSocket error'));
   ws.addEventListener('message',msg=>{
    buffer+=String(msg.data);let at;
    while((at=buffer.indexOf('\0'))>=0){const frame=buffer.slice(0,at).replace(/^\n+/, '');buffer=buffer.slice(at+1);const split=frame.indexOf('\n\n'),head=frame.slice(0,split),body=frame.slice(split+2);rawFrames.push({uid,chat,atMs:performance.now(),frame});
     if(head.startsWith('CONNECTED')){connected=true;ws.send('SUBSCRIBE\nid:sub-0\ndestination:/user/chat/rsp\nack:auto\n\n\0');}
     else if(head.startsWith('ERROR'))c.errors.push(frame);
     else if(head.startsWith('MESSAGE')){try{
      const event=JSON.parse(body);if(event.event==='heartbeat'){heartbeat=true;continue;}
      c.events.push({at:performance.now(),event});
      // Match production high-watermark semantics, checked against actual DOM separately.
      if(event.generationId&&event.seq<=c.seq&&event.generationId===c.generation){c.suppressed++;continue;}
      if(event.generationId){c.generation=event.generationId;c.seq=event.seq||0;}
      c.accepted.push(event);c.onEvent?.(event);
     }catch(e){c.errors.push(e.message);}}
    }
   });
   await wait(()=>connected||c.errors.length,'STOMP CONNECT');if(c.errors.length)throw Error(c.errors.join(';'));
   // A heartbeat response proves the subscription is active before timing generation.
   for(let i=0;i<40&&!heartbeat;i++){c.send({type:'ping'});await delay(25);}if(!heartbeat)throw Error('Subscription not ready');
  };
  c.close=async()=>{if(c.ws?.readyState<2)c.ws.close();await wait(()=>!c.ws||c.ws.readyState===3,'WebSocket closed',3000);};
  return c;
 }
 async function scenario(name,concurrency,mode=name){
  await get('/reset');if(mode==='timeout')await get('/timeout');
  const sessions=Array.from({length:concurrency},()=>{const id=++user;return client(id,name+'-'+id);});
  const before=await get('/metrics'),started=performance.now();
  try{
   await Promise.all(sessions.map(c=>c.open()));
   let peak=0,active=0;
   const out=await Promise.all(sessions.map(async c=>{
    const question=mode+':'+c.chat,record={scenario:name,concurrency,user:c.uid,chat:c.chat,question,latencySource:'mock'};
    let cancelAt=null,dropAt=null,recoveryAt=null;let reconnectJob=null;
    c.onEvent=e=>{
     if(mode==='cancel'&&e.event==='delta'&&e.seq===4&&!cancelAt){cancelAt=performance.now();c.send({type:'cancel',generationId:c.generation});}
     if(mode==='reconnect'&&e.event==='delta'&&e.seq===3&&!dropAt){dropAt=performance.now();const cursor=c.seq,id=c.generation;
      reconnectJob=(async()=>{await c.close();await delay(500);recoveryAt=performance.now();await c.open();c.send({type:'resume',generationId:id,lastSeq:cursor});})();reconnectJob.catch(e=>c.errors.push(e.message));
     }
    };
    record.start=performance.now();peak=Math.max(peak,++active);
    c.send({type:'generate',requestId:c.chat,content:question});
    try{
     await wait(()=>c.accepted.some(e=>e.terminal)||c.errors.length,'terminal '+c.chat);
     if(reconnectJob)await reconnectJob;
     const terminal=c.events.find(x=>x.event.terminal),first=c.events.find(x=>x.event.event==='delta'&&x.event.payload?.records?.[0]?.answer?.trim());
     record.ttftMs=first?first.at-record.start:null;record.totalMs=terminal?terminal.at-record.start:null;
     record.terminal=terminal?.event.event;record.cancelMs=cancelAt&&terminal?terminal.at-cancelAt:null;
     record.recoveryMs=recoveryAt&&terminal?terminal.at-recoveryAt:null;record.disconnectionMs=dropAt&&terminal?terminal.at-dropAt:null;
     if(mode==='duplicate_replay'){
      const previous=c.events.length;for(let n=0;n<2;n++)c.send({type:'resume',generationId:c.generation,lastSeq:0});
      await wait(()=>c.events.length>=previous+16,'duplicate replay delivery');
     }
     await delay(120); // Observe late cancellation acknowledgement / duplicate DONE.
     const seqs=new Set(c.events.map(x=>x.event.seq)),complete=['normal','reconnect','duplicate_replay','duplicate_done','unavailable'].includes(mode);
     const required=complete?8:5;record.messageLoss=Array.from({length:required},(_,i)=>i+1).filter(i=>!seqs.has(i)).length;
     record.rawEvents=c.events.length;record.duplicatesSuppressed=c.suppressed;record.duplicateAccepted=c.accepted.length-new Set(c.accepted.map(e=>e.generationId+':'+e.seq)).size;
     record.terminalMessages=c.events.filter(x=>x.event.terminal).length;record.answer=c.accepted.filter(e=>e.payload?.records?.length).at(-1)?.payload.records[0].answer?.trim()||'';
     record.expectedAnswer=complete?expected:'chunk01|chunk02|chunk03|';
     record.expectedTerminal=mode==='cancel'?'cancelled':mode==='timeout'?'timeout':'completed';
     record.passed=record.terminal===record.expectedTerminal&&record.answer===record.expectedAnswer&&record.messageLoss===0&&record.duplicateAccepted===0&&c.errors.length===0;
    }catch(e){record.passed=false;record.error=e.message;}finally{active--;record.errors=c.errors;record.events=c.events;record.accepted=c.accepted;record.connections=c.connections;await c.close().catch(e=>record.closeError=e.message);}
    return record;
   }));
   // Peer-side cancellation may be detected after the user sees the terminal frame.
   await delay(250);const after=await get('/metrics'),calls=after.calls.slice(before.calls.length);
   for(const r of out){r.providerCalls=calls.filter(x=>x.question===r.question);r.modelCalls=r.providerCalls.length;
    r.upstreamCancelled=r.providerCalls.some(x=>x.disconnectMs!==undefined);
    if(['cancel','timeout'].includes(mode))r.passed&&=r.upstreamCancelled;
    if(mode==='reconnect'||mode==='duplicate_replay')r.passed&&=r.modelCalls===1;
    samples.push(r);
   }
   const metric={scenario:name,latencySource:'mock',concurrency,peakClientInFlight:peak,sessions:out.length,passed:out.filter(x=>x.passed).length,failed:out.filter(x=>!x.passed).length,durationMs:performance.now()-started,
    ttftP50Ms:percentile(out.map(x=>x.ttftMs).filter(x=>x!=null),.5),ttftP95Ms:percentile(out.map(x=>x.ttftMs).filter(x=>x!=null),.95),ttftP99Ms:percentile(out.map(x=>x.ttftMs).filter(x=>x!=null),.99),
    totalP95Ms:percentile(out.map(x=>x.totalMs).filter(x=>x!=null),.95),totalP99Ms:percentile(out.map(x=>x.totalMs).filter(x=>x!=null),.99),cancelP95Ms:percentile(out.map(x=>x.cancelMs).filter(x=>x!=null),.95),
    messageLoss:out.reduce((a,x)=>a+(x.messageLoss??0),0),duplicateAccepted:out.reduce((a,x)=>a+(x.duplicateAccepted??0),0),duplicatesSuppressed:out.reduce((a,x)=>a+(x.duplicatesSuppressed??0),0),
    recoverySuccessRate:mode==='reconnect'?out.filter(x=>x.passed).length/out.length:null,modelCalls:calls.length,rawCalls:calls};rows.push(metric);console.log(JSON.stringify({...metric,rawCalls:undefined}));
  }finally{await Promise.all(sessions.map(c=>c.close().catch(()=>{})));}
 }
 try{
  await scenario('warmup',1,'normal');
  for(const c of [1,10,50,100])await scenario('normal-'+c,c,'normal');
  for(const mode of ['cancel','reconnect','duplicate_replay','duplicate_done','timeout'])await scenario(mode,10,mode);
  // Streaming fallback applies to subsequent generations once the circuit opens.
  await get('/reset');const c=client(++user,'fallback-'+user);await c.open();
  const before=await get('/metrics');const generations=[];
  for(let i=0;i<2;i++){
   const start=performance.now(),previous=c.events.length;c.send({type:'generate',requestId:c.chat+'-'+i,content:'unavailable:'+c.chat+'-'+i});
   await wait(()=>c.events.slice(previous).some(x=>x.event.terminal),'streaming fallback');await delay(150);
   generations.push({durationMs:performance.now()-start,events:c.events.slice(previous)});
  }
  await c.close();const calls=(await get('/metrics')).calls.slice(before.calls.length);
  const streaming={scenario:'streaming-next-request-fallback',latencySource:'mock',generations,calls,passed:generations[0].events.some(x=>x.event.event==='error')&&generations[1].events.some(x=>x.event.event==='completed'&&x.event.payload.records[0].answer.trim()===expected)&&calls.length===2&&calls[0].provider==='primary'&&calls[1].provider==='backup',sameRequestStreamingFallback:'N/A: not implemented'};
  fs.writeFileSync(path.join(dir,'streaming-fallback.json'),JSON.stringify(streaming,null,2));
  const routers=[];for(const mode of ['timeout','unavailable','template','circuit'])for(let i=0;i<10;i++){
   const r=await get('/router/'+mode);r.passed=!r.error&&r.primaryCalls===2&&r.backupCalls===(mode==='circuit'?3:1)&&r.results.every(x=>x===(mode==='template'?'template:MOCK_TEMPLATE':'backup:MOCK_OK'))&&(mode!=='timeout'||r.durationMs<500);routers.push(r);
  }
  fs.writeFileSync(path.join(dir,'router-results.json'),JSON.stringify(routers,null,2));
  const replay=samples.find(x=>x.scenario==='reconnect'&&x.passed);if(replay)fs.writeFileSync(path.join(dir,'replay-events.json'),JSON.stringify({initial:replay.events.slice(0,4).map(x=>x.event),replay:replay.events.slice(4).map(x=>x.event),replayAgain:replay.events.slice(4).map(x=>x.event),expected},null,2));
  return {passed:rows.every(r=>r.failed===0)&&streaming.passed&&routers.every(r=>r.passed),rows};
 }finally{
  fs.writeFileSync(path.join(dir,'client-samples.json'),JSON.stringify(samples,null,2));fs.writeFileSync(path.join(dir,'client-summary.json'),JSON.stringify(rows,null,2));
  fs.writeFileSync(path.join(dir,'stomp-frames.jsonl'),rawFrames.map(r=>JSON.stringify(r)).join('\n')+'\n');
 }
}
