const DAY = 24 * 60 * 60 * 1000;
function key(memberId) {
  const id = String(memberId || '').trim();
  return /^[1-9]\d*$/.test(id) ? 'phone-reminder:snooze:' + id : '';
}
function shouldPrompt(api, memberId, now) {
  const storageKey = key(memberId);
  if (!storageKey) return false;
  try {
    const until = api.getStorageSync(storageKey);
    return typeof until !== 'number' || !Number.isFinite(until) || (now == null ? Date.now() : now) >= until;
  } catch (_) { return true; }
}
function snooze(api, memberId, now) {
  const storageKey = key(memberId);
  if (!storageKey) return false;
  try { api.setStorageSync(storageKey, (now == null ? Date.now() : now) + DAY); return true; }
  catch (_) { return false; }
}
module.exports = { shouldPrompt, snooze };
