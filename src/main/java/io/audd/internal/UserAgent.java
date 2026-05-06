package io.audd.internal;

/** SDK identifier sent on every request. */
public final class UserAgent {
    public static final String SDK_VERSION = "0.1.0";

    private UserAgent() {}

    public static String value() {
        String javaVersion = System.getProperty("java.version", "unknown");
        String os = System.getProperty("os.name", "unknown").toLowerCase().replace(' ', '-');
        return "audd-java/" + SDK_VERSION + " jvm/" + javaVersion + " (" + os + ")";
    }
}
