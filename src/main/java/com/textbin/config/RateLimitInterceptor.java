package com.textbin.config;

import com.textbin.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Intercepts incoming requests and validates them against per-IP rate limits.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitingService rateLimitingService;

    public RateLimitInterceptor(RateLimitingService rateLimitingService) {
        this.rateLimitingService = rateLimitingService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String clientIp = RateLimitingService.getClientIp(request);

        // 1. File Uploads (POST .../files): Max 20 uploads / minute per IP
        if ("POST".equalsIgnoreCase(method) && path.contains("/files")) {
            if (!rateLimitingService.allowRequest(clientIp, "upload", 20)) {
                sendRateLimitResponse(response, "Upload rate limit exceeded. Please wait a minute before uploading more files.");
                return false;
            }
        }
        // 2. Text Content Saves / Edits: Max 120 saves / minute per IP (allows debounce burst up to 2/sec)
        else if ("POST".equalsIgnoreCase(method) && (path.startsWith("/api/dropbin/") || path.startsWith("/api/pastes/"))) {
            if (!rateLimitingService.allowRequest(clientIp, "save", 120)) {
                sendRateLimitResponse(response, "Text save rate limit exceeded. Please slow down.");
                return false;
            }
        }
        // 3. New workspace creation: Max 30 / minute per IP
        else if (path.equals("/dropbin/new")) {
            if (!rateLimitingService.allowRequest(clientIp, "create", 30)) {
                sendRateLimitResponse(response, "Too many workspace generation requests. Please try again shortly.");
                return false;
            }
        }

        return true;
    }

    private void sendRateLimitResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}

