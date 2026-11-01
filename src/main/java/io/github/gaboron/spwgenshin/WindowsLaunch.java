package io.github.gaboron.spwgenshin;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Uses Windows shell execution so the game's own elevation manifest is respected. */
final class WindowsLaunch {
    private static final int SEE_MASK_NOASYNC = 0x00000100;
    private static final int SEE_MASK_FLAG_NO_UI = 0x00000400;
    static final String CLOUD_URL = "https://ys.mihoyo.com/cloud/";
    record Target(String file, String arguments, Path directory, String description) { }

    static Optional<Target> target(WindowsInstallations.Found found) {
        if (gameRunning()) return Optional.empty();
        return Optional.of(plan(found));
    }

    static Target plan(WindowsInstallations.Found found) {
        if (found.game() != null) return new Target(found.game().toString(), null, found.game().getParent(), "国服原神本体");
        if (found.launcher() != null) {
            return new Target(found.launcher().toString(), "--game=hk4e_cn", found.launcher().getParent(), "米哈游启动器 · 原神");
        }
        return new Target(CLOUD_URL, null, null, "云·原神");
    }

    void open(Target target) throws IOException {
        // Shell extensions and the default browser may require a COM apartment.
        var initialized = Ole32.INSTANCE.CoInitializeEx(null,
                Ole32.COINIT_APARTMENTTHREADED | Ole32.COINIT_DISABLE_OLE1DDE);
        if (initialized.intValue() < 0) throw new IOException("Windows Shell 初始化失败：" + initialized);
        try { execute(target); }
        finally { Ole32.INSTANCE.CoUninitialize(); }
    }

    private void execute(Target target) throws IOException {
        var info = new ShellAPI.SHELLEXECUTEINFO();
        info.fMask = SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI;
        info.lpVerb = "open";
        info.lpFile = target.file();
        info.lpParameters = target.arguments();
        info.lpDirectory = target.directory() == null ? null : target.directory().toString();
        info.nShow = WinUser.SW_SHOWNORMAL;
        if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
            int error = Native.getLastError();
            throw new IOException(error == 1223 ? "已取消 Windows 权限请求" : "Windows 启动失败，错误码 " + error);
        }
    }

    private static boolean gameRunning() { return processRunning("YuanShen.exe"); }

    static boolean processRunning(String executable) {
        // Toolhelp exposes names of elevated games without opening their process handles.
        var snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        if (WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) return false;
        try {
            var entry = new Tlhelp32.PROCESSENTRY32();
            if (!Kernel32.INSTANCE.Process32First(snapshot, entry)) return false;
            do {
                if (Native.toString(entry.szExeFile).equalsIgnoreCase(executable)) return true;
            } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
            return false;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }
}
