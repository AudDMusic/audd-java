package io.audd.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lifecycle-event variant of a stream callback (e.g. "stream stopped",
 * "can't connect"). The outer callback envelope's {@code time} field is
 * lifted onto this object as {@link #time()}.
 */
public final class StreamCallbackNotification extends ForwardCompatible {
    @JsonProperty("radio_id") public Integer radioId;
    @JsonProperty("stream_running") public Boolean streamRunning;
    @JsonProperty("notification_code") public Integer notificationCode;
    @JsonProperty("notification_message") public String notificationMessage;

    @JsonIgnore private Long time;

    public Integer radioId() { return radioId; }
    public Boolean streamRunning() { return streamRunning; }
    public Integer notificationCode() { return notificationCode; }
    public String notificationMessage() { return notificationMessage; }
    /** Outer-envelope {@code time} field (unix seconds). May be {@code null}. */
    public Long time() { return time; }

    /** Internal: invoked by the parser. */
    public void setTime(Long time) { this.time = time; }
}
