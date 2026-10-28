package io.github.ravelroot.lume.core;

/** O arquivo não pode entrar na biblioteca (formato, tamanho, arquivo vazio ou corrompido). */
public class ImportRejected extends ApiException {

    public ImportRejected(String message) {
        super(message, 400, "rejected");
    }
}
