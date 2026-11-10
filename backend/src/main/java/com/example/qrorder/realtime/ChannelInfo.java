package com.example.qrorder.realtime;

import java.util.List;

/** One channel a client may listen to, and the REST calls that give the current state to show on it. */
public record ChannelInfo(String name, String topic, List<String> reload) {
}