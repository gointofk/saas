const TELEGRAM_HOSTS = new Set(['t.me', 'telegram.me'])

export function whatsappConsultationUrl(number: string | null | undefined, message?: string): string {
  const digits = number?.replace(/\D/g, '') || ''
  if (digits.length < 7 || digits.length > 15) return ''
  const url = `https://wa.me/${digits}`
  return message ? `${url}?text=${encodeURIComponent(message)}` : url
}

export function telegramJoinUrl(value: string | null | undefined): string {
  const candidate = value?.trim() || ''
  if (!candidate || candidate.length > 512) return ''
  try {
    const url = new URL(candidate)
    if (url.protocol !== 'https:' || url.username || url.password || url.port
      || !TELEGRAM_HOSTS.has(url.hostname.toLowerCase()) || !url.pathname || url.pathname === '/') return ''
    return url.toString()
  } catch {
    return ''
  }
}
