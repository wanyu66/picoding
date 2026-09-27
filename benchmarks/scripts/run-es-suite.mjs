import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
const dir=path.resolve(process.argv[2]);
async function run(command,count) {
  const name=command+(count?'-'+count:'');
  console.log('START '+name+' '+new Date().toISOString());
  const logfile=path.join(dir,name+'-suite.log'),fd=fs.openSync(logfile,'a');
  try{await new Promise((resolve,reject)=>{
    const p=spawn(process.execPath,['benchmarks/scripts/es-benchmark.mjs',command,dir,...(count?[String(count)]:[])],{stdio:['ignore',fd,fd]});
    p.on('error',reject);p.on('close',code=>code===0?resolve():reject(Error(name+' failed; see '+logfile)));
  });}finally{fs.closeSync(fd);}
  console.log('DONE '+name+' '+new Date().toISOString());
}
const c=JSON.parse(fs.readFileSync(path.join(dir,'config.json'),'utf8'));
try{
  for(const n of c.sizes) {
    if(!fs.existsSync(path.join(dir,'load-'+n+'.json')))await run('load',n);
    const attempts=fs.readdirSync(dir).filter(x=>new RegExp('^performance-'+n+'(-retry[1-9][0-9]*)?$').test(x));
    const complete=attempts.filter(x=>fs.existsSync(path.join(dir,x,'summary.json')));
    if(complete.length>1)throw Error('Ambiguous completed attempts');
    if(!complete.length) {
      if(attempts.length)throw Error('Partial performance attempt; investigate and select explicit retry suffix');
      await run('performance',n);
    }
  }
  for(const n of c.sizes) {
    const perf=fs.readdirSync(dir).find(x=>new RegExp('^performance-'+n+'(-retry[1-9][0-9]*)?$').test(x)&&fs.existsSync(path.join(dir,x,'summary.json')));
    const metadata=JSON.parse(fs.readFileSync(path.join(dir,perf,'run-metadata.json'),'utf8'));
    if(!metadata.balancedCompleteQueryCycles&&!fs.existsSync(path.join(dir,'ranking-'+n,'summary.json')))await run('ranking',n);
    if(!fs.existsSync(path.join(dir,'relevance-'+n,'manifest.json')))await run('relevance',n);
  }
  if(!fs.existsSync(path.join(dir,'alias/result.json')))await run('alias');
  if(!fs.existsSync(path.join(dir,'recall-probes-10000.json')))await run('probe',10000);
  console.log('Suite complete. Run es-report.mjs; clean up exact owned indices after verification.');
}catch(e){console.error(e.message);process.exitCode=1;}
