// Guest sweep v3: fresh guest browser per page (privacy agreed), wx.login + /api/login/code watched, walk-guest member count read back.
import fs from 'node:fs'; import { execSync } from 'node:child_process'; import { open, sleep } from './drv.mjs';
const pages = JSON.parse(fs.readFileSync('/root/j6/pages.json'));
const [a, b] = [+(process.argv[2] || 0), +(process.argv[3] || pages.length)]; const outF = process.argv[4] || '/root/j6/guest3.json';
const cnt = () => +execSync(`. /root/j6/env; sql "select count(*) from ums_member where wx_open_id like 'walk-guest-%'"`, { shell: '/bin/bash', encoding: 'utf8' }).trim();
const out = [];
for (let i = a; i < b; i++) {
  const p = pages[i]; const before = cnt();
  let w; try { w = await open({ path: 'pages/index/index', agree: true }); } catch (e) { out.push({ i, p, err: 'launch ' + e.message }); continue; }
  await w.eval(`(()=>{window.__wxl=0;const o=wx.login;wx.login=function(){window.__wxl++;return o.apply(this,arguments)}})()`);
  const n0 = (await w.net()).length, e0 = w.errors().length, c0 = w.console().length;
  try { await w.relaunch('/' + p); } catch (e) {}
  await sleep(3000);
  const net = (await w.net()).slice(n0).map(x => `${x.status} ${x.method} ${x.url.replace(/^.*\/__api/, '')}`);
  const wxl = await w.eval('window.__wxl').catch(() => -1);
  const text = (await w.text()).slice(0, 500);
  const shot = 'guest/' + String(i).padStart(3, '0') + '-' + p.replace(/\//g, '_');
  await w.shot2(shot);
  const logged = await w.eval('!!(getApp().getUserID && getApp().getUserID())').catch(() => null);
  out.push({ i, p, route: (await w.route()).join('>'), text, net, wxLogin: wxl, loggedIn: logged, newGuestMembers: cnt() - before, errs: w.errors().slice(e0), cons: w.console().slice(c0).filter(x => x.startsWith('[error]') && !/oss-rig|404 \(Not Found|ERR_/.test(x)) });
  fs.writeFileSync(outF, JSON.stringify(out, null, 1));
  await w.close();
}
