import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {queryBody,statistics,document} from './es-benchmark.mjs';
import {articles} from './articles/dataset.mjs';
const contract=JSON.parse(fs.readFileSync('benchmarks/build/es/contract.json','utf8'));
test('B preserves actual Java DSL except explicitly frozen date and requested size',()=>{
  const actual=queryBody(contract,'body','B','2026-01-01T00:00:00.000Z',10);
  actual.query.function_score.functions.find(f=>f.gauss).gauss.createTime.origin='now';
  assert.deepEqual(actual,contract.queries.body);
  assert.equal(actual.query.function_score.functions.length,4);
  assert.equal(actual.query.function_score.functions[0].field_value_factor.field,'qualityScore');
});
test('A isolates strategy without changing projection, filters, sort or highlights',()=>{
  const a=queryBody(contract,'body','A','2026-01-01T00:00:00.000Z');
  const b=queryBody(contract,'body','B','2026-01-01T00:00:00.000Z');
  for(const k of ['sort','highlight','_source','size','track_total_hits'])assert.deepEqual(a[k],b[k]);
  assert.deepEqual(a.query.bool.filter,b.query.function_score.query.bool.filter);
  assert.equal(a.query.bool.must[0].multi_match.query,'validate recovery');
  assert.ok(!a.query.function_score);
});
test('pagination uses exactly one of offset and cursor and never mutates contract',()=>{
  const original=JSON.stringify(contract);
  const x=queryBody(contract,'body','B','2026-01-01T00:00:00.000Z',10,9990,[1,2,3]);
  assert.ok(!('from' in x));assert.deepEqual(x.search_after,[1,2,3]);
  assert.equal(queryBody(contract,'body','B','2026-01-01T00:00:00.000Z',10,9990).from,9990);
  assert.equal(JSON.stringify(contract),original);
});
test('throughput includes drain time, errors are not silently removed from latency',()=>{
  const x=statistics([{latencyMs:10,ok:true,tookMs:5},{latencyMs:100,ok:false}],2);
  assert.equal(x.qps,1);assert.equal(x.successQps,.5);assert.equal(x.errorRate,.5);
  assert.equal(x.p99Ms,100);assert.equal(x.meanMs,55);assert.equal(x.meanEsTookMs,5);
});
test('deterministic prefixes and generated quality score match documented production formula',()=>{
  const spec={count:100,seed:20260924,batch:'test-es-fixtures',referenceTime:'2026-01-01T00:00:00.000Z'};
  const a=[...articles(spec)];const b=articles({...spec,count:10000});
  for(const row of a){assert.deepEqual(b.next().value,row);const d=document(row);assert.equal(Number(d.qualityScore),1+5*row.creamStat+3*row.officalStat+2*row.toppingStat);}
});
