import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('56-member-full-waitlist'); await txt(w,'full');
  await w.tap('重新加入候补'); await S(2500); try{await w.modal('confirm');}catch(e){} await S(2000); await w.shot('57-member-waitlist-joined'); await txt(w,'wl');
  for (const k of ['ticketSoldOut','waitlistState','waitlistEligibility','waitlistPaymentText','signupClosed']) console.log(k, JSON.stringify(await w.data(k)));
}
