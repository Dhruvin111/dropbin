package com.textbin.service;

import com.textbin.model.Paste;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Manages signed workspace unlock tokens and validates authorization for locked bins.
 */
@Service
public class PasteSecurityService {

    private static final Logger log = LoggerFactory.getLogger(PasteSecurityService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String COOKIE_PREFIX = "bin_auth_";

    private final byte[] secretKey;

    public PasteSecurityService() {
        // Generate cryptographic random secret on application boot
        this.secretKey = new byte[32];
        new SecureRandom().nextBytes(this.secretKey);
    }

    /**
     * Generates a signed tamper-proof unlock token bound to the paste ID and current password hash.
     * If the password hash changes, previous tokens are immediately invalidated.
     */
    public String generateToken(Paste paste) {
        if (paste == null || !paste.isLocked()) {
            return "";
        }
        long timestamp = System.currentTimeMillis();
        String payload = paste.getId() + ":" + paste.getPasswordHash() + ":" + timestamp;
        String signature = computeHmac(payload);
        return paste.getId() + "." + timestamp + "." + signature;
    }

    /**
     * Checks if the incoming request is authorized to view or edit the paste.
     */
    public boolean isAuthorized(HttpServletRequest request, Paste paste) {
        if (paste == null || !paste.isLocked()) {
            return true;
        }

        String candidateToken = extractToken(request, paste.getId());
        if (candidateToken == null || candidateToken.isBlank()) {
            return false;
        }

        return validateToken(candidateToken, paste);
    }

    /**
     * Builds an HttpOnly, SameSite=Lax authorization cookie for the response.
     */
    public ResponseCookie buildAuthCookie(Paste paste, String token, boolean isSecure) {
        return ResponseCookie.from(COOKIE_PREFIX + paste.getId(), token)
                .path("/")
                .httpOnly(true)
                .secure(isSecure)
                .sameSite("Lax")
                .maxAge(3600) // 1 hour (workspace expires after 5 mins of inactivity anyway)
                .build();
    }

    /**
     * Builds a cookie to clear authorization.
     */
    public ResponseCookie buildClearAuthCookie(String pasteId) {
        return ResponseCookie.from(COOKIE_PREFIX + pasteId, "")
                .path("/")
                .httpOnly(true)
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    /**
     * Attaches an HttpOnly, SameSite=Lax authorization cookie to the response.
     */
    public void setAuthCookie(HttpServletResponse response, Paste paste, String token, boolean isSecure) {
        ResponseCookie cookie = buildAuthCookie(paste, token, isSecure);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Clears the authorization cookie when a password is removed.
     */
    public void clearAuthCookie(HttpServletResponse response, String pasteId) {
        ResponseCookie cookie = buildClearAuthCookie(pasteId);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public boolean validateToken(String token, Paste paste) {
        if (token == null || paste == null || !paste.isLocked()) {
            return false;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        String tokenPasteId = parts[0];
        String tokenTimestamp = parts[1];
        String tokenSignature = parts[2];

        if (!tokenPasteId.equals(paste.getId())) {
            return false;
        }

        String expectedPayload = paste.getId() + ":" + paste.getPasswordHash() + ":" + tokenTimestamp;
        String expectedSignature = computeHmac(expectedPayload);

        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                tokenSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String extractToken(HttpServletRequest request, String pasteId) {
        if (request == null) return null;

        // 1. Check custom header X-Bin-Token
        String header = request.getHeader("X-Bin-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }

        // 2. Check Authorization Bearer header
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // 3. Check Cookie bin_auth_<pasteId>
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            String cookieName = COOKIE_PREFIX + pasteId;
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }

        // 4. Check query parameter token
        String param = request.getParameter("token");
        if (param != null && !param.isBlank()) {
            return param.trim();
        }

        return null;
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            log.error("Failed to compute HMAC signature", e);
            throw new IllegalStateException("HMAC generation failed", e);
        }
    }
}
