package io.github.ravelroot.lume.desktop;

import java.util.Locale;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/** Traz para a frente a janela do Lume (as janelas do processo do navegador que o Lume abriu), pelo Windows. */
public final class WindowFocus {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final int SW_RESTORE = 9;
    private static final int SW_SHOWMINIMIZED = 2;

    private WindowFocus() {
    }

    /** Devolve true se achou uma janela visível do processo {@code pid} e pediu o foco para ela. */
    public static boolean focus(long pid) {
        if (!WINDOWS || pid <= 0) {
            return false;
        }
        try {
            HWND[] found = {null};
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd) && User32.INSTANCE.GetWindowTextLength(hwnd) > 0) {
                    found[0] = hwnd;
                    return false;
                }
                return true;
            }, null);
            if (found[0] == null) {
                return false;
            }
            WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
            User32.INSTANCE.GetWindowPlacement(found[0], placement);
            if (placement.showCmd == SW_SHOWMINIMIZED) {
                User32.INSTANCE.ShowWindow(found[0], SW_RESTORE);
            }
            User32.INSTANCE.SetForegroundWindow(found[0]);
            return true;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return false;
        }
    }
}
