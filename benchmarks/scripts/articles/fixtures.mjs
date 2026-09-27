import fs from 'node:fs';
import path from 'node:path';
import { Mysql, sqlText as q } from './mysql.mjs';
import { validateOnline, fixtureUserPrefix, fixtureCategory, owner } from './sql.mjs';
import { topics, json } from './dataset.mjs';

// Add synthetic reference rows to an existing migrated schema; never create/alter business tables.
export async function fixtures(c, reportDir) {
  validateOnline(c, true);
  fs.mkdirSync(reportDir, { recursive: false });
  let db; const result = { command: 'fixtures', batch: c.batch, status: 'RUNNING', created: [] };
  try {
    db = new Mysql(c.mysql);
    const lock = await db.query(`SELECT GET_LOCK(${q(`pai-bench:${c.mysql.database}`)},0);`);
    if (lock[0] !== '1') throw Error('Another benchmark owns this database');
    const engines = await db.records("SELECT JSON_OBJECT('engine',ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('user','user_info','category');");
    if (engines.length !== 3 || engines.some(x => x.engine !== 'InnoDB')) throw Error('Existing migrated InnoDB fixture tables required');
    const ipColumn = (await db.query("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_info' AND COLUMN_NAME='ip';"))[0] === '1';
    const ids = [];
    for (let n = 0; n < 17; n++) {
      const name = fixtureUserPrefix(c) + String(n).padStart(2, '0'), marker = owner(c) + ':' + n;
      let rows = await db.records(`SELECT JSON_OBJECT('id',CAST(u.id AS CHAR),'marker',u.third_account_id,'profile',i.profile)
       FROM user u LEFT JOIN user_info i ON i.user_id=u.id WHERE u.user_name=${q(name)};`);
      if (!rows.length) {
        await db.query(`START TRANSACTION;
         INSERT INTO user (third_account_id,user_name,login_type,deleted) VALUES (${q(marker)},${q(name)},0,0);
         SET @bench_user_id=LAST_INSERT_ID();
         INSERT INTO user_info (user_id,user_name,profile,deleted${ipColumn ? ',ip' : ''}) VALUES (@bench_user_id,${q(name)},${q(owner(c))},0${ipColumn ? ',JSON_OBJECT()' : ''});
         COMMIT;`);
        rows = await db.records(`SELECT JSON_OBJECT('id',CAST(u.id AS CHAR),'marker',u.third_account_id,'profile',i.profile)
         FROM user u JOIN user_info i ON i.user_id=u.id WHERE u.user_name=${q(name)};`);
        result.created.push(name);
      }
      if (rows.length !== 1 || rows[0].marker !== marker || rows[0].profile !== owner(c)) throw Error('Foreign/incomplete synthetic user collision');
      ids.push(rows[0].id);
    }
    const categories = {};
    for (const [topic] of topics) {
      const name = fixtureCategory(c, topic);
      let rows = await db.records(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'status',status,'deleted',deleted) FROM category WHERE category_name=${q(name)};`);
      if (!rows.length) {
        await db.query(`INSERT INTO category (category_name,status,deleted) VALUES (${q(name)},1,0);`);
        rows = await db.records(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'status',status,'deleted',deleted) FROM category WHERE category_name=${q(name)};`);
        result.created.push(name);
      }
      if (rows.length !== 1 || rows[0].status !== 1 || rows[0].deleted !== 0) throw Error('Synthetic category collision');
      categories[topic] = rows[0].id;
    }
    const ready = { ...c, fixtures: { authorId: ids[0], actorIds: ids.slice(1), categories } };
    fs.writeFileSync(path.join(reportDir, 'config.ready.json'), json(ready), { flag: 'wx' });
    result.status = 'PASS'; result.config = 'config.ready.json';
  } catch (e) { result.status = 'FAIL'; result.error = e.message; process.exitCode = 1; }
  finally { db?.close(); fs.writeFileSync(path.join(reportDir, 'summary.json'), json(result)); }
  return result;
}
