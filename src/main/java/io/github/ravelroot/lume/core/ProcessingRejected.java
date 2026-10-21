package io.github.ravelroot.lume.core;

/**
 * Falha definitiva de leitura: o arquivo está corrompido, protegido por senha ou num formato que o Lume não lê.
 * Tentar de novo não adianta, então o pipeline marca o documento como "Falhou" na hora, com esta mensagem.
 */
public class ProcessingRejected extends RuntimeException {

    private final String code;

    public ProcessingRejected(String message) {
        this(message, "corrupt");
    }

    public ProcessingRejected(String message, String code) {
        super(message);
        this.code = code;
    }

    /** corrupt, encrypted ou unsupported. */
    public String code() {
        return code;
    }
}
