import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(3500); await w.tap('接受合作'); await S(1500); await w.shot('47-lead-invite-confirm'); await txt(w,'confirm');
  try { await w.modal('confirm'); } catch (e) { console.log('no wx modal'); try { await w.tap('确认接受'); } catch (e2) { try { await w.tap('接受', {exact:true}); } catch(e3){} } }
  await S(3000); await w.shot('48-lead-invite-accepted'); await txt(w,'after');
}
