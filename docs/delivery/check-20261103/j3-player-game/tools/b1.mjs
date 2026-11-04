import { run, S } from './lib.mjs';
await run({ path: 'pages/play/index?topicId=990015', identity: 'e2e-openid-9004', agree: true, lat: 31.2380, lng: 121.4900 }, async (w) => {
  await S(5000); await w.shot('20-play-open'); await w.say('play');
  await S(4000); await w.shot('21-play-open-2'); await w.say('play2');
  const d = await w.data('');
  console.log('KEYS', Object.keys(d).filter(k=>/look|node|chapter|story|phase|stage|empty/i.test(k)).join(','));
  console.log('LOOK', JSON.stringify(d.lookClass||d.look||''), 'empty', d.emptyKind, d.emptyTip);
});
