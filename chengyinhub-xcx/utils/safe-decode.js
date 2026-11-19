// URL 参数安全解码:坏的 % 序列不抛 URIError,失败回退原值(PG-003;原 publish/temp 的 safeDecode)。
function safeDecode(value) {
  try { return decodeURIComponent(String(value || '')); } catch (error) { return String(value || ''); }
}

module.exports = { safeDecode };
