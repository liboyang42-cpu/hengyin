'use strict';

function normalizeClientVersion(version) {
  return typeof version === 'string' && /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$/.test(version) ? version : '';
}

function readClientVersion(wxApi) {
  try {
    var info = wxApi.getAccountInfoSync();
    var version = info && info.miniProgram && info.miniProgram.version;
    return normalizeClientVersion(version);
  } catch (unavailable) {
    return '';
  }
}

module.exports = { readClientVersion: readClientVersion, normalizeClientVersion: normalizeClientVersion };
