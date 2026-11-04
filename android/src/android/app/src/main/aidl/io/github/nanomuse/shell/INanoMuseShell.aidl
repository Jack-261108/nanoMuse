package io.github.nanomuse.shell;

import android.os.Bundle;
import android.view.Surface;

interface INanoMuseShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=started, 1=visible on main screen (not started), 2=no launcher entry, 3=error
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    String getAudioMode(String packageName) = 9;
    boolean setAudioMode(String packageName, String mode) = 10;
    int displayOfTask(int taskId) = 11;
    String packageOfTask(int taskId) = 12;
    int[] identity() = 13;
    void releaseAll() = 14;
    String topActivity(int displayId) = 15;
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 16;
    int watchMainScreen(int windowMs) = 17;
    Bundle mainArrivals(long sinceUptimeMs) = 18;
    int restartApp(int displayId, String packageName) = 19;
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 20;
    boolean recoverMuted() = 21;
    void destroy() = 16777114;
}
