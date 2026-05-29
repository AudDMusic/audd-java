package io.audd.models;

import java.util.Optional;

/**
 * The result of parsing a stream callback POST body. Exactly one of
 * {@link #match()} or {@link #notification()} is present.
 *
 * <p>Idiomatic usage:</p>
 *
 * <pre>{@code
 * CallbackEvent event = Streams.parseCallback(requestBody);
 * event.match().ifPresent(m -> log("matched %s — %s", m.song().artist(), m.song().title()));
 * event.notification().ifPresent(n -> log("notif %d: %s", n.notificationCode(), n.notificationMessage()));
 * }</pre>
 */
public final class CallbackEvent {
    private final StreamCallbackMatch match;
    private final StreamCallbackNotification notification;

    private CallbackEvent(StreamCallbackMatch match, StreamCallbackNotification notification) {
        this.match = match;
        this.notification = notification;
    }

    public static CallbackEvent ofMatch(StreamCallbackMatch match) {
        if (match == null) throw new IllegalArgumentException("match must be non-null");
        return new CallbackEvent(match, null);
    }

    public static CallbackEvent ofNotification(StreamCallbackNotification notification) {
        if (notification == null) throw new IllegalArgumentException("notification must be non-null");
        return new CallbackEvent(null, notification);
    }

    public Optional<StreamCallbackMatch> match() { return Optional.ofNullable(match); }
    public Optional<StreamCallbackNotification> notification() { return Optional.ofNullable(notification); }

    public boolean isMatch() { return match != null; }
    public boolean isNotification() { return notification != null; }
}
