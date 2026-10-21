package io.github.ravelroot.lume.ai;

/** O provedor de IA não respondeu do jeito esperado (rede, chave, formato da resposta). */
public class ProviderException extends Exception {

    public ProviderException(String message) {
        super(message);
    }
}
