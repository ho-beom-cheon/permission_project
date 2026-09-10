import {el, api, run, initialize, allowed, feedback, cell, button, paging} from './work-ui.js';

let page = 0;
const statuses = {PENDING:'신청', APPROVED:'승인·부여', REJECTED:'반려', REVOKED:'회수'};
const kinds = {GRANT:'권한 추가', CHANGE:'권한 변경'};
const sources = {JOURNAL:'처리 기록', REQUEST:'이전 신청', ASSIGNMENT:'권한 기록'};
const timestamp = value => value ? new Date(value).toLocaleString('ko-KR', {timeZone:'Asia/Seoul'}) : '확인 불가';
const named = (name, id) => name && name !== id ? `${name} (${id})` : id || '확인 불가';

async function correctReason(event) {
    const reason = window.prompt('정정할 사유를 입력해 주세요.', event.reason || '');
    if (reason == null) return;
    if (!reason.trim()) throw new Error('사유를 입력해 주세요.');
    await api('/api/admin/authority-history/reason', 'PATCH', {
        username: event.username,
        authorityId: event.authorityId,
        ssoOfficeCode: event.ssoOfficeCode,
        ssoDepartmentCode: event.ssoDepartmentCode,
        reason: reason.trim()
    });
    await load();
    feedback('반려·회수 사유를 정정했습니다.');
}

async function load() {
    const admin = el('scope').value === 'all';
    const query = new URLSearchParams({page, size:20});
    if (el('status').value) query.set('status', el('status').value);
    if (admin && el('username').value.trim()) query.set('username', el('username').value.trim());
    const result = await api(`/api/${admin ? 'admin' : 'me'}/authority-history/full?${query}`);
    el('rows').replaceChildren();
    for (const event of result.content) {
        const row = document.createElement('tr');
        cell(row, `${sources[event.source]}${event.eventId ? ' ' + event.eventId : ''}\n${event.requestId ? '신청 ' + event.requestId : event.assignmentSequence != null ? '권한 순번 ' + event.assignmentSequence : '-'}`);
        cell(row, `${named(event.userName, event.username)}\n${event.organizationId}`
                + (event.ssoOfficeCode ? `\nSSO ${event.ssoOfficeCode} / ${event.ssoDepartmentCode}` : ''));
        cell(row, `${named(event.authorityName, event.authorityId)}\n${event.assignmentType === 'DELEGATED' ? '위임' : '직접'}${event.requestKind ? ' · ' + kinds[event.requestKind] : ''}`);
        cell(row, statuses[event.status]);
        cell(row, `${event.validFrom || '확인 불가'} ~ ${event.validTo || '확인 불가'}`);
        cell(row, timestamp(event.registeredAt));
        cell(row, `${event.status === 'PENDING' ? '-' : named(event.actorName, event.actor)}\n${event.status === 'PENDING' ? '-' : timestamp(event.processedAt)}`);
        const reasonCell = cell(row, event.reason == null ? '확인 불가' : event.reason || '-');
        const correctable = admin && event.source === 'JOURNAL' && event.assignmentType === 'DIRECT'
                && ['REJECTED', 'REVOKED'].includes(event.status) && event.ssoOfficeCode && event.ssoDepartmentCode;
        if (correctable) reasonCell.append(document.createElement('br'), button('사유 정정', () => correctReason(event)));
        el('rows').append(row);
    }
    if (!result.content.length) {
        const row = document.createElement('tr');
        cell(row, '조회 조건에 해당하는 처리 이력이 없습니다.').colSpan = 8;
        el('rows').append(row);
    }
    paging(result, page);
    feedback('권한 처리 이력을 조회했습니다.');
}

el('search').addEventListener('click', () => run(async () => {page = 0; await load();}));
el('scope').addEventListener('change', () => run(async () => {
    el('usernameField').hidden = el('scope').value !== 'all'; page = 0; await load();
}));
el('previous').addEventListener('click', () => run(async () => {page--; await load();}));
el('next').addEventListener('click', () => run(async () => {page++; await load();}));
run(async () => {
    const context = await initialize();
    el('scopeField').hidden = !allowed(context, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ');
    await load();
});
