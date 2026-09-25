package com.textbin.service;

import com.textbin.dto.PasteResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class PasteSyncService {

    // Map of pasteId -> active SSE emitters
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String pasteId) {
        // 10 minutes timeout; browser EventSource auto-reconnects when closed
        SseEmitter emitter = new SseEmitter(600_000L);
        emitters.computeIfAbsent(pasteId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(pasteId, emitter));
        emitter.onTimeout(() -> removeEmitter(pasteId, emitter));
        emitter.onError(e -> removeEmitter(pasteId, emitter));

        try {
            emitter.send(SseEmitter.event().name("init").data("connected"));
        } catch (IOException e) {
            removeEmitter(pasteId, emitter);
        }

        return emitter;
    }

    public void broadcastUpdate(String pasteId, PasteResponse update) {
        broadcastEvent(pasteId, "paste_update", update);
    }

    public void broadcastEvent(String pasteId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(pasteId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception ex) {
                deadEmitters.add(emitter);
            }
        }
        list.removeAll(deadEmitters);
    }

    /**
     * Periodic 25-second heartbeat ping (: ping) to keep connections active across
     * routers, proxies, and firewalls, and promptly clean up dead/closed sockets.
     */
    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;

        emitters.forEach((pasteId, list) -> {
            List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
            for (SseEmitter emitter : list) {
                try {
                    // SSE comment line (starting with ':') is ignored by JS EventSource but keeps TCP alive
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    deadEmitters.add(emitter);
                }
            }
            if (!deadEmitters.isEmpty()) {
                list.removeAll(deadEmitters);
                if (list.isEmpty()) {
                    emitters.remove(pasteId);
                }
            }
        });
    }

    private void removeEmitter(String pasteId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(pasteId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(pasteId);
            }
        }
    }
}
