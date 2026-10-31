package dev.phonebot.android.phone;

interface ISystemShell {
    String execute(String command) = 0;
    void cancel() = 1;
    void destroy() = 16777114;
}
