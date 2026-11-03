package dev.phonebot.android.phone;

import android.view.Surface;
import android.content.Intent;
import android.os.ResultReceiver;

interface ISystemShell {
    String execute(String requestId, String command) = 0;
    void cancel(String requestId) = 1;
    int[] identity() = 2;
    int createDisplay(in Surface surface, int width, int height, int dpi, in ResultReceiver status) = 3;
    void releaseDisplay(int displayId) = 4;
    String tasks() = 5;
    boolean launch(int displayId, in Intent intent) = 6;
    boolean touch(int displayId, float x, float y, float endX, float endY, long duration) = 7;
    boolean key(int displayId, int keyCode) = 8;
    boolean paste(int displayId, String text) = 9;
    void setDisplayActive(int displayId, boolean active) = 10;
    void destroy() = 16777114;
}
