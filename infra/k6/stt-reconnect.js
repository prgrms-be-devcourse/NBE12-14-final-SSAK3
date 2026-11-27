import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

if (!__ENV.BASE_URL) throw new Error('Set BASE_URL to the prepared backend API root.');
const api = __ENV.BASE_URL.replace(/\/$/, '');
const fixture = new SharedArray('STT reconnect fixture', () => {
    if (!__ENV.STT_FIXTURE) throw new Error('Set STT_FIXTURE to the prepared room fixture.');
    const { rooms, backlog, offlineSeconds } = JSON.parse(open(__ENV.STT_FIXTURE));
    if (!Array.isArray(rooms) || !rooms.length || !Number.isInteger(backlog) || backlog < 1 ||
        !Number.isFinite(offlineSeconds) || offlineSeconds <= 0) throw new Error('Invalid STT fixture dimensions.');
    for (const room of rooms) {
        if (!Number.isSafeInteger(room.roomId) || !Number.isSafeInteger(room.sessionId) ||
            !room.startKey || !Number.isFinite(Date.parse(room.serverTime)) || !Number.isFinite(room.clockOffsetMs) ||
            !room.email || !room.csrf?.headerName || !room.csrf.token || !Object.keys(room.cookies || {}).length) {
            throw new Error('STT fixture contains an incomplete recording session.');
        }
    }
    if (new Set(rooms.map(room => room.roomId)).size !== rooms.length) throw new Error('Duplicate fixture room.');
    return [{ backlog, offlineSeconds }, ...rooms];
});
const { backlog, offlineSeconds } = fixture[0];
const roomCount = fixture.length - 1;
const offlineMs = Math.round(offlineSeconds * 1000);
const liveSaveTime = new Trend('stt_live_save_ms', true);
const saveTime = new Trend('stt_segment_save_ms', true);
const drainTime = new Trend('stt_backlog_drain_ms', true);
const reconnectOffset = new Trend('stt_reconnect_offset_ms', true);
const created = new Counter('stt_segments_created');
const replays = new Counter('stt_backlog_replays');
const drained = new Counter('stt_rooms_drained');
const endVerified = new Counter('stt_end_race_verified');
const conflict = http.expectedStatuses(409);
const createdOrConflict = http.expectedStatuses(201, 409);

export const options = {
    setupTimeout: (roomCount * 10 + 30) + 's',
    scenarios: {
        stt_reconnect: {
            executor: 'per-vu-iterations', vus: roomCount, iterations: 1,
            maxDuration: Math.ceil(offlineSeconds + (backlog + 4) * 10 + 30) + 's',
        },
    },
    summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        checks: ['rate==1'], http_req_failed: ['rate==0'],
        stt_segments_created: ['count==' + roomCount * (backlog + 1)],
        stt_backlog_replays: ['count==' + roomCount],
        stt_rooms_drained: ['count==' + roomCount],
        stt_end_race_verified: ['count==1'],
    },
};

function assert(ok, name) {
    if (!check(Boolean(ok), { [name]: value => value })) throw new Error(name);
}

function client(room) {
    const jar = new http.CookieJar();
    for (const [name, value] of Object.entries(room.cookies)) jar.set(api, name, value, { path: '/' });
    const headers = { 'Content-Type': 'application/json', [room.csrf.headerName]: room.csrf.token };
    const roomUrl = api + '/rooms/' + room.roomId;
    const sessions = roomUrl + '/stt/sessions';
    return {
        roomUrl, sessions, session: sessions + '/' + room.sessionId,
        params: name => ({ jar, headers, timeout: '10s', tags: { name } }),
    };
}

function segment(room, key, start, end) {
    return { startKey: room.startKey, segmentKey: key, transcript: 'room ' + room.roomId + ' / ' + key,
        segmentStartAt: new Date(start).toISOString(), segmentEndAt: new Date(end).toISOString() };
}

function baseline(room) {
    return segment(room, 'live-1', room.serverTime, room.serverTime);
}

function ack(response, status, room, body, original) {
    const row = response.status === status && response.json('data');
    assert(row && Number.isSafeInteger(row.segmentId) && row.segmentId > 0 &&
        Number.isFinite(Date.parse(row.receivedAt)) &&
        (!original || (row.segmentId === original.segmentId && Date.parse(row.receivedAt) === Date.parse(original.receivedAt))) &&
        row.sttSessionId === room.sessionId && row.segmentKey === body.segmentKey && row.transcript === body.transcript &&
        Date.parse(row.segmentStartAt) === Date.parse(body.segmentStartAt) &&
        Date.parse(row.segmentEndAt) === Date.parse(body.segmentEndAt), 'segment acknowledgement matches original');
    return row;
}

export function setup() {
    let firstBaseline;
    for (let index = 1; index < fixture.length; index++) {
        const room = fixture[index];
        const c = client(room);
        const live = baseline(room);
        const response = http.post(c.session + '/segments', JSON.stringify(live), c.params('stt_live_write'));
        liveSaveTime.add(response.timings.duration);
        const row = ack(response, 201, room, live);
        if (index === 1) firstBaseline = row;
        created.add(1);
    }
    return firstBaseline;
}

export default function () {
    const room = fixture[exec.scenario.iterationInTest + 1];
    const c = client(room);
    const save = (body, name) => http.post(c.session + '/segments', JSON.stringify(body), c.params(name));

    // Keep the original capture times while every host waits for the same reconnect instant.
    const anchor = Math.max(Date.parse(room.serverTime), exec.scenario.startTime + Math.round(room.clockOffsetMs));
    const queue = Array.from({ length: backlog }, (_, i) => segment(room, 'backlog-' + (i + 1),
        anchor + Math.floor(i * offlineMs / backlog), anchor + Math.floor((i + 1) * offlineMs / backlog)));
    const resumeAt = exec.scenario.startTime + offlineMs;
    sleep(Math.max(0, resumeAt - Date.now()) / 1000);
    const begin = Date.now();
    reconnectOffset.add(begin - resumeAt);
    const me = http.get(api + '/auth/me', c.params('stt_verify_host'));
    assert(me.status === 200 && me.json('data.email') === room.email, 'reconnected host identity matches');
    for (let i = 0; i < queue.length; i++) {
        const body = queue[i];
        const response = save(body, 'stt_backlog_write');
        saveTime.add(response.timings.duration);
        const row = ack(response, 201, room, body);
        created.add(1);
        if (i === 0) {
            // Model a lost first acknowledgement while keeping this sender's uploads serial.
            ack(save(body, 'stt_backlog_replay'), 200, room, body, row);
            replays.add(1);
        }
    }
    drainTime.add(Date.now() - begin);
    drained.add(1);
}

function records(c, room) {
    const response = http.get(c.roomUrl + '/stt/segments', c.params('stt_records'));
    const data = response.status === 200 && response.json('data');
    assert(data && data.roomId === room.roomId && Array.isArray(data.segments) && Array.isArray(data.gaps),
        'room records are readable');
    return data;
}

export function teardown(firstBaseline) {
    const room = fixture[1];
    const c = client(room);
    const live = baseline(room);

    // After timing the normal flush, race a final write against room closure in just this room.
    const probe = http.post(c.sessions, JSON.stringify({ startKey: room.startKey }), c.params('stt_session_probe'));
    assert(probe.status === 200 && probe.json('data.sttSessionId') === room.sessionId && probe.json('data.canRecord'),
        'existing recording session is active');
    const gapBody = { startKey: room.startKey, eventKey: 'end-gap', reason: 'ERROR', startedAt: probe.json('data.serverTime') };
    const openGap = http.post(c.session + '/gaps', JSON.stringify(gapBody), c.params('stt_recognition_gap'));
    const gap = openGap.status === 201 && openGap.json('data');
    assert(gap && gap.sttSessionId === room.sessionId && gap.eventKey === gapBody.eventKey && gap.reason === 'ERROR' &&
        gap.endedAt === null && Date.parse(gap.startedAt) === Math.floor(Date.parse(gapBody.startedAt) / 1000) * 1000,
        'recognition gap is open');
    const race = segment(room, 'end-race', gap.startedAt, gap.startedAt);
    const url = c.session + '/segments';
    const [write, end] = http.batch([
        ['POST', url, JSON.stringify(race), { ...c.params('stt_end_race_write'), responseCallback: createdOrConflict }],
        ['POST', c.roomUrl + '/end', null, c.params('stt_room_end')],
    ]);
    assert(end.status === 200 && end.json('data.status') === 'ENDED' && end.json('data.roomId') === room.roomId,
        'room ended during race');
    const accepted = write.status === 201;
    if (accepted) ack(write, 201, room, race);
    else assert(write.status === 409 && write.json('code') === 'CONFLICT', 'racing write refused after closure');

    ack(http.post(url, JSON.stringify(live), c.params('stt_replay_after_end')), 200, room, live, firstBaseline);
    const late = segment(room, 'after-end', gap.startedAt, gap.startedAt);
    const rejected = http.post(url, JSON.stringify(late), { ...c.params('stt_write_after_end'), responseCallback: conflict });
    assert(rejected.status === 409 && rejected.json('code') === 'CONFLICT', 'new write after end is rejected');
    const after = records(c, room);
    const raceRows = after.segments.filter(row => row.sessionId === room.sessionId && row.transcript === race.transcript);
    assert(accepted ? raceRows.length === 1 && raceRows[0].segmentId === write.json('data.segmentId') : raceRows.length === 0,
        'race outcome matches stored segment');
    assert(!after.segments.some(row => row.transcript === late.transcript), 'rejected late segment is absent');
    const closed = after.gaps.find(row => row.gapId === gap.gapId);
    assert(closed && closed.sessionId === room.sessionId && closed.endedAt &&
        Date.parse(closed.endedAt) === Date.parse(end.json('data.endedAt')), 'room closure ends the recognition gap');
    endVerified.add(1);
}
