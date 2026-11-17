package io.github.gaboron.spwgenshin;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real plugin entry point against a host that denies library access. */
final class PluginLifecycleChecks {
    static int verify() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger added = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        ConfigHelper settings = proxy(ConfigHelper.class, (object, method, args) -> {
            if (method.getName().equals("get")) return args[0].equals("enabled") ? false : args[1];
            throw new AssertionError("Unexpected settings call: " + method.getName());
        });
        ConfigManager config = proxy(ConfigManager.class, (object, method, args) -> switch (method.getName()) {
            case "getConfig" -> settings;
            case "addConfigChangeListener" -> { added.incrementAndGet(); yield null; }
            case "removeConfigChangeListener" -> { removed.incrementAndGet(); yield null; }
            default -> throw new AssertionError("Unexpected config call: " + method.getName());
        });
        WorkshopApi.Manager manager = proxy(WorkshopApi.Manager.class, (object, method, args) -> {
            if (method.getName().equals("createConfigManager")) return config;
            throw new AssertionError("Unexpected manager call: " + method.getName());
        });
        WorkshopApi.Playback playback = proxy(WorkshopApi.Playback.class, (object, method, args) -> {
            reads.incrementAndGet();
            throw new SecurityException("library-read was not granted");
        });
        WorkshopApi api = proxy(WorkshopApi.class, (object, method, args) -> switch (method.getName()) {
            case "getManager" -> manager;
            case "getPlayback" -> playback;
            default -> throw new AssertionError("Unexpected host API call: " + method.getName());
        });
        var instanceField = WorkshopApi.Companion.getClass().getDeclaredField("instance");
        Object previous = instanceField.get(null);
        var context = new PluginContext("spw-genshin-start", "0.1.0", "fixture", "1.19.3", Channel.Steam);
        var plugin = new GenshinPlugin(context);
        WorkshopApi.Companion.setInstance(api);
        try {
            plugin.start();
            require(GenshinPlugin.active() == plugin, "Plugin must start without library-read permission");
            require(reads.get() == 0, "Startup must not query the protected playback API");
            require(added.get() == 1, "Settings listener registered once");

            var extension = new GenshinPlaybackExtension();
            var media = new PlaybackExtensionPoint.MediaItem("Fixture", "HOYO-MiX", "Album", "", "C:/Fixture/hoyo.flac");
            require(extension.onBeforeLoadLyrics(media) == null, "Metadata observation must not replace lyrics");
            extension.onIsPlayingChanged(true);
            extension.onPositionUpdated(1_000);
            require(reads.get() == 0, "Callbacks must use their media payload without querying the library");

            var triggerField = GenshinPlugin.class.getDeclaredField("trigger");
            triggerField.setAccessible(true);
            var trigger = (PlaybackTrigger) triggerField.get(plugin);
            // Only exercise the gate directly while enabled; never queue an actual launch.
            trigger.configure(true);
            var ticket = trigger.playing(true);
            require(ticket != null && ticket.key().equals(media.getPath()), "Callback metadata must reach the HOYO-MiX trigger");
            plugin.stop();
            require(GenshinPlugin.active() == null && removed.get() == 1, "Stop releases the active instance and settings listener");
            extension.onIsPlayingChanged(true);
            require(reads.get() == 0, "Late callbacks remain inert");
            return 8;
        } finally {
            plugin.stop();
            instanceField.set(null, previous);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void require(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
    }
}
