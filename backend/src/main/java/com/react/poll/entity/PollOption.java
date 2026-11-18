package com.react.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "poll_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollOption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @Column(nullable = false)
    private String content;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    // type=UNDERSTANDING 투표의 선택지에만 값이 있다.
    @Enumerated(EnumType.STRING)
    @Column(name = "understanding_status", length = 30)
    private UnderstandingStatus understandingStatus;

    PollOption(Poll poll, String content, int displayOrder, UnderstandingStatus understandingStatus) {
        this.poll = poll;
        this.content = content;
        this.displayOrder = displayOrder;
        this.understandingStatus = understandingStatus;
    }
}
