import { spawn } from 'node:child_process';
import crypto from 'node:crypto';

// Hex UTF-8 literals avoid shell, SQL-mode and quote/backslash interpolation.
export const sqlText = value => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
export function integer(value) {
  if (!/^[1-9][0-9]*$/.test(String(value))) throw Error('Expected positive integer identifier');
  return String(value);
}
export class Mysql {
  constructor(c) {
    const password = process.env[c.passwordEnv];
    if (!password) throw Error(`Set ${c.passwordEnv} in the process environment`);
    this.child = spawn(c.executable, ['--no-defaults', '--batch', '--raw', '--skip-column-names', '--silent', '--unbuffered',
      '--binary-mode', '--local-infile=0', '--default-character-set=utf8mb4', '--connect-timeout=10',
      '--host', c.host, '--port', String(c.port), '--user', c.user, c.database],
    { shell: false, windowsHide: true, env: { ...process.env, MYSQL_PWD: password }, stdio: ['pipe', 'pipe', 'pipe'] });
    this.buffer = ''; this.pending = null; this.closed = false;
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', chunk => {
      this.buffer += chunk;
      let at;
      while ((at = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, at).replace(/\r$/, ''); this.buffer = this.buffer.slice(at + 1);
        if (!this.pending) continue;
        if (line === this.pending.marker) {
          const p = this.pending; this.pending = null; clearTimeout(p.timer); p.resolve(p.lines);
        } else this.pending.lines.push(line);
      }
    });
    // Retain only error number, never SQL payload, user names or credentials in reports.
    this.child.stderr.on('data', b => { this.errorCode = /ERROR\s+(\d+)/.exec(b.toString())?.[1] ?? this.errorCode; });
    const fail = () => {
      this.closed = true;
      if (this.pending) { const p = this.pending; this.pending = null; clearTimeout(p.timer); p.reject(Error(`mysql exited (${this.errorCode ?? 'connection/process error'}); current transaction rolled back; inspect schema/privileges`)); }
    };
    this.child.on('error', fail); this.child.on('exit', fail);
    this.child.stdin.on('error', fail);
  }
  query(sql) {
    if (this.closed || this.pending) return Promise.reject(Error('mysql unavailable or concurrent query'));
    return new Promise((resolve, reject) => {
      const marker = `BENCH_${crypto.randomBytes(16).toString('hex')}`;
      const timer = setTimeout(() => { this.child.kill(); }, 120000);
      this.pending = { marker, resolve, reject, timer, lines: [] };
      this.child.stdin.write(`${sql}\nSELECT '${marker}';\n`);
    });
  }
  async records(sql) { return (await this.query(sql)).filter(Boolean).map(x => JSON.parse(x)); }
  close() { this.child.stdin.end(); this.child.kill(); }
}
