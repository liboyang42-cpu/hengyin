const FIELDS = ['name', 'clubType', 'activityPrefs', 'city', 'keywords', 'style', 'description', 'logo', 'cover', 'prioritySignupEnabled', 'memberReservedQuota', 'joinPolicy'];
const LABELS = ['名称', '类型', '活动倾向', '城市', '关键词', '风格', '介绍', '头像', '封面', '优先报名', '保留名额', '入会方式'];
function key(memberId, clubId) {
  return /^[1-9]\d*$/.test(String(memberId || '')) && /^[1-9]\d*$/.test(String(clubId || ''))
    ? 'cy_club_edit_v1:' + memberId + ':' + clubId : '';
}
function form(value) {
  const source = value || {}, result = {};
  FIELDS.forEach(field => { result[field] = source[field] == null ? '' : source[field]; });
  result.activityPrefs = Array.isArray(source.activityPrefs) ? source.activityPrefs.slice() : String(source.activityPrefs || '').split(',').filter(Boolean);
  result.city = source.city || source.address || '';
  result.prioritySignupEnabled = source.prioritySignupEnabled === true || Number(source.prioritySignupEnabled) === 1;
  result.memberReservedQuota = source.memberReservedQuota == null || source.memberReservedQuota === '' ? '' : String(source.memberReservedQuota);
  result.joinPolicy = Number(source.joinPolicy) === 1 ? 1 : 0;
  return result;
}
function base(value) { return JSON.stringify({ updateTime: value.updateTime || '', form: form(value) }); }
function read(storage, storageKey) {
  if (!storageKey) return { state: 'unavailable' };
  try {
    const record = storage.getStorageSync(storageKey);
    if (!record) return { state: 'empty' };
    if (record.schemaVersion !== 1 || typeof record.base !== 'string' || !record.form ||
        !FIELDS.every(field => Object.prototype.hasOwnProperty.call(record.form, field)) ||
        !Array.isArray(record.form.activityPrefs) || !record.form.activityPrefs.every(value => typeof value === 'string') ||
        !FIELDS.filter(field => !['activityPrefs', 'prioritySignupEnabled', 'joinPolicy'].includes(field)).every(field => typeof record.form[field] === 'string') ||
        typeof record.form.prioritySignupEnabled !== 'boolean' || ![0, 1].includes(record.form.joinPolicy)) return { state: 'corrupt' };
    return { state: 'ready', record };
  } catch (e) { return { state: 'error' }; }
}
function write(storage, storageKey, original, value) {
  if (!storageKey) return false;
  try { storage.setStorageSync(storageKey, { schemaVersion: 1, base: original, form: form(value) }); return true; }
  catch (e) { return false; }
}
function remove(storage, storageKey) {
  if (!storageKey) return false;
  try { storage.removeStorageSync(storageKey); return true; } catch (e) { return false; }
}
function compare(saved, latest) {
  const a = form(saved), b = form(latest);
  return FIELDS.filter(field => JSON.stringify(a[field]) !== JSON.stringify(b[field])).map(field => {
    const show = value => field === 'logo' || field === 'cover' ? (value ? '已上传' : '未上传') : (Array.isArray(value) ? value.join('、') : String(value || '未填写'));
    return LABELS[FIELDS.indexOf(field)] + '：草稿「' + show(a[field]) + '」／当前「' + show(b[field]) + '」';
  }).join('\n');
}
module.exports = { key, form, base, read, write, remove, compare };
