const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// Exercise the actual validator independently of Vue and the author test harness.
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8')
const helpers = source.match(/const CANDIDATE_TYPE[\s\S]*?(?=const STATUS_PENDING)/)[0]
const validate = new Function(`${helpers}\nreturn candidatePreviewItems`)()
const task = {
  id: 31, bizType: 'CONTENT_CAPTURE_REVIEW', bizId: 14, submitterId: 27,
  sourceType: 'content_capture_review_v1', version: 2, status: 0,
  snapshotJson: JSON.stringify({ schemaVersion: 1, contract: 'content_capture_review_v1', snapshotId: 19,
    topicId: 14, submitterMemberId: 27, sourceConfigVersion: 4, contentHash: 'b'.repeat(64),
    candidateContract: 'questify.content-capture-candidate.v1', canonicalizationVersion: 'questify.canonical-content-json.v1',
    completeRelease: false, reviewScope: 'INCOMPLETE_CANDIDATE_ONLY' })
}
const dto = text => ({ contract: 'questify.content-capture-preview.v1', auditTaskId: '31',
  auditTaskVersion: 2, status: 0, snapshotId: '19', topicId: '14', sourceConfigVersion: '4', contentHash: 'b'.repeat(64),
  textEncoding: 'HTML_ESCAPED_PLAIN_TEXT', reviewScope: 'INCOMPLETE_CANDIDATE_TEXT_ONLY', completeRelease: false,
  publicationEligible: false, previewComplete: false, entries: [{ path: 'nodes[0].name', text }] })

for (const [name, text] of [
  ['isolated high', '\ud800'], ['isolated low', '\udc00'], ['terminal high', 'before\udbff'],
  ['initial low', '\udfffafter'], ['high followed by ASCII', '\ud800x'], ['low followed by high', '\udc00\ud800'],
  ['two high surrogates', '\ud800\ud800'], ['two low surrogates', '\udc00\udc00'],
  ['bad surrogate beside valid emoji', '\ud83d\ude00\ud800']
]) test(`independent Unicode guard rejects ${name} before marking text verified`, () => {
  assert.throws(() => validate(dto(text), task), /Invalid frozen candidate preview/)
})

for (const [name, text] of [
  ['emoji', '冻结😀文字'], ['paired scalar boundaries', '\ud800\udc00\udbff\udfff'],
  ['valid whitespace', '  \t文字\n  '], ['server control marker', '[CONTROL]'],
  ['maximum UTF-16 source bound', '😀'.repeat(2048)]
]) test(`independent Unicode guard preserves ${name} exactly`, () => {
  assert.equal(validate(dto(text), task)[0].content, text)
})

test('independent one-pass decoder preserves all five literal nested escape forms', () => {
  const text = '&amp; &lt; &gt; &quot; &#39; | &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;#39; | &amp;amp;lt;'
  assert.equal(validate(dto(text), task)[0].content, '& < > " \' | &amp; &lt; &gt; &quot; &#39; | &amp;lt;')
})

test('independent decoder rejects unknown raw entities but preserves escaped literal spellings', () => {
  for (const text of ['&nbsp;', '&#60;', '&#x3c;', '&apos;', '&AMP;', '&lt', '&']) {
    assert.throws(() => validate(dto(text), task), /Invalid frozen candidate preview/)
    assert.equal(validate(dto(text.replace(/&/g, '&amp;')), task)[0].content, text)
  }
})

test('independent decoder rejects BMP and supplementary format controls while preserving LF and TAB', () => {
  for (const text of ['\u0000', '\u0008', '\u000b', '\u000d', '\u001f', '\u007f', '\u009f', '\u200b', '\u202e', '\u2066', '\u{e0001}']) {
    assert.throws(() => validate(dto(text), task), /Invalid frozen candidate preview/)
  }
})
