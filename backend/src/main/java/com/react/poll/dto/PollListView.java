package com.react.poll.dto;

import java.time.Instant;
import java.util.List;

public record PollListView(List<PollView> polls, Instant serverTime) { }
