package io.github.ravelroot.lume.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * O que a janela pede ao sistema: diálogos de arquivo, abrir no programa padrão, mostrar no Explorador e abrir links
 * no navegador. Fazia o papel do preload/IPC da versão em Electron.
 */
public interface DesktopIntegration {

    /** false quando o Lume roda sem tela (testes, servidor); os diálogos então ficam indisponíveis. */
    boolean available();

    /** Seletor de PDFs e DOCX (vários). Lista vazia se a pessoa cancelar. */
    List<Path> pickFiles();

    /** Seletor de pasta; null se a pessoa cancelar. */
    Path pickFolder();

    /** Diálogo "Salvar como"; null se a pessoa cancelar. */
    Path saveFile(String title, Path suggested, String description, String... extensions);

    /** Abre o arquivo no programa padrão do sistema. */
    void openPath(Path path) throws IOException;

    /** Abre o Explorador com o arquivo selecionado. */
    void showInFolder(Path file) throws IOException;

    /** Abre um link http(s) ou mailto no navegador padrão. */
    void openExternal(String url) throws IOException;

    /** Pasta Downloads da pessoa. */
    Path downloadsFolder();

    /** Pasta Documentos da pessoa. */
    Path documentsFolder();
}
