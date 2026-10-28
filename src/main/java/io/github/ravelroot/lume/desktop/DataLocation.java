package io.github.ravelroot.lume.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Onde ficam os dados. Ordem de escolha:
 * <ol>
 *   <li>variável LUME_DATA_DIR;</li>
 *   <li>modo portátil: uma pasta "Lume-dados" ao lado do Lume.exe (para levar num pendrive);</li>
 *   <li>padrão: %APPDATA%\Lume\Biblioteca (a mesma pasta da versão em Electron, então a biblioteca continua lá).</li>
 * </ol>
 *
 * @param dataDir  a biblioteca (lume.db e documentos/)
 * @param portable true no modo portátil
 * @param appDir   dados do aplicativo que não são da biblioteca (perfil da janela)
 */
public record DataLocation(Path dataDir, boolean portable, Path appDir) {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public static DataLocation resolve(Map<String, String> env) {
        String custom = env.get("LUME_DATA_DIR");
        if (custom != null && !custom.isBlank()) {
            Path dir = Path.of(custom).toAbsolutePath().normalize();
            return new DataLocation(dir, false, dir.resolve("app"));
        }
        // O Lume.exe (lançador) informa a pasta onde ele está; sem ele, vale a pasta de trabalho.
        String exeDir = env.get("LUME_EXE_DIR");
        if (exeDir != null && !exeDir.isBlank()) {
            Path portable = Path.of(exeDir).resolve("Lume-dados");
            if (Files.isDirectory(portable)) {
                return new DataLocation(portable.resolve("Biblioteca"), true, portable.resolve("app"));
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        if (WINDOWS) {
            String appData = env.getOrDefault("APPDATA", home.resolve("AppData").resolve("Roaming").toString());
            String localAppData = env.getOrDefault("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString());
            return new DataLocation(Path.of(appData, "Lume", "Biblioteca"), false, Path.of(localAppData, "Lume"));
        }
        String xdgData = env.getOrDefault("XDG_DATA_HOME", home.resolve(".local").resolve("share").toString());
        return new DataLocation(Path.of(xdgData, "Lume", "Biblioteca"), false, Path.of(xdgData, "Lume", "app"));
    }
}
