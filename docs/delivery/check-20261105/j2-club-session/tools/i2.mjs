import { run, S } from './lib.mjs';
// I2: lead opens 团码 on the director page; merchant at the counter: 扫码 a wrong code, then the real 团码, then the same 团码 again
let code = '';
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.tap('团码', { exact: true }); await S(3000); await w.say('code'); await w.shot('119-lead-group-code');
  const n = (await w.net()).filter(x => /groupcode\/issue/.test(x.url)).pop(); code = n.resBody.match(/"code":"(v1\.[^"]+)"/)[1]; console.log('CODE', code.slice(0, 40));
});
for (const [tag, payload] of [['120-merchant-scan-wrong', 'https://example.invalid/not-a-code'], ['121-merchant-scan-group', code], ['122-merchant-scan-again', code]]) {
  await run({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true, scan: payload }, async (w) => {
    await S(3500); await w.scan(payload); await w.tap('扫码'); await S(3500); await w.say(tag); await w.shot(tag);
  });
}
