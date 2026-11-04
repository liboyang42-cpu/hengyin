import { run, S } from './lib.mjs';
// D8: lead sends the terms invitation (the page the 合作 list's 「去发邀约」 opens; the 招商 layer offers no such step)
await run({ path: 'pages/coop/invite/index?type=0&toId=9002&toName=' + encodeURIComponent('探店日样板咖啡'), identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.shot('62-lead-coop-invite');
  await w.tap('选择我发布的主题'); await S(1500); await w.say('pick'); await w.shot('63-lead-coop-pick-theme');
  await w.tap('人民广场 · 喷泉报时'); await S(1500);
  await w.say('picked'); await w.shot('64-lead-coop-ready');
  await w.tap('发送邀约(1)'); await S(3000); await w.say('sent'); await w.shot('65-lead-coop-sent');
});
