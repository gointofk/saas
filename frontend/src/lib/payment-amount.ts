export function parseAmountCents(value: number | string): bigint | null {
  const match = String(value).trim().match(/^(\d+)(?:\.(\d{1,2}))?$/)
  return match ? BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0')) : null
}

export function amountFromCents(value: bigint): string {
  const sign = value < 0n ? '-' : ''
  const absolute = value < 0n ? -value : value
  return `${sign}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}

export function validPaymentAmount(value: string, remaining: number | string): boolean {
  const amount = parseAmountCents(value)
  const balance = parseAmountCents(remaining)
  return amount !== null && balance !== null && amount > 0n && amount <= balance
}

function toCents(value: number | string | null | undefined): bigint {
  const match = String(value ?? '0').trim().match(/^([+-]?)(\d+)(?:\.(\d*))?$/)
  if (!match) return 0n

  const [, sign, whole, fraction = ''] = match
  let cents = BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2))
  if (fraction[2] && fraction[2] >= '5') cents += 1n
  return sign === '-' ? -cents : cents
}

function roundHalfUp(numerator: bigint, denominator: bigint): bigint {
  return numerator >= 0n
    ? (numerator + denominator / 2n) / denominator
    : (numerator - denominator / 2n) / denominator
}

export function paymentAmountWithRate(
  amount: number | string | null | undefined,
  rate: number | string | null | undefined,
): string {
  const amountCents = toCents(amount)
  const rateBasisPoints = toCents(rate)
  const dueCents = roundHalfUp(amountCents * (10_000n + rateBasisPoints), 10_000n)
  const sign = dueCents < 0n ? '-' : ''
  const absoluteCents = dueCents < 0n ? -dueCents : dueCents
  return `${sign}${absoluteCents / 100n}.${String(absoluteCents % 100n).padStart(2, '0')}`
}
