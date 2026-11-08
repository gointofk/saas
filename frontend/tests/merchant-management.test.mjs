import assert from 'node:assert/strict'
import { before, after, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'

let server, Component, merchantApi, translationKey, translate
const merchant = {
  id: 17, code: 'TEST', name: 'Test merchant', active: true, shippingFee: '20.00',
  updatedAt: '2026-10-30T00:00:00Z', domain: 'merchant.example.test', logoUrl: '/logo.svg', theme: 'classic',
  registrationEnabled: false, mailSettings: null,
  administrator: { id: 31, account: 'initial-admin', name: 'Initial admin', active: true },
}

before(async () => {
  server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
  ;({ default: Component } = await server.ssrLoadModule('/src/components/MerchantManagement.vue'))
  ;({ merchantApi } = await server.ssrLoadModule('/src/lib/api.ts'))
  ;({ sellerTranslationKey: translationKey, translateSeller: translate } = await server.ssrLoadModule('/src/lib/seller-locale.ts'))
})
after(async () => { await server?.close() })

async function form(item = merchant, language = 'zh-CN') {
  let state
  const wrapper = { ...Component, setup(props, context) {
    state = Component.setup(props, context)
    state.open(item)
    return state
  } }
  const app = createSSRApp(wrapper)
  app.provide(translationKey, (text, values) => translate(text, language, values))
  return { html: await renderToString(app), state }
}

test('editing shows the administrator and optional new-password fields in both languages', async () => {
  const { html } = await form()
  assert.match(html, /initial-admin/)
  assert.match(html, /管理员新密码/)
  assert.match(html, /确认新密码/)
  assert.match(html, /留空则保持原密码/)
  const english = (await form(merchant, 'en')).html
  assert.match(english, /New administrator password/)
  assert.match(english, /Confirm new password/)
  assert.match(english, /Leave blank to keep the current password/)
})

test('blank passwords are omitted and mismatched passwords cannot issue a save request', async () => {
  const originalUpdate = merchantApi.update, originalPage = merchantApi.page
  const calls = []
  merchantApi.update = async (...args) => { calls.push(args); return merchant }
  merchantApi.page = async () => ({ items: [merchant], page: 1, total: 1, totalPages: 1 })
  try {
    const { state } = await form()
    await state.save()
    assert.equal(calls.length, 1)
    assert.equal(Object.hasOwn(calls[0][1], 'password'), false)
    assert.equal(Object.hasOwn(calls[0][1], 'confirmation'), false)
    state.open(merchant)
    state.draft.password = 'ChangedAdminPass123!'
    state.draft.confirmation = 'DifferentAdminPass123!'
    await state.save()
    assert.equal(calls.length, 1)
    assert.match(state.error.value, /两次输入需一致/)
    state.draft.password = ''
    await state.save()
    assert.equal(calls.length, 1, 'a confirmation without a password must be rejected')
  } finally {
    merchantApi.update = originalUpdate
    merchantApi.page = originalPage
  }
})

test('reset submits once, keeps failures reviewable and clears passwords on success or close', async () => {
  const originalUpdate = merchantApi.update, originalPage = merchantApi.page
  const calls = []
  let resolveSave
  merchantApi.page = async () => ({ items: [merchant], page: 1, total: 1, totalPages: 1 })
  merchantApi.update = (...args) => { calls.push(args); return new Promise(resolve => { resolveSave = resolve }) }
  try {
    const { state } = await form()
    state.draft.password = state.draft.confirmation = 'ChangedAdminPass123!'
    const saving = state.save()
    assert.equal(state.saving.value, true)
    state.close()
    assert.equal(state.modal.value, true)
    await state.save()
    assert.equal(calls.length, 1)
    assert.equal(calls[0][0], merchant.id)
    assert.equal(calls[0][1].password, 'ChangedAdminPass123!')
    assert.equal(calls[0][1].confirmation, 'ChangedAdminPass123!')
    assert.equal(calls[0][1].expectedUpdatedAt, merchant.updatedAt)
    resolveSave(merchant)
    await saving
    assert.equal(state.modal.value, false)
    assert.equal(state.draft.password, '')
    assert.equal(state.draft.confirmation, '')
    state.open(merchant)
    state.draft.password = state.draft.confirmation = 'ChangedAdminPass123!'
    merchantApi.update = async () => { throw new Error('Merchant settings changed. Refresh and try again.') }
    await state.save()
    assert.equal(state.modal.value, true)
    assert.equal(state.saving.value, false)
    assert.match(state.error.value, /settings changed/)
    state.close()
    assert.equal(state.draft.password, '')
    assert.equal(state.draft.confirmation, '')
  } finally {
    merchantApi.update = originalUpdate
    merchantApi.page = originalPage
  }
})

test('missing administrator renders disabled reset fields and still allows merchant editing', async () => {
  const { html } = await form({ ...merchant, administrator: null })
  assert.match(html, /初始管理员不存在，无法重置密码/)
  assert.match(html, /type="password"[^>]*minlength="12"[^>]*disabled/)
  assert.match(html, /type="submit" class="modal-save"[^>]*>保存修改/)
})

test('registration reveals mail settings and incomplete settings cannot be saved', async () => {
  const originalUpdate = merchantApi.update
  let calls = 0
  merchantApi.update = async () => { calls++; return merchant }
  try {
    const { state } = await form()
    assert.equal(state.mailExpanded.value, false)
    state.draft.registrationEnabled = true
    state.registrationChanged()
    assert.equal(state.mailExpanded.value, true)
    state.mailExpanded.value = false
    await state.save()
    assert.equal(calls, 0)
    assert.equal(state.modal.value, true)
    assert.equal(state.mailExpanded.value, true)
    assert.match(state.error.value, /完整配置 SMTP/)
    state.open(merchant)
    assert.equal(state.mailExpanded.value, false, 'opening another editor resets disclosure state')
  } finally { merchantApi.update = originalUpdate }
})

test('an in-progress test email prevents closing, saving, and duplicate sends', async () => {
  const originalTestEmail = merchantApi.testEmail, originalUpdate = merchantApi.update
  let sends = 0, saves = 0, resolveSend
  merchantApi.testEmail = () => { sends++; return new Promise(resolve => { resolveSend = resolve }) }
  merchantApi.update = async () => { saves++; return merchant }
  try {
    const { state } = await form({ ...merchant, registrationEnabled: true, mailSettings: {
      smtpHost: 'smtp.example.test', smtpPort: 587, smtpUsername: 'mailer', passwordConfigured: true,
      tlsMode: 'starttls', senderEmail: 'sender@example.test', senderName: 'Test store',
    } })
    assert.equal(state.mailExpanded.value, true)
    state.draft.testRecipient = 'recipient@example.test'
    const sending = state.testEmail()
    assert.equal(state.testingMail.value, true)
    state.close()
    await state.save()
    await state.testEmail()
    assert.equal(state.modal.value, true)
    assert.equal(saves, 0)
    assert.equal(sends, 1)
    resolveSend({})
    await sending
    assert.equal(state.testingMail.value, false)
    assert.match(state.mailTestStatus.value, /测试邮件已发送/)
    state.close()
    assert.equal(state.modal.value, false)
  } finally {
    merchantApi.testEmail = originalTestEmail
    merchantApi.update = originalUpdate
  }
})
