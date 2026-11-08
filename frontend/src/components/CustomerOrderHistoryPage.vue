<script setup lang="ts">
import AppModal from './AppModal.vue'
import OrderParcelList from './OrderParcelList.vue'
import OrderPaymentRecords from './OrderPaymentRecords.vue'
import CustomerDatePicker from './CustomerDatePicker.vue'
import { fulfillmentStageState, partialFulfillmentStage, type FulfillmentStageKey, type FulfillmentStageState } from '../lib/fulfillment-progress'
import { whatsappConsultationUrl } from '../lib/customer-support'
import type { CustomerOrder, CustomerOrderItem } from '../types/customer'

type OrderPage = { items: CustomerOrder[]; total: number; page: number; totalPages: number }
type OrderAddress = {
  name?: string
  street?: string
  apt?: string | null
  city?: string
  region?: string | null
  postalCode?: string
  phone?: string
}
type OrderEvent = { parcelId?: number | null; toStatus?: string | null; note?: string | null }
type OrderDetail = CustomerOrder & {
  id?: number
  status?: string
  paymentStatus?: string
  paymentSubmission?: { transactionNo?: string; submittedAt?: string }
  events?: OrderEvent[]
  address?: OrderAddress
  buyerNote?: string
}
type OrderStats = {
  total: number
  received: number
  transit: number
  pendingShip: number
  payableOrders: number
  payable: number | string
  cancelled: number
}

const props = defineProps<{
  orders: OrderPage
  orderStats: OrderStats
  orderPageNumbers: number[]
  selectedOrder: OrderDetail
  detailOpen: boolean
  dateFilter: string
  dateFrom: string
  dateTo: string
  dateLocale: string
  paymentFilter: string
  shippingFilter: string
  search: string
  loading: boolean
  pendingActions: ReadonlySet<string>
  whatsappNumber: string | null
  t: (text: string) => string
  money: (value: unknown) => string
  formatDateTime: (value: unknown) => string
  orderItemNames: (order: CustomerOrder) => string
  statusLabel: (value: string) => string
  statusClass: (value: string) => string
}>()

const emit = defineEmits<{
  dateFilter: [filter: string]
  paymentFilter: [filter: string]
  shippingFilter: [filter: string]
  'update:dateFrom': [value: string]
  'update:dateTo': [value: string]
  'update:search': [value: string]
  applyDateRange: []
  setPage: [page: number]
  copyOrderNumber: [orderNo: string]
  copyValue: [value: string, successMessage: string]
  openPayment: [order: CustomerOrder]
  editOrder: [order: CustomerOrder]
  openOrder: [order: CustomerOrder]
  closeOrderDetail: []
  cancelOrder: [order: CustomerOrder]
}>()

const dateOptions = [
  { value: 'all', label: 'All time' },
  { value: 'week', label: 'This week' },
  { value: 'month', label: 'This month' },
  { value: 'lastmonth', label: 'Last month' },
]
const fulfillmentStages: FulfillmentStageKey[] = ['pending', 'packed', 'shipped', 'received', 'completed']

function completionNote(order: OrderDetail) {
  return order.events?.find(event => event.parcelId == null && event.toStatus === 'completed')?.note
}

function stageLabel(stage: FulfillmentStageKey) {
  if (stage === 'pending') return 'Order submitted'
  return ({ packed: 'Packed', shipped: 'Shipped', received: 'Received', completed: 'Completed' } as Record<string, string>)[stage] || stage
}

function paymentStatusLabel(status: string, translate: (text: string) => string, getStatusLabel: (value: string) => string) {
  const labels: Record<string, string> = {
    unpaid: 'Awaiting payment',
    pending_verification: 'Payment confirmation pending',
    partially_paid: 'Partially paid',
    paid: 'Fully paid',
  }
  return labels[status] ? translate(labels[status]) : getStatusLabel(status)
}

function orderProgressLabel(order: CustomerOrder, translate: (text: string) => string, getStatusLabel: (value: string) => string) {
  const status = order.status
  const partial = !['completed', 'cancelled'].includes(status) ? partialFulfillmentStage(order.fulfillmentSummary) : null
  if (partial) return translate({ packed: 'Partially packed', shipped: 'Partially shipped', received: 'Partially received' }[partial])
  const labels: Record<string, string> = {
    pending: 'Seller processing',
    packed: 'Preparing to ship',
    shipped: 'In transit',
  }
  return labels[status] ? translate(labels[status]) : getStatusLabel(status)
}

function stageTitle(order: OrderDetail, stage: FulfillmentStageKey, translate: (text: string) => string, getStatusLabel: (value: string) => string) {
  return fulfillmentStageState(order.status, order.fulfillmentSummary, stage) === 'current'
    ? orderProgressLabel(order, translate, getStatusLabel)
    : translate(stageLabel(stage))
}

function stageStateLabel(state: FulfillmentStageState, translate: (text: string) => string) {
  return translate({ done: 'Completed', current: 'Current status', partial: 'Partially complete', upcoming: 'Upcoming', stopped: 'Stopped' }[state])
}

function addressLines(address?: OrderAddress) {
  if (!address) return []
  return [
    address.street,
    address.apt,
    [address.city, address.region, address.postalCode].filter(Boolean).join(', '),
    address.phone,
  ].filter((line): line is string => Boolean(line))
}

function copyableOrderAddress(address?: OrderAddress) {
  return [address?.name, ...addressLines(address)].filter(Boolean).join('\n')
}

function copyOrderItem(item: CustomerOrderItem, translate: (text: string) => string, formatMoney: (value: unknown) => string) {
  const details = [
    item.name,
    `${translate('Quantity')}: ${item.quantity}`,
    `${translate('Unit price')}: ${formatMoney(item.unitPrice)}`,
    `${translate('Amount')}: ${formatMoney(item.lineTotal)}`,
  ].join('\n')
  emit('copyValue', details, 'Items copied.')
}

function whatsappOrderUrl(orderNo: string) {
  return whatsappConsultationUrl(props.whatsappNumber, `${props.t('Hello, I have a question about order:')} ${orderNo}`)
}

</script>

<template>
  <main class="container history-page" aria-live="polite">
    <h1 class="page-title">{{ t('Order History') }}</h1>

    <section class="customer-stats" aria-labelledby="statsTitle">
      <div class="stats-head"><h2 class="stats-title" id="statsTitle">{{ t('Statistics') }}</h2></div>
      <div class="stats-grid">
        <article class="stats-card"><span class="stats-card-label">{{ t('Total orders') }}</span><span class="stats-card-value">{{ orderStats.total }}</span><span class="stats-card-unit">{{ t('Orders') }}</span></article>
        <article class="stats-card"><span class="stats-card-label">{{ t('Received') }}</span><span class="stats-card-value">{{ orderStats.received }}</span><span class="stats-card-unit">{{ t('Orders') }}</span></article>
        <article class="stats-card"><span class="stats-card-label">{{ t('In transit') }}</span><span class="stats-card-value">{{ orderStats.transit }}</span><span class="stats-card-unit">{{ t('Orders') }}</span></article>
        <article class="stats-card"><span class="stats-card-label">{{ t('Awaiting shipment') }}</span><span class="stats-card-value">{{ orderStats.pendingShip }}</span><span class="stats-card-unit">{{ t('Orders') }}</span></article>
        <article class="stats-card"><span class="stats-card-label">{{ t('Unpaid orders') }}</span><span class="stats-card-value">{{ orderStats.payableOrders }}</span><span class="stats-card-unit">{{ t('Orders') }}</span></article>
        <article class="stats-card"><span class="stats-card-label">{{ t('Payable total') }}</span><span class="stats-card-value">{{ money(orderStats.payable) }}</span><span class="stats-card-unit">{{ t('USD') }}</span></article>
      </div>
    </section>

    <section class="history-filters filter-card" :aria-label="t('Order filters')">
      <div class="filter-head"><h2 class="filter-title">{{ t('Refine orders') }}</h2></div>
      <div class="history-filter-row">
        <div class="history-filter-group history-date-group">
          <span class="history-filter-label">{{ t('Date') }}</span>
          <div class="history-seg seg">
            <button v-for="item in dateOptions" :key="item.value" type="button" :disabled="loading" :aria-pressed="dateFilter === item.value" :class="{ active: dateFilter === item.value }" @click="emit('dateFilter', item.value)">{{ t(item.label) }}</button>
          </div>
        </div>
      </div>
      <div class="history-filter-row history-payment-row">
        <div class="history-filter-group">
          <span class="history-filter-label">{{ t('Payment') }}</span>
          <div class="history-seg seg">
            <button type="button" :disabled="loading" :aria-pressed="paymentFilter === 'all'" :class="{ active: paymentFilter === 'all' }" @click="emit('paymentFilter', 'all')">{{ t('All') }}</button>
            <button type="button" :disabled="loading" :aria-pressed="paymentFilter === 'payable'" :class="{ active: paymentFilter === 'payable' }" @click="emit('paymentFilter', 'payable')">{{ t('Unpaid') }}</button>
            <button type="button" :disabled="loading" :aria-pressed="paymentFilter === 'paid'" :class="{ active: paymentFilter === 'paid' }" @click="emit('paymentFilter', 'paid')">{{ t('Fully paid') }}</button>
            <button type="button" :disabled="loading" :class="{ active: paymentFilter === 'partially_paid' }" @click="emit('paymentFilter', 'partially_paid')">{{ t('Partially paid') }}</button>
            <button type="button" :disabled="loading" :class="{ active: paymentFilter === 'pending_verification' }" @click="emit('paymentFilter', 'pending_verification')">{{ t('Pending verification') }}</button>
          </div>
        </div>
        <div class="history-filter-group">
          <span class="history-filter-label">{{ t('Shipping') }}</span>
          <div class="history-seg seg">
            <button type="button" :disabled="loading" :aria-pressed="shippingFilter === 'all'" :class="{ active: shippingFilter === 'all' }" @click="emit('shippingFilter', 'all')">{{ t('All') }}</button>
            <button type="button" :disabled="loading" :aria-pressed="shippingFilter === 'not'" :class="{ active: shippingFilter === 'not' }" @click="emit('shippingFilter', 'not')">{{ t('Awaiting shipment') }}</button>
            <button type="button" :disabled="loading" :aria-pressed="shippingFilter === 'partial'" :class="{ active: shippingFilter === 'partial' }" @click="emit('shippingFilter', 'partial')">{{ t('Partially shipped') }}</button>
            <button type="button" :disabled="loading" :aria-pressed="shippingFilter === 'shipped'" :class="{ active: shippingFilter === 'shipped' }" @click="emit('shippingFilter', 'shipped')">{{ t('Shipped') }}</button>
          </div>
        </div>
      </div>
      <div class="history-range-row">
        <div class="history-range-field"><span class="history-filter-label">{{ t('Range') }}</span><CustomerDatePicker :model-value="dateFrom" :locale="dateLocale" :label="t('Start date')" :disabled="loading" :t="t" @update:model-value="emit('update:dateFrom', $event)" @apply="emit('applyDateRange')" /></div>
        <span class="history-range-separator">{{ t('to') }}</span>
        <div class="history-range-field history-range-end"><span class="sr-only">{{ t('End date') }}</span><CustomerDatePicker :model-value="dateTo" :locale="dateLocale" :label="t('End date')" :disabled="loading" :t="t" @update:model-value="emit('update:dateTo', $event)" @apply="emit('applyDateRange')" /></div>
        <label class="history-search"><span class="sr-only">{{ t('Search orders') }}</span><input autocomplete="off" type="search" :disabled="loading" :value="search" :placeholder="t('Order number / product / tracking number…')" @input="emit('update:search', ($event.target as HTMLInputElement).value)"></label>
      </div>
    </section>

    <div class="order-list-heading"><h2 class="order-list-title">{{ t('Orders') }}</h2><span class="order-result-count">{{ orders.total }}{{ t('orders') }}</span></div>
    <div id="orderList">
      <article v-for="order in orders.items" :key="order.id" class="order-card">
        <div class="order-head">
          <div class="order-title-line">
            <span class="order-id">{{ t('Order #') }}{{ order.orderNo }}</span>
            <button class="copy-order-button" type="button" :aria-label="t('Copy order number')" @click="emit('copyOrderNumber', order.orderNo)"><svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="3" width="9" height="11" rx="1"/><path d="M12 15H4a1 1 0 0 1-1-1V5"/></svg></button>
            <small>{{ t('Created:') }}{{ formatDateTime(order.createdAt) }}</small>
          </div>
          <div class="order-state-group">
            <div :class="['order-state', 'order-progress-state', statusClass(order.status)]">
              <span class="order-state-label">{{ t('Order progress') }}</span>
              <strong>{{ orderProgressLabel(order, t, statusLabel) }}</strong>
            </div>
            <div v-if="order.status !== 'cancelled'" :class="['order-state', 'order-payment-state', statusClass(order.paymentStatus)]">
              <span class="order-state-label">{{ t('Payment status') }}</span>
              <strong>{{ paymentStatusLabel(order.paymentStatus, t, statusLabel) }}</strong>
            </div>
          </div>
        </div>
        <p v-if="order.paymentLinkRequested && order.paymentStatus !== 'paid'" class="payment-link-request-badge">{{ t('Online payment link requested') }}</p>
        <p v-if="order.fulfillmentSummary?.started" class="history-parcel-summary">{{ t('Parcels') }}: {{ order.fulfillmentSummary.parcelCount }} · {{ t('Shipped') }}: {{ order.fulfillmentSummary.shippedParcelCount }} · {{ t('Received') }}: {{ order.fulfillmentSummary.receivedParcelCount }}<span v-if="order.fulfillmentSummary.unpackedQuantity"> · {{ t('Unpacked items') }}: {{ order.fulfillmentSummary.unpackedQuantity }}</span></p>
        <div class="order-card-main">
          <div class="order-item-preview"><span class="order-item-count">{{ order.items?.length || 0 }}{{ t('items') }}</span><strong>{{ orderItemNames(order) || t('Order items') }}</strong></div>
          <div class="order-total"><span>{{ t('Order total') }}<small v-if="Number(order.discountAmount) > 0"> · {{ t('Discount') }} {{ money(order.discountAmount) }}</small></span><strong>{{ money(order.total) }}</strong></div>
        </div>
        <div class="order-card-foot">
          <div class="order-actions">
            <button v-if="['unpaid', 'partially_paid'].includes(order.paymentStatus) && order.status !== 'cancelled'" class="action-button primary-action" type="button" :disabled="loading || pendingActions.has('payment-channel-load') || pendingActions.has('payment-submit')" @click="emit('openPayment', order)"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="2"/><path d="M3 8h14M6 13h3"/></svg>{{ t('Pay now') }}</button>
            <button v-if="order.allowedActions?.includes('edit')" class="action-button" type="button" :disabled="loading || pendingActions.has('order-edit')" @click="emit('editOrder', order)"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m12.8 4.2 3 3M4 16l3.5-.8 8.8-8.8a2.1 2.1 0 0 0-3-3l-8.8 8.8L4 16Z"/></svg>{{ t('Edit') }}</button>
            <button class="action-button" type="button" :disabled="loading || pendingActions.has('order-detail')" @click="emit('openOrder', order)">{{ t('Detail') }}</button>
            <button v-if="order.allowedActions?.includes('cancel')" class="action-button danger-action" type="button" :disabled="loading || pendingActions.has('order-cancel')" @click="emit('cancelOrder', order)"><svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7"/><path d="m7.5 7.5 5 5m0-5-5 5"/></svg>{{ t('Cancel') }}</button>
          </div>
          <a v-if="whatsappOrderUrl(order.orderNo)" class="action-button whatsapp-action" :href="whatsappOrderUrl(order.orderNo)" target="_blank" rel="noopener noreferrer" :aria-label="`${t('WhatsApp consultation about this order')} ${order.orderNo}`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11.7a8 8 0 0 1-11.9 7l-4.1 1.1 1.1-4A8 8 0 1 1 20 11.7Z"/><path d="M9 8.3c.2-.4.4-.4.7-.4h.5c.2 0 .4.1.5.4l.6 1.4c.1.3.1.5-.1.7l-.5.6c-.2.2-.2.4-.1.6.5.8 1.1 1.4 1.9 1.8.2.1.4.1.6-.1l.7-.8c.2-.2.4-.3.7-.2l1.4.7c.3.1.4.3.4.5 0 .6-.3 1.2-.8 1.5-.5.4-1.1.5-1.8.3-1.1-.3-2.2-1-3.2-2-1-.9-1.7-2-2-3.1-.2-.7-.1-1.3.2-1.8.2-.4.6-.7 1-.9Z"/></svg><span>WhatsApp</span></a>
        </div>
      </article>
      <div v-if="!orders.items.length" class="empty-state">{{ t('No orders match these filters.') }}</div>
    </div>

    <div v-if="orders.totalPages > 1" class="history-pagination">
      <span>{{ t('Showing') }}{{ (orders.page - 1) * 10 + 1 }}{{ t('–') }}{{ Math.min(orders.page * 10, orders.total) }}{{ t('of') }}{{ orders.total }}</span>
      <div>
        <button type="button" :disabled="loading || orders.page <= 1" @click="emit('setPage', orders.page - 1)">{{ t('Previous') }}</button>
        <button v-for="page in orderPageNumbers" :key="page" type="button" :disabled="loading" :aria-current="orders.page === page ? 'page' : undefined" :class="{ active: orders.page === page }" @click="emit('setPage', page)">{{ page }}</button>
        <button type="button" :disabled="loading || orders.page >= orders.totalPages" @click="emit('setPage', orders.page + 1)">{{ t('Next') }}</button>
      </div>
    </div>

    <AppModal v-if="detailOpen && selectedOrder.id" :label="`${t('Order #')}${selectedOrder.orderNo || ''}`" content-class="customer-order-detail-modal" @close="emit('closeOrderDetail')">
      <header class="customer-order-modal-header">
        <div class="customer-order-modal-title-row">
          <h2 class="modal-title">{{ t('Order #') }}{{ selectedOrder.orderNo }}</h2>
          <button class="copy-order-button" type="button" :aria-label="t('Copy order number')" @click="emit('copyValue', selectedOrder.orderNo || '', 'Order number copied.')"><svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="3" width="9" height="11" rx="1"/><path d="M12 15H4a1 1 0 0 1-1-1V5"/></svg></button>
        </div>
        <button class="close-button" type="button" :aria-label="t('Close')" @click="emit('closeOrderDetail')">×</button>
      </header>
      <div class="customer-order-modal-body">
        <p v-if="selectedOrder.paymentLinkRequested && selectedOrder.paymentStatus !== 'paid'" class="payment-link-request-badge detail-payment-link-requested">{{ t('Online payment link requested') }}</p>
        <section class="order-progress-section">
          <h3>{{ t('Order Progress Timeline') }}</h3>
          <p v-if="selectedOrder.status === 'cancelled'" class="order-progress-cancelled">{{ t('Order cancelled; fulfillment stopped.') }}</p>
          <ol class="order-progress-timeline">
            <li v-for="stage in fulfillmentStages" :key="stage" class="order-progress-step" :class="fulfillmentStageState(selectedOrder.status, selectedOrder.fulfillmentSummary, stage)">
              <span class="order-progress-marker" aria-hidden="true"></span>
              <div class="order-progress-copy"><strong>{{ stageTitle(selectedOrder, stage, t, statusLabel) }}</strong><span>{{ stageStateLabel(fulfillmentStageState(selectedOrder.status, selectedOrder.fulfillmentSummary, stage), t) }}</span></div>
            </li>
          </ol>
          <p v-if="completionNote(selectedOrder)" class="order-progress-note">{{ t('Completion note') }}: {{ completionNote(selectedOrder) }}</p>
        </section>

        <section class="order-detail-section">
          <div class="order-detail-section-heading"><h3>{{ t('Items') }}</h3><button class="detail-copy-button" type="button" :aria-label="t('Copy items')" @click="emit('copyValue', (selectedOrder.items || []).map(item => `${item.name} × ${item.quantity}`).join('\n'), 'Items copied.')"><svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="3" width="9" height="11" rx="1"/><path d="M12 15H4a1 1 0 0 1-1-1V5"/></svg></button></div>
          <div class="order-line-table">
            <div class="order-line-table-head"><span>{{ t('Product') }}</span><span>{{ t('Quantity') }}</span><span>{{ t('Unit price') }}</span><span>{{ t('Amount') }}</span></div>
            <div v-for="item in selectedOrder.items || []" :key="item.productId" class="order-line-table-row">
              <div class="order-line-product"><div><strong>{{ item.name }}</strong><div v-if="item.coas?.length" class="order-item-coa-links"><a v-for="coa in item.coas" :key="coa.fileUuid" class="link-button" :href="coa.publicUrl" target="_blank" rel="noopener noreferrer">{{ t('View COA') }} · {{ coa.name }}</a></div></div><button class="detail-copy-button order-line-copy" type="button" :aria-label="`${t('Copy items')}: ${item.name}`" :title="t('Copy items')" @click="copyOrderItem(item, t, money)"><svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="6" width="9" height="11" rx="1.2"/><path d="M12 6V4.5A1.5 1.5 0 0 0 10.5 3h-7A1.5 1.5 0 0 0 2 4.5v8A1.5 1.5 0 0 0 3.5 14H6"/></svg></button></div><span>{{ item.quantity }}</span><span>{{ money(item.unitPrice) }}</span><strong>{{ money(item.lineTotal) }}</strong>
            </div>
            <p v-if="!selectedOrder.items?.length" class="order-detail-empty">{{ t('No order items') }}</p>
          </div>
        </section>

        <section v-if="selectedOrder.address" class="order-detail-section">
          <div class="order-detail-section-heading"><h3>{{ t('Shipping Address') }}</h3><button class="detail-copy-button" type="button" :aria-label="t('Copy address')" @click="emit('copyValue', copyableOrderAddress(selectedOrder.address), 'Address copied.')"><svg viewBox="0 0 18 18" aria-hidden="true"><rect x="6" y="3" width="9" height="11" rx="1"/><path d="M12 15H4a1 1 0 0 1-1-1V5"/></svg></button></div>
          <div class="order-address-card detail-address-card"><strong>{{ selectedOrder.address.name }}</strong><p v-for="line in addressLines(selectedOrder.address)" :key="line">{{ line }}</p></div>
        </section>
        <section class="order-detail-section order-note-detail">
          <h3>{{ t('Order note') }}</h3>
          <p>{{ selectedOrder.buyerNote || t('No order note provided.') }}</p>
        </section>
        <section class="order-detail-section tracking-detail"><h3>{{ t('Parcels') }}</h3><p v-if="selectedOrder.fulfillmentSummary?.unpackedQuantity">{{ t('Unpacked items') }}: {{ selectedOrder.fulfillmentSummary.unpackedQuantity }}</p><OrderParcelList :parcels="selectedOrder.parcels || []" :t="t" :format-date-time="formatDateTime" @copy-value="(value, message) => emit('copyValue', value, message)" /></section>
        <section class="order-detail-totals">
          <div><span>{{ t('Subtotal') }}</span><strong>{{ money(selectedOrder.subtotal) }}</strong></div>
          <div><span>{{ t('Shipping') }}</span><strong>{{ money(selectedOrder.shippingFee) }}</strong></div>
          <div v-if="Number(selectedOrder.discountAmount) > 0"><span>{{ t('Discount') }}</span><strong>−{{ money(selectedOrder.discountAmount) }}</strong></div>
          <div class="order-detail-grand-total"><span>{{ t('Order total') }}</span><strong>{{ money(selectedOrder.total) }}</strong></div>
          <template v-if="selectedOrder.status !== 'cancelled' && ['pending_verification', 'partially_paid'].includes(selectedOrder.paymentStatus || '')">
            <div><span>{{ t('Amount paid') }}</span><strong>{{ money(selectedOrder.paidAmount) }}</strong></div>
            <div><span>{{ t('Remaining to pay') }}</span><strong>{{ money(selectedOrder.remainingAmount) }}</strong></div>
          </template>
        </section>
        <OrderPaymentRecords :order-id="selectedOrder.id" area="customer" :t="t" :money="money" :format-date-time="formatDateTime" @copy-value="(value, message) => emit('copyValue', value, message)">
          <template #status>
            <span v-if="selectedOrder.status !== 'cancelled' && selectedOrder.paymentStatus" :class="['detail-payment-badge', statusClass(selectedOrder.paymentStatus)]" :aria-label="`${t('Payment status')}: ${paymentStatusLabel(selectedOrder.paymentStatus, t, statusLabel)}`">
              <span class="detail-payment-dot"></span>{{ paymentStatusLabel(selectedOrder.paymentStatus, t, statusLabel) }}
            </span>
          </template>
        </OrderPaymentRecords>
      </div>
      <footer class="customer-order-modal-footer"><button class="modal-cancel" type="button" @click="emit('closeOrderDetail')">{{ t('Close') }}</button></footer>
    </AppModal>
  </main>
</template>

<style scoped>
.history-parcel-summary { padding: 0 18px; color: #668173; font-size: 12px; margin: 0 0 12px; }
</style>
