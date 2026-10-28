package io.github.ravelroot.lume.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.ravelroot.lume.core.ApiException;

/** Sem tela (testes e modo servidor): os recursos do sistema respondem 501 em vez de travar esperando um diálogo. */
public class HeadlessDesktop implements DesktopIntegration {

    private static ApiException unavailable() {
        return new ApiException("Recurso disponível só com a janela do Lume aberta.", 501, "unavailable");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<Path> pickFiles() {
        throw unavailable();
    }

    @Override
    public Path pickFolder() {
        throw unavailable();
    }

    @Override
    public Path saveFile(String title, Path suggested, String description, String... extensions) {
        throw unavailable();
    }

    @Override
    public void openPath(Path path) throws IOException {
        throw unavailable();
    }

    @Override
    public void showInFolder(Path file) throws IOException {
        throw unavailable();
    }

    @Override
    public void openExternal(String url) throws IOException {
        throw unavailable();
    }

    @Override
    public Path downloadsFolder() {
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    @Override
    public Path documentsFolder() {
        return Path.of(System.getProperty("user.home"), "Documents");
    }
}
