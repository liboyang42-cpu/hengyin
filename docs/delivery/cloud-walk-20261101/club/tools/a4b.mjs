import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(3500);
  await w.tapSel('.manage-now-btn'); await S(2000);
  console.log('show', JSON.stringify(await w.data('choiceSheetShow')), JSON.stringify(await w.data('choiceSheetItems')));
  await w.shot('19-publish-choice'); await txt(w,'after');
}
