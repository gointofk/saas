package com.dailyactivity.merchant.application;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.merchant.domain.port.MerchantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Service
public class StorefrontService {
    private static final Set<String> TELEGRAM_HOSTS = Set.of("t.me", "telegram.me");
    private final MerchantRepository merchants;
    public StorefrontService(MerchantRepository merchants) { this.merchants=merchants; }

    public static String normalizeDomain(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("INVALID_DOMAIN","A storefront hostname is required.");
        try {
            String domain = IDN.toASCII(value.trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (domain.endsWith(".")) domain=domain.substring(0,domain.length()-1);
            if (domain.length()>253 || domain.isEmpty() || domain.contains(":") || domain.contains("/") || domain.contains("..")) throw new IllegalArgumentException();
            return domain;
        } catch (IllegalArgumentException e) { throw ApiException.badRequest("INVALID_DOMAIN","Enter a hostname without scheme, port or path."); }
    }
    public static void validateBranding(String logo, String theme) {
        if (!Set.of("classic","forest","editorial").contains(theme == null ? "" : theme)) throw ApiException.badRequest("INVALID_THEME","Select an available theme.");
        try {
            URI uri=URI.create(logo == null ? "" : logo);
            boolean local=logo != null && logo.startsWith("/") && !logo.startsWith("//") && !logo.contains("\\") && !uri.isAbsolute();
            boolean https="https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null;
            if (logo == null || logo.isBlank() || logo.length()>512 || (!local && !https)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw ApiException.badRequest("INVALID_LOGO","Logo must be a site-relative path or HTTPS URL."); }
    }
    public static String normalizeTelegramUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            String path = uri.getRawPath();
            boolean unsafeCharacters = candidate.chars().anyMatch(Character::isWhitespace)
                    || candidate.chars().anyMatch(Character::isISOControl);
            if (candidate.length() > 512
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || !TELEGRAM_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || path == null
                    || path.isEmpty()
                    || path.equals("/")
                    || unsafeCharacters) throw new IllegalArgumentException();
            return candidate;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_TELEGRAM_URL","Enter a valid HTTPS Telegram link such as https://t.me/yourchannel.");
        }
    }
    @Transactional(readOnly=true)
    public MerchantRepository.Merchant resolve(HttpServletRequest request) {
        // Nginx must preserve Host; forwarded headers supplied by clients are never tenant selectors.
        String host=request.getHeader("Host");
        if (host == null || host.contains(",") || host.contains("@")) throw ApiException.notFound("Storefront not found.");
        if (host.matches("[^:]+:[0-9]+")) host=host.substring(0,host.lastIndexOf(':'));
        var merchant=merchants.findByDomain(normalizeDomain(host)).orElseThrow(() -> ApiException.notFound("Storefront not found."));
        if (!merchant.active()) throw ApiException.forbidden("This storefront is unavailable.");
        return merchant;
    }
}
