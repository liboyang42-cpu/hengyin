import fs from 'node:fs'; import { execSync } from 'node:child_process'; import { open, sleep } from './drv.mjs';
const pages = JSON.parse(fs.readFileSync('/root/j6/pages.json')); const idx = JSON.parse(process.argv[2]);
const cnt = () => +execSync(`. /root/j6/env; sql "select count(*) from ums_member"`, { shell: '/bin/bash', encoding: 'utf8' }).trim();
for (const i of idx) { const b = cnt(); const w = await open({ path: 'pages/index/index', agree: true });
  try { await w.relaunch('/' + pages[i]); } catch (e) {} await sleep(4000);
  await w.shot2('guest/' + String(i).padStart(3,'0') + '-rerun-' + pages[i].replace(/\//g,'_'));
  console.log(i, pages[i], 'newMembers', cnt() - b, '|', (await w.text()).slice(0, 160), '|', (await w.net()).map(x=>x.status+' '+x.url.replace(/^.*__api/,'')).filter(s=>!/features|consents/.test(s)).join(', ').slice(0,200)); await w.close(); }
