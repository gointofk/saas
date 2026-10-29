<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppModal from './AppModal.vue'
import CustomerPaymentAmount from './CustomerPaymentAmount.vue'
import { request } from '../lib/api'
import { paymentAmountWithRate, validPaymentAmount } from '../lib/payment-amount'
import type { CustomerOrder, CustomerPaymentChannel } from '../types/customer'

const props = defineProps<{
  order: CustomerOrder
  channels: CustomerPaymentChannel[]
  paymentChannel: number | null
  paymentAmount: string
  transactionNo: string
  pending: boolean
  t: (text: string) => string
  money: (value: unknown) => string
}>()

const emit = defineEmits<{
  close: []
  'update:paymentChannel': [id: number]
  'update:paymentAmount': [amount: string]
  'update:transactionNo': [value: string]
  submit: []
}>()

const selectedChannel = computed(() => props.channels.find(channel => channel.id === props.paymentChannel))
const paymentAmountDue = computed(() => props.money(paymentAmountWithRate(props.paymentAmount, selectedChannel.value?.additionalRate)))
const qrUrl = ref('')
const qrLoading = ref(false)
const qrUnavailable = ref(false)
const qrPreviewOpen = ref(false)
const copyFeedback = ref('')
let copyResetTimer: number | undefined

watch(() => selectedChannel.value?.qrFileUuid, async (fileUuid) => {
  qrUrl.value = ''
  qrUnavailable.value = false
  if (!fileUuid) return
  qrLoading.value = true
  try {
    const response = await request<{ url: string }>(`/customer/files/${encodeURIComponent(fileUuid)}/signed-url`)
    if (selectedChannel.value?.qrFileUuid === fileUuid) qrUrl.value = response.url
  } catch {
    if (selectedChannel.value?.qrFileUuid === fileUuid) qrUnavailable.value = true
  } finally {
    if (selectedChannel.value?.qrFileUuid === fileUuid) qrLoading.value = false
  }
}, { immediate: true })

onBeforeUnmount(() => window.clearTimeout(copyResetTimer))

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
  const address = selectedChannel.value?.receivingAddress
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
  showCopyFeedback(copied ? 'Address copied.' : 'Copy is unavailable in this browser.')
}
</script>

<template>
  <AppModal :label="t('Pay') + ' ' + order.orderNo" content-class="payment-modal" @close="emit('close')">
    <header class="payment-modal-header">
      <h2 class="modal-title">{{ t('Order #') }}{{ order.orderNo }} <span>· {{ t('Pay') }}</span></h2>
      <button class="close-button" type="button" :aria-label="t('Close payment')" @click="emit('close')">×</button>
    </header>

    <section class="payment-modal-summary" :aria-label="t('Order summary')">
      <h3>{{ t('Order summary') }}</h3>
      <div v-if="order.items?.length" class="payment-modal-items">
        <div v-for="item in order.items" :key="item.productId" class="payment-modal-item">
          <span>{{ item.name }} × {{ item.quantity }}</span>
          <strong>{{ money(item.lineTotal) }}</strong>
        </div>
      </div>
      <dl class="payment-modal-totals">
        <div v-if="order.subtotal != null"><dt>{{ t('Subtotal') }}</dt><dd>{{ money(order.subtotal) }}</dd></div>
        <div v-if="order.shippingFee != null"><dt>{{ t('Shipping') }}</dt><dd>{{ money(order.shippingFee) }}</dd></div>
        <div v-if="Number(order.discountAmount) > 0"><dt>{{ t('Discount') }}</dt><dd>−{{ money(order.discountAmount) }}</dd></div>
        <div class="payment-modal-total"><dt>{{ t('Total') }}</dt><dd>{{ money(order.total) }}</dd></div>
      </dl>
      <dl v-if="order.status !== 'cancelled' && ['pending_verification', 'partially_paid'].includes(order.paymentStatus)" class="payment-modal-totals">
        <div><dt>{{ t('Amount paid') }}</dt><dd>{{ money(order.paidAmount) }}</dd></div>
        <div><dt>{{ t('Remaining to pay') }}</dt><dd>{{ money(order.remainingAmount) }}</dd></div>
      </dl>
    </section>

    <section class="payment-modal-section payment-step-panel">
      <h2 class="address-title">{{ t('Pay for your order') }}</h2>
      <p class="panel-intro">{{ t('Choose a payment channel for this order. Payment is recorded separately for each order.') }}</p>
      <fieldset class="payment-channel-list">
        <legend class="sr-only">{{ t('Choose a payment channel') }}</legend>
        <label v-for="(channel, index) in channels" :key="channel.id" class="payment-channel-option" :class="{ selected: paymentChannel === channel.id }">
          <input autocomplete="off" :checked="paymentChannel === channel.id" type="radio" name="history-payment-channel" :value="channel.id" :disabled="pending" @change="emit('update:paymentChannel', channel.id)">
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
            <div v-if="order.total != null" class="selected-channel-fee">
              <span v-if="Number(selectedChannel.additionalRate) > 0">{{ t('Additional rate') }} +{{ Number(selectedChannel.additionalRate).toFixed(2) }}%</span>
              <strong>{{ t('Amount due') }} {{ paymentAmountDue }}</strong>
            </div>
            <p>{{ t('Send the amount due to this address using the selected network.') }}</p>
          </div>
        </div>
      </div>

      <div class="payment-reference-fields">
        <CustomerPaymentAmount :model-value="paymentAmount" :remaining="order.remainingAmount" :disabled="pending" :t="t" @update:model-value="emit('update:paymentAmount', $event)" />
        <label class="transaction-field">
          <span>{{ t('Transaction ID / Tx hash') }}</span>
          <input autocomplete="off" :value="transactionNo" maxlength="256" :disabled="pending" :placeholder="t('Paste the transaction ID after sending')" @input="emit('update:transactionNo', ($event.target as HTMLInputElement).value)">
          <small>{{ t('Paste the transaction ID after sending payment. The seller will verify this payment.') }}</small>
        </label>
      </div>
    </section>

    <footer class="payment-modal-footer">
      <button class="payment-modal-close" type="button" :disabled="pending" @click="emit('close')">{{ t('Close') }}</button>
      <button class="modal-save" type="button" :disabled="pending || !paymentChannel || !transactionNo.trim() || !validPaymentAmount(paymentAmount, order.remainingAmount)" :aria-busy="pending" @click="emit('submit')"><span>{{ t(pending ? 'Submitting…' : 'Verify payment') }}</span><strong class="payment-submit-total">{{ paymentAmountDue }}</strong></button>
    </footer>
  </AppModal>

  <AppModal v-if="qrPreviewOpen && qrUrl" :label="t('Payment QR code')" content-class="payment-qr-preview" @close="qrPreviewOpen = false">
    <button class="close-button" type="button" :aria-label="t('Close payment QR code')" @click="qrPreviewOpen = false">×</button>
    <img :src="qrUrl" :alt="t('Payment QR code')" @error="onQrError">
  </AppModal>
</template>
