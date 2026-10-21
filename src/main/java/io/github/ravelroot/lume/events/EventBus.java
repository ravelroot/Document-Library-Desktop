package io.github.ravelroot.lume.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Eventos em tempo real. Na versão web eles iam do Redis para o navegador por WebSocket; aqui vão do núcleo em Java
 * para a janela por Server-Sent Events ({@code GET /api/v1/events}). O evento só avisa o que mudou: a interface
 * busca o documento de novo na API.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Set<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArraySet<>();

    public void emit(String type) {
        emit(type, Map.of());
    }

    public void emit(String type, Map<String, ?> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.putAll(data);
        for (Consumer<Map<String, Object>> listener : listeners) {
            try {
                listener.accept(payload);
            } catch (RuntimeException e) {
                log.warn("[eventos] {}", e.getMessage());
            }
        }
    }

    /** Assina os eventos; o Runnable devolvido cancela a assinatura. */
    public Runnable subscribe(Consumer<Map<String, Object>> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
