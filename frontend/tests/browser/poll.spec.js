
import { expect, test } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const pollId = 501

function makePoll(roomId, question, choices, durationSeconds = 120) {
    const createdAt = new Date()
    const closesAt = new Date(
        createdAt.getTime() + durationSeconds * 1000,
    )

    return {
        pollId,
        roomId,
        type: 'GENERAL',
        question,
        status: 'OPEN',
        options: choices.map((content, index) => ({
            optionId: index + 1,
            content,
            displayOrder: index + 1,
            understandingStatus: null,
        })),
        durationSeconds,
        createdAt: createdAt.toISOString(),
        closesAt: closesAt.toISOString(),
        serverTime: createdAt.toISOString(),
    }
}

async function mockSocket(context) {
    await context.routeWebSocket('**/ws', socket => {
        socket.onMessage(frame => {
            if (frame.toString().startsWith('CONNECT\n')) {
                socket.send(
                    'CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0',
                )
            }
        })
    })
}

test('강연자는 투표를 생성하고 새로고침 후 복원하고 마감할 수 있다', async ({ page, context }) => {
    const recording = await setupRecording(context)
    await mockSocket(context)

    const state = {
        poll: null,
        listReads: 0,
        creates: [],
        closes: [],
    }

    await page.route('**/api/v1/**', route => {
        const req = route.request()
        const url = new URL(req.url())
        const path = url.pathname
        const method = req.method()
        const base = '/api/v1/rooms/10'

        const ok = (data, status = 200) =>
            route.fulfill({
                status,
                json: { data },
            })

        // 강연방 정보 조회
        if (path === base && method === 'GET') {
            return ok({
                roomId: 10,
                title: '투표 테스트 강연',
                description: '',
                status: 'LIVE',
                createdAt: '2026-11-21T00:00:00Z',
                startedAt: '2026-11-21T01:00:00Z',
                endedAt: null,
            })
        }

        // 청중 초대 정보 조회
        // 초대 창이 닫혀 있어도 RoomInvitation이 이 API를 호출한다.
        // 모킹하지 않으면 404로 인해 강연방 화면 전체가 사라진다.
        if (path === `${base}/invitation` && method === 'GET') {
            return ok({
                joinCode: 'WORKSHOP',
                joinUrl: 'http://127.0.0.1:5174/join/WORKSHOP',
                qrText: 'http://127.0.0.1:5174/join/WORKSHOP',
            })
        }

        // 진행 중인 투표 목록
        if (path === `${base}/polls` && method === 'GET') {
            expect(url.searchParams.get('status')).toBe('OPEN')

            state.listReads++

            return ok({
                polls: state.poll ? [state.poll] : [],
                serverTime: new Date().toISOString(),
            })
        }

        // 투표 생성
        if (path === `${base}/polls` && method === 'POST') {
            expect(req.headers()['x-csrf-token']).toBe(
                recording.csrfToken,
            )

            const body = req.postDataJSON()
            state.creates.push(body)

            if (state.poll) {
                return route.fulfill({
                    status: 409,
                    json: { code: 'POLL_ALREADY_OPEN' },
                })
            }

            state.poll = makePoll(
                10,
                body.question,
                body.options,
                body.durationSeconds,
            )

            return ok(state.poll, 201)
        }

        // 투표 마감
        if (
            path === `${base}/polls/${pollId}/close` &&
            method === 'POST'
        ) {
            expect(req.headers()['x-csrf-token']).toBe(
                recording.csrfToken,
            )

            state.closes.push(pollId)
            state.poll = null

            return ok({
                pollId,
                status: 'CLOSED',
                closedAt: new Date().toISOString(),
                closeReason: 'HOST',
            })
        }

        // 강연 채팅 내역
        if (path === `${base}/messages` && method === 'GET') {
            return ok({
                messages: [],
                nextCursor: null,
                hasNext: false,
            })
        }

        // AI 요약 목록
        if (path === `${base}/ai/analyses` && method === 'GET') {
            return ok({
                roomId: 10,
                analyses: [],
            })
        }

        // 인증, CSRF, STT 요청은 기존 fixture에 맡긴다.
        return route.fallback()
    })

    await page.goto('/host/rooms/10')

    // 강연방이 정상 표시되는지 확인
    await expect(
        page.getByRole('heading', {
            name: '투표 테스트 강연',
        }),
    ).toBeVisible()

    const panel = page.locator('.room-poll')

    await expect(
        panel.getByRole('heading', {
            name: '일반 투표',
        }),
    ).toBeVisible()

    // 서버의 투표 목록 조회가 호출되었는지 확인
    await expect.poll(
        () => state.listReads,
    ).toBeGreaterThan(0)

    // 진행 중인 투표가 없으면 생성 폼 표시
    await expect(
        panel.getByRole('button', {
            name: '투표 생성',
            exact: true,
        }),
    ).toBeVisible()

    // 투표 질문과 선택지 입력
    await panel.getByLabel('투표 질문').fill(
        'Redis를 사용하는 이유는?',
    )

    await panel.getByLabel('선택지 1').fill(
        '조회 성능 개선',
    )

    await panel.getByLabel('선택지 2').fill(
        '화면 렌더링',
    )

    await panel.getByLabel('제한 시간 (초)').fill('120')

    // 투표 생성
    await panel.getByRole('button', {
        name: '투표 생성',
        exact: true,
    }).click()

    await expect(
        panel.getByText('진행 중인 투표가 있습니다.'),
    ).toBeVisible()

    expect(state.creates).toHaveLength(1)

    expect(state.creates[0]).toEqual({
        type: 'GENERAL',
        question: 'Redis를 사용하는 이유는?',
        options: ['조회 성능 개선', '화면 렌더링'],
        durationSeconds: 120,
    })

    // 새로고침 후 기존 투표 복원
    await page.reload()

    await expect(
        panel.getByText('진행 중인 투표가 있습니다.'),
    ).toBeVisible()

    await expect(
        panel.getByText('Redis를 사용하는 이유는?'),
    ).toBeVisible()

    // 중복 생성되지 않았는지 검증
    expect(state.creates).toHaveLength(1)

    // 강연자가 투표 마감
    await panel.getByRole('button', {
        name: '투표 마감',
    }).click()

    // 마감 후 새 투표를 생성할 수 있는지 확인
    await expect(
        panel.getByRole('button', {
            name: '투표 생성',
            exact: true,
        }),
    ).toBeVisible()

    expect(state.closes).toEqual([pollId])
})

test('청중은 응답 제출, 선택 변경, 새로고침 복원, 마감을 확인한다', async ({ page, context }) => {
    await mockSocket(context)

    const state = {
        poll: makePoll(
            42,
            '가장 중요한 백엔드 기술은?',
            ['Java', 'Spring Boot', 'SQL'],
        ),
        savedOption: null,
        responses: [],
    }

    await page.route('**/api/v1/**', route => {
        const req = route.request()
        const url = new URL(req.url())
        const path = url.pathname
        const method = req.method()
        const base = '/api/v1/rooms/42'

        const ok = (data, status = 200) =>
            route.fulfill({
                status,
                json: { data },
            })

        // CSRF 토큰
        if (path === '/api/v1/auth/csrf') {
            return ok({
                headerName: 'X-CSRF-TOKEN',
                token: 'test-csrf-token',
            })
        }

        // 강연방 공개 정보
        if (path === '/api/v1/public/rooms/WORKSHOP') {
            return ok({
                roomId: 42,
                title: '백엔드 테스트 강연',
                status: 'LIVE',
                canJoin: true,
            })
        }

        // 청중 세션
        if (path === `${base}/participants/me`) {
            return ok({
                roomId: 42,
                participantId: 7,
                nickname: '테스트 청중',
                joinedAt: '2026-11-21T01:00:00Z',
            })
        }

        // 채팅 내역
        if (path === `${base}/messages` && method === 'GET') {
            return ok({
                messages: [],
                nextCursor: null,
                hasNext: false,
            })
        }

        // 진행 중인 투표 조회
        if (path === `${base}/polls` && method === 'GET') {
            expect(url.searchParams.get('status')).toBe('OPEN')

            return ok({
                polls: state.poll ? [state.poll] : [],
                serverTime: new Date().toISOString(),
            })
        }

        // 청중의 투표 응답 조회 및 저장
        if (path === `${base}/polls/${pollId}/responses/me`) {
            if (method === 'GET') {
                return ok({
                    pollId,
                    participantId: 7,
                    optionId: state.savedOption,
                    respondedAt: state.savedOption === null
                        ? null
                        : new Date().toISOString(),
                })
            }

            if (method === 'PUT') {
                expect(req.headers()['x-csrf-token']).toBe(
                    'test-csrf-token',
                )

                const { optionId } = req.postDataJSON()

                state.savedOption = optionId
                state.responses.push(optionId)

                return ok({
                    pollId,
                    participantId: 7,
                    optionId,
                    changed: true,
                    respondedAt: new Date().toISOString(),
                    closesAt: state.poll.closesAt,
                })
            }
        }

        throw new Error(
            `예상하지 않은 API: ${method} ${path}`,
        )
    })

    await page.goto('/join/WORKSHOP')

    const panel = page.locator('.participation-poll')

    await expect(
        panel.getByRole('heading', {
            name: '실시간 투표',
        }),
    ).toBeVisible()

    await expect(
        panel.getByText('가장 중요한 백엔드 기술은?'),
    ).toBeVisible()

    const first = panel.getByRole('radio', {
        name: 'Java',
    })

    const second = panel.getByRole('radio', {
        name: 'Spring Boot',
    })

    // 첫 번째 선택지 응답
    await first.check()

    await panel.getByRole('button', {
        name: '응답 제출',
    }).click()

    await expect(
        panel.getByText('선택이 저장되었습니다.', {
            exact: false,
        }),
    ).toBeVisible()

    expect(state.responses).toEqual([1])

    // 다른 선택지로 변경
    await second.check()

    await panel.getByRole('button', {
        name: '선택 변경 저장',
    }).click()

    await expect.poll(
        () => state.responses.length,
    ).toBe(2)

    expect(state.responses).toEqual([1, 2])

    // 새로고침 후 마지막 선택 복원
    await page.reload()

    await expect(
        panel.getByRole('radio', {
            name: 'Spring Boot',
        }),
    ).toBeChecked()

    await expect(
        panel.getByRole('button', {
            name: '선택 변경 저장',
        }),
    ).toBeDisabled()

    // 서버에서 투표가 마감된 상황
    state.poll = null

    await panel.getByRole('button', {
        name: '투표 상태 다시 확인',
    }).click()

    await expect(
        panel.getByText('현재 진행 중인 투표가 없습니다.'),
    ).toBeVisible()

    expect(state.responses).toEqual([1, 2])
    expect(state.savedOption).toBe(2)
})
