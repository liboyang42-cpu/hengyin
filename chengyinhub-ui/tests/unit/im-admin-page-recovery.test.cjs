const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const root = path.resolve(__dirname, '../..')
function harness() {
  const storage = new Map(); let token = 'session-a'; const sends = []; const lists = []; const receipts = []
  const box = { window: { sessionStorage: { getItem: k => storage.get(k) || null, setItem: (k,v) => storage.set(k,v), removeItem: k => storage.delete(k) }, crypto: { randomUUID: () => 'request_0001' } },
    getImSendReceipt: data => new Promise(resolve => receipts.push({data, resolve})),
    getToken: () => token, sendImMessage: data => new Promise(resolve => sends.push({data, resolve})),
    listImConversations: () => new Promise(resolve => lists.push(resolve)), listImMessages: () => Promise.resolve({data:{list:[]}}),
    CyEmptyState: {}, CyPageHeader: {}, CyResourceState: {} }
  vm.createContext(box)
  vm.runInContext(fs.readFileSync(path.join(root,'src/utils/im-admin-send-intent.js'),'utf8').replace(/export /g,''),box)
  const script = fs.readFileSync(path.join(root,'src/views/business/im/index.vue'),'utf8').split('<script>')[1].split('</script>')[0]
  vm.runInContext(script.replace(/^import .*$/gm,'').replace('export default','component ='),box)
  const page = Object.assign(box.component.data(), {$store:{state:{user:{id:'1'}}}, $modal:{msgError(){}}, $nextTick(){}, parseTime(){return ''}})
  for (const [key, fn] of Object.entries(box.component.methods)) page[key] = fn.bind(page)
  page.current = {conversationId:'10',memberId:'2'}
  return {page,sends,lists,receipts, component:box.component, switchAccount() {token='session-b'; page.$store.state.user.id='3'; box.component.watch['$store.state.user.token'].call(page)} }
}
test('actual page duplicate send waits and unknown reentry keeps original payload', async () => {
  const h=harness(); h.page.sendText='original'; const promise=h.page.doSend()
  h.page.doSend(); assert.equal(h.sends.length,1)
  h.sends[0].resolve({code:500}); await promise
  h.page.sendText='changed'; const retry=h.page.doSend()
  assert.equal(h.sends[1].data.content,'original'); assert.equal(h.sends[1].data.requestId,h.sends[0].data.requestId)
  h.sends[1].resolve({code:500}); await retry
})
test('actual page ignores old account send acknowledgement', async () => {
  const h=harness(); h.page.sendText='private'; const promise=h.page.doSend()
  h.switchAccount(); h.sends[0].resolve({code:200,data:'9007199254740993'}); await promise
  assert.equal(h.page.current,null); assert.equal(h.page.sendText,''); assert.equal(h.lists.length,0)
})
test('actual page ignores old account conversation read', async () => {
  const h=harness(); const promise=h.page.loadConvs(); h.switchAccount()
  h.lists[0]({data:[{conversationId:'secret'}]}); await promise
  assert.equal(h.page.convs.length,0); assert.equal(h.page.convsLoaded,false)
})
test('switching recipients clears drafts and restores only recipient pending intent', async () => {
  const h=harness(); h.page.sendText='private'; const promise=h.page.doSend()
  await h.page.selectConv({conversationId:'11',memberId:'4'}); assert.equal(h.page.sendText,'')
  h.sends[0].resolve({code:500}); await promise
  await h.page.selectConv({conversationId:'10',memberId:'2'}); assert.equal(h.page.sendText,'private')
  assert.equal(h.page.pendingSend.requestId,'request_0001')
})

async function unknownSend(h) {
  h.page.sendText='original'; const sent=h.page.doSend()
  h.sends[0].resolve({code:500}); await sent
}
test('negative receipt preserves frozen request and causes zero sends', async () => {
  const h=harness(); await unknownSend(h)
  const pending=h.page.pendingSend; const query=h.page.checkSendReceipt()
  h.receipts[0].resolve({code:200,data:{found:false}}); await query
  assert.equal(h.page.pendingSend,pending); assert.equal(h.page.sendText,'original')
  assert.equal(h.sends.length,1); assert.match(h.page.sendRecoveryError,/仍待确认/)
})
test('positive matching receipt clears only original intent and does not send', async () => {
  const h=harness(); await unknownSend(h); const query=h.page.checkSendReceipt()
  h.receipts[0].resolve({code:200,data:{found:true,requestId:'request_0001',memberId:'2',
    messageId:'9007199254740993',msgType:1,content:'original',extraJson:null,status:0}})
  await query; assert.equal(h.page.pendingSend,null); assert.equal(h.page.sendText,''); assert.equal(h.sends.length,1)
})
test('mismatched receipt cannot release frozen input', async () => {
  const h=harness(); await unknownSend(h); const query=h.page.checkSendReceipt()
  h.receipts[0].resolve({code:200,data:{found:true,requestId:'other_0001',memberId:'2',
    messageId:'100',msgType:1,content:'original',status:0}})
  await query; assert.equal(h.page.pendingSend.requestId,'request_0001'); assert.match(h.page.sendRecoveryError,/不一致/)
})
test('late receipt after account switch neither clears record nor refreshes new account', async () => {
  const h=harness(); await unknownSend(h); const query=h.page.checkSendReceipt(); h.switchAccount()
  h.receipts[0].resolve({code:200,data:{found:true,requestId:'request_0001',memberId:'2',
    messageId:'100',msgType:1,content:'original',status:0}})
  await query; assert.equal(h.page.current,null); assert.equal(h.lists.length,0); assert.equal(h.sends.length,1)
})

test('receipt arriving after leaving and reentering clears restored copy of same request', async () => {
  const h=harness(); await unknownSend(h); const query=h.page.checkSendReceipt()
  await h.page.selectConv({conversationId:'11',memberId:'4'})
  await h.page.selectConv({conversationId:'10',memberId:'2'})
  h.receipts[0].resolve({code:200,data:{found:true,requestId:'request_0001',memberId:'2',
    messageId:'100',msgType:1,content:'original',status:0}})
  await query; assert.equal(h.page.pendingSend,null); assert.equal(h.page.sendText,''); assert.equal(h.sends.length,1)
})
