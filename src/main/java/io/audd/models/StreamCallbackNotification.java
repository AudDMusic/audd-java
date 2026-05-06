package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class StreamCallbackNotification extends ForwardCompatible {
    @JsonProperty("radio_id") public Integer radioId;
    @JsonProperty("stream_running") public Boolean streamRunning;
    @JsonProperty("notification_code") public Integer notificationCode;
    @JsonProperty("notification_message") public String notificationMessage;

    public Integer radioId() { return radioId; }
    public Boolean streamRunning() { return streamRunning; }
    public Integer notificationCode() { return notificationCode; }
    public String notificationMessage() { return notificationMessage; }
}
