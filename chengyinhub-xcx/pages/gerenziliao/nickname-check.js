/**
 * 昵称校验与推荐(N4) · 只消费后端既有合同,不另立规则。
 *
 * 合同来源(ApiUmsMemberController + NicknameSuggestionServiceImpl):
 *   POST /api/user/nickname/check    参数 nickname(表单字段,非 JSON body)
 *        → data: { nickname, available, errorCode, cursor }
 *          errorCode ∈ NICKNAME_INVALID | NICKNAME_TAKEN | null
 *   POST /api/user/nickname/suggest  参数 baseName, cursor(表单字段)
 *        → 成功 data: { nickname, available:true, cursor }
 *        → 失败 code=400 + errorCode ∈ NICKNAME_INVALID | NICKNAME_SUFFIX_EXHAUSTED
 *   服务端规则:trim 后 2–24 个字符,只允许 字母/数字/汉字 与 _ # - . · 空格。
 *   保存仍走 /api/user/update,服务端会再查一遍 —— 这里的结论只是提示,不替代保存校验。
 */

var MIN_LENGTH = 2;
var MAX_LENGTH = 24;

var ERROR_INVALID = 'NICKNAME_INVALID';
var ERROR_TAKEN = 'NICKNAME_TAKEN';
var ERROR_EXHAUSTED = 'NICKNAME_SUFFIX_EXHAUSTED';

// 与服务端同一字符集。Unicode 属性转义在极旧的基础库里可能不被支持:构造失败时
// 本地只查长度,字符集交给服务端判 —— 宁可多问一次后端,也不在客户端误拦合法昵称。
var CHARSET = (function () {
  try {
    return new RegExp('^[\\p{L}\\p{N}_#\\-.· ]+$', 'u');
  } catch (e) {
    return null;
  }
})();

function codePointLength(value) {
  return Array.from(String(value || '')).length;
}

function normalize(value) {
  return typeof value === 'string' ? value.trim() : '';
}

/** 本地先判一遍:返回问题文案;空串表示本地无法判错,需要问服务端。 */
function localIssue(raw) {
  var value = normalize(raw);
  if (!value) return '请输入昵称';
  var length = codePointLength(value);
  if (length < MIN_LENGTH) return '昵称至少 ' + MIN_LENGTH + ' 个字';
  if (length > MAX_LENGTH) return '昵称最多 ' + MAX_LENGTH + ' 个字';
  if (CHARSET && !CHARSET.test(value)) return '昵称只能用文字、数字和 _ # - . · 空格';
  return '';
}

function isOk(res) {
  return !!res && (res.code === 200 || res.code === '200');
}

/**
 * 把 /nickname/check 的响应折成页面状态。
 * 只认后端给的 available / errorCode;拿不到确定结论就是 'error'(可重试),不当作可用。
 */
function readCheckResult(res) {
  if (!isOk(res) || !res.data || typeof res.data !== 'object') {
    return { state: 'error', text: '暂时没查到昵称是否可用，可以重试或直接保存' };
  }
  var data = res.data;
  if (data.available === true) return { state: 'available', text: '这个昵称可以用' };
  if (data.errorCode === ERROR_TAKEN) return { state: 'taken', text: '这个昵称已被占用，换一个试试' };
  if (data.errorCode === ERROR_INVALID) {
    return { state: 'invalid', text: localIssue(data.nickname) || '昵称不符合规则' };
  }
  return { state: 'error', text: '暂时没查到昵称是否可用，可以重试或直接保存' };
}

/** /nickname/suggest 的响应:成功给出候选与下一次的 cursor;失败给出可读原因。 */
function readSuggestResult(res) {
  if (isOk(res) && res.data && typeof res.data === 'object'
      && res.data.available === true && typeof res.data.nickname === 'string' && res.data.nickname) {
    var cursor = Number(res.data.cursor);
    return { ok: true, nickname: res.data.nickname, cursor: Number.isFinite(cursor) ? cursor : null };
  }
  var code = res && res.errorCode;
  if (code === ERROR_EXHAUSTED) return { ok: false, text: '这个名字的候选都被占用了，换个写法试试' };
  if (code === ERROR_INVALID) return { ok: false, text: '先把昵称改成符合规则的写法，再换一个' };
  return { ok: false, text: '暂时没拿到推荐，可以再点一次' };
}

module.exports = {
  MIN_LENGTH: MIN_LENGTH,
  MAX_LENGTH: MAX_LENGTH,
  ERROR_INVALID: ERROR_INVALID,
  ERROR_TAKEN: ERROR_TAKEN,
  ERROR_EXHAUSTED: ERROR_EXHAUSTED,
  normalize: normalize,
  codePointLength: codePointLength,
  localIssue: localIssue,
  readCheckResult: readCheckResult,
  readSuggestResult: readSuggestResult,
};
