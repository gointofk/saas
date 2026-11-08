package com.dailyactivity.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Accepts forwarded client addresses only from the loopback Nginx upstream.
 * The backend is bound to loopback in production, so direct public callers
 * cannot choose an address by supplying X-Forwarded-For themselves.
 */
@Component
public class TrustedClientIpResolver {
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isLoopback(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null) return peer;
        String candidate = forwarded.split(",", 2)[0].trim();
        return candidate.length() <= 64 && candidate.matches("[0-9A-Fa-f:.]+") ? candidate : peer;
    }

    private boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
