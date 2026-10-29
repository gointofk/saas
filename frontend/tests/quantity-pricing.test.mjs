import assert from 'node:assert/strict'
import { before, after, test } from 'node:test'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
let server, pricing, Editor, Catalog, OrderEdit
const bands = [{ minQuantity: 1, maxQuantity: 4, unitPrice: '95.00' }, { minQuantity: 5, maxQuantity: 29, unitPrice: '85.00' }, { minQuantity: 30, maxQuantity: null, unitPrice: '80.00' }]
before(async () => {
  server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, entries: [] } })
  pricing = await server.ssrLoadModule('/src/lib/quantity-pricing.ts')
  ;({ default: Editor } = await server.ssrLoadModule('/src/components/SellerQuantityPriceEditor.vue'))
  ;({ default: Catalog } = await server.ssrLoadModule('/src/components/CustomerProductCatalog.vue'))
  ;({ default: OrderEdit } = await server.ssrLoadModule('/src/components/CustomerOrderEditModal.vue'))
})
after(async () => { await server?.close() })
test('quantity boundaries and fixed price fallback match the server rules', () => {
  for (const [q, expected] of [[1,'95.00'],[4,'95.00'],[5,'85.00'],[29,'85.00'],[30,'80.00']]) assert.equal(pricing.quantityUnitPrice(bands,q,'90.00'),expected)
  assert.equal(pricing.quantityUnitPrice(undefined,30,'90.00'),'90.00')
  assert.equal(pricing.quantityPriceError([{ tier: 'V1', bands: [bands[2]] }]).length > 0,true)
  assert.equal(pricing.quantityPriceError([{ tier: 'V1', bands: [{ minQuantity: 1, maxQuantity: null, unitPrice: '95.00' }] },{ tier: 'V2', bands }]),'')
})
test('the editor shows only level names in tabs and a level specific configuration', async () => {
  const html = await renderToString(createSSRApp(Editor, { modelValue: [{ tier: 'V1', bands: [{ minQuantity: 1, maxQuantity: null, unitPrice: '95.00' }] }, { tier: 'V2', bands }], fixedPrices: Object.fromEntries(pricing.priceTiers.map(t=>[t,'90.00'])), disabled: false, t:s=>s }))
  const tabs = html.match(/<div[^>]*class="quantity-tabs"[\s\S]*?<\/div>/)[0]
  const labels = [...tabs.matchAll(/<button[^>]*>([\s\S]*?)<\/button>/g)].map(match => match[1].trim())
  assert.deepEqual(labels, ['V1', 'V2', 'V3', 'V4', 'V5', 'V6'])
  assert.equal((html.match(/class="quantity-row"/g)||[]).length,1)
})
test('buyers see their applicable quantity ranges without level labels', async () => {
  const html = await renderToString(createSSRApp(Catalog, { categories: [], activeCategory: null, productSearch: '', cartPending: false, pendingCartProductIds: new Set(), visibleProducts: [{id:1,sku:'SKU',name:'Product',category:'Category',price:'95.00',stock:10,quantityPrices:bands}],t:s=>s,money:v=>`$${v}` }))
  assert.match(html,/1–4/); assert.match(html,/5–29/); assert.match(html,/30\+/)
  assert.match(html,/\$85.00/); assert.doesNotMatch(html,/V[1-6]/)
})
test('order edit preview reprices from the saved bands when quantity crosses a boundary', async () => {
  const html = await renderToString(createSSRApp(OrderEdit, { order: { orderNo:'TEST',shippingFee:'20.00',allowedActions:['edit_items'] }, addresses:[],selectedAddressId:null,items:[{orderItemId:1,productId:1,name:'Product',quantity:4,unitPrice:'85.00',quantityPrices:bands}],note:'',pending:false,t:s=>s,money:v=>`$${v}` }))
  assert.match(html,/\$95.00/);assert.match(html,/\$380.00/);assert.match(html,/\$400.00/)
})

test('approved orders freeze quantities and prices while allowing address and note saves', async () => {
  const html = await renderToString(createSSRApp(OrderEdit, {
    order: { orderNo: 'APPROVED', amountLocked: true, subtotal: '380.00', total: '400.00', shippingFee: '20.00', allowedActions: ['edit'] },
    addresses: [], selectedAddressId: 1, items: [{ orderItemId: 1, productId: 1, name: 'Product', quantity: 4, unitPrice: '95.00', quantityPrices: bands }],
    note: '', pending: false, t: s => s, money: v => `$${v}`,
  }))
  assert.match(html, /Order items and prices are locked/)
  assert.match(html.match(/<input[^>]*id="edit-quantity-1"[^>]*>/)?.[0] ?? '', /disabled/)
  assert.doesNotMatch(html.match(/<button class="modal-save"[^>]*>/)?.[0] ?? '', /disabled/)
  assert.match(html, /\$95.00/); assert.match(html, /\$380.00/); assert.match(html, /\$400.00/)
})
