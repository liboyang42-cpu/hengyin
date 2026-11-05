import { run, S } from './lib.mjs';
// D8a: coop list 我发出的 tab; D8b: open the terms invitation by its URL (the page 确认 opened in D4), pick 据点, send 引流合作
if (!process.env.SKIP_A) await run({ path: 'pages/coop/list/index', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3000); await w.tap('我发出的'); await S(2500); await w.say('sent-tab'); await w.shot('50-coop-list-sent-tab');
});
await run({ path: 'pages/coop/invite/index?type=0&toId=9002&toName=' + encodeURIComponent('探店日样板咖啡') + '&topicId=990014', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3500); await w.say('invite'); await w.shot('51-lead-invite-by-url');
  await w.tap('请选择一个据点'); await S(1500); await w.say('station'); await w.shot('52-lead-invite-station-sheet');
  await w.tap('人民广场音乐喷泉', { nth: -1 }); await S(1200); await w.say('picked'); await w.shot('53-lead-invite-ready');
  await w.tap('发送邀约(1)'); await S(2500); await w.shot('54-lead-invite-sheet');
  await w.page.evaluate(() => { const f = (r) => { for (const e of r.querySelectorAll('*')) { if (e.classList && e.classList.contains('iv-send')) return e; if (e.shadowRoot) { const x = f(e.shadowRoot); if (x) return x; } } return null; }; const b = f(document); b && b.scrollIntoView({ block: 'center' }); });
  await S(800); await w.shot('55-lead-invite-sheet-bottom'); await w.tapSel('.iv-send'); await S(3500); await w.say('sent'); await w.shot('56-lead-invite-sent');
});
