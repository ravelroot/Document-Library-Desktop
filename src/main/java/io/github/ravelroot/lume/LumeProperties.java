package io.github.ravelroot.lume;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do Lume (prefixo {@code lume.}). O {@link LumeApplication} preenche as pastas e a chave da janela ao
 * abrir; os testes passam os valores pelas propriedades do Spring.
 *
 * @param dataDir      pasta da biblioteca (lume.db, documentos/, tmp/)
 * @param portable     true quando os dados ficam em Lume-dados, ao lado do Lume.exe
 * @param launchKey    chave que a janela apresenta em /entrar para receber o cookie de sessão
 * @param retryDelayMs espera fixa entre tentativas (testes); null usa 5 s, 15 s, 45 s...
 * @param dev          modo de desenvolvimento (sem cache nos arquivos da interface)
 * @param appDir       pasta do próprio aplicativo (perfil do navegador da janela)
 * @param window       abre a janela ao iniciar (false nos testes e no modo servidor)
 */
@ConfigurationProperties(prefix = "lume")
public record LumeProperties(Path dataDir, boolean portable, String launchKey, Long retryDelayMs, boolean dev, Path appDir, boolean window) {

    public LumeProperties {
        if (dataDir == null) {
            dataDir = Path.of(System.getProperty("user.home"), "Lume-dados", "Biblioteca");
        }
        if (appDir == null) {
            appDir = dataDir.toAbsolutePath().getParent() == null ? dataDir.resolve("app") : dataDir.toAbsolutePath().getParent().resolve("app");
        }
    }
}
