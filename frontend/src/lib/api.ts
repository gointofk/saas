import type { QuantityPriceBand } from './quantity-pricing'
import { invalidateSellerSession, type SellerIdentity } from './seller-sessions'
const CUSTOMER_TOKEN = 'dailyactivity.customer.token'
const ADMIN_TOKEN = 'dailyactivity.admin.token'

export type Area = 'customer' | 'admin' | 'platform'
export type CustomerOrderAction = 'edit' | 'edit_items' | 'cancel' | 'pay'

export type FulfillmentSummary = {
  parcelCount: number; shippedParcelCount: number; receivedParcelCount: number
  totalQuantity: number; packedQuantity: number; shippedQuantity: number; receivedQuantity: number; unpackedQuantity: number
  packingStatus: 'none' | 'partial' | 'all'; shippingStatus: 'none' | 'partial' | 'all'; receiptStatus: 'none' | 'partial' | 'all'; started: boolean
}
export type FinanceOrderStatus = { orderStatus: string; fulfillmentSummary?: FulfillmentSummary }
export type ParcelFile = { fileUuid: string; url: string; mediaType?: string }
export type ParcelEvent = {
  type: string; parcelId: number | null; toStatus: string | null; note: string | null; occurredAt: string
  actorName?: string; files: string[]; signedFileUrls: Record<string, string>; labels: ParcelFile[]
}
export type OrderParcel = {
  id: number; parcelUuid: string; parcelNo: number; status: 'packed' | 'shipped' | 'received' | 'repacked'
  trackingNo: string | null; carrier: string | null; packedAt: string | null; shippedAt: string | null; receivedAt: string | null
  items: Array<{ orderItemId: number; productId: number; name: string; sku: string; productType: string; quantity: number }>
  events: ParcelEvent[]
}
export type OrderAddressSnapshot = {
  name?: string; street?: string; apt?: string | null; city?: string; region?: string | null; postalCode?: string; phone?: string
}
export type OrderCustomerSnapshot = { name?: string; email?: string }
export type ParcelOrder = {
  id: number; orderNo: string; status: string; updatedAt: string; allowedActions: string[]
  fulfillmentSummary: FulfillmentSummary; parcels: OrderParcel[]
  items: Array<{ orderItemId: number; productId: number; name: string; sku: string; quantity: number; productType: string }>
  address?: OrderAddressSnapshot; customer?: OrderCustomerSnapshot
}
export type ParcelFileReference = { fileUuid: string; uploadToken: string }
export type PackParcelInput = { parcelUuid: string; items: Array<{ orderItemId: number; quantity: number }>; files: ParcelFileReference[]; publicNote: string | null }
export type ShipParcelInput = { parcelId: number; trackingNo: string; carrier: string | null; labelFiles?: ParcelFileReference[]; publicNote: string | null }
export const orderParcelApi = {
  complete: (id: number, publicNote: string | null, expectedUpdatedAt: string) => request<ParcelOrder>(`/admin/orders/${id}/complete`, { method: 'POST', body: JSON.stringify({ confirmed: true, publicNote, expectedUpdatedAt }) }, 'admin'),
  detail: (id: number) => request<ParcelOrder>(`/admin/orders/${id}`, {}, 'admin'),
  pack: (id: number, parcels: PackParcelInput[], expectedUpdatedAt: string) => request<ParcelOrder>(`/admin/orders/${id}/parcels`, { method: 'POST', body: JSON.stringify({ parcels, expectedUpdatedAt }) }, 'admin'),
  ship: (id: number, parcels: ShipParcelInput[], expectedUpdatedAt: string) => request<ParcelOrder>(`/admin/orders/${id}/parcels/ship`, { method: 'POST', body: JSON.stringify({ parcels, expectedUpdatedAt }) }, 'admin'),
  receive: (id: number, parcelIds: number[], publicNote: string | null, expectedUpdatedAt: string) => request<ParcelOrder>(`/admin/orders/${id}/parcels/receive`, { method: 'POST', body: JSON.stringify({ parcelIds, confirmed: true, publicNote, expectedUpdatedAt }) }, 'admin'),
  upload: async (id: number, parcelUuid: string, stage: 'packed' | 'shipped', file: File) => {
    const form = new FormData(); form.append('file', file)
    return request<ParcelFileReference>(`/admin/files?${new URLSearchParams({ purpose: stage === 'shipped' ? 'order_shipping_label' : 'order_fulfillment', orderId: String(id), parcelUuid, stage })}`, { method: 'POST', body: form }, 'admin')
  },
}

export type PaymentSubmission = {
  id: number
  channelId: number
  channelSnapshot: Record<string, unknown>
  transactionNo: string
  expectedTotalAmount: number | string
  declaredAmount: number | string
  submittedAt: string
  status: 'pending' | 'confirmed'
}
export type PaymentOrder = {
  id: number
  orderNo: string
  total: number | string
  paidAmount: number | string
  remainingAmount: number | string
  updatedAt: string
  paymentLinkRequested?: boolean
  pendingPaymentSubmission?: PaymentSubmission
}
export type PaymentRecord = {
  submissionId: number | null
  receiptId: number | null
  channelSnapshot: Record<string, unknown>
  originalTransactionNo: string | null
  transactionNo: string | null
  orderTotalSnapshot: number | string | null
  declaredAmount: number | string | null
  receivedAmount: number | string | null
  status: 'pending' | 'confirmed'
  submittedAt: string | null
  verifiedAt: string | null
  verifiedByName?: string | null
  internalNote?: string | null
}
export type PaymentRecordPage = { items: PaymentRecord[]; page: number; total: number; totalPages: number }
export const orderPaymentApi = {
  submit: <T>(id: number, body: { channelId: number; transactionNo: string; declaredAmount: string; expectedTotalAmount: number | string; expectedUpdatedAt: string }) =>
    request<T>(`/customer/orders/${id}/payment-submissions`, { method: 'POST', body: JSON.stringify(body) }),
  requestLink: <T>(id: number) => request<T>(`/customer/orders/${id}/payment-link-request`, { method: 'POST' }),
  confirm: (id: number, body: { receivedAmount: string; submissionId: number | null; expectedTotalAmount: number | string; transactionNo: string; channelId: number | null; internalNote: string | null; expectedUpdatedAt: string }) =>
    request<PaymentOrder>(`/admin/orders/${id}/confirm-payment`, { method: 'POST', body: JSON.stringify(body) }, 'admin'),
  records: (area: Area, id: number, page: number) => request<PaymentRecordPage>(`/${area}/orders/${id}/payments?page=${page}`, {}, area),
}

export type AuditOrderItem = {
  quantityPrices?: QuantityPriceBand[]
  productId: number
  sku: string
  name: string
  productType: 'regular' | 'gift'
  quantity: number
  unitPrice: string | number
  lineTotal: string | number
}
export type AuditEditableOrder = {
  id: number
  orderNo: string
  status: string
  auditStatus: string
  paymentStatus: string
  createdAt: string
  updatedAt: string
  shippingFee: string | number
  subtotal: string | number
  discountAmount: string | number
  grossTotal: string | number
  total: string | number
  items: AuditOrderItem[]
  allowedActions: string[]
}
export type AuditEditProduct = {
  quantityPrices?: QuantityPriceBand[]
  id: number
  sku: string
  name: string
  productType: 'regular' | 'gift'
  unitPrice: string | number
  stock: number
}
export type AuditEditProductPage = { items: AuditEditProduct[]; page: number; total: number; totalPages: number }

export const auditOrderApi = {
  decide: (id: number, body: { result: 'approved' | 'rejected'; remark: string | null; expectedUpdatedAt: string; discountAmount?: string | null }) =>
    request<AuditEditableOrder>(`/admin/order-audits/${id}/decision`, { method: 'POST', body: JSON.stringify(body) }, 'admin'),
  detail: (id: number) => request<AuditEditableOrder>(`/admin/order-audits/${id}`, {}, 'admin'),
  products: (id: number, q: string, page: number) => request<AuditEditProductPage>(`/admin/order-audits/${id}/products?${new URLSearchParams({ q, page: String(page) })}`, {}, 'admin'),
  update: (id: number, body: { items: { productId: number; quantity: number; unitPrice: string | null; manualPrice?: boolean }[]; reason: string | null; expectedUpdatedAt: string; discountAmount?: string | null }) =>
    request<AuditEditableOrder>(`/admin/order-audits/${id}`, { method: 'PATCH', body: JSON.stringify(body) }, 'admin'),
}

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly fields: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

function apiRequestError(payload: unknown, status: number) {
  const body = typeof payload === 'object' && payload !== null ? payload as Record<string, unknown> : {}
  const fields = typeof body.fields === 'object' && body.fields !== null
    ? Object.fromEntries(Object.entries(body.fields).filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
    : {}
  return new ApiRequestError(
    typeof body.message === 'string' ? body.message : 'Request failed.',
    status,
    typeof body.code === 'string' ? body.code : '',
    fields,
  )
}

type PendingButtonState = { count: number; ariaBusy: string | null; ariaDisabled: string | null }
const pendingButtons = new WeakMap<HTMLButtonElement, PendingButtonState>()
let clickedButton: HTMLButtonElement | null = null

if (typeof document !== 'undefined') {
  document.addEventListener('click', (event) => {
    const target = event.target
    const button = target instanceof Element ? target.closest('button') : null
    if (button && (pendingButtons.get(button)?.count ?? 0) > 0) {
      event.preventDefault()
      event.stopImmediatePropagation()
      return
    }
    clickedButton = button
    window.setTimeout(() => {
      if (clickedButton === button) clickedButton = null
    }, 0)
  }, true)
}

function lockActiveButton() {
  const button = clickedButton || (typeof document !== 'undefined' && document.activeElement instanceof HTMLButtonElement
    ? document.activeElement
    : null)
  if (!button) return () => {}

  let state = pendingButtons.get(button)
  if (!state) {
    state = {
      count: 0,
      ariaBusy: button.getAttribute('aria-busy'),
      ariaDisabled: button.getAttribute('aria-disabled'),
    }
    pendingButtons.set(button, state)
  }
  state.count += 1
  button.setAttribute('data-request-pending', 'true')
  button.setAttribute('aria-busy', 'true')
  button.setAttribute('aria-disabled', 'true')

  return () => {
    const current = pendingButtons.get(button)
    if (!current) return
    current.count -= 1
    if (current.count > 0) return
    window.setTimeout(() => {
      const latest = pendingButtons.get(button)
      if (!latest || latest.count > 0) return
      button.removeAttribute('data-request-pending')
      if (latest.ariaBusy === null) button.removeAttribute('aria-busy')
      else button.setAttribute('aria-busy', latest.ariaBusy)
      if (latest.ariaDisabled === null) button.removeAttribute('aria-disabled')
      else button.setAttribute('aria-disabled', latest.ariaDisabled)
      pendingButtons.delete(button)
    }, 0)
  }
}

function tokenKey(area: Area) {
  return area === 'platform' ? 'dailyactivity.platform.token' : area === 'customer' ? CUSTOMER_TOKEN : ADMIN_TOKEN
}

function storedToken(area: Area) {
  const key = tokenKey(area)
  const persistentToken = localStorage.getItem(key)
  if (persistentToken) return persistentToken

  // Preserve an existing login when upgrading from sessionStorage to persistent storage.
  const sessionToken = sessionStorage.getItem(key)
  if (sessionToken) {
    localStorage.setItem(key, sessionToken)
    sessionStorage.removeItem(key)
  }
  return sessionToken
}

// Keep the rendered seller page bound to its identity until the document reloads.
let renderedAdminToken: string | null | undefined
export function pinAdminToken() {
  renderedAdminToken = storedToken('admin')
}

export function getToken(area: Area) {
  return area === 'admin' && renderedAdminToken !== undefined ? renderedAdminToken : storedToken(area)
}

export function clearToken(area: Area, expectedToken = getToken(area)) {
  if (storedToken(area) !== expectedToken) return
  if (area === 'admin' && expectedToken) invalidateSellerSession(expectedToken)
  const key = tokenKey(area)
  localStorage.removeItem(key)
  sessionStorage.removeItem(key)
  window.dispatchEvent(new CustomEvent('authentication-cleared', { detail: area }))
}

export function setToken(area: Area, token: string) {
  const key = tokenKey(area)
  localStorage.setItem(key, token)
  sessionStorage.removeItem(key)
}

type RequestAuth = { token: string | null; clearOnUnauthorized: boolean }

function assertRequestIdentity(area: Area, token: string | null) {
  if (area === 'admin' && storedToken(area) !== token) {
    window.location.reload()
    throw new ApiRequestError('账号已切换，请等待页面刷新。', 409, 'AUTH_CONTEXT_CHANGED', {})
  }
}

export async function request<T>(path: string, init: RequestInit = {}, area: Area = 'customer', auth?: RequestAuth): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const method = (init.method || 'GET').toUpperCase()
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const contextToken = getToken(area)
  assertRequestIdentity(area, contextToken)
  const token = auth ? auth.token : contextToken
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const unlockButton = lockActiveButton()
  try {
    const response = await fetch(`/api/v1${path}`, { ...init, headers })
    const contentType = response.headers.get('content-type') || ''
    const payload = contentType.includes('json') ? await response.json() : await response.text()
    assertRequestIdentity(area, contextToken)
    if (!response.ok) {
      if (response.status === 401 && (auth?.clearOnUnauthorized ?? true)) clearToken(area, contextToken)
      throw apiRequestError(payload, response.status)
    }
    return payload as T
  } finally {
    unlockButton()
  }
}

export async function download(path: string, init: RequestInit = {}, area: Area = 'customer') {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'text/csv, application/json')
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = getToken(area)
  assertRequestIdentity(area, token)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const unlockButton = lockActiveButton()
  try {
    const response = await fetch(`/api/v1${path}`, { ...init, headers })
    assertRequestIdentity(area, token)
    if (!response.ok) {
      const contentType = response.headers.get('content-type') || ''
      const payload = contentType.includes('json') ? await response.json() : await response.text()
      if (response.status === 401) clearToken(area, token)
      throw apiRequestError(payload, response.status)
    }
    const disposition = response.headers.get('content-disposition') || ''
    const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1]
    const blob = await response.blob()
    assertRequestIdentity(area, token)
    return { blob, filename: encodedName ? decodeURIComponent(encodedName) : plainName || 'export.csv' }
  } finally {
    unlockButton()
  }
}

export const api = {
  login: (area: Area, body: Record<string, string>) => request<{ accessToken: string; member?: SellerIdentity['member']; merchant: MerchantIdentity }>(`/${area}/auth/login`, { method: 'POST', body: JSON.stringify(body) }, area, { token: null, clearOnUnauthorized: false }),
  sellerSession: (token: string) => request<SellerIdentity>('/admin/me', {}, 'admin', { token, clearOnUnauthorized: false }),
  me: (area: Area) => request<Record<string, unknown>>(`/${area}/me`, {}, area),
}

export type AdminMember = {
  id: number; account: string; name: string; active: boolean; merchantAdmin: boolean
  permissions: string[]; updatedAt: string
}
export type AdminCustomer = {
  id: number; userCode: string; name: string; email: string; referrerName: string | null
  customerType: 'partner' | 'regular'; priceTier: 'V1' | 'V2' | 'V3' | 'V4' | 'V5' | 'V6'
  active: boolean; orderCount: number; referralCount?: number; totalAmount: number | string
  unpaidCount: number; unpaidAmount: number | string; lastLogin: string | null; createdAt: string
}

export type MerchantMailSettings = {
  smtpHost: string; smtpPort: number; smtpUsername: string; tlsMode: 'starttls' | 'ssl'
  senderEmail: string; senderName: string; passwordConfigured: boolean
}
export type MerchantMailSettingsInput = {
  smtpHost: string; smtpPort: number | null; smtpUsername: string; smtpPassword: string
  tlsMode: 'starttls' | 'ssl'; senderEmail: string; senderName: string
}
export type MerchantShippingFees = Record<'v1' | 'v2' | 'v3' | 'v4' | 'v5' | 'v6', number | string>
export type MerchantFreeShippingThresholds = Record<'v1' | 'v2' | 'v3' | 'v4' | 'v5' | 'v6', number | string | null>
export type MerchantPricingTier = 'V1' | 'V2' | 'V3' | 'V4' | 'V5' | 'V6'
export type MerchantIdentity = {
  id: number; code: string; name: string; active: boolean
  updatedAt: string; domain: string; logoUrl: string; theme: string
  registrationEnabled: boolean; commissionEnabled: boolean
  cryptoEnabled: boolean; onlinePaymentLinkEnabled: boolean
}
export type MerchantAdministrator = { id: number; account: string; name: string; active: boolean }
export type Merchant = MerchantIdentity & { registrationDefaultTier: MerchantPricingTier; shippingFees: MerchantShippingFees; freeShippingThresholds: MerchantFreeShippingThresholds; mailSettings: MerchantMailSettings | null; administrator: MerchantAdministrator | null }
export type MerchantPage = { items: Merchant[]; page: number; total: number; totalPages: number }
export type MerchantCreate = {
  code: string; name: string; shippingFees: MerchantShippingFees; freeShippingThresholds: MerchantFreeShippingThresholds; domain: string; logoUrl: string; theme: string
  adminAccount: string; adminName: string; password: string; confirmation: string
  registrationEnabled: boolean; registrationDefaultTier: MerchantPricingTier; commissionEnabled: boolean
  cryptoEnabled: boolean; onlinePaymentLinkEnabled: boolean
  mailSettings: MerchantMailSettingsInput | null
}
export const customerRegistrationApi = {
  requestCode: (email: string) => request<{ message: string }>('/customer/auth/registration-code', { method: 'POST', body: JSON.stringify({ email }) }, 'customer', { token: null, clearOnUnauthorized: false }),
  register: (body: { email: string; name: string; password: string; confirmation: string; verificationCode: string; inviteCode?: string }) =>
    request<{ registered: boolean }>('/customer/auth/register', { method: 'POST', body: JSON.stringify(body) }, 'customer', { token: null, clearOnUnauthorized: false }),
}
export const merchantApi = {
  page: (page: number) => request<MerchantPage>(`/platform/merchants?page=${page}`, {}, 'platform'),
  create: (body: MerchantCreate) => request<Merchant>('/platform/merchants', { method: 'POST', body: JSON.stringify(body) }, 'platform'),
  uploadLogo: async (file: File) => {
    const form = new FormData(); form.append('file', file)
    return request<{ logoUrl: string }>('/platform/merchant-logos', { method: 'POST', body: form }, 'platform')
  },
  update: (id: number, body: { name: string; active: boolean; shippingFees: MerchantShippingFees; freeShippingThresholds: MerchantFreeShippingThresholds; expectedUpdatedAt: string; domain: string; logoUrl: string; theme: string; registrationEnabled: boolean; registrationDefaultTier: MerchantPricingTier; commissionEnabled: boolean; cryptoEnabled: boolean; onlinePaymentLinkEnabled: boolean; mailSettings: MerchantMailSettingsInput | null; password?: string; confirmation?: string }) =>
    request<Merchant>(`/platform/merchants/${id}`, { method: 'PUT', body: JSON.stringify(body) }, 'platform'),
  testEmail: (id: number, recipient: string) => request<void>(`/platform/merchants/${id}/email-settings/test`, { method: 'POST', body: JSON.stringify({ recipient }) }, 'platform'),
}

export type PlatformPage<T> = { items: T[]; page: number; pageSize: number; total: number; totalPages: number }
export type PlatformOrder = {
  id: number; merchantId: number; merchantName: string; merchantCode: string; orderNo: string
  customerId: number; customerCodeSnapshot: string; buyerName: string; email: string
  orderStatus: string; paymentStatus: string; auditStatus: string
  subtotal: number | string; shippingFee: number | string; discountAmount: number | string
  totalAmount: number | string; paidAmount: number | string; createdAt: string; updatedAt: string; city?: string
  items?: Array<{ skuSnapshot?: string; sku?: string; name: string; productType?: string; type?: string; quantity: number; unitPrice: number | string; lineAmount: number | string }>
  payments?: Array<{ kind: string; channelSnapshot: string; transactionNo: string; amount: number | string; status: string; occurredAt: string }>
  events?: Array<{ action: string; fromStatus: string | null; toStatus: string | null; note: string | null; occurredAt: string; actorType: string; actorName: string }>
}
export type PlatformBuyer = {
  id: number; merchantId: number; merchantName: string; merchantCode: string; customerCode: string
  customerName: string; email: string; customerType: string; priceTier: string; active: boolean
  createdAt: string; lastLoginAt: string | null; orderCount: number; orderAmount: number | string; openPaymentCount: number
  recentOrders?: Array<{ orderNo: string; orderStatus: string; paymentStatus: string; auditStatus: string; totalAmount: number | string; createdAt: string }>
}
export type PlatformMerchantPerformance = {
  merchantId: number; merchantCode: string; merchantName: string; active: boolean; domain: string
  orderCount: number; grossAmount: number | string; receivedAmount: number | string; openOrderCount: number
  buyerCount: number; newBuyerCount: number; lastOrderAt: string | null
}
export type PlatformOverview = {
  merchantCount: number; activeMerchantCount: number; buyerCount: number; activeBuyerCount: number
  orderCount: number; grossOrderAmount: number | string; receivedAmount: number | string
  pendingAuditCount: number; pendingVerificationCount: number; pendingFulfillmentCount: number
  trend: Array<{ day: string; orderCount: number; orderAmount: number | string }>
  trendFrom: string; trendTo: string
}
export type PlatformAccessRequest = { purpose: string }
export type PlatformOrderContact = {
  merchantId: number; orderNo: string; customerName: string; email: string; recipientName: string; city: string
  address: { street?: string; apt?: string | null; city?: string; region?: string | null; postalCode?: string; phone?: string }
}
export type PlatformBuyerContact = {
  merchantId: number; id: number; customerCode: string; customerName: string; email: string
  active: boolean; emailVerifiedAt: string | null; lastLoginAt: string | null; createdAt: string
}
export type PlatformAuditEntry = {
  source: string; auditId: number; actorName: string; merchantId: number; merchantName: string
  resourceType: string; resourceId: number; action: string; purpose: string; createdAt: string
}
export type PlatformOrderFilter = {
  merchantId?: number | null; q?: string; orderStatus?: string; paymentStatus?: string; auditStatus?: string; from?: string; to?: string
}
export type PlatformBuyerFilter = { merchantId?: number | null; q?: string; active?: boolean | null; from?: string; to?: string }

function platformQuery(values: Record<string, string | number | boolean | null | undefined>) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => { if (value !== null && value !== undefined && value !== '') params.set(key, String(value)) })
  return params.toString()
}

export const platformApi = {
  overview: () => request<PlatformOverview>('/platform/overview', {}, 'platform'),
  orders: (filter: PlatformOrderFilter, page: number) => request<PlatformPage<PlatformOrder>>(`/platform/orders?${platformQuery({ ...filter, page })}`, {}, 'platform'),
  orderAccess: (merchantId: number, orderId: number, body: PlatformAccessRequest) => request<PlatformOrder>(`/platform/orders/${merchantId}/${orderId}/access`, { method: 'POST', body: JSON.stringify(body) }, 'platform'),
  orderContact: (merchantId: number, orderId: number, body: PlatformAccessRequest) => request<PlatformOrderContact>(`/platform/orders/${merchantId}/${orderId}/contact`, { method: 'POST', body: JSON.stringify(body) }, 'platform'),
  buyers: (filter: PlatformBuyerFilter, page: number) => request<PlatformPage<PlatformBuyer>>(`/platform/buyers?${platformQuery({ ...filter, page })}`, {}, 'platform'),
  buyerAccess: (merchantId: number, buyerId: number, body: PlatformAccessRequest) => request<PlatformBuyer>(`/platform/buyers/${merchantId}/${buyerId}/access`, { method: 'POST', body: JSON.stringify(body) }, 'platform'),
  buyerContact: (merchantId: number, buyerId: number, body: PlatformAccessRequest) => request<PlatformBuyerContact>(`/platform/buyers/${merchantId}/${buyerId}/contact`, { method: 'POST', body: JSON.stringify(body) }, 'platform'),
  tasks: (type: 'audit' | 'payment' | 'fulfillment', merchantId: number | null, page: number) => request<PlatformPage<PlatformOrder>>(`/platform/tasks/${type}?${platformQuery({ merchantId, page })}`, {}, 'platform'),
  merchantPerformance: (from: string | null, to: string | null, page: number) => request<PlatformPage<PlatformMerchantPerformance> & { from: string; to: string }>(`/platform/merchant-performance?${platformQuery({ from, to, page })}`, {}, 'platform'),
  audit: (page: number) => request<PlatformPage<PlatformAuditEntry>>(`/platform/audit?page=${page}`, {}, 'platform'),
  export: (type: 'orders' | 'buyers' | 'merchants', body: Record<string, unknown>) => download(`/platform/exports/${type}`, { method: 'POST', body: JSON.stringify(body) }, 'platform'),
}
