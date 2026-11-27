import { expect, test } from '@playwright/test'

import {
  closedCard,
  expandRound,
  historyPanel,
  livePanel,
  openResults,
  pollsTopic,
  results,
  roomId,
  setupUnderstanding,
  understanding,
} from './understanding-fixture.js'

test('마감된 체크가 없으면 회차별 결과를 표시하지 않는다', async ({
                                              page,
                                              context,
                                            }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(501, 'OPEN'),

        results: {
          501: results(501, 'OPEN', [0, 0, 0], 12),
        },
      },
  )

  await openResults(page)

  await expect(livePanel(page)).toBeVisible()

  await expect(historyPanel(page)).toHaveCount(0)

  expect(state.roundReads).toBe(0)
})

test('마감된 회차는 누적 막대와 펼쳐보기로 표시한다', async ({
                                           page,
                                           context,
                                         }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        closed: [
          understanding(
              502,
              'CLOSED',
              '2026-11-21T03:15:00Z',
          ),
          understanding(501, 'CLOSED'),
        ],

        results: {
          501: results(501, 'CLOSED', [8, 2, 1], 12),
          502: results(502, 'CLOSED', [3, 4, 3], 12),
        },
      },
  )

  await openResults(page)

  await expect(historyPanel(page)).toBeVisible()

  await expect(
      historyPanel(page).getByText('마감된 2회'),
  ).toBeVisible()

  const first = closedCard(page, 1)
  const second = closedCard(page, 2)

  await expect(first).toContainText('11/12명 응답')
  await expect(second).toContainText('10/12명 응답')
  await expect(second).toContainText('12:15')

  await expect(
      first.locator('.understanding-rounds-segment'),
  ).toHaveCount(3)

  await expect(
      second.locator('.understanding-rounds-segment'),
  ).toHaveCount(3)

  await expect(
      first.locator('details'),
  ).toHaveJSProperty('open', false)

  const firstDetails = await expandRound(page, 1)

  await expect(firstDetails).toContainText('27.3%')

  await expect(
      first.getByText('이해 부족', { exact: true }),
  ).toHaveCount(0)

  await expect(
      second.getByText('이해 부족', { exact: true }),
  ).toBeVisible()

  const secondDetails = await expandRound(page, 2)

  await expect(secondDetails).toContainText('70.0%')

  // 마감된 결과가 별도 요약 패널에 중복되지 않는다.
  await expect(livePanel(page)).toHaveCount(0)

  expect(state.roundReads).toBe(1)
})

test('응답자가 없는 회차는 빈 막대와 응답 없음으로 표시한다', async ({
                                                page,
                                                context,
                                              }) => {
  await setupUnderstanding(
      page,
      context,
      {
        closed: [
          understanding(501, 'CLOSED'),
        ],

        results: {
          501: results(501, 'CLOSED', [0, 0, 0], 12),
        },
      },
  )

  await openResults(page)

  const card = closedCard(page, 1)

  await expect(card).toContainText('0/12명 응답')

  await expect(
      card.getByText('응답 없음', { exact: true }),
  ).toBeVisible()

  await expect(
      card.locator('.understanding-rounds-segment'),
  ).toHaveCount(0)

  const details = await expandRound(page, 1)

  await expect(details).toContainText('12명')

  await expect(
      details.locator('.understanding-rounds-value strong'),
  ).toHaveText(['—', '—', '—'])
})

test('새 체크가 마감되면 회차별 결과 목록에 추가한다', async ({
                                            page,
                                            context,
                                          }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        open: understanding(502, 'OPEN'),

        closed: [
          understanding(501, 'CLOSED'),
        ],

        results: {
          501: results(501, 'CLOSED', [8, 2, 1], 12),
          502: results(502, 'OPEN', [3, 4, 3], 12),
        },
      },
  )

  await openResults(page)

  await expect(
      historyPanel(page).getByText('마감된 1회'),
  ).toBeVisible()

  await expect(livePanel(page)).toBeVisible()

  await expect.poll(
      () => state.roundReads,
  ).toBe(1)

  await expect.poll(
      () => state.subscribed.has(pollsTopic),
  ).toBe(true)

  state.open = null

  state.closed = [
    understanding(502, 'CLOSED'),
    understanding(501, 'CLOSED'),
  ]

  state.results[502] = results(
      502,
      'CLOSED',
      [3, 4, 3],
      12,
  )

  state.push[pollsTopic]({
    type: 'POLL_CLOSED',

    data: {
      pollId: 502,
      roomId,
      closeReason: 'HOST',
    },
  })

  await expect(
      historyPanel(page).getByText('마감된 2회'),
  ).toBeVisible()

  await expect(
      closedCard(page, 2).getByText(
          '이해 부족',
          { exact: true },
      ),
  ).toBeVisible()

  await expect(livePanel(page)).toHaveCount(0)

  expect(state.roundReads).toBe(2)
})

test('라운드 조회 실패 시 응답 결과를 유지하고 재시도할 수 있다', async ({
                                                   page,
                                                   context,
                                                 }) => {
  const state = await setupUnderstanding(
      page,
      context,
      {
        closed: [
          understanding(501, 'CLOSED'),
        ],

        results: {
          501: results(501, 'CLOSED', [3, 4, 3], 12),
        },
      },
  )

  state.roundsFail = true

  await openResults(page)

  await expect(
      historyPanel(page).getByRole('alert'),
  ).toContainText(
      '최신 이해 부족 판정 정보를 불러오지 못했습니다.',
  )

  const card = closedCard(page, 1)

  await expect(card).toContainText('10/12명 응답')

  await expect(
      card.getByText('이해 부족', { exact: true }),
  ).toHaveCount(0)

  const details = await expandRound(page, 1)

  await expect(details).toContainText('70.0%')

  await expect(details).toContainText(
      '이해 부족 판정을 확인하지 못했습니다.',
  )

  state.roundsFail = false

  await historyPanel(page).getByRole('button', {
    name: '다시 시도',
  }).click()

  await expect(
      card.getByText('이해 부족', { exact: true }),
  ).toBeVisible()

  await expect(
      historyPanel(page).getByRole('alert'),
  ).toHaveCount(0)
})