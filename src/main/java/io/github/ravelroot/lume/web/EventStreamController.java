package io.github.ravelroot.lume.web;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.ravelroot.lume.core.Json;
import io.github.ravelroot.lume.desktop.Presence;
import io.github.ravelroot.lume.events.EventBus;

/**
 * Eventos ao vivo para a janela, por Server-Sent Events. Cada conexão também conta como "janela aberta" para o
 * {@link Presence}; um comentário a cada 3 segundos mantém a conexão viva e revela quando a janela fechou.
 */
@RestController
public class EventStreamController {

    private static final long HEARTBEAT_SECONDS = 3;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Presence presence;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> daemon(r, "lume-eventos"));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "lume-pulso"));
    private final Runnable unsubscribe;

    public EventStreamController(EventBus events, Presence presence) {
        this.presence = presence;
        this.unsubscribe = events.subscribe(payload -> sender.execute(() -> broadcast(payload)));
        heartbeat.scheduleAtFixedRate(this::ping, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @GetMapping(path = "/api/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() throws IOException {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        presence.connected();
        Runnable gone = () -> {
            if (emitters.remove(emitter)) {
                presence.disconnected();
            }
        };
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(error -> gone.run());
        emitter.send(SseEmitter.event().comment("conectado"));
        return emitter;
    }

    private void broadcast(Map<String, Object> payload) {
        String data = Json.write(payload);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        }
    }

    private void ping() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("pulso"));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        }
    }

    private void drop(SseEmitter emitter) {
        if (emitters.remove(emitter)) {
            presence.disconnected();
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // já encerrado
        }
    }

    @PreDestroy
    public void close() {
        unsubscribe.run();
        heartbeat.shutdownNow();
        sender.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }
}
