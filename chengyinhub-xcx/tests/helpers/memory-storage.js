'use strict';
module.exports = function memoryStorage() {
  const values = new Map();
  return {
    getStorageSync: key => values.has(key) ? structuredClone(values.get(key)) : '',
    setStorageSync: (key, value) => values.set(key, structuredClone(value)),
    removeStorageSync: key => values.delete(key),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }),
  };
};
