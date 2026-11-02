import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('104a-member-waitlist-offer-view'); await txt(w,'full');
   await w.shot('104-member-waitlist-offered'); await txt(w,'wl');
  for (const k of ['ticketSoldOut','waitlistState','waitlistEligibility','waitlistPaymentText','signupClosed']) console.log(k, JSON.stringify(await w.data(k)));
}

