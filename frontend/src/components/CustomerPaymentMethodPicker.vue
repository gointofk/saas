<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: 'crypto' | 'link'
  cryptoEnabled: boolean
  cryptoAvailable: boolean
  onlinePaymentLinkEnabled: boolean
  requestSent: boolean
  busy: boolean
  t: (text: string) => string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: 'crypto' | 'link']
  request: []
}>()

const linkAvailable = computed(() => props.onlinePaymentLinkEnabled || props.requestSent)
const cryptoAvailable = computed(() => props.cryptoEnabled && props.cryptoAvailable)
const showMethodChoice = computed(() => cryptoAvailable.value && linkAvailable.value)
</script>

<template>
  <section v-if="showMethodChoice || (modelValue === 'link' && linkAvailable)" class="payment-method-picker" :class="{ 'has-method-choice': showMethodChoice }">
    <fieldset v-if="showMethodChoice" class="payment-method-options" :disabled="busy">
      <legend>{{ t('Choose a payment method') }}</legend>
      <label class="payment-method-option" :class="{ selected: modelValue === 'crypto' }">
        <input
          type="radio"
          name="customer-payment-method"
          value="crypto"
          :checked="modelValue === 'crypto'"
          @change="emit('update:modelValue', 'crypto')"
        >
        <span class="payment-method-option-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M18 7V5.5A2.5 2.5 0 0 0 15.5 3h-9A2.5 2.5 0 0 0 4 5.5V7m10.5 6.5H21"/><circle cx="15" cy="13.5" r=".8"/></svg>
        </span>
        <span class="payment-method-option-copy">
          <strong>{{ t('Cryptocurrency') }}</strong>
          <small>{{ t('Pay from your crypto wallet using a listed channel.') }}</small>
        </span>
      </label>
      <label class="payment-method-option" :class="{ selected: modelValue === 'link' }">
        <input
          type="radio"
          name="customer-payment-method"
          value="link"
          :checked="modelValue === 'link'"
          @change="emit('update:modelValue', 'link')"
        >
        <span class="payment-method-option-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M9.5 14.5 14.5 9.5m-7.1 8.1-1.1 1.1a4 4 0 0 1-5.7-5.7l4-4a4 4 0 0 1 5.7 0m3.4-3.1 1.1-1.1a4 4 0 0 1 5.7 5.7l-4 4a4 4 0 0 1-5.7 0"/></svg>
        </span>
        <span class="payment-method-option-copy">
          <strong>{{ t('Online payment link') }}</strong>
          <small>{{ t('Ask the seller to email you a payment link.') }}</small>
        </span>
        <span v-if="requestSent" class="payment-method-requested-tag">{{ t('Link requested') }}</span>
      </label>
    </fieldset>

    <div v-if="modelValue === 'link' && linkAvailable" class="payment-link-request-card" :class="{ requested: requestSent }" aria-live="polite">
      <div>
        <strong>{{ t(requestSent ? 'Your request is recorded.' : 'Need a payment link?') }}</strong>
        <p v-if="requestSent && showMethodChoice">{{ t('The seller will email you after preparing the link. You can still choose another available payment method.') }}</p>
        <p v-else-if="requestSent">{{ t('The seller will email you after preparing the link.') }}</p>
        <p v-else-if="showMethodChoice">{{ t('Requesting a link does not count as payment.') }}</p>
        <p v-else>{{ t('The seller will email you a link. Requesting one does not record payment.') }}</p>
      </div>
      <button type="button" :disabled="busy || requestSent" :aria-busy="busy" @click="emit('request')">
        {{ t(requestSent ? 'Link requested' : busy ? 'Submitting…' : 'Request link') }}
      </button>
    </div>
  </section>
</template>
