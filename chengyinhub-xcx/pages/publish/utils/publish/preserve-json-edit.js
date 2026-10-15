'use strict';

// 编辑器可补齐缺省值供显示；写回时只应用用户修改过的字段。
function preserve(raw, before, changed, serialized) {
  if (JSON.stringify(before) === JSON.stringify(changed)) return raw;
  const source = typeof raw === 'string' ? JSON.parse(raw) : raw;
  const saved = typeof serialized === 'string' ? JSON.parse(serialized) : serialized;
  const patch = (original, oldValue, newValue, savedValue) => {
    if (JSON.stringify(oldValue) === JSON.stringify(newValue)) return original;
    if (Array.isArray(oldValue) && Array.isArray(newValue) && Array.isArray(original)) {
      // 稳定身份跟随条目移动；加删/重排不能把未知字段和旧 override 留给另一行。
      const identity = ['id', 'key', 'planKey', 'url'].find(key => [oldValue, newValue, original].every(rows =>
        rows.every(row => row && typeof row === 'object' && typeof row[key] === 'string' && row[key])
        && new Set(rows.map(row => row[key])).size === rows.length));
      if (identity) return newValue.map((entry, index) => {
        const before = oldValue.find(row => row[identity] === entry[identity]);
        const source = original.find(row => row[identity] === entry[identity]);
        return patch(source, before, entry, savedValue && savedValue[index]);
      });
      if (oldValue.length === newValue.length && original.length === newValue.length)
        return original.map((entry, index) => patch(entry, oldValue[index], newValue[index], savedValue && savedValue[index]));
    }
    if (oldValue && newValue && typeof oldValue === 'object' && typeof newValue === 'object'
        && !Array.isArray(oldValue) && !Array.isArray(newValue)) {
      const result = Object.assign({}, original && typeof original === 'object' ? original : {});
      new Set(Object.keys(oldValue).concat(Object.keys(newValue))).forEach((key) => {
        const value = patch(result[key], oldValue[key], newValue[key], savedValue && savedValue[key]);
        if (value === undefined) delete result[key];
        else result[key] = value;
      });
      return result;
    }
    return savedValue;
  };
  return JSON.stringify(patch(source, before, changed, saved));
}

module.exports = preserve;
