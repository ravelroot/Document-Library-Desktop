package io.github.ravelroot.lume.ai;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Como o Lume fala com os provedores de IA. A implementação padrão usa o {@link java.net.http.HttpClient} do Java;
 * os testes trocam por uma resposta programada.
 */
public interface HttpTransport {

    Reply post(URI url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException;

    record Reply(int status, String body) {
    }
}
