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
    private JumpScare scare;
    private ExecutorService worker;
    private ConfigManager config;
    private Consumer<ConfigHelper> listener;
    private volatile String gamePath = "";
    private volatile String launcherPath = "";

    public GenshinPlugin(PluginContext context) { super(context); }

    @Override public synchronized void start() {
        if (trigger != null) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            LOG.info("当前插件仅支持 Windows");
            return;
        }
        try {
            trigger = new PlaybackTrigger();
            scare = new JumpScare();
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
        scare.configure(enabled);
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
        JumpScare effect = scare;
        try {
            worker.execute(() -> launch(owner, ticket, effect));
        } catch (RejectedExecutionException ignored) { /* Plugin stopped while receiving a host callback. */ }
    }

    private void launch(PlaybackTrigger owner, PlaybackTrigger.Ticket ticket, JumpScare effect) {
        try {
            if (!owner.isCurrent(ticket)) return;
            var found = new WindowsInstallations().discover(gamePath, launcherPath);
            var target = WindowsLaunch.target(found);
            if (!owner.beginLaunch(ticket)) return;
            if (target.isEmpty()) return;
            var outcome = effect.launch(target.get());
            if (outcome.opened()) LOG.info("已打开：" + target.get().description());
            if (outcome.soundError() != null) {
                LOG.log(Level.WARNING, "启动已完成，音效播放失败", outcome.soundError());
            }
        } catch (Exception | LinkageError error) {
            owner.beginLaunch(ticket);
            LOG.log(Level.WARNING, "启动失败", error);
        }
    }

    @Override public synchronized void stop() {
        if (current == this) current = null;
        if (trigger != null) trigger.close();
        trigger = null;
        if (scare != null) {
            try { scare.close(); }
            catch (Exception | LinkageError error) { LOG.log(Level.WARNING, "停止音效失败", error); }
        }
        scare = null;
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
