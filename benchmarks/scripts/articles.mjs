import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { generate, validateDataset } from './articles/dataset.mjs';
import { workflow } from './articles/workflow.mjs';
import { fixtures } from './articles/fixtures.mjs';

export async function main(args = process.argv.slice(2)) {
  const [command, ...rest] = args;
  if (!['fixtures', 'generate', 'import', 'sync', 'verify', 'cleanup'].includes(command)) throw Error('Usage: node benchmarks/scripts/articles.mjs fixtures|generate|import|sync|verify|cleanup --config FILE [--data DIRECTORY] [--report NEW_DIRECTORY]');
  const opts = {};
  for (let i = 0; i < rest.length; i += 2) {
    if (!['--config', '--data', '--report', '--env-file'].includes(rest[i]) || !rest[i + 1] || opts[rest[i]]) throw Error('Invalid/duplicate CLI argument');
    opts[rest[i]] = rest[i + 1];
  }
  if (!opts['--config'] || (command !== 'fixtures' && !opts['--data'])) throw Error('--config and --data are required (fixtures needs only config/report)');
  const c = JSON.parse(fs.readFileSync(opts['--config'], 'utf8')); validateDataset(c);
  if (opts['--env-file']) {
    const source = parseEnv(fs.readFileSync(opts['--env-file'], 'utf8'));
    // Whitelist only database/ES credentials; never load model API keys or dump the file.
    const password = source[c.mysql.passwordEnv] || source.PAICODING_DB_PASSWORD;
    if (password) process.env[c.mysql.passwordEnv] = password;
    if (source[c.es.apiKeyEnv]) process.env[c.es.apiKeyEnv] = source[c.es.apiKeyEnv];
  }
  if (command === 'fixtures') {
    if (!opts['--report']) throw Error('fixtures requires new --report directory');
    const report = path.resolve(opts['--report']); fs.mkdirSync(path.dirname(report), { recursive: true });
    console.log(JSON.stringify(await fixtures(c, report), null, 2)); return;
  }
  const data = path.resolve(opts['--data']);
  if (command === 'generate') {
    fs.mkdirSync(path.dirname(data), { recursive: true });
    console.log(JSON.stringify(await generate(c, data), null, 2)); return;
  }
  if (!opts['--report']) throw Error('Online commands require a new --report directory');
  const report = path.resolve(opts['--report']);
  fs.mkdirSync(path.dirname(report), { recursive: true });
  console.log(JSON.stringify(await workflow(command, c, data, report), null, 2));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(e => { console.error(e.message); process.exitCode = 1; });
}
