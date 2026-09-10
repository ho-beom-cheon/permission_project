'use strict';
// 신청 화면의 사용자·권한과 CSRF는 서버 세션에서 조회한다.
const workflow = {csrf: null, authorities: [], classifications: [], selected: new Set(), classificationImport: null,
    termsImport: null, agreementImport: null, termsPlan: null, termsSelectionKey: null,
    availableRegions: [],
    canRead: false, canWrite: false, minePage: 0, reviewPage: 0};
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

function selectedAuthorityKey() {
    return [...workflow.selected].sort().join('\u001f');
}

function invalidateTermsPlan() {
    workflow.termsPlan = null;
    workflow.termsSelectionKey = null;
    el('requestTermsPanel').hidden = true;
    el('requestTermsSummary').textContent = '';
    el('requestTermsItems').replaceChildren();
    el('requestTermsUnconfigured').textContent = '';
    el('submitRequest').textContent = '약관 확인';
}

function renderChoices() {
    el('authorityChoices').replaceChildren();
    workflow.authorities.filter(item => item.systemId === el('requestSystem').value).forEach(item => {
        const label = document.createElement('label');
        const box = document.createElement('input');
        box.type = el('requestKind').value === 'CHANGE' ? 'radio' : 'checkbox';
        box.value = item.id;
        box.name = 'authority';
        box.checked = workflow.selected.has(item.id);
        box.addEventListener('change', () => run(async () => {
            const before = new Set(workflow.selected);
            try {
                if (el('requestKind').value === 'CHANGE') workflow.selected.clear();
                if (box.checked) {
                    workflow.selected.add(item.id);
                    if (el('requestKind').value === 'GRANT') {
                        const plan = await api('/api/me/authority-requests/linked-plan', 'POST',
                            {kind: 'GRANT', authorityIds: [item.id]});
                        plan.automaticallyAddedAuthorityIds.forEach(id => workflow.selected.add(id));
                    }
                } else workflow.selected.delete(item.id);
                invalidateTermsPlan();
                renderChoices(); renderSelectedChoices();
            } catch (error) {
                workflow.selected = before;
                renderChoices(); renderSelectedChoices();
                throw error;
            }
        }));
        label.append(box, document.createTextNode(`${item.name} (${item.id})`));
        el('authorityChoices').append(label);
    });
}

async function loadAuthorityOptions() {
    workflow.authorities = await api('/api/me/authority-options');
    const available = new Set(workflow.authorities.map(item => item.id));
    const previousSelectionKey = selectedAuthorityKey();
    workflow.selected = new Set([...workflow.selected].filter(id => available.has(id)));
    if (selectedAuthorityKey() !== previousSelectionKey) invalidateTermsPlan();
    const selectedSystem = el('requestSystem').value;
    const systems = [...new Set(workflow.authorities.map(item => item.systemId))].sort();
    el('requestSystem').replaceChildren(...systems.map(id => new Option(id, id)));
    if (systems.includes(selectedSystem)) el('requestSystem').value = selectedSystem;
    renderChoices(); renderSelectedChoices();
}

function renderSelectedChoices() {
    el('selectedAuthorityChoices').replaceChildren();
    for (const id of [...workflow.selected]) {
        const authority = workflow.authorities.find(item => item.id === id);
        const label = document.createElement('label');
        const box = document.createElement('input'); box.type = 'checkbox'; box.checked = true; box.value = id;
        box.addEventListener('change', () => { workflow.selected.delete(id); invalidateTermsPlan(); renderChoices(); renderSelectedChoices(); });
        label.append(box, document.createTextNode(authority ? `${authority.name} (${id})` : id));
        el('selectedAuthorityChoices').append(label);
    }
    if (!workflow.selected.size) el('selectedAuthorityChoices').textContent = '선택한 권한이 없습니다.';
}

function renderApplicantRegions(selectedRegionIds) {
    const selected = new Set(Array.isArray(selectedRegionIds) ? selectedRegionIds : []);
    el('applicantRegions').replaceChildren();
    workflow.availableRegions.forEach(region => {
        const label = document.createElement('label');
        const box = document.createElement('input');
        box.type = 'checkbox'; box.value = region.code; box.checked = selected.has(region.code);
        const location = region.districtName ? `${region.districtName} · ` : '';
        label.append(box, document.createTextNode(`${location}${region.name} (${region.code})`));
        el('applicantRegions').append(label);
    });
    if (!workflow.availableRegions.length) {
        el('applicantRegions').textContent = '현재 사업소에 등록된 활성 담당지역이 없습니다.';
    }
}

async function loadApplicant() {
    const applicant = await api('/api/me/authority-requests/applicant');
    workflow.availableRegions = Array.isArray(applicant.availableRegions) ? applicant.availableRegions : [];
    el('applicantRankCode').value = applicant.rankCode || '';
    el('applicantTelephone').value = applicant.telephone || '';
    el('applicantMobile').value = applicant.mobile || '';
    el('applicantEmail').value = applicant.email || '';
    el('applicantJobDescription').value = applicant.jobDescription || '';
    renderApplicantRegions(applicant.regionIds);
}

function applicantPayload() {
    let regionIds = [...el('applicantRegions').querySelectorAll('input:checked')].map(box => box.value);
    if (!regionIds.length) regionIds = workflow.availableRegions.map(region => region.code);
    return {
        rankCode: el('applicantRankCode').value,
        telephone: el('applicantTelephone').value,
        mobile: el('applicantMobile').value,
        email: el('applicantEmail').value,
        jobDescription: el('applicantJobDescription').value,
        regionIds
    };
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
        cell(row, `${item.systemId}\n${kindNames[item.kind]}${item.workflowVersion >= 3 ? '\nSSO ' + item.ssoOfficeCode + ' / ' + item.ssoDepartmentCode : ''}`);
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

function validateClassificationImport(payload) {
    if (!payload || Array.isArray(payload) || typeof payload !== 'object'
            || !Array.isArray(payload.classifications) || !Array.isArray(payload.authorityLinks)) {
        throw new Error('JSON은 classifications와 authorityLinks 배열을 포함해야 합니다.');
    }
    payload.classifications.forEach((item, index) => {
        if (!item || typeof item !== 'object' || Array.isArray(item)
                || typeof item.authClsfId !== 'string' || !item.authClsfId
                || typeof item.authClsfNm !== 'string' || !item.authClsfNm
                || !['Y', 'N'].includes(item.useFlag)
                || !(item.upperAuthClsfId == null || typeof item.upperAuthClsfId === 'string')) {
            throw new Error(`classifications ${index + 1}번째 행의 형식을 확인해 주세요.`);
        }
    });
    payload.authorityLinks.forEach((item, index) => {
        if (!item || typeof item !== 'object' || Array.isArray(item)
                || typeof item.authId !== 'string' || !item.authId
                || typeof item.authClsfId !== 'string' || !item.authClsfId) {
            throw new Error(`authorityLinks ${index + 1}번째 행의 형식을 확인해 주세요.`);
        }
    });
    return payload;
}

function systemCounts(items, idField) {
    const counts = new Map();
    items.forEach(item => {
        const system = item[idField].substring(0, 2) || '(없음)';
        counts.set(system, (counts.get(system) || 0) + 1);
    });
    return [...counts].sort(([left], [right]) => left.localeCompare(right))
        .map(([system, count]) => `${system} ${count}건`).join(', ') || '0건';
}

function renderClassificationImportPreview(payload) {
    const preview = {
        classifications: payload.classifications.slice(0, 3),
        authorityLinks: payload.authorityLinks.slice(0, 3)
    };
    el('classificationImportPreview').textContent =
        `분류 ${payload.classifications.length}건 (${systemCounts(payload.classifications, 'authClsfId')})\n`
        + `권한 연결 ${payload.authorityLinks.length}건 (${systemCounts(payload.authorityLinks, 'authId')})\n`
        + `형식 미리보기(각 배열 최대 3건)\n${JSON.stringify(preview, null, 2)}`;
}

async function selectClassificationImportFile() {
    const file = el('classificationImportFile').files[0];
    workflow.classificationImport = null;
    el('importClassifications').disabled = true;
    if (!file) {
        el('classificationImportPreview').textContent = '선택한 파일이 없습니다.';
        return;
    }
    const payload = validateClassificationImport(JSON.parse(await file.text()));
    workflow.classificationImport = payload;
    renderClassificationImportPreview(payload);
    el('importClassifications').disabled = !workflow.canWrite;
}

function validateTermsImport(payload) {
    if (!payload || Array.isArray(payload) || typeof payload !== 'object' || !Array.isArray(payload.terms)) {
        throw new Error('JSON은 terms 배열을 포함해야 합니다.');
    }
    payload.terms.forEach((item, index) => {
        const validSequence = Number.isSafeInteger(item?.seq) && item.seq > 0;
        if (!item || typeof item !== 'object' || Array.isArray(item) || !validSequence
                || typeof item.systemId !== 'string' || !item.systemId
                || typeof item.typeCode !== 'string' || !item.typeCode
                || typeof item.version !== 'string'
                || typeof item.title !== 'string'
                || typeof item.body !== 'string'
                || typeof item.statusCode !== 'string' || !item.statusCode
                || !(item.enforcementStart == null || typeof item.enforcementStart === 'string')
                || !(item.enforcementEnd == null || typeof item.enforcementEnd === 'string')
                || !['Y', 'N'].includes(item.requiredFlag)
                || !(item.sortOrder == null || Number.isSafeInteger(item.sortOrder))
                || !['Y', 'N'].includes(item.useFlag)) {
            throw new Error(`terms ${index + 1}번째 행의 형식을 확인해 주세요.`);
        }
    });
    return payload;
}

function renderTermsImportPreview(payload) {
    const bySystem = new Map();
    payload.terms.forEach(item => {
        const count = bySystem.get(item.systemId) || {total: 0, required: 0};
        count.total++;
        if (item.requiredFlag === 'Y') count.required++;
        bySystem.set(item.systemId, count);
    });
    const counts = [...bySystem].sort(([left], [right]) => left.localeCompare(right))
        .map(([system, count]) => `${system} ${count.total}건(필수 ${count.required}건)`).join(', ') || '0건';
    el('termsImportPreview').textContent = `약관 ${payload.terms.length}건 (${counts})\n`
        + `형식 미리보기(최대 3건)\n${JSON.stringify({terms: payload.terms.slice(0, 3)}, null, 2)}`;
}

async function selectTermsImportFile() {
    const file = el('termsImportFile').files[0];
    workflow.termsImport = null;
    el('importTerms').disabled = true;
    if (!file) {
        el('termsImportPreview').textContent = '선택한 파일이 없습니다.';
        return;
    }
    const payload = validateTermsImport(JSON.parse(await file.text()));
    workflow.termsImport = payload;
    renderTermsImportPreview(payload);
    el('importTerms').disabled = !workflow.canWrite;
}

function validateAgreementImport(payload) {
    if (!payload || Array.isArray(payload) || typeof payload !== 'object' || !Array.isArray(payload.agreements)) {
        throw new Error('JSON은 agreements 배열을 포함해야 합니다.');
    }
    if (!payload.agreements.length || payload.agreements.length > 10000) {
        throw new Error('agreements는 1~10000건이어야 합니다.');
    }
    const sourceDateTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/;
    const identities = new Set();
    const duplicateGroups = new Map();
    payload.agreements.forEach((item, index) => {
        const validUsername = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 200;
        const validOptionalText = value => value == null || typeof value === 'string' && value.length <= 200;
        if (!item || !validUsername(item.username) || !Number.isSafeInteger(item.termSequence) || item.termSequence <= 0
                || typeof item.agreedAt !== 'string' || !sourceDateTime.test(item.agreedAt)
                || !['Y', 'N'].includes(item.agreedFlag)
                || !validOptionalText(item.connectionIp)
                || item.registeredAt != null && (typeof item.registeredAt !== 'string' || !sourceDateTime.test(item.registeredAt))
                || !validOptionalText(item.registrarId)
                || !Number.isSafeInteger(item.duplicateOrdinal)
                || item.duplicateOrdinal <= 0) {
            throw new Error(`agreements[${index}]의 필수 필드와 형식을 확인해 주세요.`);
        }
        const identity = JSON.stringify([item.username, item.termSequence, item.agreedAt, item.agreedFlag,
            item.connectionIp ?? null, item.registeredAt ?? null, item.registrarId ?? null, item.duplicateOrdinal]);
        if (identities.has(identity)) throw new Error(`agreements[${index}]에 같은 원본 행과 중복 순번이 반복됩니다.`);
        identities.add(identity);
        const group = JSON.stringify([item.username, item.termSequence, item.agreedAt, item.agreedFlag,
            item.connectionIp ?? null, item.registeredAt ?? null, item.registrarId ?? null]);
        const ordinals = duplicateGroups.get(group) || [];
        ordinals.push(item.duplicateOrdinal);
        duplicateGroups.set(group, ordinals);
    });
    for (const ordinals of duplicateGroups.values()) {
        ordinals.sort((left, right) => left - right);
        if (ordinals.some((ordinal, index) => ordinal !== index + 1)) {
            throw new Error('같은 원본 7컬럼 그룹의 duplicateOrdinal은 1부터 빠짐없이 이어져야 합니다.');
        }
    }
    return payload;
}

function renderAgreementImportPreview(payload) {
    const users = new Set(payload.agreements.map(item => item.username));
    const yes = payload.agreements.filter(item => item.agreedFlag === 'Y').length;
    const no = payload.agreements.length - yes;
    const duplicateRows = payload.agreements.filter(item => item.duplicateOrdinal > 1).length;
    el('agreementImportPreview').textContent = `동의 이력 ${payload.agreements.length}건 · 사용자 ${users.size}명\n`
        + `동의 Y ${yes}건 · N ${no}건 · 완전 중복 추가행 ${duplicateRows}건\n`
        + `형식 미리보기(최대 3건)\n${JSON.stringify({agreements: payload.agreements.slice(0, 3)}, null, 2)}`;
}

async function selectAgreementImportFile() {
    const file = el('agreementImportFile').files[0];
    workflow.agreementImport = null;
    el('importAgreements').disabled = true;
    if (!file) {
        el('agreementImportPreview').textContent = '선택한 파일이 없습니다.';
        return;
    }
    const payload = validateAgreementImport(JSON.parse(await file.text()));
    workflow.agreementImport = payload;
    renderAgreementImportPreview(payload);
    el('importAgreements').disabled = !workflow.canWrite;
}

function renderTermsPlan(plan) {
    workflow.termsPlan = plan;
    workflow.termsSelectionKey = selectedAuthorityKey();
    el('requestTermsPanel').hidden = false;
    const systems = Array.isArray(plan.requiredSystems) ? plan.requiredSystems : [];
    const items = Array.isArray(plan.items) ? plan.items : [];
    const unconfigured = Array.isArray(plan.unconfiguredSystems) ? plan.unconfiguredSystems : [];
    el('requestTermsSummary').textContent = systems.length
        ? `동의 확인 시스템: ${systems.join(', ')}` : '새로 동의할 필수 약관이 없습니다.';
    el('requestTermsItems').replaceChildren();
    items.forEach(item => {
        const label = document.createElement('label');
        const box = document.createElement('input');
        box.type = 'checkbox'; box.dataset.termSequence = String(item.termSequence);
        box.dataset.required = item.required ? 'true' : 'false';
        const title = item.title || `약관 ${item.termSequence}`;
        label.append(box, document.createTextNode(`${item.required ? '[필수] ' : '[선택] '}${item.systemId} · ${title}`));
        const body = document.createElement('p'); body.textContent = item.body || '';
        label.append(body); el('requestTermsItems').append(label);
    });
    if (!items.length) el('requestTermsItems').textContent = '동의할 약관 항목이 없습니다.';
    el('requestTermsUnconfigured').textContent = unconfigured.length
        ? `약관 미설정 시스템: ${unconfigured.join(', ')}` : '';
    el('submitRequest').textContent = '동의 후 신청 제출';
}

function termDecisions() {
    const boxes = [...el('requestTermsItems').querySelectorAll('input[data-term-sequence]')];
    if (boxes.some(box => box.dataset.required === 'true' && !box.checked)) {
        throw new Error('필수 이용약관에 동의해 주세요.');
    }
    return boxes.map(box => ({termSequence: Number(box.dataset.termSequence), agreed: box.checked}));
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
    el('termsAdminSection').hidden = !workflow.canRead;
    el('adminLink').hidden = !workflow.canRead;
    await Promise.all([loadAuthorityOptions(), loadApplicant()]);
    el('submitRequest').disabled = false;
    el('approve').disabled = !workflow.canWrite; el('reject').disabled = !workflow.canWrite; el('saveClassification').disabled = !workflow.canWrite;
    el('importClassifications').hidden = !workflow.canWrite;
    el('importTerms').hidden = !workflow.canWrite;
    el('importAgreements').hidden = !workflow.canWrite;
    el('requestSystem').addEventListener('change', renderChoices);
    el('requestKind').addEventListener('change', () => { workflow.selected.clear(); invalidateTermsPlan(); renderChoices(); renderSelectedChoices(); });
    el('classificationSystem').addEventListener('input', renderParents); el('classificationId').addEventListener('input', renderParents);
    el('classificationImportFile').addEventListener('change', () => run(selectClassificationImportFile));
    el('termsImportFile').addEventListener('change', () => run(selectTermsImportFile));
    el('agreementImportFile').addEventListener('change', () => run(selectAgreementImportFile));
    el('importClassifications').addEventListener('click', () => run(async () => {
        if (!workflow.classificationImport) throw new Error('검증할 분류 이관 JSON 파일을 선택해 주세요.');
        el('importClassifications').disabled = true;
        try {
            await api('/api/admin/authority-classifications/import', 'POST', workflow.classificationImport);
            await Promise.all([loadClassifications(), loadAuthorityOptions()]);
            workflow.classificationImport = null;
            el('classificationImportFile').value = '';
            el('classificationImportPreview').textContent = '이관을 완료했습니다. 새 파일을 선택해 다시 검증할 수 있습니다.';
            feedback('권한 분류와 권한 연결을 이관하고 최신 목록을 조회했습니다.');
        } finally {
            el('importClassifications').disabled = !workflow.canWrite || !workflow.classificationImport;
        }
    }));
    el('importTerms').addEventListener('click', () => run(async () => {
        if (!workflow.termsImport) throw new Error('검증할 약관 이관 JSON 파일을 선택해 주세요.');
        el('importTerms').disabled = true;
        try {
            await api('/api/admin/authority-terms/import', 'POST', workflow.termsImport);
            workflow.termsImport = null;
            el('termsImportFile').value = '';
            el('termsImportPreview').textContent = '이관을 완료했습니다. 새 파일을 선택해 다시 검증할 수 있습니다.';
            feedback('권한 신청 이용약관을 이관했습니다.');
        } finally {
            el('importTerms').disabled = !workflow.canWrite || !workflow.termsImport;
        }
    }));
    el('importAgreements').addEventListener('click', () => run(async () => {
        if (!workflow.agreementImport) throw new Error('검증할 약관 동의 이력 JSON 파일을 선택해 주세요.');
        el('importAgreements').disabled = true;
        try {
            await api('/api/admin/authority-term-agreements/import', 'POST', workflow.agreementImport);
            workflow.agreementImport = null;
            el('agreementImportFile').value = '';
            el('agreementImportPreview').textContent = '이관을 완료했습니다. 새 파일을 선택해 다시 검증할 수 있습니다.';
            feedback('기존 사용자 약관 동의 이력을 이관했습니다.');
        } finally {
            el('importAgreements').disabled = !workflow.canWrite || !workflow.agreementImport;
        }
    }));
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
            const authorityIds = [...workflow.selected];
            if (!authorityIds.length) throw new Error('신청할 권한을 선택해 주세요.');
            if (el('requestKind').value === 'CHANGE' && authorityIds.length !== 1) throw new Error('변경할 권한 하나를 선택해 주세요.');
            el('submitRequest').disabled = true;
            try {
                const selectionKey = selectedAuthorityKey();
                if (!workflow.termsPlan || workflow.termsSelectionKey !== selectionKey) {
                    const plan = await api('/api/me/authority-requests/terms-plan', 'POST', {authorityIds});
                    renderTermsPlan(plan);
                    feedback('시스템별 이용약관을 확인한 뒤 다시 제출해 주세요.');
                    return;
                }
                const decisions = termDecisions();
                const endpoint = el('requestKind').value === 'GRANT'
                    ? '/api/me/authority-requests/batch' : '/api/me/authority-requests';
                try {
                    await api(endpoint, 'POST', {kind: el('requestKind').value, authorityIds,
                        reason: el('requestReason').value, termDecisions: decisions, applicant: applicantPayload()});
                } catch (error) {
                    invalidateTermsPlan();
                    throw new Error(`${error.message} 선택한 권한은 유지했습니다. 이용약관을 다시 확인해 주세요.`);
                }
                el('requestReason').value = ''; workflow.selected.clear(); invalidateTermsPlan(); renderChoices(); renderSelectedChoices(); workflow.minePage = 0;
                await Promise.all([loadApplicant(), loadList()]); if (workflow.canRead) await loadList(true);
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
