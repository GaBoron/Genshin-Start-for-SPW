package io.github.gaboron.spwgenshin;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinNT;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads only mainland installation records and a bounded set of conventional paths. */
final class WindowsInstallations {
    record Found(Path game, Path launcher) { }
    private static final String HYP = "Software\\miHoYo\\HYP";
    private static final String UNINSTALL = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    Found discover(String gameOverride, String launcherOverride) {
        Set<Path> games = new LinkedHashSet<>();
        Set<Path> launchers = new LinkedHashSet<>();
        add(games, gameOverride);
        add(launchers, launcherOverride);
        for (var hive : List.of(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            for (int view : new int[]{WinNT.KEY_WOW64_64KEY, WinNT.KEY_WOW64_32KEY}) {
                readHyp(hive, HYP, view, 0, games, launchers);
                readUninstall(hive, view, games, launchers);
            }
        }
        for (String name : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            Path root = path(System.getenv(name));
            if (root != null) addConventional(root, games, launchers);
        }
        for (File drive : File.listRoots()) addConventional(drive.toPath(), games, launchers);
        for (Path launcher : new ArrayList<>(launchers)) {
            Path root = launcher.getFileName() != null && launcher.getFileName().toString().equalsIgnoreCase("launcher.exe")
                    ? launcher.getParent() : launcher;
            if (root == null) continue;
            addGameDirectory(games, root.resolve("games/Genshin Impact Game"));
            addGameDirectory(games, root.resolve("Genshin Impact Game"));
            // Older launchers record a separately located game in their config.
            addGameDirectory(games, path(readIni(root.resolve("config.ini"), "game_install_path")));
        }
        return choose(games, launchers);
    }

    static Found choose(Iterable<Path> games, Iterable<Path> launchers) {
        Path game = firstValid(games, "YuanShen.exe");
        Path launcher = firstValid(launchers, "launcher.exe");
        return new Found(game, launcher);
    }

    private static Path firstValid(Iterable<Path> paths, String filename) {
        for (Path candidate : paths) {
            Path executable = candidate.getFileName() != null && candidate.getFileName().toString().equalsIgnoreCase(filename)
                    ? candidate : candidate.resolve(filename);
            if (!Files.isRegularFile(executable)) continue;
            if (filename.equals("YuanShen.exe")) {
                if (!Files.isDirectory(executable.resolveSibling("YuanShen_Data"))) continue;
                String biz = readIni(executable.resolveSibling("config.ini"), "game_biz");
                if (!biz.isEmpty() && !biz.equals("hk4e_cn")) continue;
            } else {
                // Explicitly exclude an international HoYoPlay installation even at a custom path.
                String primary = readIni(executable.resolveSibling("config.ini"), "primary_game");
                if (primary.endsWith("_global")) continue;
            }
            return executable.toAbsolutePath().normalize();
        }
        return null;
    }

    private void readHyp(WinReg.HKEY hive, String key, int view, int depth, Set<Path> games, Set<Path> launchers) {
        if (depth > 4) return;
        add(launchers, value(hive, key, "InstallPath", view));
        if (key.toLowerCase(Locale.ROOT).endsWith("\\hk4e_cn")) {
            addGameDirectory(games, path(value(hive, key, "GameInstallPath", view)));
        }
        for (String child : keys(hive, key, view)) readHyp(hive, key + "\\" + child, view, depth + 1, games, launchers);
    }

    private void readUninstall(WinReg.HKEY hive, int view, Set<Path> games, Set<Path> launchers) {
        for (String child : keys(hive, UNINSTALL, view)) {
            String key = UNINSTALL + "\\" + child;
            String name = value(hive, key, "DisplayName", view);
            if (name.contains("米哈游启动器")) add(launchers, value(hive, key, "InstallLocation", view));
            if (name.equals("原神")) {
                Path root = path(value(hive, key, "InstallLocation", view));
                addGameDirectory(games, root);
                if (root != null) addGameDirectory(games, root.resolve("Genshin Impact Game"));
            }
        }
    }

    private static void addConventional(Path root, Set<Path> games, Set<Path> launchers) {
        launchers.add(root.resolve("miHoYo Launcher"));
        launchers.add(root.resolve("miHoYo/miHoYo Launcher"));
        for (String folder : List.of("Genshin Impact", "原神")) {
            addGameDirectory(games, root.resolve(folder));
            addGameDirectory(games, root.resolve(folder).resolve("Genshin Impact Game"));
        }
        addGameDirectory(games, root.resolve("Games/Genshin Impact Game"));
    }

    private static void addGameDirectory(Set<Path> games, Path directory) {
        if (directory != null) games.add(directory);
    }

    private static void add(Set<Path> paths, String text) {
        Path parsed = path(text);
        if (parsed != null) paths.add(parsed);
    }

    private static Path path(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.strip();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            Path result = Path.of(normalized);
            return result.isAbsolute() ? result.normalize() : null;
        } catch (InvalidPathException error) { return null; }
    }

    private static String[] keys(WinReg.HKEY hive, String key, int view) {
        try { return Advapi32Util.registryGetKeys(hive, key, view); }
        catch (Win32Exception error) { return new String[0]; }
    }

    private static String value(WinReg.HKEY hive, String key, String name, int view) {
        try { return Advapi32Util.registryGetStringValue(hive, key, name, view); }
        catch (Win32Exception error) { return ""; }
    }

    private static String readIni(Path file, String name) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 256_000) return "";
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).strip().equalsIgnoreCase(name)) return line.substring(equals + 1).strip();
            }
        } catch (java.io.IOException | SecurityException error) { /* Missing or unreadable config is optional. */ }
        return "";
    }
}
