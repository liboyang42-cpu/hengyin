import { run, S } from './lib.mjs';
// H3: lead issues the 团码 (page), merchant scans it at the counter (merchant home 扫码)
let code = '';
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.tap('团码', { exact: true }); await S(3000);
  const n = (await w.net()).filter(x => /groupcode\/issue/.test(x.url)).pop(); code = n.resBody.match(/"code":"(v1\.[^"]+)"/)[1]; console.log('CODE', code.slice(0, 40));
});
await run({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true, scan: code }, async (w) => {
  await S(3500); await w.say('mhome'); await w.shot('125-merchant-home-running');
  await w.scan(code); const t = await w.text(); const m = t.match(/扫码核销|扫一扫|扫码/); console.log('SCANBTN', m && m[0]);
  await w.tap(m ? m[0] : '扫码'); await S(3500); await w.say('scanned'); await w.shot('126-merchant-scanned-group');
});
