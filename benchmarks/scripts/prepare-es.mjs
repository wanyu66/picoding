import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
const out = path.resolve('benchmarks/build/es');
fs.mkdirSync(out, {recursive:true});
const java = path.resolve(process.env.JAVA8_HOME || '.runtime/java8', 'bin');
const executable = name => path.join(java, name + (process.platform === 'win32' ? '.exe' : ''));
const deps = process.env.PAI_BENCH_CLASSPATH_FILE || 'paicoding-web/target/model-test-classpath.txt';
const cp = ['paicoding-api','paicoding-core','paicoding-service','paicoding-web'].map(p=>path.resolve(p,'target/classes'))
  .concat(fs.readFileSync(deps,'utf8').trim().split(path.delimiter));
for(const p of cp) if(!fs.existsSync(p)) throw Error('Missing compiled dependency: '+p);
const urls = cp.map(p=>pathToFileURL(p).href+(fs.statSync(p).isDirectory()?'/':''));
const line = 'Class-Path: '+urls.join(' ');
let manifest='Manifest-Version: 1.0\r\n';
for(let i=0;i<line.length;i+=68) manifest+=(i?' ':'')+line.slice(i,i+68)+'\r\n';
fs.writeFileSync(path.join(out,'MANIFEST.MF'),manifest+'\r\n');
function run(name,args,log) {
  const r=spawnSync(executable(name),args,{encoding:'utf8',maxBuffer:8e6});
  fs.writeFileSync(path.join(out,log),(r.stdout||'')+(r.stderr||''));
  if(r.status!==0) throw Error(name+' failed: '+log);
}
run('java',['-version'],'java-version.txt');
if(!fs.readFileSync(path.join(out,'java-version.txt'),'utf8').includes('1.8.0')) throw Error('Java 8 required');
run('jar',['cfm',path.join(out,'classpath.jar'),path.join(out,'MANIFEST.MF')],'jar.log');
const source='paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java';
const compileArgs=['-encoding','UTF-8','-cp',cp.join(path.delimiter),'-d',out,source,
  'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java',
  ...fs.readdirSync('benchmarks/java').filter(p=>p.endsWith('.java')).map(p=>'benchmarks/java/'+p)];
fs.writeFileSync(path.join(out,'javac.args'),compileArgs.map(x=>'"'+x.replaceAll('\\','/')+'"').join('\n'));
run('javac',['-J-Dfile.encoding=UTF-8','@'+path.join(out,'javac.args')],'compile.log');
run('java',['-cp',out+path.delimiter+path.join(out,'classpath.jar'),'SearchContract',
  process.argv[2]||'benchmarks/config/es-benchmark.json',path.join(out,'contract.json')],'export.log');
const tracked=[source,'paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java',deps,
  ...fs.readdirSync('benchmarks/java').filter(p=>p.endsWith('.java')).map(p=>'benchmarks/java/'+p)];
fs.writeFileSync(path.join(out,'build-provenance.json'),JSON.stringify({javaVersion:fs.readFileSync(path.join(out,'java-version.txt'),'utf8'),
  files:Object.fromEntries(tracked.map(p=>[p,crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]))},null,2)+'\n');
console.log('Compiled current search service with Java 8; exported '+path.join(out,'contract.json'));
