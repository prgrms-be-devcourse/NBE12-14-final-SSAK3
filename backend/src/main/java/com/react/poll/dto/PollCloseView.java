package com.react.poll.dto;

import com.react.poll.entity.PollCloseReason;
import com.react.poll.entity.PollStatus;

import java.time.Instant;

public record PollCloseView(long pollId, PollStatus status, Instant closedAt, PollCloseReason closeReason) { }
