import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const root='benchmarks/results/es';
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);}
const files=[...walk(root).filter(p=>!p.endsWith('artifact-inventory.json')&&!p.endsWith('rebuild-input.jsonl')),
  'benchmarks/README.md','benchmarks/.gitignore','benchmarks/es-test-plan.md','benchmarks/es-operations.md',
  'benchmarks/config/es-benchmark.json','benchmarks/config/es-logback.xml',...walk('benchmarks/java'),
  ...fs.readdirSync('benchmarks/scripts').filter(p=>/^(.*es.*\.mjs)$/.test(p)&&!p.startsWith('articles')).map(p=>'benchmarks/scripts/'+p)];
const rows=[];
for(const file of [...new Set(files)].sort()) {
  const hash=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(file))hash.update(chunk);
  rows.push({path:file.replaceAll('\\','/'),bytes:fs.statSync(file).size,sha256:hash.digest('hex')});
}
fs.writeFileSync(root+'/artifact-inventory.json',JSON.stringify({at:new Date().toISOString(),files:rows,
  excluded:['benchmarks/build (generated classes/dependency paths)','alias/rebuild-input.jsonl (regenerable fixture; exact input hash in fixture-manifest.json)','artifact-inventory.json (self)']},null,2)+'\n');
console.log(JSON.stringify({files:rows.length,bytes:rows.reduce((s,r)=>s+r.bytes,0)}));
