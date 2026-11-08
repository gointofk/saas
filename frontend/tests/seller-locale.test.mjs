import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createSSRApp, h, reactive } from 'vue'
import { renderToString } from '@vue/server-renderer'

let server, App, translateSeller, sellerTranslationKey
const storage = new Map()
const permissions = ['orders', 'order_audit', 'commissions', 'bills', 'products', 'certificates', 'customers', 'categories', 'payment_channels', 'members']

before(async () => {
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
  globalThis.sessionStorage = { getItem: () => null, removeItem() {} }
  globalThis.location = { pathname: '/admin', search: '' }
  globalThis.document = { documentElement: { lang: '' }, addEventListener() {}, removeEventListener() {} }
  globalThis.window = { setTimeout, clearTimeout, scrollTo() {} }
  server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
  ;({ default: App } = await server.ssrLoadModule('/src/App.vue'))
  ;({ translateSeller, sellerTranslationKey } = await server.ssrLoadModule('/src/lib/seller-locale.ts'))
})
after(async () => { await server?.close() })

async function renderApp(configure = () => {}) {
  const component = { ...App, setup(props, context) {
    const state = App.setup(props, context)
    configure(state)
    return state
  } }
  return renderToString(createSSRApp(component))
}
const visibleText = html => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ')

// These checks render real templates; they catch labels that bypass translation.
test('seller login defaults to Chinese and honors its independent saved preference', async () => {
  storage.clear()
  storage.set('dailyactivity.language', 'en')
  assert.match(await renderApp(), /登录商家后台/)
  storage.set('dailyactivity.admin.language', 'en')
  const html = await renderApp()
  assert.match(html, /Sign in to Seller Portal/)
  assert.match(html, /Enter your login account/)
  assert.doesNotMatch(visibleText(html).replaceAll('中文', ''), /[\u3400-\u9fff]/)
  assert.equal(document.documentElement.lang, 'en')
})

test('switching languages persists the preference without changing drafts or customer preference', async () => {
  storage.clear()
  storage.set('dailyactivity.language', 'en')
  await renderApp(state => {
    state.productDraft.name = '已支付 / 中文商品'
    state.productDraft.basePrice = '42.50'
    state.language.value = 'en'
    state.changeLanguage()
    assert.equal(state.t('订单管理'), 'Orders')
    assert.equal(state.sellerStatusLabel('partially_paid'), 'Partially paid')
    state.language.value = 'zh-CN'
    state.changeLanguage()
    assert.equal(state.t('订单管理'), '订单管理')
    assert.equal(state.sellerStatusLabel('partially_paid'), '部分支付')
    assert.equal(state.productDraft.name, '已支付 / 中文商品')
    assert.equal(state.productDraft.basePrice, '42.50')
    assert.equal(storage.get('dailyactivity.language'), 'en')
    assert.equal(storage.get('dailyactivity.admin.language'), 'zh-CN')
    assert.equal(state.loginAccount.value, '')
  })
  assert.match(await renderApp(), /登录商家后台/)
  location.pathname = '/'
  assert.match(await renderApp(), /Sign In/)
  location.pathname = '/admin'
})

test('all seller navigation pages render English UI and preserve business names', async () => {
  storage.clear()
  storage.set('dailyactivity.admin.language', 'en')
  for (const page of ['order-audit', 'orders', 'finance', 'billing', 'products', 'certificates', 'customers', 'categories', 'settings', 'members']) {
    const html = await renderApp(state => {
      state.loggedIn.value = true
      state.adminPermissions.value = permissions
      state.adminPage.value = page === 'billing' ? 'finance' : page
      state.adminMode.value = page === 'billing' ? 'billing' : page === 'finance' ? 'finance' : ''
      state.adminMember.value = { name: '测试成员' }
    })
    const text = visibleText(html).replaceAll('中文', '').replaceAll('测试成员', '').replaceAll('测', '')
    assert.doesNotMatch(text, /[\u3400-\u9fff]/, `${page}: ${text}`)
    assert.match(html, /测试成员/)
    assert.match(html, /Seller Portal/)
    assert.match(html, /Switch account/)
  }
})

test('all seller list searches render an accessible refresh icon after the input', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  for (const [page, mode] of [['order-audit', ''], ['orders', ''], ['products', ''], ['certificates', ''], ['customers', ''], ['members', ''], ['finance', 'billing'], ['finance', 'finance']]) {
    const html = await renderApp(state => {
      state.loggedIn.value = true
      state.adminPage.value = page
      state.adminMode.value = mode
      state.adminPermissions.value = permissions
    })
    assert.match(html, /<input[^>]*>\s*<!--[\s\S]*?class="seller-search-refresh"[^>]*aria-label="Refresh results"/, page + mode)
  }
})

test('refresh icons are disabled while loading and do not submit their surrounding forms', async () => {
  const { default: Component } = await server.ssrLoadModule('/src/components/SellerSearchField.vue')
  for (const loading of [false, true]) {
    const events = []
    let state
    const component = { ...Component, setup(props, context) {
      state = Component.setup(props, context)
      return state
    } }
    const app = createSSRApp({ render: () => h(component, { loading, onRefresh: () => events.push('refresh') }, { default: () => h('input', { value: 'kept query' }) }) })
    app.provide(sellerTranslationKey, text => translateSeller(text, 'en'))
    const html = await renderToString(app)
    assert.match(html, /type="button"/)
    assert.equal(/<button[^>]*\sdisabled(?:\s|>)/.test(html), loading)
    state.refresh()
    assert.equal(events.length, loading ? 0 : 1)
  }
})

test('seller search refresh uses current keywords and filters on page one', async () => {
  const originalFetch = globalThis.fetch, originalButton = globalThis.HTMLButtonElement
  globalThis.HTMLButtonElement = class {}
  const calls = []
  globalThis.fetch = async url => {
    calls.push(new URL(url, 'http://localhost'))
    const payload = url.endsWith('/categories') ? [] : url.includes('/summary') ? {} : { items: [], page: 1, total: 0, totalPages: 1 }
    return new Response(JSON.stringify(payload), { headers: { 'Content-Type': 'application/json' } })
  }
  try {
    for (const [page, mode, endpoint, expectedQuery] of [
      ['order-audit', '', 'order-audits', 'audit query'], ['orders', '', 'orders', 'query'],
      ['products', '', 'products', 'query'], ['certificates', '', 'certificates', 'query'],
      ['customers', '', 'customers', 'query'], ['members', '', 'members', 'member query'],
      ['finance', 'billing', 'bills', 'query'], ['finance', 'finance', 'commissions', 'query'],
    ]) {
      calls.length = 0
      let state
      await renderApp(current => {
        state = current; current.loggedIn.value = true; current.adminPermissions.value = permissions
        current.adminPage.value = page; current.adminMode.value = mode
        current.adminSearch.value = ' query '; current.auditSearch.value = ' audit query '; current.adminMemberSearch.value = ' member query '
        for (const key of ['auditPage', 'adminOrderPage', 'adminProductPage', 'adminCertificatePage', 'adminCustomerPage', 'adminBillPage', 'adminCommissionPage']) current[key].value = 3
        current.adminCategoryFilter.value = '17'; current.adminProductType.value = 'gift'; current.adminProductStatus.value = 'active'
        current.adminShippingFilter.value = 'shipped'; current.adminPaymentFilter.value = 'unpaid'
      })
      state.busy.value = true
      await state.refreshAdminSearch()
      assert.equal(calls.length, 0)
      state.busy.value = false
      await state.refreshAdminSearch()
      const request = calls.find(url => url.pathname === `/api/v1/admin/${endpoint}`)
      assert.ok(request, endpoint)
      assert.equal(request.searchParams.get('page'), '1', endpoint)
      assert.equal(request.searchParams.get('q'), expectedQuery, endpoint)
      if (page === 'products') {
        assert.equal(request.searchParams.get('categoryId'), '17')
        assert.equal(request.searchParams.get('productType'), 'gift')
        assert.equal(request.searchParams.get('active'), 'true')
      }
      if (page === 'orders') {
        assert.equal(request.searchParams.get('status'), 'shipped')
        assert.equal(request.searchParams.get('paymentStatus'), 'unpaid')
      }
    }
  } finally { globalThis.fetch = originalFetch; globalThis.HTMLButtonElement = originalButton }
})

test('member management keeps designated managers read-only for every operator', async () => {
  storage.set('dailyactivity.admin.language', 'zh-CN')
  const protectedMember = { id: 7, account: 'manager', name: 'Protected Manager', active: true, permissions: ['members'], merchantManagement: true, updatedAt: '2026-10-27T00:00:00Z' }
  const ordinaryMember = { ...protectedMember, id: 8, account: 'ordinary', name: 'Ordinary Member', merchantManagement: false }
  for (const manager of [false, true]) {
    let state
    const html = await renderApp(current => {
      state = current
      current.loggedIn.value = true
      current.adminPermissions.value = permissions
      current.adminPage.value = 'members'
      current.merchantManagement.value = manager
      current.adminMembers.value = { items: [protectedMember, ordinaryMember], page: 1, totalPages: 1, total: 2 }
    })
    const rows = html.match(/<tr>[\s\S]*?<\/tr>/g)
    const protectedRow = rows.find(row => row.includes('Protected Manager'))
    const ordinaryRow = rows.find(row => row.includes('Ordinary Member'))
    assert.equal((protectedRow.match(/<button[^>]*\sdisabled(?:\s|>)/g) || []).length, 2)
    assert.match(protectedRow, /商户管理账号在权限管理中只读。/)
    assert.doesNotMatch(ordinaryRow, /<button[^>]*\sdisabled(?:\s|>)/)
    state.openMember(protectedMember)
    assert.equal(state.memberModalOpen.value, false)
    await state.toggleMember(protectedMember)
    state.openMember(ordinaryMember)
    assert.equal(state.memberModalOpen.value, true)
    assert.equal(state.memberEditingId.value, ordinaryMember.id)
  }
})

test('member pagination reaches later managers and isolates server search from other pages', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  storage.set('dailyactivity.admin.token', 'test-only-token')
  const originalFetch = globalThis.fetch
  const originalButton = globalThis.HTMLButtonElement
  globalThis.HTMLButtonElement = class {}
  const member = { id: 107, account: 'manager', name: 'Later Manager', active: true, permissions: ['members'], merchantManagement: true, updatedAt: '2026-10-27T00:00:00Z' }
  const calls = []
  let failNext = false
  let pendingResponse
  globalThis.fetch = async url => {
    const params = new URL(url, 'http://localhost').searchParams
    calls.push({ page: params.get('page'), q: params.get('q') })
    if (failNext) { failNext = false; throw new Error('Simulated network failure') }
    if (pendingResponse) await pendingResponse
    const searched = params.has('q')
    const page = Number(params.get('page'))
    return new Response(JSON.stringify({ items: searched || page === 2 ? [member] : [], page, total: searched ? 1 : 13, totalPages: searched ? 1 : 2 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    let state
    await renderApp(current => {
      state = current
      current.loggedIn.value = true
      current.adminPage.value = 'members'
      current.adminPermissions.value = permissions
      current.adminSearch.value = 'unrelated product search'
    })
    await state.loadAdminPage()
    assert.deepEqual(calls[0], { page: '1', q: null })
    let release
    pendingResponse = new Promise(resolve => { release = resolve })
    const loading = state.setAdminMemberPage(2)
    assert.equal(state.adminMembersLoading.value, true)
    await state.setAdminMemberPage(2)
    assert.equal(calls.length, 2, 'repeated clicks must not issue duplicate requests')
    release()
    await loading
    pendingResponse = null
    assert.equal(state.adminMembers.value.page, 2)
    assert.deepEqual(state.visibleAdminMembers.value.map(row => row.id), [107])
    const html = await renderApp(current => {
      current.loggedIn.value = true
      current.adminPage.value = 'members'
      current.adminPermissions.value = permissions
      current.adminSearch.value = 'unrelated product search'
      current.adminMembers.value = state.adminMembers.value
    })
    assert.match(html, /Later Manager/)
    assert.match(html, /13 records · Page 2 \/ 2/)
    assert.match(html, /class="active" aria-current="page"[^>]*>2<\/button>/)
    failNext = true
    await state.setAdminMemberPage(1)
    assert.equal(state.adminMembers.value.page, 2, 'failed loading must preserve the displayed page')
    assert.equal(state.adminMembersLoading.value, false)
    state.adminMemberSearch.value = ' manager '
    await state.searchAdminMembers()
    assert.deepEqual(calls.at(-1), { page: '1', q: 'manager' })
    assert.equal(state.adminMembers.value.page, 1)
    assert.equal(state.adminMemberQuery.value, 'manager')
    state.adminMemberSearch.value = ''
    await state.searchAdminMembers()
    assert.deepEqual(calls.at(-1), { page: '1', q: null })
    await state.setAdminMemberPage(2)
    assert.deepEqual(calls.at(-1), { page: '2', q: null })
  } finally {
    globalThis.fetch = originalFetch
    globalThis.HTMLButtonElement = originalButton
    storage.delete('dailyactivity.admin.token')
  }
})

test('seller forms, validation errors, and server errors render in English', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  for (const modal of ['productModalOpen', 'categoryModalOpen', 'certificateModalOpen', 'userModalOpen', 'memberModalOpen', 'paymentModalOpen']) {
    const html = await renderApp(state => {
      state.loggedIn.value = true
      state.adminPermissions.value = permissions
      state[modal].value = true
      state.memberFormSubmitted.value = true
      state.customerFormSubmitted.value = true
      state.customerRequestError.value = '订单已变化，请刷新后重试。'
    })
    const text = visibleText(html).replaceAll('中文', '')
    assert.doesNotMatch(text, /[\u3400-\u9fff]/, `${modal}: ${text}`)
  }
  assert.equal(translateSeller('Account or password is incorrect.', 'zh-CN'), '账号或密码不正确。')
  assert.equal(translateSeller('订单已变化，请刷新后重试。', 'en'), 'The order changed after it was loaded. Refresh and try again.')
  assert.equal(translateSeller('本次收款金额不能超过剩余待收 $12.50。', 'en'), 'The amount received cannot exceed the remaining balance of $12.50.')
})

test('message parameters preserve business content, amounts, and newlines', () => {
  const name = '已支付 <商品> $10.00\n订单管理'
  assert.equal(translateSeller('删除 {0}', 'en', [name]), `Delete ${name}`)
  assert.equal(translateSeller(`确定删除“${name}”吗？`, 'en'), `Delete ${name}?`)
  assert.equal(translateSeller('Unrecognized message', 'en'), 'Unrecognized message')
  const message = translateSeller('确认后恢复原商品 {0} 件，沿用原成交单价 {1}。', 'en', [2, '$5.00'])
  assert.equal(translateSeller(message, 'zh-CN'), '确认后恢复原商品 2 件，沿用原成交单价 $5.00。')
})

test('standalone seller dialogs and shared parcels use the provided locale', async () => {
  for (const name of ['SellerOrderEditModal', 'SellerOrderProductPicker', 'SellerParcelManager', 'OrderParcelList', 'ParcelPhotoPicker']) {
    const { default: Component } = await server.ssrLoadModule(`/src/components/${name}.vue`)
    const app = createSSRApp({ render: () => h(Component, {
      orderId: 1, orderNo: 'TEST-1', giftsOnly: false, unavailableReason: () => '', additionHint: () => '',
      formatDateTime: value => String(value), money: value => `$${value}`, parcels: [], seller: true,
      modelValue: [], label: 'Packing photo',
    }) })
    app.provide(sellerTranslationKey, (text, values) => translateSeller(text, 'en', values))
    const text = visibleText(await renderToString(app))
    assert.doesNotMatch(text, /[\u3400-\u9fff]/, `${name}: ${text}`)
  }
})

test('populated orders and packing forms translate statuses without translating names or notes', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  const productName = '订单管理商品'
  const customerName = '客户姓名样例'
  const note = '待支付是买家备注'
  const item = { orderItemId: 1, productId: 1, name: productName, sku: 'TEST-SKU', quantity: 2, productType: 'regular', unitPrice: '10.00', lineTotal: '20.00' }
  const summary = { parcelCount: 1, shippedParcelCount: 0, receivedParcelCount: 0, totalQuantity: 2, packedQuantity: 1, shippedQuantity: 0, receivedQuantity: 0, unpackedQuantity: 1, packingStatus: 'partial', shippingStatus: 'none', receiptStatus: 'none', started: true }
  const parcel = { id: 1, parcelNo: 1, parcelUuid: 'test-parcel', status: 'packed', trackingNo: null, items: [{ ...item, quantity: 1 }], events: [] }
  const order = { id: 1, orderNo: 'TEST-ORDER', status: 'pending', paymentStatus: 'partially_paid', auditStatus: 'pending', items: [item], total: '20.00', paidAmount: '5.00', remainingAmount: '15.00', shippingFee: '0.00', discountAmount: '0.00', customer: { name: customerName }, address: { name: customerName, street: 'Test street' }, buyerNote: note, parcels: [parcel], fulfillmentSummary: summary, allowedActions: ['audit_edit', 'audit_discount', 'pack'] }
  const html = await renderApp(state => {
    state.loggedIn.value = true
    state.adminPermissions.value = permissions
    state.adminOrders.value = { items: [order], total: 1, page: 1, totalPages: 1 }
    state.auditOrder.value = order
  })
  assert.match(html, /Partially paid/)
  assert.match(html, /Partially packed/)
  assert.doesNotMatch(html, /<th>Updated at<\/th>/)
  assert.match(html, /1 records · Page 1 \/ 1/)
  assert.match(html, /\$15.00/)
  assert.match(html, new RegExp(productName))
  assert.match(html, new RegExp(note))
  assert.doesNotMatch(visibleText(html).replaceAll('中文', '').replaceAll(productName, '').replaceAll(customerName, '').replaceAll(note, ''), /[\u3400-\u9fff]/)

  for (const name of ['SellerOrderEditModal', 'SellerParcelManager']) {
    const { default: Component } = await server.ssrLoadModule(`/src/components/${name}.vue`)
    const component = { ...Component, setup(props, context) {
      const state = Component.setup(props, context)
      state.loading.value = false
      state.order.value = order
      if (name === 'SellerOrderEditModal') state.items.value = [{ ...item, initialUnitPrice: '10.00' }]
      else {
        state.mode.value = 'pack'
        state.activeDraft.value = { uuid: 'draft-test', selection: 'all', quantities: { 1: 1 }, files: [], note }
      }
      return state
    } }
    const app = createSSRApp({ render: () => h(component, { orderId: 1, orderNo: order.orderNo, formatDateTime: String }) })
    app.provide(sellerTranslationKey, (text, values) => translateSeller(text, 'en', values))
    const text = visibleText(await renderToString(app)).replaceAll(productName, '').replaceAll(customerName, '').replaceAll(note, '')
    assert.doesNotMatch(text, /[\u3400-\u9fff]/, `${name}: ${text}`)
  }
})

test('order completion belongs to received order list actions and is absent from parcel management', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  for (const status of ['pending', 'shipped', 'received', 'completed', 'cancelled']) {
    const order = { id: 1, orderNo: 'TEST-ORDER', status, paymentStatus: 'unpaid', items: [] }
    const html = await renderApp(state => {
      state.loggedIn.value = true
      state.adminPage.value = 'orders'
      state.adminPermissions.value = permissions
      state.adminOrders.value = { items: [order], total: 1, page: 1, totalPages: 1 }
    })
    assert.equal(html.includes('>Complete order</button>'), status === 'received')
  }
  const { default: Component } = await server.ssrLoadModule('/src/components/SellerParcelManager.vue')
  const component = { ...Component, setup(props, context) {
    const state = Component.setup(props, context)
    state.loading.value = false
    state.order.value = { id: 1, orderNo: 'TEST-ORDER', status: 'received', allowedActions: ['complete'], items: [], parcels: [], fulfillmentSummary: {} }
    return state
  } }
  const app = createSSRApp({ render: () => h(component, { orderId: 1, formatDateTime: String }) })
  app.provide(sellerTranslationKey, text => translateSeller(text, 'en'))
  assert.doesNotMatch(await renderToString(app), />Complete order<|>Confirm completion</)
})

test('list completion reloads the order version, requires confirmation and prevents duplicate submissions', async () => {
  const { orderParcelApi } = await server.ssrLoadModule('/src/lib/api.ts')
  const originalDetail = orderParcelApi.detail, originalComplete = orderParcelApi.complete
  const originalFetch = globalThis.fetch, originalButton = globalThis.HTMLButtonElement
  globalThis.HTMLButtonElement = class {}
  const requests = []
  const latest = { id: 1, orderNo: 'TEST-ORDER', status: 'received', updatedAt: 'latest-version', allowedActions: ['complete'], items: [], parcels: [] }
  const completed = { ...latest, status: 'completed', allowedActions: [] }
  orderParcelApi.detail = async () => latest
  let release
  const submissions = []
  orderParcelApi.complete = (...args) => { submissions.push(args); return new Promise(resolve => { release = () => resolve(completed) }) }
  globalThis.fetch = async url => {
    requests.push(url)
    return new Response(JSON.stringify(url.includes('/summary') ? { completedOrders: 1 } : { items: [completed], total: 1, page: 1, totalPages: 1 }), { headers: { 'Content-Type': 'application/json' } })
  }
  try {
    let state
    await renderApp(current => {
      state = current; current.loggedIn.value = true; current.adminPage.value = 'orders'; current.adminPermissions.value = permissions
    })
    await state.beginOrderCompletion({ ...latest, updatedAt: 'old-list-version' })
    assert.equal(state.canCompleteAdminOrder.value, true)
    await state.completeAdminOrder()
    assert.equal(submissions.length, 0)
    state.orderCompletionConfirmed.value = true
    state.orderCompletionNote.value = ' Received all items '
    const saving = state.completeAdminOrder()
    state.closeOrderCompletion()
    assert.equal(state.orderCompletionOpen.value, true)
    await state.completeAdminOrder()
    assert.deepEqual(submissions, [[1, 'Received all items', 'latest-version']])
    release()
    await saving
    assert.equal(state.orderCompletionOpen.value, false)
    assert.equal(state.adminOrders.value.items[0].status, 'completed')
    assert.ok(requests.some(url => url.includes('/orders/summary')))
  } finally {
    orderParcelApi.detail = originalDetail; orderParcelApi.complete = originalComplete
    globalThis.fetch = originalFetch; globalThis.HTMLButtonElement = originalButton
  }
})

test('completion failures require reloading and late detail responses cannot reopen a closed confirmation', async () => {
  const { orderParcelApi } = await server.ssrLoadModule('/src/lib/api.ts')
  const originalDetail = orderParcelApi.detail, originalComplete = orderParcelApi.complete
  const latest = { id: 1, orderNo: 'TEST-ORDER', status: 'received', updatedAt: 'latest-version', allowedActions: ['complete'], items: [], parcels: [] }
  let attempts = 0
  orderParcelApi.detail = async () => latest
  orderParcelApi.complete = async () => { attempts++; throw new Error('Response lost') }
  try {
    let state
    await renderApp(current => { state = current; current.adminPermissions.value = permissions })
    await state.beginOrderCompletion(latest)
    state.orderCompletionConfirmed.value = true
    await state.completeAdminOrder()
    assert.equal(state.orderCompletionStale.value, true)
    await state.completeAdminOrder()
    assert.equal(attempts, 1)
    orderParcelApi.detail = async () => ({ ...latest, status: 'completed', allowedActions: [] })
    await state.reloadOrderCompletion()
    assert.equal(state.orderCompletionConfirmed.value, false)
    assert.equal(state.canCompleteAdminOrder.value, false)
    let release
    orderParcelApi.detail = () => new Promise(resolve => { release = () => resolve(latest) })
    const loading = state.reloadOrderCompletion()
    state.closeOrderCompletion()
    release()
    await loading
    assert.equal(state.orderCompletionOrder.value, null)
    assert.equal(state.orderCompletionOpen.value, false)
  } finally {
    orderParcelApi.detail = originalDetail; orderParcelApi.complete = originalComplete
  }
})

test('payment records translate labels while preserving channel names and transaction IDs', async () => {
  const { orderPaymentApi } = await server.ssrLoadModule('/src/lib/api.ts')
  const { default: Component } = await server.ssrLoadModule('/src/components/OrderPaymentRecords.vue')
  const originalRecords = orderPaymentApi.records
  orderPaymentApi.records = async () => ({ items: [{ submissionId: 1, receiptId: null, channelSnapshot: { name: '测试收款渠道' }, originalTransactionNo: 'TX-测试-01', transactionNo: null, declaredAmount: '12.50', receivedAmount: null, status: 'pending', submittedAt: '2026-01-01T00:00:00Z', verifiedAt: null }], page: 1, total: 1, totalPages: 1 })
  try {
    const component = { ...Component, async setup(props, context) {
      const state = Component.setup(props, context)
      await Promise.resolve()
      return state
    } }
    for (const language of ['en', 'zh-CN']) {
      const html = await renderToString(createSSRApp({ render: () => h(component, { orderId: 1, area: 'admin', t: text => translateSeller(text, language), money: value => `$${value}`, formatDateTime: String }) }))
      assert.match(html, language === 'en' ? /Not verified/ : /未核验/)
      assert.match(html, language === 'en' ? /Transaction ID/ : /交易号/)
      assert.match(html, /测试收款渠道/)
      assert.match(html, /TX-测试-01/)
      assert.match(html, /\$12.50/)
    }
  } finally { orderPaymentApi.records = originalRecords }
})

test('field errors and nested packing messages translate when the language changes', () => {
  const error = 'Please check the submitted fields.\nemail：must be a well-formed email address'
  assert.equal(translateSeller(error, 'zh-CN'), '提交内容未通过校验。\n邮箱：邮箱格式不正确')
  assert.equal(translateSeller(error, 'en'), 'Please check the submitted fields.\nemail: must be a well-formed email address')
  assert.equal(translateSeller('包裹 2 · 中文商品：最多可装 3 件。', 'en'), 'Parcel 2 · 中文商品: At most 3 units can be packed.')
  assert.equal(translateSeller('包裹 2 已保存，可继续打下一包。', 'en'), 'Parcel 2 saved; you can pack the next parcel.')
})

test('seller header keeps account switching and language controls without an avatar or sign-out button', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  for (const page of ['orders', 'billing', 'certificates']) {
    const html = await renderApp(state => {
      state.loggedIn.value = true
      state.adminMember.value = { id: 1, name: 'Test User' }
      state.currentMerchant.value = { name: 'Test Merchant' }
      state.adminPage.value = page === 'billing' ? 'finance' : page
      state.adminMode.value = page === 'billing' ? 'billing' : ''
      state.certificateModalOpen.value = page === 'certificates'
    })
    assert.match(html, /class="topbar-user">Test Merchant · Test User<\/span>/)
    const header = html.match(/<header class="topbar">[\s\S]*?<\/header>/)[0]
    assert.ok(header.indexOf('Switch account') > 0)
    assert.ok(header.indexOf('Switch account') < header.indexOf('seller-language-picker'))
    assert.match(header, /class="seller-language-icon"/)
    assert.match(header, /<select[^>]*aria-label="Language"/)
    assert.match(header, /<option[^>]*value="en"[^>]*selected/)
    assert.doesNotMatch(header, /topbar-avatar|topbar-logout|Sign out/)
    assert.doesNotMatch(html, /type="date"/)
    assert.match(html, /aria-label="Choose date"/)
    assert.match(html, /placeholder="YYYY-MM-DD"/)
    assert.match(html, page === 'certificates' ? /aria-label="Test Date"/ : /aria-label="Start date"/)
  }
})

test('open calendars translate month, weekdays, actions and date labels in both languages', async () => {
  const { default: Calendar } = await server.ssrLoadModule('/src/components/CustomerDatePicker.vue')
  for (const locale of ['en', 'zh-CN']) {
    const component = { ...Calendar, setup(props, context) {
      const state = Calendar.setup(props, context)
      state.isOpen.value = true
      return state
    } }
    const html = await renderToString(createSSRApp({ render: () => h(component, {
      modelValue: '2026-10-15', locale, disabled: false, label: 'Test date',
      t: text => translateSeller(text, locale), class: 'seller-date-control',
    }) }))
    assert.match(html, /value="2026-10-15"/)
    assert.match(html, locale === 'en' ? /October 2026/ : /2026年10月/)
    assert.match(html, locale === 'en' ? /<span>Sun<\/span>/ : /<span>周一<\/span>/)
    assert.match(html, locale === 'en' ? />Today<\/button>/ : />今天<\/button>/)
    assert.match(html, locale === 'en' ? /aria-label="Previous month"/ : /aria-label="上个月"/)
    assert.match(html, locale === 'en' ? /aria-label="Thursday, October 15, 2026"/ : /aria-label="2026年10月15日星期四"/)
    assert.match(html, locale === 'en' ? />Clear<\/button>/ : />清除<\/button>/)
  }
})

test('calendar language changes preserve selection; selection, clearing and disabled states behave correctly', async () => {
  const { default: Calendar } = await server.ssrLoadModule('/src/components/CustomerDatePicker.vue')
  const events = []
  const component = { ...Calendar, setup(props, context) {
    const input = reactive({ ...props })
    const state = Calendar.setup(input, { ...context, emit: (...args) => events.push(args) })
    state.isOpen.value = true
    assert.equal(state.monthLabel.value, 'October 2026')
    input.locale = 'zh-CN'
    assert.equal(state.monthLabel.value, '2026年10月')
    assert.equal(input.modelValue, '2026-10-15')
    assert.equal(state.isOpen.value, true)
    state.selectDate('2026-10-20')
    assert.deepEqual(events, [['update:modelValue', '2026-10-20'], ['apply']])
    assert.equal(state.isOpen.value, false)
    state.clearDate()
    assert.deepEqual(events.slice(2), [['update:modelValue', ''], ['apply']])
    input.disabled = true
    state.selectDate('2026-10-21')
    state.clearDate()
    assert.equal(events.length, 4)
    return state
  } }
  await renderToString(createSSRApp({ render: () => h(component, { modelValue: '2026-10-15', locale: 'en', disabled: false, label: 'Test date', t: text => text }) }))
})

test('seller date filters reject invalid or reversed dates and preserve valid ISO values', async () => {
  storage.set('dailyactivity.admin.language', 'en')
  await renderApp(state => {
    state.adminDateFrom.value = '2026-02-30'
    state.applyAdminDateRange('orders')
    assert.equal(state.adminDateFilter.value, 'all')
    assert.equal(state.notice.value, 'Enter a valid date in YYYY-MM-DD format.')
    state.adminDateFrom.value = '2026-10-20'
    state.adminDateTo.value = '2026-10-10'
    state.applyAdminDateRange('orders')
    assert.equal(state.adminDateFilter.value, 'all')
    assert.equal(state.notice.value, 'Start date must be on or before end date.')
    state.adminDateTo.value = '2026-10-21'
    state.adminOrderPage.value = 3
    state.applyAdminDateRange('orders')
    assert.equal(state.adminDateFilter.value, 'custom')
    assert.equal(state.adminOrderPage.value, 1)
    assert.equal(state.adminDateFrom.value, '2026-10-20')
    state.adminBillDateFrom.value = ''
    state.adminBillDateTo.value = '2026-10-21'
    state.applyAdminDateRange('bills')
    assert.equal(state.adminBillDateFilter.value, 'custom')
    state.clearNotice()
  })
})

test('file pickers translate their controls while preserving file names and selections', async () => {
  const { default: Picker } = await server.ssrLoadModule('/src/components/SellerFilePicker.vue')
  const file = new File(['test'], '中文证书.pdf', { type: 'application/pdf' })
  for (const locale of ['en', 'zh-CN']) {
    storage.set('dailyactivity.admin.language', locale)
    for (const modal of ['certificateModalOpen', 'paymentModalOpen']) {
      const html = await renderApp(state => { state.loggedIn.value = true; state[modal].value = true })
      assert.match(html, locale === 'en' ? /Choose file/ : /选择文件/)
      assert.match(html, locale === 'en' ? /No file selected/ : /未选择文件/)
      assert.match(html, /type="file" hidden/)
    }
    const events = []
    const component = { ...Picker, setup(props, context) {
      const state = Picker.setup(props, { ...context, emit: (...args) => events.push(args) })
      state.selectFile({ target: { files: [] } })
      assert.equal(events.length, 0, 'Cancelling the dialog keeps the current selection')
      state.selectFile({ target: { files: [file] } })
      assert.deepEqual(events, [['update:modelValue', file]])
      return state
    } }
    const app = createSSRApp({ render: () => h(component, { modelValue: file, label: 'COA', accept: 'application/pdf' }) })
    app.provide(sellerTranslationKey, (text, values) => translateSeller(text, locale, values))
    const html = await renderToString(app)
    assert.match(html, /中文证书.pdf/)
    assert.doesNotMatch(html, /No file selected|未选择文件/)
  }
})
