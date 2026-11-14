package com.textbin.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding-window rate limiting service to guard against flood attacks,
 * DoS attempts, and rapid-fire script uploads.
 */
@Service
public class RateLimitingService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingService.class);
    private static final long WINDOW_MS = 60_000L; // 1 minute window

    private static class WindowCounter {
        long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }
    }

    // endpointGroup -> (ip -> WindowCounter)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WindowCounter>> limits = new ConcurrentHashMap<>();

    public boolean allowRequest(String clientIp, String endpointGroup, int maxRequestsPerMinute) {
        if (clientIp == null || clientIp.isBlank()) {
            return true;
        }

        ConcurrentHashMap<String, WindowCounter> ipMap = limits.computeIfAbsent(endpointGroup, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();

        WindowCounter counter = ipMap.compute(clientIp, (ip, wc) -> {
            if (wc == null || (now - wc.windowStart) > WINDOW_MS) {
                WindowCounter fresh = new WindowCounter(now);
                fresh.count.set(1);
                return fresh;
            } else {
                wc.count.incrementAndGet();
                return wc;
            }
        });

        boolean allowed = counter.count.get() <= maxRequestsPerMinute;
        if (!allowed) {
            log.warn("Rate limit exceeded for IP {} on group '{}' (count: {}, max: {})",
                    clientIp, endpointGroup, counter.count.get(), maxRequestsPerMinute);
        }
        return allowed;
    }

    public static String getClientIp(HttpServletRequest request) {
        // Rely on remote address provided by servlet container.
        // Spring Boot's server.forward-headers-strategy=framework will safely populate getRemoteAddr()
        // when running behind a configured reverse proxy.
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "unknown";
    }

    /**
     * Purges stale IP tracking entries older than 2 minutes every minute
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        limits.values().forEach(ipMap -> {
            ipMap.entrySet().removeIf(entry -> (now - entry.getValue().windowStart) > (2 * WINDOW_MS));
        });
    }
}

