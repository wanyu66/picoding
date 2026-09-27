import { main } from './articles.mjs';
main(['cleanup', ...process.argv.slice(2)]).catch(e => { console.error(e.message); process.exitCode = 1; });
