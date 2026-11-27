import { expect } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

export const roomId = 10

const base = `/api/v1/rooms/${roomId}`

export const pollsTopic =
    `/topic/rooms/${roomId}/polls`

export const resultsTopic =
    `${pollsTopic}/results`

const options = [
    {
        optionId: 1,
        content: '이해됨',
        understandingStatus: 'UNDERSTOOD',
    },
    {
        optionId: 2,
        content: '헷갈림',
        understandingStatus: 'CONFUSED',
    },
    {
        optionId: 3,
        content: '모르겠음',
        understandingStatus: 'NOT_UNDERSTOOD',
    },
]

const rate = (part, whole) =>
    whole
        ? Math.round(part * 1000 / whole) / 10
        : 0

export function results(pollId, status, counts, total) {
    const responded = counts.reduce(
        (sum, count) => sum + count,
        0,
    )

    return {
        pollId,
        type: 'UNDERSTANDING',
        status,

        closesAt: '2026-11-21T03:01:00Z',

        totalParticipants: total,
        respondedParticipants: responded,
        unrespondedParticipants: total - responded,
        responseRate: rate(responded, total),

        options: options.map((option, index) => ({
            ...option,
            count: counts[index],

            percentageOfResponses: rate(
                counts[index],
                responded,
            ),

            percentageOfAllParticipants: rate(
                counts[index],
                total,
            ),
        })),

        calculatedAt: '2026-11-21T03:00:30Z',
    }
}

export function understanding(
    pollId,
    status,
    createdAt = '2026-11-21T03:00:00Z',
) {
    return {
        pollId,
        roomId,
        type: 'UNDERSTANDING',
        status,
        options,
        createdAt,

        closedAt: status === 'CLOSED'
            ? '2026-11-21T03:01:00Z'
            : null,
    }
}

function serverRound(poll, result) {
    const counts = Object.fromEntries(
        result.options.map(option => [
            option.understandingStatus,
            option.count,
        ]),
    )

    const lackCount =
        counts.CONFUSED + counts.NOT_UNDERSTOOD

    const lackRatio = rate(
        lackCount,
        result.respondedParticipants,
    )

    return {
        pollId: poll.pollId,
        openedAt: poll.createdAt,
        closedAt: poll.closedAt,

        totalParticipants: result.totalParticipants,
        respondedParticipants: result.respondedParticipants,

        counts,
        lackCount,
        lackRatio,

        lowUnderstanding:
            lackCount >= 3 &&
            lackRatio >= 40,
    }
}

export async function openResults(page) {
    await page.goto(`/host/rooms/${roomId}`)

    await page.getByRole('button', {
        name: '전체 결과 보기',
        exact: true,
    }).click()
}

export const historyPanel = page =>
    page.getByRole('region', {
        name: '회차별 이해도 결과',
        exact: true,
    })

export const livePanel = page =>
    page.getByRole('region', {
        name: '진행 중인 이해도 체크',
        exact: true,
    })

export const closedCard = (page, number) =>
    historyPanel(page).getByRole('listitem', {
        name: `${number}회차`,
        exact: true,
    })

export const liveCard = (page, number) =>
    livePanel(page).getByRole('listitem', {
        name: `이해도 체크 ${number}`,
        exact: true,
    })

export function liveOption(page, number, label) {
    return liveCard(page, number)
        .getByRole('listitem')
        .filter({ hasText: label })
}

export function liveTotal(page, number, label) {
    return liveCard(page, number)
        .getByText(label, { exact: true })
        .locator('xpath=..')
}

export async function expandRound(page, number) {
    const details = closedCard(
        page,
        number,
    ).locator('details')

    if (!(await details.evaluate(node => node.open))) {
        await details.locator('summary').click()
    }

    await expect(details).toHaveJSProperty(
        'open',
        true,
    )

    return details
}

export async function setupUnderstanding(
    page,
    context,
    {
        open = null,
        closed = [],
        results: initial = {},
    } = {},
) {
    await setupRecording(context)

    const state = {
        open,
        closed,
        results: { ...initial },

        roundReads: 0,
        resultReads: [],
        roundsFail: false,

        subscribed: new Set(),
        push: {},
        senders: {},
    }

    await context.routeWebSocket('**/ws', socket => {
        socket.onMessage(frame => {
            const message = frame.toString()

            if (message.startsWith('CONNECT\n')) {
                socket.send(
                    'CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0',
                )
            }

            const destination =
                /^destination:(.+)$/m.exec(message)?.[1]

            if (
                message.startsWith('SUBSCRIBE\n') &&
                destination
            ) {
                const id =
                    /^id:(.+)$/m.exec(message)[1]

                state.subscribed.add(destination)

                const senders =
                    (state.senders[destination] ??= [])

                senders.push(event =>
                    socket.send(
                        `MESSAGE\n` +
                        `destination:${destination}\n` +
                        `subscription:${id}\n` +
                        `message-id:${crypto.randomUUID()}\n\n` +
                        `${JSON.stringify(event)}\0`,
                    ),
                )

                state.push[destination] = event =>
                    senders.forEach(send => send(event))
            }
        })
    })

    await page.route('**/api/v1/**', route => {
        const url = new URL(route.request().url())

        const ok = data =>
            route.fulfill({ json: { data } })

        if (url.pathname === base) {
            return ok({
                roomId,
                title: '이해도 테스트 강연',
                description: '',
                status: 'LIVE',

                createdAt: '2026-11-21T00:00:00Z',
                startedAt: '2026-11-21T01:00:00Z',
                endedAt: null,
            })
        }

        if (url.pathname === `${base}/invitation`) {
            return ok({
                joinCode: 'WORKSHOP',
                joinUrl: 'http://127.0.0.1:5174/join/WORKSHOP',
                qrText: 'http://127.0.0.1:5174/join/WORKSHOP',
            })
        }

        if (url.pathname === `${base}/polls`) {
            const polls =
                url.searchParams.get('status') === 'OPEN'
                    ? state.open
                        ? [state.open]
                        : []
                    : state.closed

            return ok({
                polls,
                serverTime: '2026-11-21T03:00:00Z',
            })
        }

        if (
            url.pathname ===
            `${base}/polls/understanding-rounds`
        ) {
            state.roundReads++

            if (state.roundsFail) {
                return route.fulfill({
                    status: 500,
                    json: { code: 'INTERNAL_ERROR' },
                })
            }

            return ok({
                roomId,
                ratio: 40,
                minCount: 3,

                rounds: [...state.closed]
                    .sort((a, b) => a.pollId - b.pollId)
                    .map(poll =>
                        serverRound(
                            poll,
                            state.results[poll.pollId],
                        ),
                    ),
            })
        }

        const read =
            /\/polls\/(\d+)\/results$/.exec(url.pathname)

        if (read) {
            const id = Number(read[1])

            state.resultReads.push(id)

            return ok(state.results[id])
        }

        if (url.pathname === `${base}/messages`) {
            return ok({
                messages: [],
                nextCursor: null,
                hasNext: false,
            })
        }

        if (url.pathname === `${base}/ai/analyses`) {
            return ok({
                roomId,
                analyses: [],
            })
        }

        return route.fallback()
    })

    return state
}