package com.react.poll.dto;

import com.react.poll.entity.PollType;

import java.util.List;

/** type 을 생략하면 GENERAL, durationSeconds 를 생략하면 기본 제한 시간이다. UNDERSTANDING 은 options 를 무시한다. */
public record CreatePollRequest(
        PollType type,
        String question,
        List<String> options,
        Integer durationSeconds
) { }
