package io.github.ravelroot.lume.desktop;

import java.awt.Desktop;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;

/**
 * Integração com o Windows: diálogos nativos (AWT/Swing com a aparência do sistema), Explorador e programa padrão.
 * Em Linux e macOS (desenvolvimento) usa os equivalentes do sistema.
 */
public class NativeDesktop implements DesktopIntegration {

    private static final Logger log = LoggerFactory.getLogger(NativeDesktop.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public NativeDesktop() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            log.debug("Aparência do sistema indisponível: {}", e.getMessage());
        }
    }

    @Override
    public boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    /**
     * Os diálogos precisam de uma janela "dona" visível e sempre no topo; senão abrem atrás da janela do Lume, que é
     * de outro processo (o Edge).
     */
    private synchronized <T> T withOwner(Function<Frame, T> action) {
        Frame owner = new Frame("Lume");
        owner.setUndecorated(true);
        owner.setType(Window.Type.UTILITY);
        owner.setAlwaysOnTop(true);
        owner.setSize(1, 1);
        owner.setLocationRelativeTo(null);
        owner.setVisible(true);
        owner.toFront();
        try {
            return action.apply(owner);
        } finally {
            owner.dispose();
        }
    }

    @Override
    public List<Path> pickFiles() {
        return withOwner(owner -> {
            FileDialog dialog = new FileDialog(owner, "Adicionar documentos", FileDialog.LOAD);
            dialog.setMultipleMode(true);
            dialog.setFile("*.pdf;*.docx");
            dialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx)$"));
            dialog.setVisible(true);
            List<Path> out = new ArrayList<>();
            for (File file : dialog.getFiles()) {
                out.add(file.toPath());
            }
            return out;
        });
    }

    @Override
    public Path pickFolder() {
        return withOwner(owner -> {
            AtomicReference<Path> chosen = new AtomicReference<>();
            runOnSwing(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Importar uma pasta");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setApproveButtonText("Importar pasta");
                if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    chosen.set(chooser.getSelectedFile().toPath());
                }
            });
            return chosen.get();
        });
    }

    @Override
    public Path saveFile(String title, Path suggested, String description, String... extensions) {
        return withOwner(owner -> {
            FileDialog dialog = new FileDialog(owner, title, FileDialog.SAVE);
            if (suggested.getParent() != null) {
                dialog.setDirectory(suggested.getParent().toString());
            }
            dialog.setFile(suggested.getFileName().toString());
            dialog.setVisible(true);
            if (dialog.getFile() == null) {
                return null;
            }
            Path target = Path.of(dialog.getDirectory(), dialog.getFile());
            String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean hasExtension = extensions.length == 0;
            for (String extension : extensions) {
                hasExtension |= name.endsWith("." + extension);
            }
            return hasExtension ? target : target.resolveSibling(target.getFileName() + "." + extensions[0]);
        });
    }

    private static void runOnSwing(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @Override
    public void openPath(Path path) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(path.toFile());
            return;
        }
        if (WINDOWS) {
            new ProcessBuilder("cmd", "/c", "start", "", path.toString()).start();
        } else {
            new ProcessBuilder("xdg-open", path.toString()).start();
        }
    }

    @Override
    public void showInFolder(Path file) throws IOException {
        if (WINDOWS) {
            new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
        } else {
            openPath(Files.isDirectory(file) ? file : file.getParent());
        }
    }

    @Override
    public void openExternal(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        }
        if (WINDOWS) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } else {
            new ProcessBuilder("xdg-open", url).start();
        }
    }

    private static Path knownFolder(com.sun.jna.platform.win32.Guid.GUID id, String fallback) {
        if (WINDOWS) {
            try {
                return Path.of(Shell32Util.getKnownFolderPath(id));
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                log.debug("Pasta conhecida indisponível: {}", e.getMessage());
            }
        }
        return Path.of(System.getProperty("user.home"), fallback);
    }

    @Override
    public Path downloadsFolder() {
        return knownFolder(KnownFolders.FOLDERID_Downloads, "Downloads");
    }

    @Override
    public Path documentsFolder() {
        return knownFolder(KnownFolders.FOLDERID_Documents, "Documents");
    }
}
