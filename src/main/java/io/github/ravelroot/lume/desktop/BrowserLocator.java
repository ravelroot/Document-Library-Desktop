package io.github.ravelroot.lume.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * Procura o navegador que vai desenhar a janela do Lume. O Microsoft Edge (WebView2/Chromium) vem com o Windows 10 e
 * 11, então a interface roda no mesmo motor que rodava no Electron; o Chrome serve de reserva.
 */
public final class BrowserLocator {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String APP_PATHS = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\";

    private BrowserLocator() {
    }

    public static Path find(Map<String, String> env) {
        String custom = env.get("LUME_BROWSER");
        if (custom != null && !custom.isBlank() && Files.isRegularFile(Path.of(custom))) {
            return Path.of(custom);
        }
        for (Path candidate : candidates(env)) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return WINDOWS ? fromRegistry() : null;
    }

    private static List<Path> candidates(Map<String, String> env) {
        List<Path> out = new ArrayList<>();
        if (WINDOWS) {
            for (String root : new String[] {env.get("ProgramFiles(x86)"), env.get("ProgramFiles"), env.get("LOCALAPPDATA")}) {
                if (root != null) {
                    out.add(Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe"));
                }
            }
            for (String root : new String[] {env.get("ProgramFiles"), env.get("ProgramFiles(x86)"), env.get("LOCALAPPDATA")}) {
                if (root != null) {
                    out.add(Path.of(root, "Google", "Chrome", "Application", "chrome.exe"));
                }
            }
        } else {
            String path = env.getOrDefault("PATH", "");
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String name : new String[] {"microsoft-edge", "google-chrome", "chromium", "chromium-browser"}) {
                    out.add(Path.of(dir, name));
                }
            }
        }
        return out;
    }

    private static Path fromRegistry() {
        for (String exe : new String[] {"msedge.exe", "chrome.exe"}) {
            for (WinReg.HKEY root : new WinReg.HKEY[] {WinReg.HKEY_LOCAL_MACHINE, WinReg.HKEY_CURRENT_USER}) {
                try {
                    if (Advapi32Util.registryKeyExists(root, APP_PATHS + exe)) {
                        String value = Advapi32Util.registryGetStringValue(root, APP_PATHS + exe, "");
                        if (value != null && Files.isRegularFile(Path.of(value))) {
                            return Path.of(value);
                        }
                    }
                } catch (RuntimeException | UnsatisfiedLinkError e) {
                    // chave ausente ou sem permissão: tenta a próxima
                }
            }
        }
        return null;
    }
}
