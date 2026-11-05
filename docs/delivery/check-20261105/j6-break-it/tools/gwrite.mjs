// Guest write → login prompt → 微信登录 → where does the user land, and did the write happen once?
import { open, sleep } from '/root/j6/drv.mjs';
const cases = [
 ['g01-topic-buy','pages/topic/index/index?id=990014',['查看详情与报名','立即支付']],
 ['g02-activity-join-club','pages/activity/detail/index?id=12',['去加入俱乐部',['加入',{exact:true}]]],
 ['g03-club-join','pages/club/detail/index?id=7002',[['加入',{exact:true}]]],
 ['g04-club-login-row','pages/club/detail/index?id=7002',[['登录',{exact:true}]]],
 ['g05-team-join','pages/team/join/index?code=<invite-code>',['接受邀请并加入']],
 ['g06-talent-join','pages/talent/list/index',[['免费加入',{nth:0}]]],
 ['g07-clerk-invite','pages/merchant/team/index?invite=<invite-token-redacted>',[['登录',{exact:true}]]],
 ['g08-publish-tab','pages/index/index',['和朋友报名活动']],
];
const only = process.argv[2];
for (const [k,p,taps] of cases) {
  if (only && !k.startsWith(only)) continue;
  const w = await open({ path: p, agree: true }); await sleep(3500);
  await w.shot2(k + '-a-guest'); const n0 = (await w.net()).length;
  for (const t of taps) { try { await (Array.isArray(t) ? w.tap(t[0], t[1]) : w.tap(t)); } catch (e) { console.log(k, 'TAPFAIL', JSON.stringify(t)); } await sleep(1500); }
  const prompt = await w.text(); await w.shot2(k + '-b-prompt');
  const hasPrompt = /登录后继续/.test(prompt);
  if (hasPrompt) { try { await w.tap('微信登录', { exact: true }); } catch (e) { console.log(k, 'no 微信登录'); } }
  await sleep(4500); await w.shot2(k + '-c-after-login');
  const net = (await w.net()).slice(n0).map(x => `${x.status} ${x.url.replace(/^.*__api/, '')} ${(x.reqBody||'').slice(0,60)} => ${String(x.resBody||'').slice(0,90)}`).filter(s => !/config\/features|compliance|banner|\.(png|jpg)/.test(s));
  console.log('##', k, '| prompt:', hasPrompt, '| route after:', (await w.route()).join('>'));
  console.log('   prompt text:', prompt.slice(0, 160));
  console.log('   after text :', (await w.text()).slice(0, 300));
  console.log('   net:\n     ' + net.join('\n     '));
  console.log('   errors:', JSON.stringify(w.errors()).slice(0, 300));
  await w.close();
}
