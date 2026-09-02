'use strict';
// 신청 화면의 사용자·권한과 CSRF는 서버 세션에서 조회한다.
const workflow = {csrf: null, authorities: [], classifications: [], canRead: false, canWrite: false, minePage: 0, reviewPage: 0};
const el = id => document.getElementById(id);
const statusNames = {PENDING: '신청 대기', APPROVED: '승인', REJECTED: '반려'};
const kindNames = {GRANT: '권한 추가', CHANGE: '기존 권한 변경'};

function feedback(message, error = false) {
    el('feedback').textContent = message;
    el('feedback').classList.toggle('error', error);
}

async function api(url, method = 'GET', body) {
    const headers = {'Accept': 'application/json'};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && workflow.csrf) headers[workflow.csrf.headerName] = workflow.csrf.token;
    const response = await fetch(url, {method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)});
    if (response.status === 401) {
        location.assign('/login');
        throw new Error('로그인이 필요합니다.');
    }
    const result = await response.json();
    if (!response.ok) throw new Error(result.error?.message || '요청을 처리하지 못했습니다.');
    return result.data;
}

function cell(row, text) {
    const td = document.createElement('td');
    td.textContent = text;
    row.append(td);
    return td;
}

function renderChoices() {
    el('authorityChoices').replaceChildren();
    workflow.authorities.filter(item => item.systemId === el('requestSystem').value).forEach(item => {
        const label = document.createElement('label');
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.value = item.id;
        box.name = 'authority';
        label.append(box, document.createTextNode(`${item.name} (${item.id})`));
        el('authorityChoices').append(label);
    });
}

async function loadList(admin = false) {
    const prefix = admin ? 'review' : 'mine';
    const page = workflow[`${prefix}Page`];
    const status = admin ? el('reviewStatus').value : '';
    const data = await api(`/api/${admin ? 'admin' : 'me'}/authority-requests?page=${page}&size=20${status ? `&status=${status}` : ''}`);
    const rows = el(`${prefix}Rows`);
    rows.replaceChildren();
    for (const item of data.content) {
        const row = document.createElement('tr');
        if (admin) {
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.value = item.id;
            box.disabled = item.status !== 'PENDING' || !workflow.canWrite;
            box.setAttribute('aria-label', `${item.username} 신청 ${item.id} 선택`);
            cell(row, '').append(box);
            cell(row, `${item.username}\n${item.organizationId}`);
        } else cell(row, String(item.id));
        cell(row, `${item.systemId}\n${kindNames[item.kind]}`);
        cell(row, `${item.authorityIds.join(', ')}\n${item.validFrom} ~ ${item.validTo}`);
        cell(row, item.reason);
        cell(row, statusNames[item.status]);
        if (!admin) cell(row, `${item.reviewer || '-'}\n${item.reviewReason || ''}`);
        rows.append(row);
    }
    if (!data.content.length) {
        const row = document.createElement('tr');
        cell(row, '신청 내역이 없습니다.').colSpan = 6;
        rows.append(row);
    }
    el(`${prefix}Page`).textContent = `${data.totalElements ? page + 1 : 0} / ${data.totalPages}쪽 (${data.totalElements}건)`;
    el(`${prefix}Prev`).disabled = page === 0;
    el(`${prefix}Next`).disabled = page + 1 >= data.totalPages;
}

function renderParents() {
    const selected = el('classificationParent').value;
    el('classificationParent').replaceChildren(new Option('최상위', ''));
    workflow.classifications.filter(item => item.id !== el('classificationId').value
            && item.systemId === el('classificationSystem').value && item.active)
        .forEach(item => el('classificationParent').add(new Option(`${item.name} (${item.id})`, item.id)));
    el('classificationParent').value = selected;
    if (el('classificationParent').selectedIndex < 0) el('classificationParent').value = '';
}

async function loadClassifications() {
    workflow.classifications = await api('/api/admin/authority-classifications');
    el('classificationRows').replaceChildren();
    for (const item of workflow.classifications) {
        const row = document.createElement('tr');
        [item.id, item.name, item.parentId || '-', item.systemId, item.active ? '사용' : '미사용'].forEach(value => cell(row, value));
        const actions = cell(row, '');
        const edit = document.createElement('button');
        edit.type = 'button'; edit.textContent = '편집'; edit.disabled = !workflow.canWrite;
        edit.addEventListener('click', () => {
            el('classificationId').value = item.id; el('classificationName').value = item.name;
            el('classificationSystem').value = item.systemId; el('classificationActive').checked = item.active;
            renderParents(); el('classificationParent').value = item.parentId || '';
        });
        const remove = document.createElement('button');
        remove.type = 'button'; remove.textContent = '삭제'; remove.className = 'secondary'; remove.disabled = !workflow.canWrite;
        remove.addEventListener('click', () => run(async () => {
            if (!confirm(`${item.name} 분류를 삭제할까요?`)) return;
            await api(`/api/admin/authority-classifications/${encodeURIComponent(item.id)}`, 'DELETE');
            await loadClassifications(); feedback('분류를 삭제했습니다.');
        }));
        actions.append(edit, document.createTextNode(' '), remove); el('classificationRows').append(row);
    }
    renderParents();
}

async function run(task) {
    try { await task(); } catch (error) { feedback(error.message, true); }
}

async function review(decision) {
    const requestIds = [...el('reviewRows').querySelectorAll('input:checked')].map(box => Number(box.value));
    if (!requestIds.length) throw new Error('처리할 신청을 선택해 주세요.');
    if (decision === 'APPROVE' && !confirm('선택 신청을 승인할까요? 변경 신청은 해당 시스템의 기존 직접 권한을 교체합니다.')) return;
    el('approve').disabled = true; el('reject').disabled = true;
    try {
        await api('/api/admin/authority-requests/review', 'POST', {requestIds, decision, reason: el('reviewReason').value});
        el('reviewReason').value = ''; workflow.reviewPage = 0;
        await Promise.all([loadList(), loadList(true)]);
        feedback(decision === 'APPROVE' ? '선택한 신청을 승인했습니다.' : '선택한 신청을 반려했습니다.');
    } finally { el('approve').disabled = !workflow.canWrite; el('reject').disabled = !workflow.canWrite; }
}

document.addEventListener('DOMContentLoaded', () => run(async () => {
    const bootstrap = await api('/api/bootstrap');
    workflow.csrf = bootstrap.csrf;
    const actions = bootstrap.context.programActions.filter(item => item.menuId === 'SYSTEM_AUTH' && item.programId === 'AUTHORITY');
    workflow.canRead = actions.some(item => item.actionId === 'AUTHORITY_READ');
    workflow.canWrite = actions.some(item => item.actionId === 'AUTHORITY_UPDATE');
    el('identity').textContent = `${bootstrap.context.user.username} · ${bootstrap.context.user.organizationId}`;
    el('reviewSection').hidden = !workflow.canRead; el('classificationSection').hidden = !workflow.canRead;
    el('adminLink').hidden = !workflow.canRead;
    workflow.authorities = await api('/api/me/authority-options');
    [...new Set(workflow.authorities.map(item => item.systemId))].sort().forEach(id => el('requestSystem').add(new Option(id, id)));
    renderChoices();
    const today = new Date().toLocaleDateString('sv-SE', {timeZone: 'Asia/Seoul'});
    el('validFrom').value = today; el('validTo').value = `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`;
    el('submitRequest').disabled = false;
    el('approve').disabled = !workflow.canWrite; el('reject').disabled = !workflow.canWrite; el('saveClassification').disabled = !workflow.canWrite;
    el('requestSystem').addEventListener('change', renderChoices);
    el('classificationSystem').addEventListener('input', renderParents); el('classificationId').addEventListener('input', renderParents);
    el('refreshMine').addEventListener('click', () => run(() => loadList()));
    el('refreshReview').addEventListener('click', () => run(() => loadList(true)));
    el('reviewStatus').addEventListener('change', () => run(async () => { workflow.reviewPage = 0; await loadList(true); }));
    for (const prefix of ['mine', 'review']) for (const direction of ['Prev', 'Next']) {
        el(prefix + direction).addEventListener('click', () => run(async () => {
            workflow[prefix + 'Page'] += direction === 'Prev' ? -1 : 1; await loadList(prefix === 'review');
        }));
    }
    el('approve').addEventListener('click', () => run(() => review('APPROVE')));
    el('reject').addEventListener('click', () => run(() => review('REJECT')));
    el('requestForm').addEventListener('submit', event => {
        event.preventDefault(); run(async () => {
            const authorityIds = [...el('authorityChoices').querySelectorAll('input:checked')].map(box => box.value);
            if (!authorityIds.length) throw new Error('신청할 권한을 선택해 주세요.');
            el('submitRequest').disabled = true;
            try {
                await api('/api/me/authority-requests', 'POST', {kind: el('requestKind').value, authorityIds,
                    validFrom: el('validFrom').value, validTo: el('validTo').value, reason: el('requestReason').value});
                el('requestReason').value = ''; workflow.minePage = 0;
                await loadList(); if (workflow.canRead) await loadList(true);
                feedback('권한 신청을 제출했습니다. 승인 후 권한이 반영됩니다.');
            } finally { el('submitRequest').disabled = false; }
        });
    });
    el('classificationForm').addEventListener('submit', event => {
        event.preventDefault(); run(async () => {
            await api(`/api/admin/authority-classifications/${encodeURIComponent(el('classificationId').value)}`, 'POST', {
                parentId: el('classificationParent').value || null, name: el('classificationName').value,
                systemId: el('classificationSystem').value, active: el('classificationActive').checked});
            await loadClassifications(); feedback('권한 분류를 저장했습니다.');
        });
    });
    await loadList(); if (workflow.canRead) await Promise.all([loadList(true), loadClassifications()]);
}));
