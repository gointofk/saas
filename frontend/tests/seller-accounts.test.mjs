import assert from 'node:assert/strict'
import { after, before, beforeEach, test } from 'node:test'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

let server, auth, sessions, Switcher, translateSeller
let reloads = 0, calls = [], events = []
const persistent = new Map(), transient = new Map()
const storage = values => ({ getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) })
const key = 'dailyactivity.admin.token'
const customerKey = 'dailyactivity.customer.token'
const a = { member: { id: 1, account: 'alice', name: 'Alice' }, merchant: { id: 11, name: 'Shop A' } }
const b = { member: { id: 2, account: 'bob', name: 'Bob' }, merchant: { id: 22, name: 'Shop B' } }
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

before(async () => {
  globalThis.localStorage = storage(persistent)
  globalThis.sessionStorage = storage(transient)
  globalThis.window = { location: { reload: () => { reloads++ } }, dispatchEvent: event => events.push(event), setTimeout, clearTimeout }
  server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, entries: [] } })
  auth = await server.ssrLoadModule('/src/lib/api.ts')
  sessions = await server.ssrLoadModule('/src/lib/seller-sessions.ts')
  ;({ default: Switcher } = await server.ssrLoadModule('/src/components/SellerAccountSwitcher.vue'))
  ;({ translateSeller } = await server.ssrLoadModule('/src/lib/seller-locale.ts'))
})
after(async () => { await server?.close() })
beforeEach(() => {
  persistent.clear(); transient.clear(); calls = []; events = []; reloads = 0
  localStorage.setItem(key, 'token-a')
  localStorage.setItem(customerKey, 'customer-token')
  auth.pinAdminToken()
  sessions.rememberSellerSession(a, 'token-a')
  sessions.rememberSellerSession(b, 'token-b')
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return json(b) }
})

async function switcher(props = {}, configure = () => {}) {
  let state, activated = 0
  const component = { ...Switcher, setup(p, context) {
    state = Switcher.setup(p, context)
    configure(state)
    return state
  } }
  const context = {}
  const sourceHtml = await renderToString(createSSRApp(component, { currentMemberId: 1, t: text => text, ...props, onActivated: () => { activated++ } }), context)
  const dialogHtml = context.teleports?.body || ''
  return { state, sourceHtml, dialogHtml, html: sourceHtml + dialogHtml, activated: () => activated }
}

test('existing persistent and legacy sessions remain available; customer token is independent', () => {
  persistent.delete(key); transient.set(key, 'legacy')
  auth.pinAdminToken()
  assert.equal(auth.getToken('admin'), 'legacy')
  assert.equal(persistent.get(key), 'legacy')
  assert.equal(transient.has(key), false)
  assert.equal(auth.getToken('customer'), 'customer-token')
})

test('saved sessions are deduplicated and contain only display data and tokens', () => {
  sessions.rememberSellerSession({ ...a, password: 'never-save', permissions: ['members'] }, 'new-a')
  const list = sessions.savedSellerSessions()
  assert.equal(list.length, 2)
  assert.equal(list.find(s => s.memberId === 1).token, 'new-a')
  const raw = persistent.get(sessions.SELLER_SESSIONS_KEY)
  assert.doesNotMatch(raw, /password|never-save|permissions/)
  persistent.set(sessions.SELLER_SESSIONS_KEY, 'broken-json')
  assert.deepEqual(sessions.savedSellerSessions(), [])
  persistent.set(sessions.SELLER_SESSIONS_KEY, '[null, {}, {"memberId":"1"}]')
  assert.deepEqual(sessions.savedSellerSessions(), [])
})

test('switching validates the target and activates it without calling logout', async () => {
  const view = await switcher()
  await view.state.select(sessions.savedSellerSessions()[1])
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/v1/admin/me')
  assert.equal(calls[0].init.headers.get('Authorization'), 'Bearer token-b')
  assert.equal(persistent.get(key), 'token-b')
  assert.equal(persistent.get(customerKey), 'customer-token')
  assert.equal(sessions.savedSellerSessions().find(s => s.memberId === 1).token, 'token-a')
  assert.equal(view.activated(), 1)
})

test('current account cannot be selected or removed', async () => {
  const { state } = await switcher()
  const current = sessions.savedSellerSessions()[0]
  await state.select(current); state.remove(current)
  assert.equal(calls.length, 0)
  assert.equal(sessions.savedSellerSessions().length, 2)
})

test('invalid target asks for sign-in without clearing the current account', async () => {
  fetch = async () => json({ message: 'Expired', code: 'UNAUTHORIZED' }, 401)
  const view = await switcher()
  await view.state.select(sessions.savedSellerSessions()[1])
  assert.equal(persistent.get(key), 'token-a')
  assert.equal(sessions.savedSellerSessions().find(s => s.memberId === 2).token, null)
  assert.equal(view.state.adding.value, true)
  assert.equal(view.state.account.value, 'bob')
  assert.match(view.state.error.value, /重新登录/)
  assert.equal(view.activated(), 0)
})

test('network failures preserve saved target credentials for retry', async () => {
  fetch = async () => { throw new TypeError('Failed to fetch') }
  const view = await switcher()
  await view.state.select(sessions.savedSellerSessions()[1])
  assert.equal(persistent.get(key), 'token-a')
  assert.equal(sessions.savedSellerSessions()[1].token, 'token-b')
  assert.equal(view.state.pending.value, false)
  assert.equal(view.activated(), 0)
})

test('wrong password while adding an account does not sign out the active account', async () => {
  fetch = async (url, init) => { calls.push({ url, init }); return json({ message: 'Account or password is incorrect.' }, 401) }
  const view = await switcher()
  view.state.add('bob'); view.state.password.value = 'incorrect'
  await view.state.submit()
  assert.equal(calls[0].init.headers.has('Authorization'), false)
  assert.equal(persistent.get(key), 'token-a')
  assert.equal(view.state.password.value, '')
  assert.equal(view.state.pending.value, false)
  assert.equal(events.length, 0)
})

test('successful sign-in saves the account, clears the password and activates it', async () => {
  fetch = async () => json({ ...b, accessToken: 'new-b' })
  const view = await switcher()
  view.state.add('bob'); view.state.password.value = 'valid-test-password'
  await view.state.submit()
  assert.equal(persistent.get(key), 'new-b')
  assert.equal(sessions.savedSellerSessions().length, 2)
  assert.equal(sessions.savedSellerSessions().find(s => s.memberId === 2).token, 'new-b')
  assert.equal(view.state.password.value, '')
  assert.equal(view.activated(), 1)
})

test('pending validation prevents repeated submissions and closing the dialog', async () => {
  let resolve
  fetch = (url, init) => { calls.push({ url, init }); return new Promise(r => { resolve = r }) }
  const view = await switcher()
  view.state.show()
  const target = sessions.savedSellerSessions()[1]
  const pending = view.state.select(target)
  await view.state.select(target)
  view.state.close()
  assert.equal(view.state.open.value, true)
  assert.equal(calls.length, 1)
  resolve(json(b)); await pending
  assert.equal(view.activated(), 1)
})

test('local removal and sign-out only discard the intended saved credential', () => {
  auth.clearToken('admin')
  assert.equal(persistent.has(key), false)
  assert.equal(persistent.get(customerKey), 'customer-token')
  assert.equal(sessions.savedSellerSessions()[0].token, null)
  assert.equal(sessions.savedSellerSessions()[1].token, 'token-b')
  sessions.removeSellerSession(1)
  assert.deepEqual(sessions.savedSellerSessions().map(s => s.memberId), [2])
})

test('stale tabs cannot submit forms or exports with the newly active account', async () => {
  persistent.set(key, 'token-b') // storage event may not have run yet
  assert.equal(auth.getToken('admin'), 'token-a')
  for (const operation of [
    () => auth.request('/admin/orders/1', { method: 'PATCH', body: '{}' }, 'admin'),
    () => auth.download('/admin/orders/export', {}, 'admin'),
  ]) await assert.rejects(operation, e => e.code === 'AUTH_CONTEXT_CHANGED')
  auth.clearToken('admin')
  assert.equal(persistent.get(key), 'token-b')
  assert.equal(calls.length, 0)
  assert.equal(reloads, 2)
})

test('late responses and old 401s cannot update the page or clear the new login', async () => {
  for (const status of [200, 401]) {
    persistent.set(key, 'token-a'); auth.pinAdminToken()
    let resolve
    fetch = () => new Promise(r => { resolve = r })
    const pending = auth.request('/admin/orders', {}, 'admin')
    persistent.set(key, 'token-b')
    resolve(json({ items: ['old-account-data'] }, status))
    await assert.rejects(() => pending, e => e.code === 'AUTH_CONTEXT_CHANGED')
    assert.equal(persistent.get(key), 'token-b')
  }
})

test('an invalid current session removes only its saved token', async () => {
  fetch = async () => json({ message: 'Revoked' }, 401)
  await assert.rejects(() => auth.request('/admin/me', {}, 'admin'), e => e.status === 401)
  assert.equal(persistent.has(key), false)
  assert.equal(sessions.savedSellerSessions()[0].token, null)
  assert.equal(sessions.savedSellerSessions()[1].token, 'token-b')
})

test('switcher renders saved accounts, recovery states and translated guidance', async () => {
  sessions.invalidateSellerSession('token-b')
  let signOutEvents = 0
  const { state, html, sourceHtml, dialogHtml } = await switcher({ canSignOut: true, onSignOut: () => { signOutEvents++ }, t: text => translateSeller(text, 'en') }, state => state.show())
  assert.doesNotMatch(sourceHtml, /role="dialog"/)
  assert.match(dialogHtml, /role="dialog"/)
  assert.match(dialogHtml, /seller-account-overlay/)
  assert.match(html, /Shop A · Alice/)
  assert.match(html, /Shop B · Bob/)
  assert.match(html, /Current account/)
  assert.match(html, /Sign-in required/)
  assert.doesNotMatch(html, /Save sign-ins only|Unsaved changes|Removing an account only/)
  assert.doesNotMatch(html, /token-a|token-b/)
  assert.match(dialogHtml, />Sign out<\/button>/)
  assert.doesNotMatch(sourceHtml, /Sign out/)
  state.signOut()
  assert.equal(signOutEvents, 1)
  assert.equal(persistent.get(key), 'token-a', 'parent retains confirmation and server logout handling')
  const loginView = await switcher({ currentMemberId: undefined }, state => state.show())
  assert.doesNotMatch(loginView.dialogHtml, /seller-account-signout/)
  const busyView = await switcher({ canSignOut: true, signingOut: true, onSignOut: () => { signOutEvents++ } }, state => state.show())
  busyView.state.signOut()
  busyView.state.close()
  await busyView.state.select(sessions.savedSellerSessions()[1])
  assert.equal(signOutEvents, 1)
  assert.equal(busyView.state.open.value, true)
  assert.equal(calls.length, 0)
})

test('platform authentication is independent from seller and customer sessions', async () => {
  auth.setToken('platform', 'platform-token')
  fetch = async (path, init) => {
    assert.equal(path, '/api/v1/platform/merchants?page=1')
    assert.equal(new Headers(init.headers).get('Authorization'), 'Bearer platform-token')
    return json({ message: 'Expired' }, 401)
  }
  await assert.rejects(() => auth.request('/platform/merchants?page=1', {}, 'platform'), e => e.status === 401)
  assert.equal(auth.getToken('platform'), null)
  assert.equal(auth.getToken('customer'), 'customer-token')
  assert.equal(auth.getToken('admin'), 'token-a')
})

test('buyer header renders the current merchant name and logo', async () => {
  const { storefrontBrand } = await server.ssrLoadModule('/src/lib/storefront.ts')
  const { default: Header } = await server.ssrLoadModule('/src/components/CustomerHeader.vue')
  Object.assign(storefrontBrand, { name: 'Forest Research', logoUrl: 'https://cdn.example.com/forest.png', theme: 'forest' })
  const html = await renderToString(createSSRApp(Header, { activePage: 'products', cartCount: 0, language: 'en', t: text => text }))
  assert.match(html, /Forest Research/)
  assert.match(html, /https:\/\/cdn.example.com\/forest.png/)
})
