package com.textbin.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filter that applies fundamental security response headers to all HTTP responses,
 * guarding against clickjacking, MIME-type sniffing, and cross-origin leaks.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            // Guard against MIME sniffing
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");

            // Prevent framing in external websites (Clickjacking defense)
            if (!httpResponse.containsHeader("X-Frame-Options")) {
                httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
            }

            // Cross-site scripting legacy filter support
            httpResponse.setHeader("X-XSS-Protection", "1; mode=block");

            // Control referrer leakage to third-party domains
            httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

            // Restrict sensitive browser permissions
            httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

            // Enforce HTTPS HSTS when behind a reverse proxy (Railway, Cloudflare, Render)
            if (request instanceof HttpServletRequest httpRequest) {
                String proto = httpRequest.getHeader("X-Forwarded-Proto");
                if (httpRequest.isSecure() || "https".equalsIgnoreCase(proto)) {
                    httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                }
            }
        }
        chain.doFilter(request, response);
    }
}
