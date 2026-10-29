<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppModal from './AppModal.vue'
import CustomerPaymentAmount from './CustomerPaymentAmount.vue'
import { request } from '../lib/api'
import { paymentAmountWithRate, validPaymentAmount } from '../lib/payment-amount'
import type { CustomerAddress, CustomerCart, CustomerCartItem, CustomerOrderQuote, CustomerPaymentChannel } from '../types/customer'

const props = defineProps<{
  cart: CustomerCart
  addresses: CustomerAddress[]
  checkoutStep: number
  selectedAddressId: number | null
  selectedAddress?: CustomerAddress
  addressPickerOpen: boolean
  orderNote: string
  orderNo?: string
  orderTotal?: number | string
  orderSubtotal?: number | string
  orderShippingFee?: number | string
  orderDiscountAmount?: number | string
  orderPaidAmount?: number | string
  orderRemainingAmount?: number | string
  orderPaymentStatus?: string
  checkoutQuote?: CustomerOrderQuote | null
  paymentChannel: number | null
  channels: CustomerPaymentChannel[]
  selectedChannel?: CustomerPaymentChannel
  paymentAmount: string
  transactionNo: string
  paymentSuccessOpen: boolean
  t: (text: string) => string
  money: (value: unknown) => string
  statusLabel: (value: string) => string
  cartPending: boolean
  checkoutPending: boolean
  paymentPending: boolean
}>()

const emit = defineEmits<{
  'update:checkoutStep': [step: number]
  'update:selectedAddressId': [addressId: number | null]
  'update:addressPickerOpen': [open: boolean]
  'update:orderNote': [note: string]
  'update:paymentChannel': [channelId: number]
  'update:paymentAmount': [amount: string]
  'update:transactionNo': [transactionNo: string]
  'close-payment-success': []
  continue: []
  back: []
  setQuantity: [item: CustomerCartItem, quantity: number]
  removeFromCart: [productId: number]
  openAddressForm: []
  submitPayment: []
  navigate: [page: string]
}>()

const steps = ['Cart', 'Address', 'Place order', 'Pay']
const hasInvalidCart = computed(() => props.cart.items.some(item => Boolean(item.invalidReason) || item.product.stock < 1 || item.product.stock < item.quantity))
const paymentAmountDue = computed(() => props.money(paymentAmountWithRate(props.paymentAmount, props.selectedChannel?.additionalRate)))
const displayItems = computed(() => {
  if (props.checkoutStep !== 2 || !props.checkoutQuote) return props.cart.items
  return props.checkoutQuote.items.map(quoteItem => {
    const cartItem = props.cart.items.find(item => item.productId === quoteItem.productId)
    return {
      ...cartItem,
      ...quoteItem,
      product: cartItem?.product || { name: quoteItem.name, sku: '', stock: quoteItem.quantity },
    } as CustomerCartItem
  })
})

const qrUrl = ref('')
const qrLoading = ref(false)
const qrUnavailable = ref(false)
const qrPreviewOpen = ref(false)
const copyFeedback = ref('')
let copyResetTimer: number | undefined

watch(() => props.selectedChannel?.qrFileUuid, async (fileUuid) => {
  qrUrl.value = ''
  qrUnavailable.value = false
  if (!fileUuid) return
  qrLoading.value = true
  try {
    const response = await request<{ url: string }>(`/customer/files/${encodeURIComponent(fileUuid)}/signed-url`)
    if (props.selectedChannel?.qrFileUuid === fileUuid) qrUrl.value = response.url
  } catch {
    if (props.selectedChannel?.qrFileUuid === fileUuid) qrUnavailable.value = true
  } finally {
    if (props.selectedChannel?.qrFileUuid === fileUuid) qrLoading.value = false
  }
}, { immediate: true })

onBeforeUnmount(() => window.clearTimeout(copyResetTimer))

function updateQuantity(item: CustomerCartItem, event: Event) {
  const input = event.target as HTMLInputElement
  const quantity = Number(input.value)
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > item.product.stock) {
    input.value = String(item.quantity)
    return
  }
  emit('setQuantity', item, quantity)
}

function selectAddress(addressId: number) {
  emit('update:selectedAddressId', addressId)
  emit('update:addressPickerOpen', false)
}

function addAddress() {
  emit('update:addressPickerOpen', false)
  emit('openAddressForm')
}

function onQrError() {
  qrUrl.value = ''
  qrUnavailable.value = true
}

function showCopyFeedback(message: string) {
  window.clearTimeout(copyResetTimer)
  copyFeedback.value = props.t(message)
  copyResetTimer = window.setTimeout(() => { copyFeedback.value = '' }, 3200)
}

async function copyReceivingAddress() {
  const address = props.selectedChannel?.receivingAddress
  if (!address) return
  let copied = false
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(address)
      copied = true
    } catch {
      copied = false
    }
  }
  if (!copied) {
    const copyBuffer = document.createElement('textarea')
    copyBuffer.value = address
    copyBuffer.setAttribute('readonly', '')
    copyBuffer.style.position = 'fixed'
    copyBuffer.style.opacity = '0'
    document.body.append(copyBuffer)
    try {
      copyBuffer.select()
      copied = document.execCommand('copy')
    } catch {
      copied = false
    } finally {
      copyBuffer.remove()
    }
  }
  if (copied) {
    showCopyFeedback('Address copied.')
  } else {
    showCopyFeedback('Copy is unavailable in this browser.')
  }
}

function openOrderHistory() {
  emit('close-payment-success')
  emit('navigate', 'orders')
}
</script>

<template>
  <main
    class="container checkout-container"
    :class="{
      'checkout-empty-cart': checkoutStep === 0 && !displayItems.length,
      'checkout-address-stage': checkoutStep === 1,
      'checkout-order-stage': checkoutStep === 2,
      'checkout-payment-stage': checkoutStep === 3,
    }"
  >
    <div class="checkout-heading">
      <p class="checkout-kicker">{{ t('Your selection') }}</p>
      <h1 class="page-title">{{ t('Shopping Cart') }}</h1>
      <p class="checkout-intro">{{ t('Review your items, choose where they should go, then place the order.') }}</p>
    </div>

    <div class="step-indicator" :aria-label="t('Checkout progress')">
      <template v-for="(step, index) in steps" :key="step">
        <button
          type="button"
          class="step-item"
          :class="{ active: checkoutStep === index, complete: checkoutStep > index }"
          :aria-current="checkoutStep === index ? 'step' : undefined"
          :disabled="index > checkoutStep || checkoutStep === 3 || cartPending || checkoutPending"
          @click="index <= checkoutStep && checkoutStep < 3 && emit('update:checkoutStep', index)"
        ><span class="step-num">{{ checkoutStep > index ? '✓' : index + 1 }}</span><span class="step-label">{{ t(step) }}</span></button>
        <div v-if="index < 3" class="step-line" :class="{ complete: checkoutStep > index }"></div>
      </template>
    </div>

    <div class="page-wrap">
      <div v-if="checkoutStep === 0 || checkoutStep === 2" id="cartCard" class="cart-card">
        <div class="cart-head"><span class="h-item">{{ t('Item') }}</span><span class="h-price">{{ t('Price') }}</span><span class="h-qty">{{ t('Qty') }}</span><span></span></div>
        <div
          v-for="item in displayItems"
          :key="item.productId"
          class="cart-item"
          :class="{ 'cart-item-unavailable': item.invalidReason || item.product.stock < item.quantity }"
        >
          <div class="cart-item-main">
            <strong class="item-name">{{ item.product.name }}</strong>
            <small>{{ item.product.sku }}</small>
            <small v-if="item.invalidReason || item.product.stock < item.quantity || item.product.stock < 1" class="cart-item-warning">{{ t('Unavailable. Remove this item to continue.') }}</small>
            <small v-else class="cart-stock">{{ item.product.stock }} {{ t('available') }}</small>
          </div>
          <span class="item-price">{{ money(item.unitPrice) }}</span>
          <input autocomplete="off"
            v-if="checkoutStep === 0"
            class="qty-input"
            :value="item.quantity"
            type="number"
            min="1"
            :max="item.product.stock"
            step="1"
            :aria-label="t('Quantity for') + ' ' + item.product.name"
            :disabled="cartPending || Boolean(item.invalidReason) || item.product.stock < 1"
            @change="updateQuantity(item, $event)"
          >
          <span v-else class="confirm-quantity">{{ item.quantity }}</span>
          <button
            v-if="checkoutStep === 0"
            class="remove-btn"
            type="button"
            :disabled="cartPending"
            :aria-label="t('Remove') + ' ' + item.product.name"
            @click="emit('removeFromCart', item.productId)"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 6h12M8 6V4h4v2m-6 0 1 10h6l1-10M8 9v4m4-4v4" /></svg>
          </button>
          <span v-else></span>
        </div>
        <div v-if="!displayItems.length" class="empty-state cart-empty-state">
          <span>{{ t('Your cart is empty.') }}</span>
          <a class="cart-empty-link" href="/">{{ t('Browse the product') }}</a>
        </div>
      </div>

      <div v-if="checkoutStep === 0 && displayItems.length" id="cartTotalBlock" class="cart-total-card"><span>{{ t('Items total') }}</span><strong>{{ money(cart.subtotal) }}</strong></div>

      <section v-if="checkoutStep === 1" id="addressPanel" class="address-panel">
        <p class="panel-kicker">{{ t('Step 2 of 4') }}</p>
        <h2 class="address-title">{{ t('Select Shipping Address') }}</h2>
        <p class="panel-intro">{{ t('Your default shipping address is selected. Change it only if this order should go somewhere else.') }}</p>
        <div v-if="selectedAddress" class="selected-address-card">
          <small class="address-eyebrow">{{ t(selectedAddress.isDefault ? 'Default shipping address' : 'Shipping address') }}</small>
          <strong>{{ selectedAddress.name }}</strong>
          <span>{{ selectedAddress.street }}</span>
          <span v-if="selectedAddress.apt">{{ selectedAddress.apt }}</span>
          <span>{{ selectedAddress.city }} {{ selectedAddress.region }}</span>
          <span>{{ selectedAddress.postalCode }}</span>
          <span>{{ selectedAddress.phone }}</span>
        </div>
        <p v-else class="empty-state address-empty-state">{{ t('No shipping address saved yet.') }}</p>
        <button v-if="addresses.length" class="address-change-btn" type="button" @click="emit('update:addressPickerOpen', true)">{{ t('Change shipping address') }}</button>
        <button v-else class="address-change-btn" type="button" @click="addAddress">{{ t('Add shipping address') }}</button>
      </section>

      <section v-if="checkoutStep === 2" id="confirmPanel" class="confirm-panel">
        <p class="panel-kicker">{{ t('Step 3 of 4') }}</p>
        <h2 class="address-title">{{ t('Place your order') }}</h2>
        <p class="panel-intro">{{ t('We’ll use this address for the shipment.') }}</p>
        <div class="confirm-address">
          <span class="confirm-address-label">{{ t('Deliver to') }}</span>
          <strong v-if="selectedAddress">{{ selectedAddress.name }}<br>{{ selectedAddress.street }}<br v-if="selectedAddress.apt">{{ selectedAddress.apt }}<br>{{ selectedAddress.city }} {{ selectedAddress.region }} {{ selectedAddress.postalCode }}<br>{{ selectedAddress.phone }}</strong>
        </div>
      </section>

      <section v-if="checkoutStep === 2" class="order-note-panel">
        <p class="panel-kicker">{{ t('Optional') }}</p>
        <h2 class="address-title">{{ t('Order Note') }}</h2>
        <p class="panel-intro">{{ t('Add instructions for the shipping team if needed.') }}</p>
        <textarea autocomplete="off" :value="orderNote" maxlength="1000" :aria-label="t('Order Note')" :placeholder="t('Optional note for shipping company, batch number, or other instructions')" @input="emit('update:orderNote', ($event.target as HTMLTextAreaElement).value)"></textarea>
      </section>

      <section v-if="checkoutStep === 3" class="payment-step-panel">
        <p class="panel-kicker">{{ t('Step 4 of 4') }}</p>
        <h2 class="address-title">{{ t('Pay for your order') }}</h2>
        <p class="panel-intro">{{ t('Choose a payment channel for this order. Payment is recorded separately for each order.') }}</p>
        <div class="payment-step-order"><span>{{ t('Order') }} <strong>{{ orderNo }}</strong> · {{ t('is waiting for payment.') }}</span><strong>{{ money(orderTotal) }}</strong></div>

        <div v-if="!['unpaid', 'partially_paid'].includes(orderPaymentStatus || '')" class="payment-submitted-state">
          <strong>{{ t('Payment submitted for verification') }}</strong>
          <p>{{ statusLabel(orderPaymentStatus || '') }}</p>
          <button class="action-button" type="button" @click="emit('navigate', 'orders')">{{ t('View Order History') }}</button>
        </div>

        <template v-else-if="channels.length">
          <p v-if="['pending_verification', 'partially_paid'].includes(orderPaymentStatus || '')">{{ t('Amount paid') }} {{ money(orderPaidAmount) }} · {{ t('Remaining to pay') }} {{ money(orderRemainingAmount) }}</p>
          <fieldset class="payment-channel-list">
            <legend class="sr-only">{{ t('Choose a payment channel') }}</legend>
            <label v-for="(channel, index) in channels" :key="channel.id" class="payment-channel-option" :class="{ selected: paymentChannel === channel.id }">
              <input autocomplete="off" :checked="paymentChannel === channel.id" type="radio" name="payment-channel" :value="channel.id" :disabled="paymentPending" @change="emit('update:paymentChannel', channel.id)">
              <span class="payment-channel-copy"><strong>{{ channel.name }}</strong><small>{{ channel.currencyCode }} · {{ channel.network }}</small></span>
              <span v-if="index === 0" class="recommended-tag">{{ t('Recommended') }}</span>
              <span v-if="Number(channel.additionalRate) > 0" class="additional-rate-tag">{{ t('Additional rate') }} +{{ Number(channel.additionalRate).toFixed(2) }}%</span>
            </label>
          </fieldset>

          <div v-if="selectedChannel" class="selected-channel-panel">
            <div class="selected-channel-heading">
              <div><small>{{ t('Selected channel') }}</small><strong>{{ selectedChannel.name }}</strong><span>{{ selectedChannel.currencyCode }} · {{ selectedChannel.network }}</span></div>
              <span class="channel-network-tag">{{ selectedChannel.network }}</span>
            </div>
            <div class="payment-transfer-grid">
              <button v-if="qrUrl" class="payment-qr-button" type="button" :aria-label="t('Enlarge payment QR code')" @click="qrPreviewOpen = true">
                <img :src="qrUrl" :alt="t('Payment QR code')" @error="onQrError">
              </button>
              <div v-else class="payment-qr-placeholder" role="status">
                <span v-if="qrLoading">{{ t('Loading payment QR code…') }}</span>
                <span v-else-if="qrUnavailable">{{ t('QR code unavailable. Use the receiving address below.') }}</span>
                <span v-else>{{ t('Payment QR code') }}</span>
              </div>
              <div class="receiving-address-block">
                <div class="receiving-address-heading">
                  <span class="receiving-address-label">{{ t('Receiving address') }}</span>
                  <button class="copy-address-icon" type="button" :aria-label="t('Copy address')" :title="t('Copy address')" @click="copyReceivingAddress">
                    <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="7" y="6" width="9" height="11" rx="1.5"/><path d="M12.5 6V4.5A1.5 1.5 0 0 0 11 3H5.5A1.5 1.5 0 0 0 4 4.5v8A1.5 1.5 0 0 0 5.5 14H7"/></svg>
                  </button>
                  <span v-if="copyFeedback" role="status">{{ copyFeedback }}</span>
                </div>
                <p class="receiving-address-value">{{ selectedChannel.receivingAddress }}</p>
                <div v-if="orderTotal != null" class="selected-channel-fee"><span v-if="Number(selectedChannel.additionalRate) > 0">{{ t('Additional rate') }} +{{ Number(selectedChannel.additionalRate).toFixed(2) }}%</span><strong>{{ t('Amount due') }} {{ paymentAmountDue }}</strong></div>
                <p>{{ t('Send the amount due to this address using the selected network.') }}</p>
              </div>
            </div>
          </div>

          <div class="payment-reference-fields">
            <CustomerPaymentAmount :model-value="paymentAmount" :remaining="orderRemainingAmount ?? '0'" :disabled="paymentPending" :t="t" @update:model-value="emit('update:paymentAmount', $event)" />
            <label class="transaction-field">{{ t('Transaction ID / Tx hash') }}
              <input autocomplete="off" :value="transactionNo" maxlength="256" :disabled="paymentPending" :placeholder="t('Paste the transaction ID after sending')" @input="emit('update:transactionNo', ($event.target as HTMLInputElement).value)">
              <small>{{ t('Paste the transaction ID after sending payment. The seller will verify this payment.') }}</small>
            </label>
          </div>
          <div class="payment-actions">
            <button class="next-btn" type="button" :disabled="paymentPending || !paymentChannel || !transactionNo.trim() || !validPaymentAmount(paymentAmount, orderRemainingAmount ?? '0')" :aria-busy="paymentPending" @click="emit('submitPayment')"><span>{{ t(paymentPending ? 'Submitting…' : 'Verify payment') }}</span><strong v-if="orderTotal != null" class="payment-submit-total">{{ paymentAmountDue }}</strong></button>
            <button class="back-btn payment-later-btn" type="button" @click="emit('navigate', 'orders')">{{ t('Pay later / view order history') }}</button>
          </div>
        </template>
        <div v-else class="no-payment-channels">
          <p class="empty-state">{{ t('No active payment channels are available. You can submit payment later from Order History.') }}</p>
          <button class="back-btn" type="button" @click="emit('navigate', 'orders')">{{ t('Pay later / view order history') }}</button>
        </div>
      </section>

      <div v-if="checkoutStep < 3" class="checkout-actions">
        <button v-if="checkoutStep > 0" class="back-btn" type="button" :disabled="cartPending || checkoutPending" @click="emit('back')">{{ t('Back') }}</button>
        <button class="next-btn" type="button" :disabled="((checkoutStep === 2 || checkoutStep === 0) && (!cart.items.length || hasInvalidCart)) || cartPending || checkoutPending" :aria-busy="checkoutPending" @click="emit('continue')">
          {{ t(checkoutPending ? (checkoutQuote ? 'Confirming order…' : 'Creating order…') : checkoutStep === 0 ? 'Continue to Shipping' : checkoutStep === 1 ? 'Continue to Place Order' : checkoutQuote ? 'Confirm updated total' : 'Place Order') }}
        </button>
      </div>

      <div v-if="checkoutStep >= 1" id="totalsBlock" class="totals-card">
        <div class="summary-heading"><div><p class="summary-kicker">{{ t('At a glance') }}</p><h2 class="summary-title">{{ t('Order summary') }}</h2></div><span class="summary-mark" aria-hidden="true">✦</span></div>
        <p v-if="checkoutStep === 2 && checkoutQuote" class="quote-review-notice" role="status">{{ t('Prices or shipping changed. Review the updated total, then confirm your order.') }}</p>
        <p class="review-hint">{{ t(checkoutStep === 1 ? 'Shipping uses the store’s current flat rate.' : checkoutStep === 3 ? 'You can pay now or return to Order History and pay later.' : 'Review the address and place the order.') }}</p>
        <div class="totals-row"><span>{{ t('Subtotal') }}</span><span>{{ money(checkoutStep === 3 ? orderSubtotal : checkoutStep === 2 && checkoutQuote ? checkoutQuote.subtotal : cart.subtotal) }}</span></div>
        <div class="totals-row"><span>{{ t('Shipping') }}</span><span>{{ money(checkoutStep === 3 ? orderShippingFee : checkoutStep === 2 && checkoutQuote ? checkoutQuote.shippingFee : cart.shippingFee) }}</span></div>
        <div v-if="checkoutStep === 3 && Number(orderDiscountAmount) > 0" class="totals-row"><span>{{ t('Discount') }}</span><span>−{{ money(orderDiscountAmount) }}</span></div>
        <div class="totals-row totals-total"><span>{{ t('Total') }}</span><span>{{ money(checkoutStep === 3 ? orderTotal : checkoutStep === 2 && checkoutQuote ? checkoutQuote.total : cart.total) }}</span></div>
      </div>
    </div>

    <AppModal v-if="addressPickerOpen" :label="t('Select Shipping Address')" content-class="checkout-address-picker" @close="emit('update:addressPickerOpen', false)">
      <div class="checkout-modal-heading">
        <div><p class="checkout-kicker">{{ t('Shipping details') }}</p><h2 class="address-title">{{ t('Select Shipping Address') }}</h2></div>
        <button class="close-button" type="button" :aria-label="t('Close address selection')" @click="emit('update:addressPickerOpen', false)">×</button>
      </div>
      <p class="panel-intro">{{ t('Choose an address for this order. Your selection will return to checkout.') }}</p>
      <div class="address-option-list">
        <label v-for="address in addresses" :key="address.id" class="address-option" :class="{ selected: selectedAddressId === address.id }">
          <input autocomplete="off" :checked="selectedAddressId === address.id" type="radio" name="shipping-address" :value="address.id" @change="selectAddress(address.id)">
          <span class="address-option-body"><strong>{{ address.name }}</strong><span>{{ address.street }}</span><span v-if="address.apt">{{ address.apt }}</span><span>{{ address.city }} {{ address.region }}</span><span>{{ address.postalCode }}</span><span>{{ address.phone }}</span></span>
          <span v-if="address.isDefault" class="address-default-tag">{{ t('Default') }}</span>
        </label>
      </div>
      <button class="add-address-modal-btn" type="button" @click="addAddress">＋ {{ t('Add new address') }}</button>
      <button class="cancel-address-modal-btn" type="button" @click="emit('update:addressPickerOpen', false)">{{ t('Cancel') }}</button>
    </AppModal>

    <AppModal v-if="qrPreviewOpen && qrUrl" :label="t('Payment QR code')" content-class="payment-qr-preview" @close="qrPreviewOpen = false">
      <button class="close-button" type="button" :aria-label="t('Close payment QR code')" @click="qrPreviewOpen = false">×</button>
      <img :src="qrUrl" :alt="t('Payment QR code')" @error="onQrError">
    </AppModal>

    <AppModal v-if="paymentSuccessOpen" :label="t('Payment information submitted')" content-class="payment-success-modal" @close="emit('close-payment-success')">
      <div class="payment-success-mark" aria-hidden="true">✓</div>
      <p class="panel-kicker">{{ t('Payment submitted') }}</p>
      <h2 class="address-title">{{ t('Payment information submitted') }}</h2>
      <p class="payment-success-copy">{{ t('The seller will verify your payment information. Your order will remain available in Order History.') }}</p>
      <div class="payment-success-actions">
        <button class="back-btn" type="button" @click="emit('close-payment-success')">{{ t('Stay on payment') }}</button>
        <button class="next-btn" type="button" @click="openOrderHistory">{{ t('View Order History') }}</button>
      </div>
    </AppModal>
  </main>
</template>
