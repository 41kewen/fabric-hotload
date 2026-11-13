package dev.fabric.hotload.host;

public final class ProbeTarget {
    public static int mixinTouches;

    private ProbeTarget() {
    }

    public static String value() {
        return "original";
    }
}
