import { expect, test } from '@playwright/test'
import { setupHostRoom } from './host-room-fixture.js'

const joinCode = 'AB7KQ9M4D2ZX'
const invitation = `/join/${joinCode}`
const roomId = 42

const participant = {
    participantId: 7,
    roomId,
    nickname: '테스트 청중',
    joinedAt: '2026-11-21T01:00:00Z',
}

test.describe('강연방 운영 제한', () => {
    const startLimits = [
        {
            code: 'LIVE_ROOM_LIMIT_EXCEEDED',
            message: '현재 동시에 진행할 수 있는 강연방 수에 도달했습니다. 다른 강연이 종료된 후 다시 시도해 주세요.',
        },
        {
            code: 'HOST_LIVE_ROOM_LIMIT_EXCEEDED',
            message: '이미 진행 중인 강연이 있습니다. 기존 강연을 종료한 후 새로운 강연을 시작해 주세요.',
        },
    ]

    for (const limit of startLimits) {
        test(`${limit.code} 오류가 발생하면 강연 시작 제한 안내를 표시한다`, async ({ page, context }) => {
            const { room } = await setupHostRoom(context, 'READY')

            let startRequests = 0

            await context.route('**/api/v1/rooms/10/start', async route => {
                startRequests++

                if (startRequests === 1) {
                    return route.fulfill({
                        status: 409,
                        json: {
                            code: limit.code,
                            message: limit.message,
                        },
                    })
                }

                return route.fallback()
            })

            await page.goto('/host/rooms/10')

            const startButton = page.getByRole('button', {
                name: '강연 시작',
                exact: true,
            })

            await expect(startButton).toBeVisible()

            await startButton.click()

            await expect(page.getByText(limit.message, {
                exact: true,
            })).toBeVisible()

            await expect(startButton).toBeEnabled()
            expect(room.status).toBe('READY')
            expect(startRequests).toBe(1)

            await startButton.click()

            await expect.poll(() => room.status).toBe('LIVE')

            await expect(page.getByText(limit.message, {
                exact: true,
            })).toHaveCount(0)

            expect(startRequests).toBe(2)
        })
    }

    test('청중 정원 초과 시 입장 실패 이유를 표시한다', async ({ page }) => {
        const state = await setupAudience(page)

        await page.goto(invitation)

        const nickname = page.getByLabel('닉네임')
        const joinButton = page.getByRole('button', {
            name: '입장',
            exact: true,
        })

        await nickname.fill(participant.nickname)
        await joinButton.click()

        await expect(page.getByText(
            '강연방 정원이 가득 찼습니다. 현재 새로운 청중은 입장할 수 없습니다.',
            { exact: true },
        )).toBeVisible()

        await expect(joinButton).toBeEnabled()
        await expect(nickname).toBeEditable()
        await expect(nickname).toHaveValue(participant.nickname)

        await expect(page.getByText(
            '이전 입장 결과를 확인하지 못했습니다.',
        )).toHaveCount(0)

        expect(state.posts).toHaveLength(1)

        const savedAttempt = await page.evaluate(code => {
            return sessionStorage.getItem(`react:audience:join:${code}`)
        }, joinCode)

        expect(savedAttempt).toBeNull()
    })

    test('정원이 가득 차도 기존 청중은 신규 입장 없이 복귀한다', async ({ page }) => {
        const state = await setupAudience(page)

        state.hasSession = true

        await page.goto(invitation)

        await expect(page.getByText(
            `${participant.nickname}님으로 입장했습니다.`,
        )).toBeVisible()

        await expect(page.getByLabel('닉네임')).toHaveCount(0)

        expect(state.posts).toHaveLength(0)
    })
})

async function setupAudience(page) {
    const state = {
        status: 'READY',
        hasSession: false,
        full: true,
        posts: [],
    }

    await page.route('**/api/v1/**', async route => {
        const request = route.request()
        const path = new URL(request.url()).pathname

        const ok = (data, status = 200) => route.fulfill({
            status,
            json: { data },
        })

        const fail = (status, code) => route.fulfill({
            status,
            json: { code },
        })

        if (path === '/api/v1/auth/csrf') {
            return ok({
                headerName: 'X-CSRF-TOKEN',
                token: 'public-token',
            })
        }

        if (path === `/api/v1/public/rooms/${joinCode}`) {
            return ok({
                roomId,
                title: '운영 제한 테스트 강연',
                status: state.status,
                canJoin: state.status !== 'ENDED',
            })
        }

        if (path === `/api/v1/rooms/${roomId}/participants/me`) {
            return state.hasSession
                ? ok(participant)
                : fail(401, 'UNAUTHENTICATED')
        }

        if (
            path === `/api/v1/public/rooms/${joinCode}/participants`
            && request.method() === 'POST'
        ) {
            expect(request.headers()['x-csrf-token']).toBe('public-token')

            state.posts.push({
                key: request.headers()['idempotency-key'],
                ...request.postDataJSON(),
            })

            if (state.full) {
                return fail(409, 'ROOM_PARTICIPANT_LIMIT_EXCEEDED')
            }

            state.hasSession = true

            return ok({
                ...participant,
                roomStatus: state.status,
            }, 201)
        }

        return fail(404, 'ROOM_NOT_FOUND')
    })

    await page.routeWebSocket('**/ws', socket => {
        socket.onMessage(frame => {
            if (frame.toString().startsWith('CONNECT\n')) {
                socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
            }
        })
    })

    return state
}