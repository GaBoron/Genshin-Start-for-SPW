package io.github.gaboron.spwgenshin;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GenshinPlugin extends SpwPlugin {
    private static final Logger LOG = Logger.getLogger("原神，启动！");
    private static volatile GenshinPlugin current;
    private PlaybackTrigger trigger;
    private ExecutorService worker;
    private ConfigManager config;
    private Consumer<ConfigHelper> listener;
    private volatile String gamePath = "";
    private volatile String launcherPath = "";
    private volatile String status = "等待播放 HOYO-MiX";

    public GenshinPlugin(PluginContext context) { super(context); }

    @Override public synchronized void start() {
        if (trigger != null) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            status = "当前插件仅支持 Windows";
            LOG.info(status);
            return;
        }
        try {
            trigger = new PlaybackTrigger();
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SPW Genshin Start");
                thread.setDaemon(true);
                thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
                return thread;
            });
            config = WorkshopApi.manager().createConfigManager();
            listener = this::configure;
            config.addConfigChangeListener(listener);
            configure(config.getConfig());
            selectCurrent();
            current = this;
        } catch (Exception | LinkageError error) {
            stop();
            LOG.log(Level.WARNING, "原神启动插件初始化失败", error);
        }
    }

    private synchronized void configure(ConfigHelper settings) {
        if (trigger == null) return;
        gamePath = settings.get("game_path", "");
        launcherPath = settings.get("launcher_path", "");
        boolean enabled = settings.get("enabled", true);
        trigger.configure(enabled);
        status = enabled ? "等待播放 HOYO-MiX" : "已关闭自动启动";
    }

    static GenshinPlugin active() { return current; }

    synchronized void playing(boolean playing) {
        if (trigger == null) return;
        selectCurrent();
        submit(trigger.playing(playing));
    }

    synchronized void position(long position) {
        if (trigger == null) return;
        selectCurrent();
        submit(trigger.position(position));
    }

    synchronized void seek() { if (trigger != null) trigger.seek(); }

    private void selectCurrent() { trigger.select(track(WorkshopApi.playback().getCurrentMediaItem())); }

    private static PlaybackTrigger.Track track(PlaybackExtensionPoint.MediaItem media) {
        if (media == null) return null;
        String key = media.getPath().isBlank() ? media.getId() : media.getPath();
        if (key.isBlank()) key = media.getTitle() + "\n" + media.getArtist() + "\n" + media.getAlbum();
        return new PlaybackTrigger.Track(key, media.getArtist(), media.getAlbumArtist());
    }

    private void submit(PlaybackTrigger.Ticket ticket) {
        if (ticket == null) return;
        PlaybackTrigger owner = trigger;
        try {
            worker.execute(() -> launch(owner, ticket));
        } catch (RejectedExecutionException ignored) { /* Plugin stopped while receiving a host callback. */ }
    }

    private void launch(PlaybackTrigger owner, PlaybackTrigger.Ticket ticket) {
        try {
            if (!owner.isCurrent(ticket)) return;
            var found = new WindowsInstallations().discover(gamePath, launcherPath);
            var target = WindowsLaunch.target(found);
            if (!owner.beginLaunch(ticket)) return;
            if (target.isEmpty()) {
                status = "国服原神已在运行";
                return;
            }
            new WindowsLaunch().open(target.get());
            status = "已打开：" + target.get().description();
            LOG.info(status);
        } catch (Exception | LinkageError error) {
            owner.beginLaunch(ticket);
            status = "启动失败：" + error.getMessage();
            LOG.log(Level.WARNING, status, error);
        }
    }

    public static void showStatus() {
        GenshinPlugin plugin = current;
        WorkshopApi.ui().toast(plugin == null ? "插件未启用或当前系统不受支持" : plugin.status,
                WorkshopApi.Ui.ToastType.Success);
    }

    @Override public synchronized void stop() {
        if (current == this) current = null;
        if (trigger != null) trigger.close();
        trigger = null;
        if (config != null && listener != null) {
            try { config.removeConfigChangeListener(listener); }
            catch (Exception | LinkageError error) { LOG.log(Level.WARNING, "取消设置监听失败", error); }
        }
        config = null;
        listener = null;
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    @Override public void update() { stop(); }
    @Override public void delete() { stop(); }
}
