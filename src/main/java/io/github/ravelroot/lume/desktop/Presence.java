package io.github.ravelroot.lume.desktop;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Quantas janelas estão abertas, contadas pelas conexões de eventos ao vivo (SSE). Quando a última janela fecha, o
 * {@link AppWindow} encerra o Lume depois de alguns segundos (tempo de um F5 ou de uma troca de página).
 */
@Component
public class Presence {

    private final AtomicInteger windows = new AtomicInteger();
    private volatile boolean everConnected;
    private volatile long emptySince = System.currentTimeMillis();

    public void connected() {
        windows.incrementAndGet();
        everConnected = true;
    }

    public void disconnected() {
        if (windows.decrementAndGet() <= 0) {
            windows.set(0);
            emptySince = System.currentTimeMillis();
        }
    }

    public int windows() {
        return windows.get();
    }

    public boolean everConnected() {
        return everConnected;
    }

    /** Há quanto tempo não existe nenhuma janela conectada (0 se há alguma). */
    public long emptyForMillis() {
        return windows.get() > 0 ? 0 : System.currentTimeMillis() - emptySince;
    }
}
