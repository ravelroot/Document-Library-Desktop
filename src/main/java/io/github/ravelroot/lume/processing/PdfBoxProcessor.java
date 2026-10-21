package io.github.ravelroot.lume.processing;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ravelroot.lume.core.ProcessingRejected;

/**
 * Processador padrão: PDFBox para PDFs e Java2D para as capas tipográficas.
 *
 * <p>Cada trabalho roda num fio separado com tempo limite. Se um PDF travar o leitor, o fio é abandonado, um novo é
 * criado e o pipeline tenta de novo mais tarde, sem afetar a interface (o mesmo papel da janela invisível que era
 * recriada na versão em Electron).</p>
 */
public class PdfBoxProcessor implements DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxProcessor.class);
    private static final long PDF_TIMEOUT_MS = 240_000;
    private static final long COVER_TIMEOUT_MS = 30_000;

    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger restarts = new AtomicInteger();
    private final AtomicLong totalMs = new AtomicLong();
    private volatile String lastError = "";
    private ExecutorService worker = newWorker();

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lume-processador");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ExtractionResult extractPdf(Path file, int maxChars, int coverWidth) throws Exception {
        return run(() -> PdfExtractor.extract(file, maxChars, coverWidth), PDF_TIMEOUT_MS);
    }

    @Override
    public Cover renderCover(CoverRequest request) throws Exception {
        return run(() -> TypographicCover.render(request), COVER_TIMEOUT_MS);
    }

    private <T> T run(Callable<T> job, long timeoutMs) throws Exception {
        long started = System.nanoTime();
        busy.incrementAndGet();
        Future<T> future;
        synchronized (this) {
            future = worker.submit(job);
        }
        try {
            T result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            processed.incrementAndGet();
            totalMs.addAndGet((System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            restart();
            failed.incrementAndGet();
            lastError = "O processador demorou demais e foi reiniciado.";
            throw new IllegalStateException(lastError);
        } catch (ExecutionException e) {
            failed.incrementAndGet();
            Throwable cause = e.getCause();
            lastError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            if (cause instanceof ProcessingRejected rejected) {
                throw rejected;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof OutOfMemoryError) {
                restart();
                throw new IllegalStateException("O PDF é grande demais para a memória disponível.");
            }
            throw new IllegalStateException(lastError, cause);
        } finally {
            busy.decrementAndGet();
        }
    }

    private synchronized void restart() {
        restarts.incrementAndGet();
        worker.shutdownNow();
        worker = newWorker();
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        int done = processed.get();
        out.put("online", true);
        out.put("busy", busy.get());
        out.put("processed", done);
        out.put("failed", failed.get());
        out.put("avg_ms", done == 0 ? null : Math.round((double) totalMs.get() / done));
        out.put("restarts", restarts.get());
        out.put("error", lastError);
        out.put("engine", "Apache PDFBox " + org.apache.pdfbox.util.Version.getVersion());
        return out;
    }

    /** O PDFBox monta o cache das fontes do sistema na primeira capa; aqui isso acontece em segundo plano. */
    @Override
    public void warmUp() {
        Thread thread = new Thread(() -> {
            try {
                org.apache.pdfbox.pdmodel.font.FontMappers.instance().getFontBoxFont("Helvetica", null);
            } catch (RuntimeException e) {
                log.debug("Aquecimento das fontes: {}", e.getMessage());
            }
        }, "lume-fontes");
        thread.setDaemon(true);
        thread.start();
    }

    public void close() {
        worker.shutdownNow();
    }
}
