// Page-state units isolate transport. Real canonical HTTP + bundle integration is
// covered separately by community-post-cutover-contract/page-integration tests.
module.exports = {
  createClient(app) {
    return { request: options => app.sendRequest(options), captureUpload: data => data.url, share() {} };
  }
};

// Explicitly scoped to old page-state harnesses; restore the real module afterwards.
module.exports.loadPage = function loadPage(modulePath) {
  const adapter = require.resolve('../../utils/community-post-api.js');
  const previous = require.cache[adapter];
  require.cache[adapter] = { id: adapter, filename: adapter, loaded: true, exports: module.exports };
  try { delete require.cache[require.resolve(modulePath)]; return require(modulePath); }
  finally { if (previous) require.cache[adapter] = previous; else delete require.cache[adapter]; }
};
