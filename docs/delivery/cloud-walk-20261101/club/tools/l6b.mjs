import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(3500);
  const d = await w.data('topics'); console.log('topics', JSON.stringify((d||[]).map(t => [t.id, t.name])));
  const idx = (d||[]).findIndex(t => t.id == 990018);
  await w.page.evaluate((i) => { const p = getCurrentPages().slice(-1)[0]; if (p.onTopicChange) p.onTopicChange({ detail: { value: i } }); }, idx);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onStartTimeChange({ detail: { value: '14:00' } }));
  await S(500); await w.scroll(600); await S(400);
  await ty(w, '沿用票种', '8');
  await w.shot('71-lead-open-co-session'); await txt(w,'form');
  await w.scroll(800); await S(500); await w.tap('创建系列场次'); await S(3000); await txt(w,'after'); await w.shot('72-lead-co-session-created');
}
