import { expect, test } from '@playwright/test'

import {
  closedCard,
  expandRound,
  historyPanel,
  liveCard,
  liveOption,
  livePanel,
  liveTotal,
  openResults,
  pollsTopic,
  results,
  resultsTopic,
  roomId,
  setupUnderstanding,
  understanding,
} from './understanding-fixture.js'

test('진행 중인 이해도 체크는 응답 이벤트에 따라 갱신된다', async ({
                                               page,
                                               context,
                                             }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(501, 'OPEN'),

        results: {
          501: results(501, 'OPEN', [0, 0, 0], 20),
        },
      },
  )

  await openResults(page)

  await expect(liveCard(page, 1)).toBeVisible()

  await expect(
      liveTotal(page, 1, '미응답'),
  ).toContainText('20명')

  await expect.poll(
      () => state.subscribed.has(resultsTopic),
  ).toBe(true)

  state.push[resultsTopic]({
    type: 'POLL_RESULTS_UPDATED',

    data: {
      pollId: 501,
      totalParticipants: 20,
      respondedParticipants: 12,
      unrespondedParticipants: 8,

      counts: {
        1: 6,
        2: 4,
        3: 2,
      },
    },
  })

  await expect(
      liveOption(page, 1, '이해됨'),
  ).toContainText('6명')

  await expect(
      liveOption(page, 1, '이해됨'),
  ).toContainText(
      '응답자의 50.0% · 전체의 30.0%',
  )

  await expect(
      liveOption(page, 1, '모르겠음'),
  ).toContainText('2명')

  await expect(
      liveTotal(page, 1, '응답'),
  ).toContainText('12명 (60.0%)')

  await expect(
      liveTotal(page, 1, '미응답'),
  ).toContainText('8명')
})

test('다른 투표 이벤트는 무시하고 마감된 결과를 다시 받는다', async ({
                                                page,
                                                context,
                                              }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(501, 'OPEN'),

        results: {
          501: results(501, 'OPEN', [1, 0, 0], 10),
        },
      },
  )

  await openResults(page)

  await expect(liveCard(page, 1)).toBeVisible()

  await expect.poll(
      () =>
          state.subscribed.has(pollsTopic) &&
          state.subscribed.has(resultsTopic),
  ).toBe(true)

  state.push[resultsTopic]({
    type: 'POLL_RESULTS_UPDATED',

    data: {
      pollId: 999,
      totalParticipants: 10,
      respondedParticipants: 9,
      unrespondedParticipants: 1,
      counts: { 1: 9 },
    },
  })

  await expect(
      liveOption(page, 1, '이해됨'),
  ).toContainText('1명')

  state.open = null

  state.closed = [
    understanding(501, 'CLOSED'),
  ]

  state.results[501] = results(
      501,
      'CLOSED',
      [5, 3, 1],
      12,
  )

  state.push[pollsTopic]({
    type: 'POLL_CLOSED',

    data: {
      pollId: 501,
      roomId,
      closeReason: 'TIMEOUT',
    },
  })

  await expect(livePanel(page)).toHaveCount(0)

  await expect(
      closedCard(page, 1),
  ).toContainText('9/12명 응답')

  const details = await expandRound(page, 1)

  await expect(
      details.locator('.understanding-rounds-understood'),
  ).toContainText('5명')

  await expect(
      details.getByText('미응답', { exact: true })
          .locator('xpath=..'),
  ).toContainText('3명')
})

test('진행 중인 회차와 마감 회차를 분리하고 마감 결과는 재조회하지 않는다', async ({
                                                        page,
                                                        context,
                                                      }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(503, 'OPEN'),

        closed: [
          understanding(501, 'CLOSED'),
          understanding(502, 'CLOSED'),
        ],

        results: {
          501: results(501, 'CLOSED', [2, 2, 2], 6),
          502: results(502, 'CLOSED', [4, 1, 0], 6),
          503: results(503, 'OPEN', [1, 0, 0], 6),
        },
      },
  )

  await openResults(page)

  await expect(
      historyPanel(page).getByText('마감된 2회'),
  ).toBeVisible()

  await expect(
      historyPanel(page).locator('.understanding-rounds-card'),
  ).toHaveCount(2)

  await expect(
      liveCard(page, 3),
  ).toBeVisible()

  const firstDetails = await expandRound(page, 1)
  const secondDetails = await expandRound(page, 2)

  await expect(
      firstDetails.locator('.understanding-rounds-confused'),
  ).toContainText('2명')

  await expect(
      secondDetails.locator('.understanding-rounds-understood'),
  ).toContainText('4명')

  await expect.poll(
      () => state.subscribed.has(resultsTopic),
  ).toBe(true)

  state.push[resultsTopic]({
    type: 'POLL_RESULTS_UPDATED',

    data: {
      pollId: 503,
      totalParticipants: 6,
      respondedParticipants: 3,
      unrespondedParticipants: 3,

      counts: {
        1: 2,
        2: 1,
        3: 0,
      },
    },
  })

  await expect(
      liveOption(page, 3, '이해됨'),
  ).toContainText('2명')

  await expect(
      secondDetails.locator('.understanding-rounds-understood'),
  ).toContainText('4명')

  expect(
      state.resultReads.filter(id => id === 501),
  ).toHaveLength(1)

  expect(
      state.resultReads.filter(id => id === 502),
  ).toHaveLength(1)
})

test('체크가 많아도 모든 회차를 확인하고 상세 결과를 열 수 있다', async ({
                                                   page,
                                                   context,
                                                 }) => {
  const ids = Array.from(
      { length: 12 },
      (_, index) => 600 + index,
  )

  const state = await setupUnderstanding(
      page,
      context,
      {
        closed: ids.map(id =>
            understanding(id, 'CLOSED'),
        ),

        results: Object.fromEntries(
            ids.map(id => [
              id,

              results(
                  id,
                  'CLOSED',
                  [id - 599, 0, 0],
                  20,
              ),
            ]),
        ),
      },
  )

  await page.setViewportSize({
    width: 1280,
    height: 900,
  })

  await openResults(page)

  await expect(
      historyPanel(page).getByText('마감된 12회'),
  ).toBeVisible()

  await expect(
      historyPanel(page).locator('.understanding-rounds-card'),
  ).toHaveCount(12)

  await closedCard(page, 12).scrollIntoViewIfNeeded()

  const latest = await expandRound(page, 12)

  await expect(
      latest.locator('.understanding-rounds-understood'),
  ).toContainText('12명')

  await closedCard(page, 1).scrollIntoViewIfNeeded()

  const first = await expandRound(page, 1)

  await expect(
      first.locator('.understanding-rounds-understood'),
  ).toContainText('1명')

  expect(
      [...state.resultReads].sort((a, b) => a - b),
  ).toEqual(ids)
})

test('이해도 체크가 없으면 결과 패널을 표시하지 않는다', async ({
                                             page,
                                             context,
                                           }) => {
  const state = await setupUnderstanding(
      page,
      context,
  )

  await openResults(page)

  await expect.poll(
      () => state.subscribed.has(resultsTopic),
  ).toBe(true)

  await expect(livePanel(page)).toHaveCount(0)
  await expect(historyPanel(page)).toHaveCount(0)

  expect(state.roundReads).toBe(0)
})

test('마감 이후 늦은 이벤트가 확정된 결과를 덮어쓰지 않는다', async ({
                                                page,
                                                context,
                                              }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(501, 'OPEN'),

        results: {
          501: results(501, 'OPEN', [1, 0, 0], 10),
        },
      },
  )

  await openResults(page)

  await expect(liveCard(page, 1)).toBeVisible()

  await expect.poll(
      () =>
          state.subscribed.has(pollsTopic) &&
          state.subscribed.has(resultsTopic),
  ).toBe(true)

  state.open = null

  state.closed = [
    understanding(501, 'CLOSED'),
  ]

  state.results[501] = results(
      501,
      'CLOSED',
      [5, 3, 1],
      12,
  )

  state.push[pollsTopic]({
    type: 'POLL_CLOSED',

    data: {
      pollId: 501,
      roomId,
      closeReason: 'HOST',
    },
  })

  await expect(
      closedCard(page, 1),
  ).toBeVisible()

  const details = await expandRound(page, 1)

  await expect(
      details.locator('.understanding-rounds-understood'),
  ).toContainText('5명')

  state.open = understanding(502, 'OPEN')

  state.results[502] = results(
      502,
      'OPEN',
      [0, 0, 0],
      12,
  )

  state.push[pollsTopic]({
    type: 'POLL_CREATED',
    data: state.open,
  })

  await expect(liveCard(page, 2)).toBeVisible()

  // 이전 회차에 늦게 도착한 응답
  state.push[resultsTopic]({
    type: 'POLL_RESULTS_UPDATED',

    data: {
      pollId: 501,
      totalParticipants: 12,
      respondedParticipants: 1,
      unrespondedParticipants: 11,
      counts: { 1: 1 },
    },
  })

  // 현재 회차의 새로운 응답
  state.push[resultsTopic]({
    type: 'POLL_RESULTS_UPDATED',

    data: {
      pollId: 502,
      totalParticipants: 12,
      respondedParticipants: 4,
      unrespondedParticipants: 8,
      counts: { 1: 4 },
    },
  })

  await expect(
      liveOption(page, 2, '이해됨'),
  ).toContainText('4명')

  await expect(
      details.locator('.understanding-rounds-understood'),
  ).toContainText('5명')

  await expect(
      details.getByText('미응답', { exact: true })
          .locator('xpath=..'),
  ).toContainText('3명')
})