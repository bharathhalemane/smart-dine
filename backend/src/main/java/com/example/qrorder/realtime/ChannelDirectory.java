package com.example.qrorder.realtime;

import java.util.List;

public record ChannelDirectory(String serverTime, List<ChannelInfo> channels) {
}