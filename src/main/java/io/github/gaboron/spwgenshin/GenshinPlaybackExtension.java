package io.github.gaboron.spwgenshin;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Observes playback without replacing lyrics or changing SPW's transport. */
public final class GenshinPlaybackExtension implements PlaybackExtensionPoint {
    @Override public String onBeforeLoadLyrics(MediaItem mediaItem) {
        dispatch(plugin -> plugin.mediaChanged(mediaItem));
        return null;
    }

    @Override public void onIsPlayingChanged(boolean playing) { dispatch(plugin -> plugin.playing(playing)); }
    @Override public void onPositionUpdated(long position) { dispatch(plugin -> plugin.position(position)); }
    @Override public void onSeekTo(long position) { dispatch(GenshinPlugin::seek); }

    @Override public void onStateChanged(State state) {
        if (state == State.Idle || state == State.Ended) dispatch(plugin -> plugin.playing(false));
    }

    private static void dispatch(Consumer<GenshinPlugin> action) {
        GenshinPlugin plugin = GenshinPlugin.active();
        if (plugin == null) return;
        try { action.accept(plugin); }
        catch (Exception | LinkageError error) {
            Logger.getLogger("原神，启动！").log(Level.WARNING, "读取播放状态失败", error);
        }
    }
}
