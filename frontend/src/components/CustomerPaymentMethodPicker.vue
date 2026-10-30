<script setup lang="ts">
defineProps<{
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
</script>

<template>
  <section class="payment-method-picker">
    <fieldset v-if="cryptoEnabled && (onlinePaymentLinkEnabled || requestSent)" class="payment-method-options" :disabled="busy">
      <legend>{{ t('Choose a payment method') }}</legend>
      <label class="payment-method-option" :class="{ selected: modelValue === 'crypto', unavailable: !cryptoAvailable }">
        <input
          type="radio"
          name="customer-payment-method"
          value="crypto"
          :checked="modelValue === 'crypto'"
          :disabled="!cryptoAvailable"
          @change="emit('update:modelValue', 'crypto')"
        >
        <span class="payment-method-option-copy">
          <strong>{{ t('Pay with cryptocurrency') }}</strong>
          <small>{{ t(cryptoAvailable ? 'Choose a listed cryptocurrency channel.' : 'No cryptocurrency channels are available right now.') }}</small>
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
        <span class="payment-method-option-copy">
          <strong>{{ t('Request an online payment link') }}</strong>
          <small>{{ t('The seller will prepare a link and contact you by email.') }}</small>
        </span>
        <span v-if="requestSent" class="payment-method-requested-tag">{{ t('Link requested') }}</span>
      </label>
    </fieldset>

    <fieldset v-else-if="cryptoEnabled || onlinePaymentLinkEnabled || requestSent" class="payment-method-options single-payment-method" :disabled="busy">
      <legend>{{ t('Payment method') }}</legend>
      <label v-if="cryptoEnabled" class="payment-method-option" :class="{ selected: modelValue === 'crypto', unavailable: !cryptoAvailable }">
        <input type="radio" name="customer-payment-method" value="crypto" :checked="modelValue === 'crypto'" :disabled="!cryptoAvailable" @change="emit('update:modelValue', 'crypto')">
        <span class="payment-method-option-copy"><strong>{{ t('Pay with cryptocurrency') }}</strong><small>{{ t(cryptoAvailable ? 'Choose a listed cryptocurrency channel.' : 'No cryptocurrency channels are available right now.') }}</small></span>
      </label>
      <label v-if="onlinePaymentLinkEnabled || requestSent" class="payment-method-option" :class="{ selected: modelValue === 'link' }">
        <input type="radio" name="customer-payment-method" value="link" :checked="modelValue === 'link'" @change="emit('update:modelValue', 'link')">
        <span class="payment-method-option-copy"><strong>{{ t('Request an online payment link') }}</strong><small>{{ t('The seller will prepare a link and contact you by email.') }}</small></span>
        <span v-if="requestSent" class="payment-method-requested-tag">{{ t('Link requested') }}</span>
      </label>
    </fieldset>

    <div v-if="modelValue === 'link' && (onlinePaymentLinkEnabled || requestSent)" class="payment-link-request-card" :class="{ requested: requestSent }" aria-live="polite">
      <div>
        <strong>{{ t(requestSent ? 'Your request is recorded.' : 'Need a payment link?') }}</strong>
        <p>{{ t(requestSent ? 'The seller will email you after preparing the link. You can still choose another available payment method.' : 'Requesting a link does not count as payment. The seller will prepare it and contact you by email.') }}</p>
      </div>
      <button type="button" :disabled="busy || requestSent" :aria-busy="busy" @click="emit('request')">
        {{ t(requestSent ? 'Link requested' : busy ? 'Submitting…' : 'Request link') }}
      </button>
    </div>
  </section>
</template>
