export function whatsappConsultationUrl(number: string | null | undefined, message?: string): string {
  const digits = number?.replace(/\D/g, '') || ''
  if (digits.length < 7 || digits.length > 15) return ''
  const url = `https://wa.me/${digits}`
  return message ? `${url}?text=${encodeURIComponent(message)}` : url
}
