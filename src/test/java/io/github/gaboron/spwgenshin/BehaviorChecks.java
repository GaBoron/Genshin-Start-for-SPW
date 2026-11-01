package io.github.gaboron.spwgenshin;

import com.sun.jna.platform.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Behavioral checks never call ShellExecuteEx or start a game, launcher or browser. */
public final class BehaviorChecks {
    private static int checks;

    public static void main(String[] args) throws IOException {
        artistMatching();
        playbackGating();
        playbackClock();
        routing();
        installationValidation();
        String currentExecutable = Path.of(ProcessHandle.current().info().command().orElseThrow()).getFileName().toString();
        expect(WindowsLaunch.processRunning(currentExecutable), "enumerate current process without elevation");
        expect(!WindowsLaunch.processRunning("spw-genshin-nonexistent-process.exe"), "missing process");
        var found = new WindowsInstallations().discover("", "");
        var invalidOverrides = new WindowsInstallations().discover("\"", "C:\\");
        expect(found.equals(invalidOverrides), "malformed overrides preserve automatic discovery");
        System.out.println("Read-only local discovery: " + found);
        System.out.println("Selected route (not opened): " + WindowsLaunch.plan(found));
        System.out.println("Passed " + checks + " behavior checks; no applications launched.");
    }

    private static void artistMatching() {
        for (String artist : List.of("HOYO-MiX", "hoyo-mix", "HOYO–MiX", "HOYO MIX", "ＨＯＹＯ－ＭｉＸ", "陈致逸 / HOYO-MiX", "HOYO-MiX;歌手")) {
            expect(PlaybackTrigger.matchesArtist(artist), "recognize artist: " + artist);
        }
        for (String artist : List.of("", "陈致逸", "NotHOYO-MiX", "HOYO-MiXtape", "HOYO-MiXology")) {
            expect(!PlaybackTrigger.matchesArtist(artist), "reject artist: " + artist);
        }
        expect(!PlaybackTrigger.matchesArtist(null), "null artist");
        expect(new PlaybackTrigger.Track("album", "歌手", "HOYO-MiX").matches(), "album artist match");
    }

    private static void playbackGating() {
        var trigger = new PlaybackTrigger();
        var hoyo = new PlaybackTrigger.Track("a", "HOYO-MiX", "");
        trigger.select(hoyo);
        expect(trigger.playing(false) == null, "loaded but paused");
        var first = trigger.playing(true);
        expect(first != null && trigger.isCurrent(first), "first play");
        expect(trigger.playing(true) == null, "duplicate playing callback");
        expect(trigger.beginLaunch(first), "claim accepted for execution");
        expect(!trigger.beginLaunch(first), "execution can begin only once");
        expect(trigger.position(1_000) == null, "position callback deduplicated");
        trigger.playing(false);
        expect(!trigger.isCurrent(first), "queued launch blocked while paused");
        expect(trigger.playing(true) == null, "resume deduplicated");
        trigger.seek();
        expect(trigger.position(0) == null, "seek deduplicated");
        trigger.select(hoyo);
        expect(trigger.playing(true) == null, "repeat same track deduplicated");
        trigger.select(new PlaybackTrigger.Track("b", "普通歌手", ""));
        expect(!trigger.isCurrent(first), "stale launch blocked on track change");
        expect(trigger.playing(true) == null, "unrelated song");
        trigger.select(hoyo);
        var again = trigger.playing(true);
        expect(again != null, "return to a song can trigger");
        trigger.configure(false);
        expect(!trigger.isCurrent(again), "disable cancels queued launch");
        trigger.select(new PlaybackTrigger.Track("c", "HOYO-MiX", ""));
        expect(trigger.playing(true) == null, "disabled match");
        trigger.configure(true);
        var enabled = trigger.playing(true);
        expect(enabled != null, "enable can trigger unattempted current song");
        trigger.close();
        expect(!trigger.isCurrent(enabled), "unload cancels queued launch");
        expect(trigger.playing(true) == null, "no launch after close");
        trigger.select(hoyo);
        expect(trigger.position(5_000) == null, "late callback after close");

        var queued = new PlaybackTrigger();
        queued.select(hoyo);
        var beforePause = queued.playing(true);
        queued.playing(false);
        expect(!queued.beginLaunch(beforePause), "pause cancels a not-yet-started launch");
        var afterPause = queued.playing(true);
        expect(afterPause != null && queued.beginLaunch(afterPause), "resume retries a cancelled queued launch");

        var disabled = new PlaybackTrigger();
        disabled.select(hoyo);
        var beforeDisable = disabled.playing(true);
        disabled.configure(false);
        disabled.configure(true);
        var afterEnable = disabled.position(1_000);
        expect(!disabled.beginLaunch(beforeDisable), "disabled ticket stays invalid after re-enable");
        expect(afterEnable != null && disabled.beginLaunch(afterEnable), "re-enable retries an unstarted launch");
    }

    private static void playbackClock() {
        AtomicLong now = new AtomicLong();
        var trigger = new PlaybackTrigger(now::get);
        trigger.select(new PlaybackTrigger.Track("hot-enable", "HOYO-MiX", ""));
        expect(trigger.position(0) == null, "unknown playing state needs evidence");
        now.addAndGet(100_000_000);
        expect(trigger.position(100) == null, "one clock advance is insufficient");
        now.addAndGet(100_000_000);
        expect(trigger.position(200) != null, "advancing clock supports hot enable");
        trigger.select(new PlaybackTrigger.Track("paused", "HOYO-MiX", ""));
        trigger.playing(false);
        now.addAndGet(100_000_000);
        expect(trigger.position(500) == null, "paused seek does not launch");
        now.addAndGet(100_000_000);
        expect(trigger.position(600) == null, "known pause overrides clock inference");
        trigger.select(new PlaybackTrigger.Track("seek", "HOYO-MiX", ""));
        trigger.position(0);
        now.addAndGet(100_000_000);
        trigger.position(100);
        trigger.seek();
        now.addAndGet(100_000_000);
        expect(trigger.position(50_000) == null, "seek clears initial clock evidence");
        now.addAndGet(100_000_000);
        expect(trigger.position(50_000) == null, "stationary clock after seek");
        now.addAndGet(100_000_000);
        expect(trigger.position(50_000) == null, "stationary clock remains inactive");
        trigger.select(new PlaybackTrigger.Track("jump", "HOYO-MiX", ""));
        trigger.position(0);
        now.addAndGet(100_000_000);
        expect(trigger.position(60_000) == null, "large clock jump is not playback");
        trigger.select(null);
        expect(trigger.playing(true) == null, "no current media");
    }

    private static void routing() {
        Path game = Path.of("C:/Test/Genshin Impact Game/YuanShen.exe");
        Path launcher = Path.of("C:/Test/miHoYo Launcher/launcher.exe");
        var direct = WindowsLaunch.plan(new WindowsInstallations.Found(game, launcher));
        expect(direct.file().equals(game.toString()) && direct.arguments() == null, "game bypasses launcher");
        expect(direct.directory().equals(game.getParent()), "game working directory");
        var page = WindowsLaunch.plan(new WindowsInstallations.Found(null, launcher));
        expect(page.file().equals(launcher.toString()), "launcher fallback");
        expect("--game=hk4e_cn".equals(page.arguments()), "explicit mainland Genshin page");
        var cloud = WindowsLaunch.plan(new WindowsInstallations.Found(null, null));
        expect("https://ys.mihoyo.com/cloud/".equals(cloud.file()), "official mainland cloud fallback");
        expect(cloud.arguments() == null, "cloud has no executable parameters");
    }

    private static void installationValidation() throws IOException {
        FileUtils trash = FileUtils.getInstance();
        if (!trash.hasTrash()) throw new IOException("Recycle Bin unavailable; installation fixtures were not created.");
        Path fixture = Files.createTempDirectory("spw-genshin-check-").toAbsolutePath().normalize();
        try {
            Path game = Files.createDirectories(fixture.resolve("国服 游戏/Genshin Impact Game"));
            Files.write(game.resolve("YuanShen.exe"), new byte[]{'M', 'Z'});
            expect(chooseGame(game) == null, "incomplete game without data directory");
            Files.createDirectory(game.resolve("YuanShen_Data"));
            expect(chooseGame(game).equals(game.resolve("YuanShen.exe")), "mainland game directory");
            expect(chooseGame(game.resolve("YuanShen.exe")) != null, "manual full executable path");
            Files.writeString(game.resolve("config.ini"), "[General]\ngame_biz=hk4e_global\n");
            expect(chooseGame(game) == null, "reject explicit international config");
            Files.writeString(game.resolve("config.ini"), "[General]\ngame_biz=hk4e_cn\n");
            expect(chooseGame(game) != null, "accept mainland config");
            Path global = Files.createDirectories(fixture.resolve("international"));
            Files.write(global.resolve("GenshinImpact.exe"), new byte[]{'M', 'Z'});
            Files.createDirectory(global.resolve("GenshinImpact_Data"));
            expect(chooseGame(global) == null, "international executable not selected");
            Path launcher = Files.createDirectories(fixture.resolve("米哈游启动器"));
            Files.write(launcher.resolve("launcher.exe"), new byte[]{'M', 'Z'});
            Files.writeString(launcher.resolve("config.ini"), "[hyp]\nprimary_game=nap_cn\n");
            expect(WindowsInstallations.choose(List.of(), List.of(launcher)).launcher() != null, "launcher may default to another mainland game");
            Files.writeString(launcher.resolve("config.ini"), "[hyp]\nprimary_game=hk4e_global\n");
            expect(WindowsInstallations.choose(List.of(), List.of(launcher)).launcher() == null, "reject international launcher config");
            expect(chooseGame(fixture.resolve("missing")) == null, "stale install record");
        } finally {
            // This exact, newly created temp directory is recycled, never permanently deleted.
            if (!fixture.getFileName().toString().startsWith("spw-genshin-check-") || fixture.getParent() == null) {
                throw new IOException("Refusing to recycle unexpected fixture path: " + fixture);
            }
            trash.moveToTrash(fixture.toFile());
        }
    }

    private static Path chooseGame(Path candidate) {
        return WindowsInstallations.choose(List.of(candidate), List.of()).game();
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
