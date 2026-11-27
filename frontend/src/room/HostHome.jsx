import React from 'react'
import { Link } from 'react-router-dom'
import { HomeRoomLists } from './HomeRoomLists.jsx'
import { Icon } from '../ui/Icon.jsx'
import './host-home.css'

const reviewFeatures = [
    {
        icon: 'message',
        title: '청중 질문 및 의견',
        description: 'AI가 정리한 주요 질문과 의견을 확인하고, 청중이 어떤 부분에 관심을 가졌는지 파악할 수 있습니다.',
    },
    {
        icon: 'chart',
        title: '투표 및 이해도 결과',
        description: '강연 중 진행한 투표와 이해도 체크 결과를 확인하고, 청중의 참여도와 이해 수준을 돌아볼 수 있습니다.',
    },
    {
        icon: 'file',
        title: 'AI 회고 보고서',
        description: '강연 내용과 청중의 반응을 종합한 AI 보고서를 확인하고, 다음 강연의 개선 방향을 찾아볼 수 있습니다.',
    },
]

const waveBars = [
    8, 12, 18, 28, 42, 60, 84, 62,
    38, 20, 12, 24, 48, 78, 108, 132,
    96, 64, 36, 18, 32, 58, 86, 118,
    92, 68, 42, 24, 14, 28, 52, 76,
    56, 34, 20, 12, 8, 14, 10, 6,
]

function StudioBanner() {
    return (
        <section
            className="hh-hero"
            aria-labelledby="hh-hero-title"
        >
            <svg
                className="hh-hero-wave"
                viewBox="0 0 400 160"
                aria-hidden="true"
                focusable="false"
            >
                {waveBars.map((height, index) => (
                    <rect
                        key={index}
                        x={index * 10 + 3}
                        y={(160 - height) / 2}
                        width="4"
                        height={height}
                        rx="2"
                        fill="currentColor"
                        opacity={
                            0.12 +
                            0.48 * Math.sin(
                                Math.PI * index / (waveBars.length - 1)
                            )
                        }
                    />
                ))}
            </svg>

            <div className="hh-hero-content">
                <span className="hh-eyebrow">
                    <Icon name="radio" size={15} />
                    LIVE LECTURE STUDIO
                </span>

                <h2 id="hh-hero-title">
                    강연을 열고,<br />
                    청중의 반응을 듣다.
                </h2>

                <p>
                    실시간 질문과 이해도를 확인하고<br />
                    강연이 끝나면 AI 회고 보고서를 만나보세요.
                </p>

                <Link
                    to="/host/rooms/new"
                    className="hh-create-button"
                >
                    <Icon name="plus" size={18} />
                    새 강연방 만들기
                    <Icon name="arrow-right" size={16} />
                </Link>
            </div>
        </section>
    )
}

function ReviewOverview() {
    return (
        <section
            className="hh-review"
            aria-labelledby="hh-review-title"
        >
            <header className="hh-review-heading">
                <span className="hh-section-icon">
                    <Icon name="file" size={24} />
                </span>

                <div>
                    <h2 id="hh-review-title">
                        강연 기록 및 회고 보고서
                    </h2>

                    <p>
                        지난 강연을 돌아보고 다음 강연을 준비하세요.
                    </p>
                </div>
            </header>

            <div className="hh-feature-grid">
                {reviewFeatures.map(feature => (
                    <article
                        className="hh-feature"
                        key={feature.title}
                    >
                        <span className="hh-feature-icon">
                            <Icon
                                name={feature.icon}
                                size={22}
                            />
                        </span>

                        <h3>{feature.title}</h3>

                        <p>{feature.description}</p>
                    </article>
                ))}
            </div>

            <footer className="hh-review-footer">
                <div className="hh-review-notice">
                    <Icon name="info" size={16} />

                    <p>
                        종료된 강연을 선택해 상세 기록을 확인할 수 있습니다.
                        AI 회고 보고서는 생성이 완료된 후 열람할 수 있습니다.
                    </p>
                </div>

                <Link
                    to="/host/rooms/history"
                    className="hh-history-button"
                >
                    내 강연 기록 보기
                    <Icon name="arrow-right" size={17} />
                </Link>
            </footer>
        </section>
    )
}

export function HostHome() {
    return (
        <div className="host-home-page hh-home">
            <header className="hh-heading">
                <h1>강연자 홈</h1>

                <p>
                    강연을 준비하고 진행 상황을 관리하세요.
                </p>
            </header>

            <div className="hh-room-lists">
                <HomeRoomLists
                    betweenSections={<StudioBanner />}
                />
            </div>

            <ReviewOverview />
        </div>
    )
}