const FIELDS = ['name', 'clubType', 'activityPrefs', 'city', 'keywords', 'style', 'description', 'logo', 'cover', 'prioritySignupEnabled', 'memberReservedQuota', 'joinPolicy'];
const LABELS = ['名称', '类型', '活动倾向', '城市', '关键词', '风格', '介绍', '头像', '封面', '优先报名', '保留名额', '入会方式'];
function key(memberId, clubId) {
  const id = value => (typeof value === 'string' || (typeof value === 'number' && Number.isSafeInteger(value))) && /^[1-9]\d*$/.test(String(value));
  return id(memberId) && id(clubId)
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
function base(value) { return JSON.stringify({ updateTime: typeof value.updateTime === 'string' ? value.updateTime : '', form: form(value) }); }
function validForm(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value) &&
    FIELDS.every(field => Object.prototype.hasOwnProperty.call(value, field)) &&
    Array.isArray(value.activityPrefs) && value.activityPrefs.every(item => typeof item === 'string') &&
    FIELDS.filter(field => !['activityPrefs', 'prioritySignupEnabled', 'joinPolicy'].includes(field))
      .every(field => typeof value[field] === 'string') &&
    typeof value.prioritySignupEnabled === 'boolean' && [0, 1].includes(value.joinPolicy);
}
function validBase(value) {
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed.updateTime === 'string' && validForm(parsed.form);
  } catch (e) { return false; }
}
function read(storage, storageKey) {
  if (!storageKey) return { state: 'unavailable' };
  try {
    const record = storage.getStorageSync(storageKey);
    if (record == null || record === '') return { state: 'empty' };
    if (typeof record !== 'object' || Array.isArray(record) || record.schemaVersion !== 1 ||
        typeof record.base !== 'string' || !validBase(record.base) || !validForm(record.form)) return { state: 'corrupt' };
    return { state: 'ready', record: JSON.parse(JSON.stringify(record)) };
  } catch (e) { return { state: 'error' }; }
}
// expected is the exact previously observed record; another editor's newer record is never overwritten.
function matches(found, expected) {
  return expected === undefined || (found.state === 'empty' ? expected === '' :
    found.state === 'ready' && JSON.stringify(found.record) === expected);
}
function write(storage, storageKey, original, value, expected) {
  if (!storageKey || !validBase(original)) return false;
  try {
    const found = read(storage, storageKey);
    if (!['empty', 'ready'].includes(found.state) || !matches(found, expected)) return false;
    const normalized = form(value);
    if (!validForm(normalized)) return false;
    storage.setStorageSync(storageKey, { schemaVersion: 1, base: original, form: normalized });
    return true;
  } catch (e) { return false; }
}
function remove(storage, storageKey, expected) {
  if (!storageKey) return false;
  try {
    const found = read(storage, storageKey);
    if (!['empty', 'ready'].includes(found.state) || !matches(found, expected)) return false;
    if (found.state === 'ready') storage.removeStorageSync(storageKey);
    return true;
  } catch (e) { return false; }
}
function compare(saved, latest) {
  const a = form(saved), b = form(latest);
  return FIELDS.filter(field => JSON.stringify(a[field]) !== JSON.stringify(b[field])).map(field => {
    const show = value => field === 'logo' || field === 'cover' ? (value ? '已上传' : '未上传') : (Array.isArray(value) ? value.join('、') : (value === false ? '关闭' : value === true ? '开启' : String(value === '' || value == null ? '未填写' : value)));
    return LABELS[FIELDS.indexOf(field)] + '：草稿「' + show(a[field]) + '」／当前「' + show(b[field]) + '」';
  }).join('\n');
}
module.exports = { key, form, base, read, write, remove, compare };
