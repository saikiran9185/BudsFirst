package dev.saikiran.budsfirst;

interface IGuard {
    /** Reserved by Shizuku: called when the service is removed. */
    void destroy() = 16777114;

    /** Bluetooth addresses, highest priority first. */
    void setPriority(in List<String> addresses) = 1;

    /** JSON: ready, uid, holder, media, calls, connected[], log[]. */
    String status() = 2;

    /** Turns back on switches the old 0.1 version turned off. */
    void releaseAll() = 3;
}
