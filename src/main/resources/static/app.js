'use strict';

let csrf = null;
let authorityAdmin = null;
let menuAdmin = null;
let programAdmin = null;
let projectAdmin = null;
let commonCodeAdmin = null;
let commonCodeGroups = [];
let commonCodeItems = [];
let adminToastTimer = null;
let mutationInFlight = false;
let authorityRequestPage = 0;
let auditPage = 0;
let adminMenuOpen = false;
let dashboardPendingCount = null;
let dashboardErrorCount = null;
const loadedAdminViews = new Set();
const allowedActionIds = new Set();
const latestListRequests = new Map();
const adminListState = {
    authorityMaster: {page: 0, size: 20, sort: 'ID', direction: 'ASC'},
    authorityUser: {page: 0, size: 20, sort: 'USERNAME', direction: 'ASC'},
    menu: {page: 0, size: 20, sort: 'ORDER', direction: 'ASC'},
    program: {page: 0, size: 20, sort: 'ID', direction: 'ASC'},
    programAction: {page: 0, size: 20, sort: 'ORDER', direction: 'ASC'},
    project: {page: 0, size: 20, sort: 'ID', direction: 'ASC'},
    commonCode: {page: 0, size: 20, sort: 'ORDER', direction: 'ASC'}
};
const selectedRows = {
    authorityMaster: null,
    authorityUser: null,
    menu: null,
    program: null,
    programAction: null,
    project: null,
    projectMember: null,
    commonCode: null
};

/**
 * DOM 구성이 끝나면 모든 버튼과 선택 이벤트를 먼저 연결한 뒤 화면 공통 데이터를 조회한다.
 * 초기화 중 발생한 인증·인가·검증 오류도 하단 응답 콘솔에 동일한 형식으로 표시한다.
 */
document.addEventListener('DOMContentLoaded', () => {
    if (window.location.pathname === '/index.html') history.replaceState(null, '', '/admin' + window.location.hash);
    bindEvents();
    initialize().catch(showError);
});

/**
 * 로그인 사용자의 공통 화면 컨텍스트를 적용하고, 관리자에게만 세 관리 화면의 원본 데이터를 추가 조회한다.
 * 버튼 활성 여부는 서버가 계산한 프로그램 기능 목록을 사용하므로 클라이언트가 관리자 여부를 추측하지 않는다.
 */
async function initialize() {
    const context = await refreshContext();
    await routeToHash(true);
    showResult('공통 부트스트랩 및 관리 화면 초기화 완료', context);
}

/**
 * 부트스트랩 응답으로 현재 사용자, 유효 권한, 메뉴, 기능 버튼, 관심 메뉴와 지역 코드를 갱신한다.
 * 권한이나 메뉴를 변경한 직후에도 이 메서드를 다시 호출해 화면을 서버의 최종 판정과 일치시킨다.
 */
async function refreshContext() {
    const payload = await requestJson('/api/admin/bootstrap');
    const bootstrap = payload.data;
    const context = bootstrap.context;
    csrf = bootstrap.csrf;

    document.getElementById('username').textContent =
        `${context.user.username} (${context.user.organizationId}) 로그인`;
    renderAuthorities(context.user.effectiveAuthorityIds);
    renderMenuTree(context.menus);
    enableAuthorizedButtons(context.programActions);
    renderRegions(context.codeGroups.REGION.items);
    renderFavorites(context.favorites);
    renderFavoriteOptions(context.menus);
    routeToHash(false);
    return context;
}

/** 정적인 화면 요소에 API 조회, 저장, 선택 연동과 해시 메뉴 탐색 동작을 연결한다. */
function bindEvents() {
    bindApiButton('btnContentRead', '/api/content/preview', 'GET');
    bindApiButton('btnContentSave', '/api/content/save', 'POST');
    bindApiButton('btnContentPublish', '/api/content/publish', 'POST');
    bindApiButton('btnForcePublish', '/api/content/publish', 'POST');
    document.getElementById('btnAuditRead').addEventListener('click', () => loadAuditEvents(0));
    document.getElementById('btnAuthorityRequestRead').addEventListener('click', () => loadAuthorityRequests(0));
    bindFilter('authorityRequestSearch', () => loadAuthorityRequests(0));
    bindFilter('authorityRequestStatus', () => loadAuthorityRequests(0));
    bindFilter('authorityRequestSize', () => loadAuthorityRequests(0));
    bindFilter('auditSearch', () => loadAuditEvents(0));
    bindFilter('auditActor', () => loadAuditEvents(0));
    bindFilter('auditEventType', () => loadAuditEvents(0));
    bindFilter('auditResult', () => loadAuditEvents(0));
    bindFilter('auditFrom', () => loadAuditEvents(0));
    bindFilter('auditTo', () => loadAuditEvents(0));
    bindFilter('auditSize', () => loadAuditEvents(0));

    document.getElementById('btnAuthorityRead').addEventListener('click', () => loadAuthorityView());
    document.getElementById('btnAuthMasterRead').addEventListener('click', () => loadAuthorityView());
    document.getElementById('btnAuthMasterSave').addEventListener('click', saveAuthMaster);
    document.getElementById('btnAuthMasterDelete').addEventListener('click', deleteAuthMaster);
    document.getElementById('btnAuthorityUpdate').addEventListener('click', assignAuthority);
    document.getElementById('btnAuthorityRevoke').addEventListener('click', revokeAuthority);
    document.getElementById('authorityTypeSelect').addEventListener('change', toggleAuthoritySource);
    bindFilter('authorityMasterSearch', () => loadAuthorityMasterPage(0));
    bindFilter('authorityMasterStatusFilter', () => loadAuthorityMasterPage(0));
    bindFilter('authorityUserSearch', () => loadAuthorityUserPage(0));
    bindFilter('authorityGrantTypeFilter', () => loadAuthorityUserPage(0));
    document.getElementById('btnAuthMasterResetFilters').addEventListener('click', resetAuthorityMasterFilters);
    document.getElementById('btnAuthorityResetFilters').addEventListener('click', resetAuthorityUserFilters);
    document.getElementById('btnAuthMasterNew').addEventListener('click', resetAuthMasterForm);
    document.getElementById('btnAuthorityNew').addEventListener('click', resetAuthorityGrantForm);

    document.getElementById('btnMenuRead').addEventListener('click', () => loadMenuView());
    document.getElementById('btnMenuSave').addEventListener('click', saveMenu);
    document.getElementById('btnMenuDelete').addEventListener('click', deleteMenu);
    document.getElementById('btnMenuGrant').addEventListener('click', saveMenuGrant);
    document.getElementById('menuAuthoritySelect').addEventListener('change', syncMenuGrant);
    document.getElementById('menuGrantSelect').addEventListener('change', syncMenuGrant);

    document.getElementById('btnProgramRead').addEventListener('click', () => loadProgramView());
    document.getElementById('btnProgramSave').addEventListener('click', saveProgram);
    document.getElementById('btnProgramDelete').addEventListener('click', deleteProgram);
    document.getElementById('btnActionSave').addEventListener('click', saveAction);
    document.getElementById('btnActionDelete').addEventListener('click', deleteAction);
    document.getElementById('btnActionGrant').addEventListener('click', saveActionGrant);
    document.getElementById('actionAuthoritySelect').addEventListener('change', syncActionGrant);
    document.getElementById('actionGrantSelect').addEventListener('change', syncActionGrant);
    bindFilter('programSearch', () => loadProgramPage(0));
    bindFilter('programStatusFilter', () => loadProgramPage(0));
    bindFilter('programActionSearch', () => loadProgramActionPage(0));
    bindFilter('programActionStatusFilter', () => loadProgramActionPage(0));
    document.getElementById('btnProgramResetFilters').addEventListener('click', resetProgramFilters);
    document.getElementById('btnProgramActionResetFilters').addEventListener('click', resetProgramActionFilters);
    document.getElementById('btnProgramNew').addEventListener('click', resetProgramForm);
    document.getElementById('btnActionNew').addEventListener('click', resetActionForm);

    document.getElementById('btnProjectRead').addEventListener('click', () => loadProjectView());
    document.getElementById('btnProjectSave').addEventListener('click', saveProject);
    document.getElementById('btnProjectMemberSave').addEventListener('click', saveProjectMember);
    document.getElementById('btnProjectMemberRemove').addEventListener('click', removeProjectMember);
    document.getElementById('btnProjectHistoryRead').addEventListener('click', loadSelectedProjectHistory);
    document.getElementById('btnProjectOwner').addEventListener('click', transferProjectOwner);
    document.getElementById('btnProjectArchive').addEventListener('click', () => changeProjectArchiveState(true));
    document.getElementById('btnProjectRestore').addEventListener('click', () => changeProjectArchiveState(false));
    bindFilter('projectSearch', () => loadProjectPage(0));
    bindFilter('projectStatusFilter', () => loadProjectPage(0));
    document.getElementById('btnProjectResetFilters').addEventListener('click', resetProjectFilters);
    document.getElementById('btnProjectNew').addEventListener('click', resetProjectForm);

    document.getElementById('commonGroupSelect').addEventListener('change', () => {
        syncCommonCodeGroupForm();
        loadCodeGroup();
    });
    document.getElementById('btnCommonCodeRead').addEventListener('click', () => loadCodeAdmin());
    document.getElementById('btnCommonCodeSave').addEventListener('click', saveCommonCode);
    document.getElementById('btnCommonCodeGroupNew').addEventListener('click', resetCommonCodeGroupForm);
    document.getElementById('btnCommonCodeGroup').addEventListener('click', saveCommonCodeGroup);
    document.getElementById('btnCommonCodeGroupDelete').addEventListener('click', deleteCommonCodeGroup);
    document.getElementById('btnCommonCodeDeactivate').addEventListener('click', deactivateCommonCode);
    document.getElementById('btnCommonCodeDeleteItem').addEventListener('click', deleteCommonCode);
    document.getElementById('btnCommonCodeImportTemplate').addEventListener('click', fillCommonCodeImportTemplate);
    document.getElementById('btnCommonCodeImport').addEventListener('click', importCommonCodes);
    bindFilter('commonCodeSearch', () => loadCommonCodePage(0));
    bindFilter('commonCodeStatusFilter', () => loadCommonCodePage(0));
    document.getElementById('btnCodeResetFilters').addEventListener('click', resetCodeFilters);
    document.getElementById('btnCodeNew').addEventListener('click', resetCodeForm);
    document.getElementById('btnFavoriteAdd').addEventListener('click', addFavorite);
    document.getElementById('btnFavoriteRemove').addEventListener('click', removeFavorite);
    document.getElementById('regionParentSelect').addEventListener('change', filterRegions);
    document.getElementById('btnLogout').addEventListener('click', logout);
    document.getElementById('btnPortalHome').addEventListener('click', () => {
        window.location.assign('/portal.html');
    });
    document.getElementById('btnAdminMenu').addEventListener('click', toggleAdminMenu);
    document.getElementById('adminSidebarBackdrop').addEventListener('click', closeAdminMenu);
    document.getElementById('adminSidebar').addEventListener('keydown', trapAdminMenuFocus);
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && adminMenuOpen) {
            closeAdminMenu();
        }
    });
    window.addEventListener('resize', syncAdminResponsiveState);
    document.addEventListener('click', event => {
        const blocked = event.target.closest('[data-protected][aria-disabled="true"]');
        if (blocked) {
            event.preventDefault();
            event.stopImmediatePropagation();
            showAdminToast('현재 사용자에게 허용되지 않은 기능입니다.', true);
        }
    }, true);
    installServerTableSorting();
    installAdminValidation();
    initializeAdminFormDefaults();
    syncAdminResponsiveState();
    window.addEventListener('hashchange', () => routeToHash(true).catch(showError));
}

/** 신규 등록 폼의 날짜를 샘플 데이터가 아닌 브라우저의 오늘 기준으로 초기화한다. */
function initializeAdminFormDefaults() {
    const today = new Date();
    const projectEnd = new Date(today);
    projectEnd.setMonth(projectEnd.getMonth() + 3);
    document.getElementById('authorityValidFrom').value = toLocalDateInput(today);
    document.getElementById('authorityValidTo').value = '2099-12-31';
    document.getElementById('projectStartInput').value = toLocalDateInput(today);
    document.getElementById('projectEndInput').value = toLocalDateInput(projectEnd);
    document.getElementById('codeValidFrom').value = toLocalDateInput(today);
    document.getElementById('codeValidTo').value = '2099-12-31';
}

function syncAdminResponsiveState() {
    if (!window.matchMedia('(max-width: 1024px)').matches) {
        adminMenuOpen = false;
        document.getElementById('adminSidebar').classList.remove('open');
        document.getElementById('adminSidebar').removeAttribute('aria-hidden');
        document.getElementById('adminSidebarBackdrop').hidden = true;
        document.getElementById('adminMain').inert = false;
        document.querySelector('.topbar-actions').inert = false;
        document.body.classList.remove('drawer-open');
    } else if (!adminMenuOpen) {
        document.getElementById('adminSidebar').setAttribute('aria-hidden', 'true');
    }
}

/** 관리자 권한 요청 큐를 서버 조건과 페이지 메타데이터로 렌더링한다. */
async function loadAuthorityRequests(page = authorityRequestPage) {
    authorityRequestPage = page;
    const query = new URLSearchParams({
        page: String(page),
        size: document.getElementById('authorityRequestSize').value,
        sort: 'REQUESTED_AT',
        direction: 'DESC'
    });
    const search = document.getElementById('authorityRequestSearch').value.trim();
    const status = document.getElementById('authorityRequestStatus').value;
    if (search) {
        query.set(search.toUpperCase().startsWith('AUTH_') ? 'authorityId' : 'username', search);
    }
    if (status) {
        query.set('status', status);
    }
    setTableMessage('authorityRequestRows', 8, '권한 요청을 불러오는 중입니다.', 'loading');
    try {
        const payload = await requestLatestList('authorityRequests', `/api/admin/authority-requests?${query}`);
        if (!payload) return;
        renderAuthorityRequests(payload.data);
    } catch (error) {
        setTableMessage('authorityRequestRows', 8, '권한 요청을 불러오지 못했습니다.', 'error');
        showError(error);
    }
}

function renderAuthorityRequests(result) {
    const body = document.getElementById('authorityRequestRows');
    body.replaceChildren();
    result.content.forEach(request => {
        const row = document.createElement('tr');
        appendCell(row, `#${request.id}`);
        appendCell(row, `${request.targetUsername}\n${request.organizationId}`);
        appendCell(row, request.authorityId);
        appendCell(row, request.assignmentType === 'DELEGATED' ? '위임' : '직접');
        appendCell(row, `${request.validFrom} ~ ${request.validTo}`);
        appendStatusCell(row, requestStatusLabel(request.status), requestStatusTone(request.status));
        appendCell(row, request.requestReason);
        const actionCell = document.createElement('td');
        if (request.status === 'REQUESTED') {
            const approve = actionButton('승인', 'AUTHORITY_REQUEST_APPROVE', () => decideAuthorityRequest(request, true));
            const reject = actionButton('반려', 'AUTHORITY_REQUEST_REJECT', () => decideAuthorityRequest(request, false));
            actionCell.append(approve, reject);
        } else {
            actionCell.textContent = request.decidedBy ? `${request.decidedBy} 처리` : '처리 완료';
        }
        row.appendChild(actionCell);
        body.appendChild(row);
    });
    if (!result.content.length) {
        setTableMessage('authorityRequestRows', 8, '조건에 맞는 권한 요청이 없습니다.', 'empty');
    }
    document.getElementById('authorityRequestSummary').textContent =
        `총 ${result.totalElements}건 · ${result.totalPages ? result.page + 1 : 0}/${result.totalPages}페이지`;
    renderAdminPagination('authorityRequestPagination', result, loadAuthorityRequests);
}

function actionButton(label, actionId, handler) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'table-action';
    button.textContent = label;
    button.dataset.protected = '';
    button.dataset.action = actionId;
    const allowed = allowedActionIds.has(actionId);
    button.setAttribute('aria-disabled', allowed ? 'false' : 'true');
    if (!allowed) button.setAttribute('aria-describedby', 'protectedActionHelp');
    button.addEventListener('click', handler);
    return button;
}

async function decideAuthorityRequest(request, approve) {
    const reason = window.prompt(approve
        ? '승인 사유를 입력해 주세요. 비워도 승인할 수 있습니다.'
        : '반려 사유를 입력해 주세요.');
    if (reason === null || (!approve && !reason.trim())) {
        if (!approve && reason !== null) {
            showAdminToast('반려 사유는 필수입니다.', true);
        }
        return;
    }
    try {
        await requestJson(`/api/admin/authority-requests/${request.id}/${approve ? 'approve' : 'reject'}`, {
            method: 'POST',
            body: {version: request.version, reason: reason.trim() || null}
        });
        showAdminToast(approve ? '권한 요청을 승인했습니다.' : '권한 요청을 반려했습니다.');
        await Promise.all([loadAuthorityRequests(authorityRequestPage), loadAuthorityView(false), refreshContext()]);
    } catch (error) {
        if (error.status === 409) {
            showAdminToast('이미 처리되었거나 변경된 요청입니다. 최신 목록을 불러옵니다.', true);
            await loadAuthorityRequests(authorityRequestPage);
            return;
        }
        showError(error);
    }
}

/** 감사 이력을 서버 검색 조건과 페이지 메타데이터로 렌더링한다. */
async function loadAuditEvents(page = auditPage) {
    auditPage = page;
    const query = new URLSearchParams({
        page: String(page), size: document.getElementById('auditSize').value,
        sort: 'OCCURRED_AT', direction: 'DESC'
    });
    [['keyword', 'auditSearch'], ['actor', 'auditActor'], ['eventType', 'auditEventType'],
        ['result', 'auditResult'], ['from', 'auditFrom'], ['to', 'auditTo']]
        .forEach(([name, id]) => {
            const value = document.getElementById(id).value.trim();
            if (value) query.set(name, value);
        });
    setTableMessage('auditRows', 7, '감사 이력을 불러오는 중입니다.', 'loading');
    try {
        const payload = await requestLatestList('audit', `/api/audit-events?${query}`);
        if (!payload) return;
        renderAuditEvents(payload.data);
    } catch (error) {
        setTableMessage('auditRows', 7, '감사 이력을 불러오지 못했습니다.', 'error');
        showError(error);
    }
}

function renderAuditEvents(result) {
    const body = document.getElementById('auditRows');
    body.replaceChildren();
    result.content.forEach(event => {
        const row = document.createElement('tr');
        appendCell(row, new Date(event.occurredAt).toLocaleString('ko-KR'));
        appendCell(row, `${event.actor}\n${event.organizationId}`);
        appendCell(row, event.eventType);
        appendCell(row, `${event.targetType} / ${event.targetId}`);
        appendStatusCell(row, event.result, event.result === 'SUCCESS' ? 'success' : 'warning');
        appendCell(row, event.traceId || '-');
        const detail = document.createElement('td');
        const disclosure = document.createElement('details');
        const summary = document.createElement('summary');
        summary.textContent = '보기';
        const pre = document.createElement('pre');
        pre.textContent = JSON.stringify(event.details, null, 2);
        disclosure.append(summary, pre);
        detail.appendChild(disclosure);
        row.appendChild(detail);
        body.appendChild(row);
    });
    if (!result.content.length) {
        setTableMessage('auditRows', 7, '조건에 맞는 감사 이력이 없습니다.', 'empty');
    }
    document.getElementById('auditSummary').textContent =
        `총 ${result.totalElements}건 · ${result.totalPages ? result.page + 1 : 0}/${result.totalPages}페이지`;
    renderAdminPagination('auditPagination', result, loadAuditEvents);
}

function renderAdminPagination(id, result, onPage) {
    const host = document.getElementById(id);
    host.replaceChildren();
    const add = (label, page, disabled, current = false) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.disabled = disabled;
        if (current) button.setAttribute('aria-current', 'page');
        button.addEventListener('click', () => onPage(page));
        host.appendChild(button);
    };
    add('이전', result.page - 1, result.page <= 0);
    const start = Math.max(0, result.page - 2);
    const end = Math.min(result.totalPages, start + 5);
    for (let page = start; page < end; page += 1) {
        add(String(page + 1), page, false, page === result.page);
    }
    add('다음', result.page + 1, result.page + 1 >= result.totalPages);
}

function requestStatusLabel(status) {
    return {REQUESTED: '승인 대기', APPROVED: '승인', REJECTED: '반려', CANCELLED: '취소'}[status] || status;
}

function requestStatusTone(status) {
    return {REQUESTED: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'muted'}[status] || 'muted';
}

/** 권한 마스터와 사용자별 유효 권한·이력을 조회해 표와 입력 선택지를 갱신한다. */
async function loadAuthorityView(show = true) {
    setLoadState(['authorityMasterLoadState', 'authorityLoadState'], 'loading', '불러오는 중');
    setSectionBusy(['authority-master', 'authority'], true);
    if (!authorityAdmin) {
        setTableMessage('authorityMasterRows', 4, '권한 마스터를 불러오는 중입니다.', 'loading');
        setTableMessage('authorityUserRows', 4, '사용자 권한을 불러오는 중입니다.', 'loading');
    }
    try {
        const payload = await requestJson('/api/admin/authority-view');
        authorityAdmin = payload.data;
        renderAuthorityView(authorityAdmin);
        await Promise.all([loadAuthorityMasterPage(), loadAuthorityUserPage()]);
        setLoadState(['authorityMasterLoadState', 'authorityLoadState'], 'ready', '최신 데이터');
        refreshDashboardSummary();
        if (show) {
            showResult('권한 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        setLoadState(['authorityMasterLoadState', 'authorityLoadState'], 'error', '조회 실패');
        if (!authorityAdmin) {
            setTableMessage('authorityMasterRows', 4, '권한 마스터를 불러오지 못했습니다. 새로고침해 주세요.', 'error');
            setTableMessage('authorityUserRows', 4, '사용자 권한을 불러오지 못했습니다. 새로고침해 주세요.', 'error');
        }
        showError(error);
        throw error;
    } finally {
        setSectionBusy(['authority-master', 'authority'], false);
    }
}

async function loadAuthorityMasterPage(page = adminListState.authorityMaster.page) {
    if (!authorityAdmin) return;
    const state = adminListState.authorityMaster;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('authorityMasterSearch').value.trim();
    const status = document.getElementById('authorityMasterStatusFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (status !== 'ALL') query.set('active', String(status === 'ACTIVE'));
    const payload = await requestLatestList('authorityMaster', `/api/admin/authorities?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderAuthMaster({...authorityAdmin, authorities: result.content});
    document.getElementById('authorityMasterResultCount').textContent = `${result.totalElements}건`;
    renderServerPager('authorityMaster', 'authorityMasterRows', result, loadAuthorityMasterPage);
}

async function loadAuthorityUserPage(page = adminListState.authorityUser.page) {
    if (!authorityAdmin) return;
    const state = adminListState.authorityUser;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('authorityUserSearch').value.trim();
    const type = document.getElementById('authorityGrantTypeFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (type === 'REVOKED') query.set('revoked', 'true');
    else if (type !== 'ALL') query.set('assignmentType', type);
    const payload = await requestLatestList('authorityUser', `/api/admin/user-authorities?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderAuthorityUserRows({...authorityAdmin, users: result.content});
    document.getElementById('authorityUserResultCount').textContent = `${result.totalElements}명`;
    renderServerPager('authorityUser', 'authorityUserRows', result, loadAuthorityUserPage);
}

/** 입력한 사용자, 권한, 유형, 기간과 위임 원천을 서버에 전달해 승인 이력을 추가한다. */
async function assignAuthority() {
    const username = document.getElementById('authorityUserSelect').value;
    const type = document.getElementById('authorityTypeSelect').value;
    const delegatedBy = document.getElementById('authoritySourceSelect').value;
    try {
        const payload = await requestJson(
            `/api/admin/users/${encodeURIComponent(username)}/authorities`, {
                method: 'POST',
                body: {
                    authorityId: document.getElementById('authorityIdSelect').value,
                    type,
                    validFrom: nullableValue('authorityValidFrom'),
                    validTo: nullableValue('authorityValidTo'),
                    delegatedBy: type === 'DELEGATED' ? delegatedBy : null
                }
            });
        await Promise.all([loadAuthorityView(false), refreshContext()]);
        showResult('사용자 권한 부여 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 사용자·권한·부여 유형의 최신 상태를 회수 이력으로 변경한다. */
async function revokeAuthority() {
    const username = document.getElementById('authorityUserSelect').value;
    const authorityId = document.getElementById('authorityIdSelect').value;
    const type = document.getElementById('authorityTypeSelect').value;
    if (!window.confirm(`${username} 사용자의 ${authorityId} 권한을 회수하시겠습니까?`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/users/${encodeURIComponent(username)}/authorities/`
            + `${encodeURIComponent(authorityId)}?type=${encodeURIComponent(type)}`, {
                method: 'DELETE'
            });
        await Promise.all([loadAuthorityView(false), refreshContext()]);
        showResult('사용자 권한 회수 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 권한 관리 응답을 사용자 요약 표와 대상·권한·위임 원천 선택지에 반영한다. */
function renderAuthorityView(view) {
    if (!view) {
        return;
    }
    renderAuthMaster(view);
    renderAuthorityUserRows(view);

    document.getElementById('authorityUserCount').textContent = String(view.users.length);
    document.getElementById('authorityEffectiveGrantCount').textContent = String(
        view.users.reduce((count, user) => count + user.effectiveAuthorityIds.length, 0));
    document.getElementById('authorityDelegatedCount').textContent = String(
        view.users.reduce((count, user) =>
            count + user.assignments.filter(item => item.type === 'DELEGATED').length, 0));
    document.getElementById('authorityRevokedCount').textContent = String(
        view.users.reduce((count, user) =>
            count + user.assignments.filter(item => item.status === 'REVOKED').length, 0));

    const selectedUser = document.getElementById('authorityUserSelect').value;
    const selectedSource = document.getElementById('authoritySourceSelect').value;
    const selectedAuthority = document.getElementById('authorityIdSelect').value;
    fillSelect(
        document.getElementById('authorityUserSelect'),
        view.users.map(user => ({value: user.username, text: `${user.username} (${user.organizationId})`})));
    fillSelect(
        document.getElementById('authoritySourceSelect'),
        view.users.map(user => ({value: user.username, text: `${user.username} (${user.organizationId})`})));
    fillSelect(
        document.getElementById('authorityIdSelect'),
        view.authorities.filter(authority => authority.active)
            .map(authority => ({value: authority.id, text: `${authority.id} - ${authority.name}`})));
    restoreSelectValue('authorityUserSelect', selectedUser);
    restoreSelectValue('authoritySourceSelect', selectedSource);
    restoreSelectValue('authorityIdSelect', selectedAuthority);
    toggleAuthoritySource();
}

/** 검색·유형 필터를 반영해 사용자별 유효 권한과 전체 변경 이력을 렌더링한다. */
function renderAuthorityUserRows(view) {
    if (!view) {
        return;
    }
    const search = normalizedSearchValue('authorityUserSearch');
    const typeFilter = document.getElementById('authorityGrantTypeFilter').value;
    const users = view.users.filter(user => {
        const searchable = [
            user.username,
            user.organizationId,
            ...user.effectiveAuthorityIds,
            ...user.assignments.flatMap(item => [item.authorityId, item.type, item.status, item.delegatedBy])
        ];
        const searchMatched = matchesSearch(searchable, search);
        const typeMatched = typeFilter === 'ALL'
            || (typeFilter === 'REVOKED'
                ? user.assignments.some(item => item.status === 'REVOKED')
                : user.assignments.some(item => item.type === typeFilter));
        return searchMatched && typeMatched;
    });
    const rows = document.getElementById('authorityUserRows');
    rows.replaceChildren();
    users.forEach(user => {
        const row = document.createElement('tr');
        appendCell(row, user.username);
        appendCell(row, user.organizationId);
        appendBadgeListCell(row, user.effectiveAuthorityIds, '유효 권한 없음');
        appendAssignmentHistoryCell(row, user.assignments);
        makeSelectableRow(row, 'authorityUser', user.username, () => {
            document.getElementById('authorityUserSelect').value = user.username;
        });
        rows.appendChild(row);
    });
    if (users.length === 0) {
        setTableMessage('authorityUserRows', 4, search || typeFilter !== 'ALL'
            ? '검색 조건에 맞는 사용자가 없습니다.'
            : '관리할 사용자가 없습니다.', 'empty');
    }
    document.getElementById('authorityUserResultCount').textContent = `${users.length}명`;
}

/** 권한 마스터 목록, 현재 유효 사용자 수와 관리 버전을 별도 화면에 표시한다. */
function renderAuthMaster(view) {
    if (!view) {
        return;
    }
    const search = normalizedSearchValue('authorityMasterSearch');
    const status = document.getElementById('authorityMasterStatusFilter').value;
    const authorities = view.authorities.filter(authority =>
        matchesSearch([authority.id, authority.name], search)
        && matchesActiveFilter(authority.active, status));
    const rows = document.getElementById('authorityMasterRows');
    rows.replaceChildren();
    authorities.forEach(authority => {
        const userCount = view.users.filter(user =>
            user.effectiveAuthorityIds.includes(authority.id)).length;
        const row = document.createElement('tr');
        appendCell(row, authority.id);
        appendCell(row, authority.name);
        appendStatusCell(row, authority.active ? '활성' : '비활성', authority.active ? 'success' : 'muted');
        appendCell(row, `${userCount}명`);
        makeSelectableRow(row, 'authorityMaster', authority.id, () => selectAuthMaster(authority));
        rows.appendChild(row);
    });
    if (authorities.length === 0) {
        setTableMessage('authorityMasterRows', 4, search || status !== 'ALL'
            ? '검색 조건에 맞는 권한이 없습니다.'
            : '등록된 권한 마스터가 없습니다.', 'empty');
    }
    document.getElementById('authorityMasterResultCount').textContent = `${authorities.length}건`;
    document.getElementById('authorityVersion').textContent = `권한 버전 ${view.version}`;
}

/** 선택한 권한 마스터 행의 수정 가능 값을 입력 폼으로 복사한다. */
function selectAuthMaster(authority) {
    document.getElementById('authMasterId').value = authority.id;
    document.getElementById('authMasterName').value = authority.name;
    document.getElementById('authMasterActive').checked = authority.active;
}

/** 권한 마스터 편집값과 선택 행을 신규 등록 상태로 되돌린다. */
function resetAuthMasterForm() {
    selectedRows.authorityMaster = null;
    refreshSelectedRows('authorityMaster');
    document.getElementById('authMasterId').value = '';
    document.getElementById('authMasterName').value = '';
    document.getElementById('authMasterActive').checked = true;
    document.getElementById('authMasterId').focus();
}

/** 권한 마스터 검색 조건을 초기화하고 원본 목록을 다시 표시한다. */
function resetAuthorityMasterFilters() {
    document.getElementById('authorityMasterSearch').value = '';
    document.getElementById('authorityMasterStatusFilter').value = 'ALL';
    loadAuthorityMasterPage(0).catch(showError);
}

/** 사용자 권한 검색 조건을 초기화하고 전체 사용자를 다시 표시한다. */
function resetAuthorityUserFilters() {
    document.getElementById('authorityUserSearch').value = '';
    document.getElementById('authorityGrantTypeFilter').value = 'ALL';
    loadAuthorityUserPage(0).catch(showError);
}

/** 권한 부여 폼을 안전한 직접 부여 기본값으로 되돌린다. */
function resetAuthorityGrantForm() {
    selectedRows.authorityUser = null;
    refreshSelectedRows('authorityUser');
    selectFirstOption('authorityUserSelect');
    selectFirstOption('authorityIdSelect');
    document.getElementById('authorityTypeSelect').value = 'DIRECT';
    selectFirstOption('authoritySourceSelect');
    document.getElementById('authorityValidFrom').value = toLocalDateInput(new Date());
    document.getElementById('authorityValidTo').value = '2099-12-31';
    toggleAuthoritySource();
    document.getElementById('authorityUserSelect').focus();
}

/** 권한 마스터 입력값을 저장하고 사용자·메뉴·프로그램 선택지와 현재 세션을 다시 계산한다. */
async function saveAuthMaster() {
    const authorityId = document.getElementById('authMasterId').value.trim();
    if (!authorityId) {
        showResult('권한 마스터 저장값 확인', {message: '권한 ID를 입력해 주세요.'});
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/authorities/${encodeURIComponent(authorityId)}`, {
                method: 'POST',
                body: {
                    name: document.getElementById('authMasterName').value,
                    active: document.getElementById('authMasterActive').checked
                }
            });
        await Promise.all([
            loadAuthorityView(false),
            loadMenuView(false),
            loadProgramView(false),
            refreshContext()
        ]);
        showResult('권한 마스터 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 비활성·미사용 권한 마스터를 안전 삭제 API로 제거한다. */
async function deleteAuthMaster() {
    const authorityId = document.getElementById('authMasterId').value.trim();
    if (!authorityId || selectedRows.authorityMaster !== authorityId) {
        showAdminToast('삭제할 기존 권한 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${authorityId} 권한을 삭제하시겠습니까? 연결 데이터가 있으면 서버가 거부합니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(`/api/admin/authorities/${encodeURIComponent(authorityId)}`, {
            method: 'DELETE'
        });
        resetAuthMasterForm();
        await Promise.all([loadAuthorityView(false), loadMenuView(false), loadProgramView(false), refreshContext()]);
        showResult('권한 마스터 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 권한 이력 한 건을 부여 유형, 상태, 순서, 기간과 위임 원천이 보이는 문자열로 변환한다. */
function formatAssignment(item) {
    const source = item.delegatedBy ? `, 원천=${item.delegatedBy}` : '';
    return `${item.authorityId}/${item.type}/${item.status}`
        + ` (#${item.sequence}, ${item.validFrom}~${item.validTo}${source})`;
}

/** 직접 부여일 때는 필요 없는 위임 원천 선택을 잠그고 위임일 때만 입력을 허용한다. */
function toggleAuthoritySource() {
    const delegated = document.getElementById('authorityTypeSelect').value === 'DELEGATED';
    document.getElementById('authoritySourceSelect').disabled = !delegated;
}

/** 전체 메뉴 속성과 권한별 직접 메뉴 매핑을 조회해 메뉴 관리 화면을 갱신한다. */
async function loadMenuView(show = true) {
    try {
        const payload = await requestJson('/api/admin/menu-view');
        menuAdmin = payload.data;
        renderMenuView(menuAdmin);
        await loadMenuPage();
        if (show) {
            showResult('메뉴 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        showError(error);
        throw error;
    }
}

/** 메뉴 입력값을 저장하고 변경된 메뉴 트리와 관리 목록을 서버 최종 상태로 다시 조회한다. */
async function saveMenu() {
    const menuId = document.getElementById('menuIdInput').value.trim();
    if (!menuId) {
        showResult('메뉴 저장값 확인', {message: '메뉴 ID를 입력해 주세요.'});
        return;
    }
    try {
        const payload = await requestJson(`/api/admin/menus/${encodeURIComponent(menuId)}`, {
            method: 'POST',
            body: {
                parentId: nullableValue('menuParentSelect'),
                name: document.getElementById('menuNameInput').value,
                path: nullableValue('menuPathInput'),
                sortOrder: numberValue('menuSortInput'),
                active: document.getElementById('menuActiveInput').checked,
                displayed: document.getElementById('menuDisplayedInput').checked,
                area: document.getElementById('menuAreaSelect').value,
                publicMenu: document.getElementById('menuPublicInput').checked
            }
        });
        await Promise.all([loadMenuView(false), refreshContext()]);
        showResult('메뉴 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 비활성·숨김 말단 메뉴를 삭제하고 서버의 매핑 정리 결과를 다시 읽는다. */
async function deleteMenu() {
    const menuId = document.getElementById('menuIdInput').value.trim();
    if (!menuId || selectedRows.menu !== menuId) {
        showAdminToast('삭제할 기존 메뉴 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${menuId} 메뉴를 삭제하시겠습니까? 하위 메뉴가 있거나 활성 상태면 삭제되지 않습니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(`/api/admin/menus/${encodeURIComponent(menuId)}`, {
            method: 'DELETE'
        });
        await Promise.all([loadMenuView(false), loadProgramView(false), refreshContext()]);
        showResult('메뉴 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 권한과 메뉴 사이의 직접 접근 허용 여부를 저장하고 메뉴 트리를 다시 계산한다. */
async function saveMenuGrant() {
    const authorityId = document.getElementById('menuAuthoritySelect').value;
    const menuId = document.getElementById('menuGrantSelect').value;
    try {
        const payload = await requestJson(
            `/api/admin/authorities/${encodeURIComponent(authorityId)}`
            + `/menus/${encodeURIComponent(menuId)}`, {
                method: 'PUT',
                body: {granted: document.getElementById('menuGrantedInput').checked}
            });
        await Promise.all([loadMenuView(false), refreshContext()]);
        showResult('권한-메뉴 매핑 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 메뉴 관리 응답을 목록 표, 메뉴 편집 상위 선택지와 권한 매핑 선택지에 반영한다. */
function renderMenuView(view) {
    const rows = document.getElementById('menuRows');
    rows.replaceChildren();
    view.menus.forEach(menu => {
        const row = document.createElement('tr');
        appendCell(row, menu.id);
        appendCell(row, menu.parentId || '-');
        appendCell(row, menu.name);
        appendCell(row, menu.path || '-');
        appendCell(row, String(menu.sortOrder));
        appendCell(row, menuState(menu));
        row.addEventListener('click', () => selectMenu(menu));
        rows.appendChild(row);
    });

    fillSelect(
        document.getElementById('menuParentSelect'),
        [{value: '', text: '상위 메뉴 없음'}, ...view.menus.map(menu => ({
            value: menu.id,
            text: `${menu.id} - ${menu.name}`
        }))]);
    fillSelect(
        document.getElementById('menuGrantSelect'),
        view.menus.map(menu => ({value: menu.id, text: `${menu.id} - ${menu.name}`})));
    fillSelect(
        document.getElementById('menuAuthoritySelect'),
        view.authorities.map(authority => ({
            value: authority.id,
            text: `${authority.id} - ${authority.name}`
        })));
    syncMenuGrant();
}

async function loadMenuPage(page = adminListState.menu.page) {
    if (!menuAdmin) return;
    const state = adminListState.menu;
    state.page = page;
    const payload = await requestLatestList('menu', `/api/admin/menus?${pagedQuery(state)}`);
    if (!payload) return;
    const result = payload.data;
    const rows = document.getElementById('menuRows');
    rows.replaceChildren();
    result.content.forEach(menu => {
        const row = document.createElement('tr');
        appendCell(row, menu.id);
        appendCell(row, menu.parentId || '-');
        appendCell(row, menu.name);
        appendCell(row, menu.path || '-');
        appendCell(row, String(menu.sortOrder));
        appendCell(row, menuState(menu));
        makeSelectableRow(row, 'menu', menu.id, () => selectMenu(menu));
        rows.appendChild(row);
    });
    if (!result.content.length) setTableMessage('menuRows', 6, '등록된 메뉴가 없습니다.', 'empty');
    renderServerPager('menu', 'menuRows', result, loadMenuPage);
}

/** 선택한 메뉴 행의 모든 편집 가능 속성을 입력 폼에 복사한다. */
function selectMenu(menu) {
    document.getElementById('menuIdInput').value = menu.id;
    document.getElementById('menuParentSelect').value = menu.parentId || '';
    document.getElementById('menuNameInput').value = menu.name;
    document.getElementById('menuPathInput').value = menu.path || '';
    document.getElementById('menuSortInput').value = menu.sortOrder;
    document.getElementById('menuActiveInput').checked = menu.active;
    document.getElementById('menuDisplayedInput').checked = menu.displayed;
    document.getElementById('menuAreaSelect').value = menu.area;
    document.getElementById('menuPublicInput').checked = menu.publicMenu;
    document.getElementById('menuGrantSelect').value = menu.id;
    syncMenuGrant();
}

/** 메뉴의 활성, 화면 표시와 공용 접근 속성을 한눈에 볼 수 있는 요약 문자열로 만든다. */
function menuState(menu) {
    return [
        menu.area === 'ADMIN' ? '관리자용' : '사용자용',
        menu.active ? '활성' : '비활성',
        menu.displayed ? '표시' : '숨김',
        menu.publicMenu ? '공용' : '권한 필요'
    ].join(' / ');
}

/** 현재 선택한 권한과 메뉴가 이미 직접 연결되어 있는지 체크박스에 반영한다. */
function syncMenuGrant() {
    if (!menuAdmin) {
        return;
    }
    const authorityId = document.getElementById('menuAuthoritySelect').value;
    const menuId = document.getElementById('menuGrantSelect').value;
    const grants = menuAdmin.grants[authorityId] || [];
    document.getElementById('menuGrantedInput').checked = grants.includes(menuId);
}

/** 프로그램 마스터, 기능과 권한별 기능 매핑 원본을 조회해 프로그램 관리 화면을 갱신한다. */
async function loadProgramView(show = true) {
    setLoadState(['programLoadState'], 'loading', '불러오는 중');
    setSectionBusy(['program'], true);
    if (!programAdmin) {
        setTableMessage('programRows', 5, '프로그램 마스터를 불러오는 중입니다.', 'loading');
        setTableMessage('programActionRows', 8, '프로그램 기능을 불러오는 중입니다.', 'loading');
    }
    try {
        const payload = await requestJson('/api/admin/program-view');
        programAdmin = payload.data;
        renderProgramView(programAdmin);
        await Promise.all([loadProgramPage(), loadProgramActionPage()]);
        setLoadState(['programLoadState'], 'ready', '최신 데이터');
        refreshDashboardSummary();
        if (show) {
            showResult('프로그램 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        setLoadState(['programLoadState'], 'error', '조회 실패');
        if (!programAdmin) {
            setTableMessage('programRows', 5, '프로그램 마스터를 불러오지 못했습니다. 새로고침해 주세요.', 'error');
            setTableMessage('programActionRows', 8, '프로그램 기능을 불러오지 못했습니다. 새로고침해 주세요.', 'error');
        }
        showError(error);
        throw error;
    } finally {
        setSectionBusy(['program'], false);
    }
}

async function loadProgramPage(page = adminListState.program.page) {
    if (!programAdmin) return;
    const state = adminListState.program;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('programSearch').value.trim();
    const status = document.getElementById('programStatusFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (status !== 'ALL') query.set('active', String(status === 'ACTIVE'));
    const payload = await requestLatestList('program', `/api/admin/programs?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderProgramRows({...programAdmin, programs: result.content});
    document.getElementById('programResultCount').textContent = `${result.totalElements}건`;
    renderServerPager('program', 'programRows', result, loadProgramPage);
}

async function loadProgramActionPage(page = adminListState.programAction.page) {
    if (!programAdmin) return;
    const state = adminListState.programAction;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('programActionSearch').value.trim();
    const status = document.getElementById('programActionStatusFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (status === 'UNMAPPED') query.set('unmapped', 'true');
    else if (status !== 'ALL') query.set('active', String(status === 'ACTIVE'));
    const payload = await requestLatestList('programAction', `/api/admin/program-actions?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderActionRows({...programAdmin, actions: result.content});
    document.getElementById('programActionResultCount').textContent = `${result.totalElements}건`;
    renderServerPager('programAction', 'programActionRows', result, loadProgramActionPage);
}

/** 프로그램 마스터 입력값을 저장하고 기능 판정용 부트스트랩을 다시 조회한다. */
async function saveProgram() {
    const programId = document.getElementById('programIdInput').value.trim();
    if (!programId) {
        showResult('프로그램 저장값 확인', {message: '프로그램 ID를 입력해 주세요.'});
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/programs/${encodeURIComponent(programId)}`, {
                method: 'POST',
                body: {
                    name: document.getElementById('programNameInput').value,
                    description: document.getElementById('programDescInput').value,
                    screen: document.getElementById('programScreenSelect').value,
                    active: document.getElementById('programActiveInput').checked
                }
            });
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('프로그램 마스터 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 비활성 프로그램과 소속 기능·권한 매핑을 서버 정책에 따라 정리한다. */
async function deleteProgram() {
    const programId = document.getElementById('programIdInput').value.trim();
    if (!programId || selectedRows.program !== programId) {
        showAdminToast('삭제할 기존 프로그램 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${programId} 프로그램을 삭제하시겠습니까? 활성 프로그램은 삭제되지 않습니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(`/api/admin/programs/${encodeURIComponent(programId)}`, {
            method: 'DELETE'
        });
        resetProgramForm();
        resetActionForm();
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('프로그램 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 메뉴·프로그램·기능과 화면 컴포넌트 입력값을 프로그램 기능 마스터로 저장한다. */
async function saveAction() {
    const menuId = document.getElementById('actionMenuSelect').value;
    const programId = document.getElementById('actionProgramSelect').value;
    const actionId = document.getElementById('actionIdInput').value.trim();
    if (!menuId || !programId || !actionId) {
        showResult('프로그램 기능 저장값 확인', {
            message: '메뉴, 프로그램과 기능 ID를 모두 입력해 주세요.'
        });
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/program-actions/${encodeURIComponent(menuId)}`
            + `/${encodeURIComponent(programId)}/${encodeURIComponent(actionId)}`, {
                method: 'POST',
                body: {
                    label: document.getElementById('actionLabelInput').value,
                    componentId: nullableValue('actionComponentInput'),
                    sortOrder: numberValue('actionSortInput'),
                    active: document.getElementById('actionActiveInput').checked
                }
            });
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('프로그램 기능 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 비활성 프로그램 기능과 모든 권한 매핑을 안전 삭제한다. */
async function deleteAction() {
    const menuId = document.getElementById('actionMenuSelect').value;
    const programId = document.getElementById('actionProgramSelect').value;
    const actionId = document.getElementById('actionIdInput').value.trim();
    const key = [menuId, programId, actionId].join('|');
    if (!actionId || selectedRows.programAction !== key) {
        showAdminToast('삭제할 기존 프로그램 기능 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${programId}/${actionId} 기능을 삭제하시겠습니까? 활성 기능은 삭제되지 않습니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/program-actions/${encodeURIComponent(menuId)}`
            + `/${encodeURIComponent(programId)}/${encodeURIComponent(actionId)}`, {
                method: 'DELETE'
            });
        resetActionForm();
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('프로그램 기능 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택한 권한과 프로그램 기능의 실행 허용 여부를 저장하고 현재 세션 기능을 다시 계산한다. */
async function saveActionGrant() {
    const authorityId = document.getElementById('actionAuthoritySelect').value;
    const action = parseActionValue(document.getElementById('actionGrantSelect').value);
    if (!authorityId || !action) {
        showResult('권한-기능 저장값 확인', {message: '권한과 프로그램 기능을 선택해 주세요.'});
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/authorities/${encodeURIComponent(authorityId)}`
            + `/program-actions/${encodeURIComponent(action.menuId)}`
            + `/${encodeURIComponent(action.programId)}/${encodeURIComponent(action.actionId)}`, {
                method: 'PUT',
                body: {granted: document.getElementById('actionGrantedInput').checked}
            });
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('권한-프로그램 기능 매핑 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 프로그램 관리 응답을 마스터·기능 표와 모든 편집·매핑 선택지에 반영한다. */
function renderProgramView(view) {
    if (!view) {
        return;
    }
    renderProgramRows(view);
    renderActionRows(view);
    document.getElementById('programVersion').textContent = `프로그램 버전 ${view.version}`;
    document.getElementById('programTotalCount').textContent = String(view.programs.length);
    document.getElementById('programActiveCount').textContent = String(
        view.programs.filter(program => program.active).length);
    document.getElementById('programActionCount').textContent = String(view.actions.length);
    document.getElementById('programGrantCount').textContent = String(
        Object.values(view.grants || {}).reduce((count, grants) => count + grants.length, 0));

    fillSelect(
        document.getElementById('actionMenuSelect'),
        view.menus.map(menu => ({
            value: menu.id,
            text: `${menu.id} - ${menu.name}${menu.active ? '' : ' (비활성)'}`
        })));
    fillSelect(
        document.getElementById('actionProgramSelect'),
        view.programs.map(program => ({
            value: program.id,
            text: `${program.id} - ${program.name}${program.active ? '' : ' (비활성)'}`
        })));
    fillSelect(
        document.getElementById('actionAuthoritySelect'),
        view.authorities.map(authority => ({
            value: authority.id,
            text: `${authority.id} - ${authority.name}`
        })));
    fillSelect(
        document.getElementById('actionGrantSelect'),
        view.actions.map(action => ({
            value: actionKeyValue(action),
            text: `${action.programId} / ${action.actionId} - ${action.label}`
        })));
    syncActionGrant();
}

/** 프로그램 마스터 표를 만들고 행 선택 시 해당 프로그램을 편집 폼에 표시한다. */
function renderProgramRows(view) {
    if (!view) {
        return;
    }
    const search = normalizedSearchValue('programSearch');
    const status = document.getElementById('programStatusFilter').value;
    const programs = view.programs.filter(program =>
        matchesSearch([program.id, program.name, program.description], search)
        && matchesActiveFilter(program.active, status));
    const rows = document.getElementById('programRows');
    rows.replaceChildren();
    programs.forEach(program => {
        const row = document.createElement('tr');
        appendCell(row, program.id);
        appendCell(row, program.name);
        appendCell(row, program.description || '-');
        appendCell(row, `${view.actions.filter(action => action.programId === program.id).length}개`);
        appendStatusCell(row, program.active ? '활성' : '비활성', program.active ? 'success' : 'muted');
        makeSelectableRow(row, 'program', program.id, () => selectProgram(program));
        rows.appendChild(row);
    });
    if (programs.length === 0) {
        setTableMessage('programRows', 5, search || status !== 'ALL'
            ? '검색 조건에 맞는 프로그램이 없습니다.'
            : '등록된 프로그램이 없습니다.', 'empty');
    }
    document.getElementById('programResultCount').textContent = `${programs.length}건`;
}

/** 프로그램 기능 표를 만들고 행 선택 시 기능 편집과 권한 매핑 대상을 함께 선택한다. */
function renderActionRows(view) {
    if (!view) {
        return;
    }
    const search = normalizedSearchValue('programActionSearch');
    const status = document.getElementById('programActionStatusFilter').value;
    const actions = view.actions.filter(action => {
        const mappedCount = actionMappedAuthorities(view, action).length;
        const statusMatched = status === 'ALL'
            || (status === 'ACTIVE' && action.active)
            || (status === 'INACTIVE' && !action.active)
            || (status === 'UNMAPPED' && mappedCount === 0);
        return matchesSearch([
            action.menuId,
            action.programId,
            action.actionId,
            action.label,
            action.componentId
        ], search) && statusMatched;
    });
    const rows = document.getElementById('programActionRows');
    rows.replaceChildren();
    actions.forEach(action => {
        const row = document.createElement('tr');
        appendCell(row, action.menuId);
        appendCell(row, action.programId);
        appendCell(row, action.actionId);
        appendCell(row, action.label);
        appendCell(row, action.componentId || '-');
        appendCell(row, String(action.sortOrder));
        const mapped = actionMappedAuthorities(view, action);
        appendStatusCell(row, `${mapped.length}개 권한`, mapped.length > 0 ? 'info' : 'warning');
        appendStatusCell(row, action.active ? '활성' : '비활성', action.active ? 'success' : 'muted');
        makeSelectableRow(row, 'programAction', actionKeyValue(action), () => selectAction(action));
        rows.appendChild(row);
    });
    if (actions.length === 0) {
        setTableMessage('programActionRows', 8, search || status !== 'ALL'
            ? '검색 조건에 맞는 프로그램 기능이 없습니다.'
            : '등록된 프로그램 기능이 없습니다.', 'empty');
    }
    document.getElementById('programActionResultCount').textContent = `${actions.length}건`;
}

/** 선택한 프로그램 마스터 행을 수정 폼으로 복사한다. */
function selectProgram(program) {
    document.getElementById('programIdInput').value = program.id;
    document.getElementById('programNameInput').value = program.name;
    document.getElementById('programDescInput').value = program.description;
    document.getElementById('programActiveInput').checked = program.active;
    document.getElementById('programScreenSelect').value = program.screen || 'NONE';
}

/** 선택한 프로그램 기능 행을 수정 폼과 권한 매핑 선택지로 복사한다. */
function selectAction(action) {
    document.getElementById('actionMenuSelect').value = action.menuId;
    document.getElementById('actionProgramSelect').value = action.programId;
    document.getElementById('actionIdInput').value = action.actionId;
    document.getElementById('actionLabelInput').value = action.label;
    document.getElementById('actionComponentInput').value = action.componentId || '';
    document.getElementById('actionSortInput').value = action.sortOrder;
    document.getElementById('actionActiveInput').checked = action.active;
    document.getElementById('actionGrantSelect').value = actionKeyValue(action);
    syncActionGrant();
}

/** 프로그램 마스터 검색 조건을 초기화한다. */
function resetProgramFilters() {
    document.getElementById('programSearch').value = '';
    document.getElementById('programStatusFilter').value = 'ALL';
    loadProgramPage(0).catch(showError);
}

/** 프로그램 기능 검색 조건을 초기화한다. */
function resetProgramActionFilters() {
    document.getElementById('programActionSearch').value = '';
    document.getElementById('programActionStatusFilter').value = 'ALL';
    loadProgramActionPage(0).catch(showError);
}

/** 프로그램 마스터 편집 폼과 선택 행을 신규 등록 상태로 되돌린다. */
function resetProgramForm() {
    selectedRows.program = null;
    refreshSelectedRows('program');
    document.getElementById('programIdInput').value = '';
    document.getElementById('programNameInput').value = '';
    document.getElementById('programDescInput').value = '';
    document.getElementById('programActiveInput').checked = true;
    document.getElementById('programScreenSelect').value = 'NONE';
    document.getElementById('programIdInput').focus();
}

/** 프로그램 기능 편집 폼과 매핑 대상을 신규 등록 상태로 되돌린다. */
function resetActionForm() {
    selectedRows.programAction = null;
    refreshSelectedRows('programAction');
    selectFirstOption('actionMenuSelect');
    selectFirstOption('actionProgramSelect');
    document.getElementById('actionIdInput').value = '';
    document.getElementById('actionLabelInput').value = '';
    document.getElementById('actionComponentInput').value = '';
    document.getElementById('actionSortInput').value = '10';
    document.getElementById('actionActiveInput').checked = true;
    document.getElementById('actionIdInput').focus();
}

/** 현재 선택 권한의 복합 기능 키 매핑 여부를 체크박스와 안내 문구에 반영한다. */
function syncActionGrant() {
    if (!programAdmin) {
        return;
    }
    const authorityId = document.getElementById('actionAuthoritySelect').value;
    const selected = parseActionValue(document.getElementById('actionGrantSelect').value);
    const grants = programAdmin.grants[authorityId] || [];
    const granted = Boolean(selected && grants.some(action =>
        action.menuId === selected.menuId
        && action.programId === selected.programId
        && action.actionId === selected.actionId));
    document.getElementById('actionGrantedInput').checked = granted;
    document.getElementById('actionGrantState').textContent = selected
        ? `${authorityId}의 ${selected.actionId} 기능은 현재 ${granted ? '허용' : '미허용'} 상태입니다.`
        : '권한과 기능을 선택하면 현재 매핑 상태를 표시합니다.';
    const summary = document.getElementById('actionMappingSummary');
    summary.replaceChildren();
    if (!selected) {
        summary.textContent = '선택 기능에 연결된 권한을 이곳에 표시합니다.';
        return;
    }
    const action = programAdmin.actions.find(item => actionKeyValue(item) === actionKeyValue(selected));
    const authorities = action ? actionMappedAuthorities(programAdmin, action) : [];
    const label = document.createElement('strong');
    label.textContent = `현재 매핑 ${authorities.length}개`;
    summary.appendChild(label);
    if (authorities.length === 0) {
        const empty = document.createElement('span');
        empty.textContent = '이 기능에 직접 연결된 권한이 없습니다.';
        summary.appendChild(empty);
        return;
    }
    const badges = document.createElement('div');
    badges.className = 'inline-badges';
    authorities.forEach(item => badges.appendChild(createStatusBadge(item.name, 'info', item.id)));
    summary.appendChild(badges);
}

/** 프로그램 기능 복합 키를 기준으로 직접 연결된 모든 권한을 반환한다. */
function actionMappedAuthorities(view, action) {
    const key = actionKeyValue(action);
    return view.authorities.filter(authority =>
        (view.grants[authority.id] || []).some(grant => actionKeyValue(grant) === key));
}

/** 프로그램 기능의 복합 키를 HTML 선택값으로 안전하게 직렬화한다. */
function actionKeyValue(action) {
    return [action.menuId, action.programId, action.actionId].join('|');
}

/** HTML 선택값을 메뉴·프로그램·기능 복합 키 객체로 복원한다. */
function parseActionValue(value) {
    const [menuId, programId, actionId] = value.split('|');
    return menuId && programId && actionId ? {menuId, programId, actionId} : null;
}

/** 프로젝트 마스터와 참여자 현황을 조회해 운영 화면을 갱신한다. */
async function loadProjectView(show = true) {
    setLoadState(['projectLoadState'], 'loading', '불러오는 중');
    setSectionBusy(['project'], true);
    if (!projectAdmin) {
        setTableMessage('projectRows', 7, '프로젝트 현황을 불러오는 중입니다.', 'loading');
    }
    try {
        const payload = await requestJson('/api/admin/project-view');
        projectAdmin = payload.data;
        renderProjectView(projectAdmin);
        await loadProjectPage();
        setLoadState(['projectLoadState'], 'ready', '최신 데이터');
        refreshDashboardSummary();
        if (show) {
            showResult('프로젝트 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        setLoadState(['projectLoadState'], 'error', '조회 실패');
        if (!projectAdmin) {
            setTableMessage('projectRows', 7, '프로젝트 현황을 불러오지 못했습니다. 새로고침해 주세요.', 'error');
        }
        showError(error);
        throw error;
    } finally {
        setSectionBusy(['project'], false);
    }
}

async function loadProjectPage(page = adminListState.project.page) {
    if (!projectAdmin) return;
    const state = adminListState.project;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('projectSearch').value.trim();
    const status = document.getElementById('projectStatusFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (status === 'INACTIVE') query.set('active', 'false');
    else if (status !== 'ALL') query.set('statuses', status);
    const payload = await requestLatestList('project', `/api/admin/projects?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderProjectRows({...projectAdmin, projects: result.projects});
    document.getElementById('projectResultCount').textContent = `${result.totalElements}건`;
    renderServerPager('project', 'projectRows', result, loadProjectPage);
}

/** 프로젝트 마스터 입력값과 현재 버전을 저장한다. */
async function saveProject() {
    const projectId = document.getElementById('projectIdInput').value.trim();
    if (!projectId) {
        showResult('프로젝트 저장값 확인', {message: '프로젝트 ID를 입력해 주세요.'});
        return;
    }
    const current = projectAdmin?.projects.find(item => item.project.id === projectId)?.project;
    const nextStatus = document.getElementById('projectStatusSelect').value;
    if (current && current.status !== nextStatus
            && !document.getElementById('projectStatusReasonInput').value.trim()) {
        showAdminToast('프로젝트 상태를 변경할 때는 상태 변경 사유가 필요합니다.', true);
        document.getElementById('projectStatusReasonInput').focus();
        return;
    }
    try {
        const payload = await requestJson(`/api/admin/projects/${encodeURIComponent(projectId)}`, {
            method: 'POST',
            body: {
                name: document.getElementById('projectNameInput').value,
                description: document.getElementById('projectDescInput').value,
                status: document.getElementById('projectStatusSelect').value,
                startDate: nullableValue('projectStartInput'),
                endDate: nullableValue('projectEndInput'),
                ownerUsername: document.getElementById('projectOwnerSelect').value,
                active: document.getElementById('projectActiveInput').checked,
                version: numberValue('projectVersionInput'),
                statusChangeReason: nullableValue('projectStatusReasonInput')
            }
        });
        await loadProjectView(false);
        selectProject(projectAdmin.projects.find(item => item.project.id === payload.data.id));
        showResult('프로젝트 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택 프로젝트에 사용자의 역할과 활성 상태를 저장한다. */
async function saveProjectMember() {
    const projectId = document.getElementById('projectIdInput').value.trim();
    const username = document.getElementById('projectMemberUserSelect').value;
    if (!projectId || !username || numberValue('projectVersionInput') === 0) {
        showResult('참여자 저장값 확인', {message: '저장된 프로젝트와 참여 사용자를 선택해 주세요.'});
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(username)}`, {
                method: 'PUT',
                body: {
                    role: document.getElementById('projectMemberRoleSelect').value,
                    active: document.getElementById('projectMemberActiveInput').checked
                }
            });
        await loadProjectView(false);
        selectProject(projectAdmin.projects.find(item => item.project.id === projectId));
        showResult('프로젝트 참여자 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택 프로젝트에서 오너가 아닌 참여자를 제거한다. */
async function removeProjectMember() {
    const projectId = document.getElementById('projectIdInput').value.trim();
    const username = document.getElementById('projectMemberUserSelect').value;
    if (!projectId || !username || numberValue('projectVersionInput') === 0) {
        showResult('참여자 제거값 확인', {message: '저장된 프로젝트와 참여 사용자를 선택해 주세요.'});
        return;
    }
    if (!window.confirm(`${projectId} 프로젝트에서 ${username} 참여자를 제거하시겠습니까?`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/projects/${encodeURIComponent(projectId)}/members/${encodeURIComponent(username)}`,
            {method: 'DELETE'});
        await loadProjectView(false);
        selectProject(projectAdmin.projects.find(item => item.project.id === projectId));
        showResult('프로젝트 참여자 제거 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 프로젝트 표, 상태 요약과 사용자 선택지를 조회 응답으로 다시 구성한다. */
function renderProjectView(view) {
    if (!view) {
        return;
    }
    renderProjectRows(view);
    document.getElementById('projectTotalCount').textContent = String(view.projects.length);
    document.getElementById('projectActiveCount').textContent = String(
        view.projects.filter(item => item.project.active && item.project.status === 'ACTIVE').length);
    document.getElementById('projectMemberCount').textContent = String(
        view.projects.reduce((count, item) =>
            count + item.members.filter(member => member.active).length, 0));
    document.getElementById('projectCompletedCount').textContent = String(
        view.projects.filter(item => item.project.status === 'COMPLETED').length);
    const ownerValue = document.getElementById('projectOwnerSelect').value;
    const memberValue = document.getElementById('projectMemberUserSelect').value;
    const userOptions = view.users.map(user => ({
        value: user.username,
        text: `${user.username} (${user.organizationId})`
    }));
    fillSelect(document.getElementById('projectOwnerSelect'), userOptions);
    fillSelect(document.getElementById('projectMemberUserSelect'), userOptions);
    fillSelect(document.getElementById('projectNewOwnerSelect'), userOptions);
    restoreSelectValue('projectOwnerSelect', ownerValue);
    restoreSelectValue('projectMemberUserSelect', memberValue);
}

/** 검색·상태 필터를 반영해 프로젝트 운영 목록을 렌더링한다. */
function renderProjectRows(view) {
    if (!view) {
        return;
    }
    const search = normalizedSearchValue('projectSearch');
    const status = document.getElementById('projectStatusFilter').value;
    const projects = view.projects.filter(detail => {
        const project = detail.project;
        const statusMatched = status === 'ALL'
            || (status === 'INACTIVE' ? !project.active : project.status === status);
        return statusMatched && matchesSearch([
            project.id,
            project.name,
            project.description,
            project.ownerUsername,
            ...detail.members.map(member => member.username)
        ], search);
    });
    const rows = document.getElementById('projectRows');
    rows.replaceChildren();
    projects.forEach(detail => {
        const project = detail.project;
        const row = document.createElement('tr');
        appendCell(row, project.id);
        appendCell(row, project.name);
        appendStatusCell(row, project.active ? projectStatusLabel(project.status) : '비활성',
            projectStatusTone(project));
        appendCell(row, `${project.startDate} ~ ${project.endDate}`);
        appendCell(row, project.ownerUsername);
        appendCell(row, String(detail.members.filter(member => member.active).length));
        appendCell(row, `v${project.version}`);
        makeSelectableRow(row, 'project', project.id, () => selectProject(detail));
        rows.appendChild(row);
    });
    if (projects.length === 0) {
        setTableMessage('projectRows', 7, search || status !== 'ALL'
            ? '검색 조건에 맞는 프로젝트가 없습니다.'
            : '등록된 프로젝트가 없습니다.', 'empty');
    }
    document.getElementById('projectResultCount').textContent = `${projects.length}건`;
}

/** 선택한 프로젝트와 참여자 목록을 두 편집 영역에 함께 표시한다. */
function selectProject(detail) {
    if (!detail) {
        return;
    }
    const project = detail.project;
    selectedRows.project = project.id;
    refreshSelectedRows('project');
    document.getElementById('projectIdInput').value = project.id;
    document.getElementById('projectNameInput').value = project.name;
    document.getElementById('projectDescInput').value = project.description || '';
    document.getElementById('projectStatusSelect').value = project.status;
    document.getElementById('projectStartInput').value = project.startDate;
    document.getElementById('projectEndInput').value = project.endDate;
    document.getElementById('projectOwnerSelect').value = project.ownerUsername;
    document.getElementById('projectNewOwnerSelect').value = project.ownerUsername;
    document.getElementById('projectStatusReasonInput').value = '';
    document.getElementById('projectActiveInput').checked = project.active;
    document.getElementById('projectActiveInput').disabled = true;
    document.getElementById('projectActiveInput').title = '기존 프로젝트는 보관·복원 버튼으로 상태를 변경합니다.';
    document.getElementById('projectVersionInput').value = project.version;
    document.getElementById('projectSelection').textContent = `${project.id} · v${project.version}`;
    renderProjectMembers(detail.members);
    renderProjectStatusHistory(detail.statusHistory || []);
}

/** 선택 프로젝트의 참여자를 표에 렌더링하고 행 선택 시 참여자 편집값을 채운다. */
function renderProjectMembers(members) {
    const rows = document.getElementById('projectMemberRows');
    rows.replaceChildren();
    members.forEach(member => {
        const row = document.createElement('tr');
        appendCell(row, member.username);
        appendCell(row, projectRoleLabel(member.role));
        appendStatusCell(row, member.active ? '활성' : '비활성', member.active ? 'success' : 'muted');
        makeSelectableRow(row, 'projectMember', member.username, () => {
            document.getElementById('projectMemberUserSelect').value = member.username;
            document.getElementById('projectMemberRoleSelect').value = member.role;
            document.getElementById('projectMemberActiveInput').checked = member.active;
        });
        rows.appendChild(row);
    });
    if (members.length === 0) {
        const row = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 3;
        cell.textContent = '등록된 참여자가 없습니다.';
        row.appendChild(cell);
        rows.appendChild(row);
    }
}

function projectStatusLabel(status) {
    return {PLANNED: '계획', ACTIVE: '진행 중', ON_HOLD: '보류', COMPLETED: '완료'}[status] || status;
}

function projectStatusTone(project) {
    if (!project.active) {
        return 'muted';
    }
    return {PLANNED: 'info', ACTIVE: 'success', ON_HOLD: 'warning', COMPLETED: 'neutral'}[project.status]
        || 'neutral';
}

function projectRoleLabel(role) {
    return {OWNER: '책임자', MANAGER: '관리자', MEMBER: '참여자', VIEWER: '조회자'}[role] || role;
}

/** 선택 프로젝트의 상태 전이 이력을 변경자·사유·시각과 함께 표시한다. */
function renderProjectStatusHistory(history) {
    const rows = document.getElementById('projectStatusHistoryRows');
    rows.replaceChildren();
    history.forEach(item => {
        const row = document.createElement('tr');
        appendCell(row, `#${item.sequence}`);
        appendCell(row, `${item.fromStatus ? projectStatusLabel(item.fromStatus) : '생성'} → ${projectStatusLabel(item.toStatus)}`);
        appendCell(row, item.changedBy);
        appendCell(row, item.reason || '-');
        appendCell(row, formatDateTime(item.changedAt));
        rows.appendChild(row);
    });
    if (history.length === 0) {
        setTableMessage('projectStatusHistoryRows', 5, '기록된 상태 변경 이력이 없습니다.', 'empty');
    }
}

/** 현재 선택한 프로젝트의 최신 상태 이력을 서버에서 다시 조회한다. */
async function loadSelectedProjectHistory() {
    const projectId = document.getElementById('projectIdInput').value.trim();
    if (!projectId || numberValue('projectVersionInput') === 0) {
        showAdminToast('상태 이력을 조회할 프로젝트를 선택해 주세요.', true);
        return;
    }
    setTableMessage('projectStatusHistoryRows', 5, '상태 변경 이력을 불러오는 중입니다.', 'loading');
    try {
        const payload = await requestJson(
            `/api/admin/projects/${encodeURIComponent(projectId)}/status-history`);
        renderProjectStatusHistory(payload.data);
        showAdminToast('프로젝트 상태 이력을 갱신했습니다.');
    } catch (error) {
        setTableMessage('projectStatusHistoryRows', 5, '상태 변경 이력을 불러오지 못했습니다.', 'error');
        showError(error);
    }
}

/** 기대 버전과 필수 사유를 사용해 선택 프로젝트의 책임자를 이전한다. */
async function transferProjectOwner() {
    const projectId = document.getElementById('projectIdInput').value.trim();
    const version = numberValue('projectVersionInput');
    const newOwnerUsername = document.getElementById('projectNewOwnerSelect').value;
    const reason = document.getElementById('projectLifecycleReason').value.trim();
    if (!projectId || !version || !newOwnerUsername || !reason) {
        showAdminToast('프로젝트, 새 책임자와 변경 사유를 모두 확인해 주세요.', true);
        return;
    }
    if (newOwnerUsername === document.getElementById('projectOwnerSelect').value) {
        showAdminToast('현재 책임자와 다른 사용자를 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${projectId} 프로젝트 책임자를 ${newOwnerUsername}(으)로 이전하시겠습니까?`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/projects/${encodeURIComponent(projectId)}/owner-transfer`, {
                method: 'POST', body: {newOwnerUsername, version, reason}
            });
        await reloadAndSelectProject(projectId);
        document.getElementById('projectLifecycleReason').value = '';
        showResult('프로젝트 오너 이전 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 프로젝트를 물리 삭제하지 않고 보관하거나 보관 상태에서 복원한다. */
async function changeProjectArchiveState(archive) {
    const projectId = document.getElementById('projectIdInput').value.trim();
    const version = numberValue('projectVersionInput');
    const reason = document.getElementById('projectLifecycleReason').value.trim();
    if (!projectId || !version || !reason) {
        showAdminToast(`프로젝트와 ${archive ? '보관' : '복원'} 사유를 확인해 주세요.`, true);
        return;
    }
    const active = document.getElementById('projectActiveInput').checked;
    if ((archive && !active) || (!archive && active)) {
        showAdminToast(archive ? '이미 보관된 프로젝트입니다.' : '활성 프로젝트는 복원할 필요가 없습니다.', true);
        return;
    }
    if (!window.confirm(`${projectId} 프로젝트를 ${archive ? '보관' : '복원'}하시겠습니까?`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/projects/${encodeURIComponent(projectId)}/${archive ? 'archive' : 'restore'}`, {
                method: 'POST', body: {version, reason}
            });
        await reloadAndSelectProject(projectId);
        document.getElementById('projectLifecycleReason').value = '';
        showResult(`프로젝트 ${archive ? '보관' : '복원'} 성공`, payload);
    } catch (error) {
        showError(error);
    }
}

async function reloadAndSelectProject(projectId) {
    await loadProjectView(false);
    selectProject(projectAdmin.projects.find(item => item.project.id === projectId));
}

/** 프로젝트 검색 조건을 초기화하고 전체 목록을 다시 표시한다. */
function resetProjectFilters() {
    document.getElementById('projectSearch').value = '';
    document.getElementById('projectStatusFilter').value = 'ALL';
    loadProjectPage(0).catch(showError);
}

/** 프로젝트와 참여자 편집 영역을 신규 프로젝트 등록 상태로 초기화한다. */
function resetProjectForm() {
    selectedRows.project = null;
    selectedRows.projectMember = null;
    refreshSelectedRows('project');
    document.getElementById('projectIdInput').value = '';
    document.getElementById('projectNameInput').value = '';
    document.getElementById('projectDescInput').value = '';
    document.getElementById('projectStatusSelect').value = 'PLANNED';
    const today = new Date();
    const end = new Date(today);
    end.setMonth(end.getMonth() + 3);
    document.getElementById('projectStartInput').value = toLocalDateInput(today);
    document.getElementById('projectEndInput').value = toLocalDateInput(end);
    selectFirstOption('projectOwnerSelect');
    selectFirstOption('projectNewOwnerSelect');
    document.getElementById('projectStatusReasonInput').value = '';
    document.getElementById('projectLifecycleReason').value = '';
    document.getElementById('projectActiveInput').checked = true;
    document.getElementById('projectActiveInput').disabled = false;
    document.getElementById('projectActiveInput').title = '';
    document.getElementById('projectVersionInput').value = '0';
    document.getElementById('projectSelection').textContent = '신규 프로젝트';
    setTableMessage('projectMemberRows', 3, '프로젝트를 먼저 저장하거나 기존 프로젝트를 선택해 주세요.', 'empty');
    setTableMessage('projectStatusHistoryRows', 5, '프로젝트를 선택해 주세요.', 'empty');
    document.getElementById('projectIdInput').focus();
}

/** 공통코드 그룹별 건수·버전을 조회하고 선택된 그룹의 전체 항목 조회를 이어서 수행한다. */
async function loadCodeAdmin(show = true) {
    setLoadState(['commonCodeLoadState'], 'loading', '불러오는 중');
    setSectionBusy(['common-code'], true);
    try {
        const [payload, groupPayload] = await Promise.all([
            requestJson('/api/admin/common-code-view'),
            requestJson('/api/admin/common-code-groups')
        ]);
        commonCodeAdmin = payload.data;
        commonCodeGroups = groupPayload.data;
        const groupSelect = document.getElementById('commonGroupSelect');
        const selected = groupSelect.value;
        const groupCodes = Object.keys(payload.data.groups).sort();
        fillSelect(groupSelect, groupCodes.map(groupCode => ({
            value: groupCode,
            text: `${groupCode} (${payload.data.groups[groupCode]}건)`
        })));
        if (groupCodes.includes(selected)) {
            groupSelect.value = selected;
        }
        syncCommonCodeGroupForm();
        document.getElementById('commonCodeGroupCount').textContent = String(groupCodes.length);
        document.getElementById('commonCodeTotalCount').textContent = String(
            Object.values(payload.data.groups).reduce((count, itemCount) => count + itemCount, 0));
        await loadCodeGroup(false);
        setLoadState(['commonCodeLoadState'], 'ready', '최신 데이터');
        refreshDashboardSummary();
        if (show) {
            showResult('공통코드 그룹 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        setLoadState(['commonCodeLoadState'], 'error', '조회 실패');
        if (!commonCodeAdmin) {
            setTableMessage('commonCodeRows', 6, '공통코드 그룹을 불러오지 못했습니다. 다시 시도해 주세요.', 'error');
        }
        showError(error);
        throw error;
    } finally {
        setSectionBusy(['common-code'], false);
    }
}

/** 선택 그룹의 메타데이터와 시스템 보호 상태를 그룹 편집 폼에 표시한다. */
function syncCommonCodeGroupForm() {
    const groupCode = document.getElementById('commonGroupSelect').value;
    const group = commonCodeGroups.find(item => item.groupCode === groupCode);
    if (!group) {
        return;
    }
    document.getElementById('commonCodeGroupId').value = group.groupCode;
    document.getElementById('commonCodeGroupName').value = group.name;
    document.getElementById('commonCodeGroupDesc').value = group.description || '';
    document.getElementById('commonCodeGroupSort').value = group.sortOrder;
    document.getElementById('commonCodeGroupActive').checked = group.active;
    document.getElementById('commonCodeGroupSelection').textContent =
        `${group.groupCode} · ${group.system ? '시스템 보호 그룹' : '사용자 정의 그룹'}`;
}

function resetCommonCodeGroupForm() {
    document.getElementById('commonCodeGroupId').value = '';
    document.getElementById('commonCodeGroupName').value = '';
    document.getElementById('commonCodeGroupDesc').value = '';
    document.getElementById('commonCodeGroupSort').value = '100';
    document.getElementById('commonCodeGroupActive').checked = true;
    document.getElementById('commonCodeGroupSelection').textContent = '신규 코드 그룹';
    document.getElementById('commonCodeGroupId').focus();
}

/** 공통코드 그룹 기준정보를 신규 등록하거나 수정한다. */
async function saveCommonCodeGroup() {
    const groupCode = document.getElementById('commonCodeGroupId').value.trim();
    if (!groupCode) {
        showAdminToast('그룹 코드를 입력해 주세요.', true);
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/common-code-groups/${encodeURIComponent(groupCode)}`, {
                method: 'POST',
                body: {
                    name: document.getElementById('commonCodeGroupName').value,
                    description: document.getElementById('commonCodeGroupDesc').value,
                    sortOrder: numberValue('commonCodeGroupSort'),
                    active: document.getElementById('commonCodeGroupActive').checked
                }
            });
        await loadCodeAdmin(false);
        restoreSelectValue('commonGroupSelect', groupCode);
        syncCommonCodeGroupForm();
        await loadCodeGroup(false);
        showResult('공통코드 그룹 저장 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 비활성·빈 사용자 정의 그룹만 서버 정책에 따라 삭제한다. */
async function deleteCommonCodeGroup() {
    const groupCode = document.getElementById('commonCodeGroupId').value.trim();
    const selected = commonCodeGroups.find(item => item.groupCode === groupCode);
    if (!selected || document.getElementById('commonGroupSelect').value !== groupCode) {
        showAdminToast('삭제할 기존 코드 그룹을 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${groupCode} 코드 그룹을 삭제하시겠습니까? 시스템 그룹이나 항목이 있는 그룹은 삭제되지 않습니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/common-code-groups/${encodeURIComponent(groupCode)}`, {method: 'DELETE'});
        resetCommonCodeGroupForm();
        await loadCodeAdmin(false);
        showResult('공통코드 그룹 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 선택 그룹의 비활성 항목까지 포함한 전체 목록과 버전을 조회해 표와 상위 코드 선택지를 갱신한다. */
async function loadCodeGroup(show = true) {
    const groupCode = document.getElementById('commonGroupSelect').value;
    if (!groupCode) {
        commonCodeItems = [];
        renderCodeRows(commonCodeItems);
        return null;
    }
    setLoadState(['commonCodeLoadState'], 'loading', '그룹 조회 중');
    setSectionBusy(['common-code'], true);
    if (commonCodeItems.length === 0) {
        setTableMessage('commonCodeRows', 6, `${groupCode} 코드를 불러오는 중입니다.`, 'loading');
    }
    try {
        const payload = await requestJson(
            `/api/common-codes/${encodeURIComponent(groupCode)}/view?activeOnly=false`);
        commonCodeItems = payload.data.items;
        selectedRows.commonCode = null;
        renderCodeRows(commonCodeItems);
        await loadCommonCodePage();
        renderCodeParents(commonCodeItems, document.getElementById('codeValue').value);
        document.getElementById('codeVersion').textContent =
            `${groupCode} 버전: ${payload.data.version}`;
        const currentCount = commonCodeItems.filter(isCodeCurrent).length;
        document.getElementById('commonCodeActiveCount').textContent = String(currentCount);
        document.getElementById('commonCodeInactiveCount').textContent = String(
            commonCodeItems.length - currentCount);
        setLoadState(['commonCodeLoadState'], 'ready', '최신 데이터');
        if (show) {
            showResult(`${groupCode} 전체 코드 조회 성공`, payload);
        }
        return payload;
    } catch (error) {
        setLoadState(['commonCodeLoadState'], 'error', '조회 실패');
        setTableMessage('commonCodeRows', 6, `${groupCode} 코드를 불러오지 못했습니다. 다시 시도해 주세요.`, 'error');
        showError(error);
        throw error;
    } finally {
        setSectionBusy(['common-code'], false);
    }
}

async function loadCommonCodePage(page = adminListState.commonCode.page) {
    const groupCode = document.getElementById('commonGroupSelect').value;
    if (!groupCode) return;
    const state = adminListState.commonCode;
    state.page = page;
    const query = pagedQuery(state);
    const keyword = document.getElementById('commonCodeSearch').value.trim();
    const status = document.getElementById('commonCodeStatusFilter').value;
    if (keyword) query.set('keyword', keyword);
    if (status === 'CURRENT') query.set('current', 'true');
    else if (status === 'ACTIVE') query.set('active', 'true');
    else if (status === 'INACTIVE') query.set('active', 'false');
    else if (status === 'OUT_OF_PERIOD') {
        query.set('active', 'true');
        query.set('current', 'false');
    }
    const payload = await requestLatestList('commonCode',
        `/api/admin/common-codes/${encodeURIComponent(groupCode)}?${query}`);
    if (!payload) return;
    const result = payload.data;
    renderCodeRows(result.content);
    document.getElementById('commonCodeResultCount').textContent = `${result.totalElements}건`;
    renderServerPager('commonCode', 'commonCodeRows', result, loadCommonCodePage);
}

/** 공통코드 전체 속성을 저장하고 해당 그룹의 캐시 무효화 이후 최종 목록을 다시 조회한다. */
async function saveCommonCode() {
    const groupCode = document.getElementById('commonGroupSelect').value;
    try {
        const payload = await requestJson(
            `/api/common-codes/${encodeURIComponent(groupCode)}/items`, {
                method: 'POST',
                body: {
                    code: document.getElementById('codeValue').value,
                    name: document.getElementById('codeName').value,
                    parentCode: nullableValue('codeParentSelect'),
                    sortOrder: numberValue('codeSortInput'),
                    active: document.getElementById('codeActiveInput').checked,
                    validFrom: nullableValue('codeValidFrom'),
                    validTo: nullableValue('codeValidTo')
                }
            });
        await loadCodeGroup(false);
        showResult('공통코드 저장 및 캐시 갱신 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 현재 날짜를 넣은 부모·자식 2행 CSV 예시를 제공하되 사용자가 저장하기 전에는 서버를 변경하지 않는다. */
function fillCommonCodeImportTemplate() {
    const validFrom = toLocalDateInput(new Date());
    document.getElementById('commonCodeBulkCsv').value = [
        'code,name,parentCode,sortOrder,active,validFrom,validTo',
        `SAMPLE_PARENT,샘플 상위,,10,true,${validFrom},2099-12-31`,
        `SAMPLE_CHILD,샘플 하위,SAMPLE_PARENT,20,true,${validFrom},2099-12-31`
    ].join('\n');
    document.getElementById('commonCodeBulkCsv').focus();
}

/** CSV를 브라우저에서 구조화한 뒤 서버의 원자적 최대 100건 일괄 저장 API로 전달한다. */
async function importCommonCodes() {
    const groupCode = document.getElementById('commonGroupSelect').value;
    const csv = document.getElementById('commonCodeBulkCsv').value;
    if (!groupCode) {
        showAdminToast('일괄 등록할 코드 그룹을 먼저 선택해 주세요.', true);
        return;
    }
    try {
        const items = commonCodeItemsFromCsv(csv);
        if (!window.confirm(`${groupCode} 그룹에 ${items.length}건을 전체 검증 후 일괄 저장하시겠습니까?`)) {
            return;
        }
        const payload = await requestJson(
            `/api/admin/common-code-groups/${encodeURIComponent(groupCode)}/items/bulk`, {
                method: 'POST', body: {items}
            });
        await loadCodeAdmin(false);
        restoreSelectValue('commonGroupSelect', groupCode);
        await loadCodeGroup(false);
        document.getElementById('commonCodeBulkCsv').value = '';
        showResult(
            `공통코드 일괄 저장 성공 · 변경 ${payload.data.changedCount}건 · 하위 비활성 ${payload.data.cascadedCount}건`,
            payload);
    } catch (error) {
        showError(error);
    }
}

/** RFC 4180의 큰따옴표·이스케이프를 지원하며 빈 행과 선택적 헤더를 제외한 CSV 행을 만든다. */
function parseCsv(text) {
    if (!text || !text.trim()) {
        throw new Error('일괄 등록할 CSV를 입력해 주세요.');
    }
    const rows = [];
    let row = [];
    let field = '';
    let quoted = false;
    for (let index = 0; index < text.length; index += 1) {
        const char = text[index];
        if (char === '"') {
            if (quoted && text[index + 1] === '"') {
                field += '"';
                index += 1;
            } else {
                quoted = !quoted;
            }
        } else if (char === ',' && !quoted) {
            row.push(field.trim());
            field = '';
        } else if ((char === '\n' || char === '\r') && !quoted) {
            if (char === '\r' && text[index + 1] === '\n') index += 1;
            row.push(field.trim());
            if (row.some(value => value)) rows.push(row);
            row = [];
            field = '';
        } else {
            field += char;
        }
    }
    if (quoted) {
        throw new Error('CSV 큰따옴표가 닫히지 않았습니다.');
    }
    row.push(field.trim());
    if (row.some(value => value)) rows.push(row);
    return rows;
}

/** CSV 열을 공통코드 일괄 저장 요청으로 변환하고 행 번호가 포함된 입력 오류를 반환한다. */
function commonCodeItemsFromCsv(csv) {
    const rows = parseCsv(csv);
    if (rows[0]?.[0]?.toLowerCase() === 'code') rows.shift();
    if (rows.length < 1 || rows.length > 100) {
        throw new Error('CSV 데이터 행은 1~100건이어야 합니다.');
    }
    const seen = new Set();
    return rows.map((columns, index) => {
        const rowNumber = index + 1;
        if (columns.length < 2 || columns.length > 7) {
            throw new Error(`${rowNumber}행은 2~7개 열이어야 합니다.`);
        }
        const code = (columns[0] || '').trim().toUpperCase();
        const name = (columns[1] || '').trim();
        const parentCode = (columns[2] || '').trim().toUpperCase() || null;
        const sortOrderText = (columns[3] || '10').trim();
        const activeText = (columns[4] || 'true').trim().toLowerCase();
        if (!/^[A-Z0-9_]{1,30}$/.test(code) || !name) {
            throw new Error(`${rowNumber}행의 코드 또는 코드명을 확인해 주세요.`);
        }
        if (seen.has(code)) {
            throw new Error(`${rowNumber}행에 중복 코드 ${code}가 있습니다.`);
        }
        seen.add(code);
        const sortOrder = Number(sortOrderText);
        if (!Number.isInteger(sortOrder) || sortOrder < 0 || sortOrder > 9999) {
            throw new Error(`${rowNumber}행의 정렬 순서는 0~9999 정수여야 합니다.`);
        }
        const activeValues = {true: true, y: true, yes: true, '1': true,
            false: false, n: false, no: false, '0': false};
        if (!(activeText in activeValues)) {
            throw new Error(`${rowNumber}행의 active는 true/false, Y/N 또는 1/0이어야 합니다.`);
        }
        return {
            code, name, parentCode, sortOrder, active: activeValues[activeText],
            validFrom: (columns[5] || '').trim() || null,
            validTo: (columns[6] || '').trim() || null
        };
    });
}

/** 선택 코드와 모든 하위 코드를 하나의 서버 변경 단위로 비활성화한다. */
async function deactivateCommonCode() {
    const groupCode = document.getElementById('commonGroupSelect').value;
    const code = document.getElementById('codeValue').value.trim();
    if (!groupCode || !code || selectedRows.commonCode !== code) {
        showAdminToast('비활성화할 기존 코드 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${groupCode}/${code} 코드와 모든 하위 코드를 비활성화하시겠습니까?`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/common-code-groups/${encodeURIComponent(groupCode)}`
            + `/items/${encodeURIComponent(code)}/deactivate`, {method: 'POST'});
        await loadCodeGroup(false);
        showResult('공통코드 계층 비활성화 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 사용자 정의 그룹의 비활성 말단 코드만 안전 삭제한다. */
async function deleteCommonCode() {
    const groupCode = document.getElementById('commonGroupSelect').value;
    const code = document.getElementById('codeValue').value.trim();
    if (!groupCode || !code || selectedRows.commonCode !== code) {
        showAdminToast('삭제할 기존 코드 행을 먼저 선택해 주세요.', true);
        return;
    }
    if (!window.confirm(`${groupCode}/${code} 코드를 삭제하시겠습니까? 활성·상위 코드는 삭제되지 않습니다.`)) {
        return;
    }
    try {
        const payload = await requestJson(
            `/api/admin/common-code-groups/${encodeURIComponent(groupCode)}`
            + `/items/${encodeURIComponent(code)}`, {method: 'DELETE'});
        resetCodeForm();
        await loadCodeAdmin(false);
        showResult('공통코드 삭제 성공', payload);
    } catch (error) {
        showError(error);
    }
}

/** 공통코드 목록의 모든 관리 속성을 표로 만들고 행 선택 시 수정 폼을 채운다. */
function renderCodeRows(items) {
    const search = normalizedSearchValue('commonCodeSearch');
    const status = document.getElementById('commonCodeStatusFilter').value;
    const filtered = items.filter(item => {
        const current = isCodeCurrent(item);
        const statusMatched = status === 'ALL'
            || (status === 'CURRENT' && current)
            || (status === 'ACTIVE' && item.active)
            || (status === 'INACTIVE' && !item.active)
            || (status === 'OUT_OF_PERIOD' && !current);
        return statusMatched && matchesSearch([item.code, item.name, item.parentCode], search);
    });
    const rows = document.getElementById('commonCodeRows');
    rows.replaceChildren();
    filtered.forEach(item => {
        const row = document.createElement('tr');
        appendCell(row, item.code);
        appendCell(row, item.name);
        appendCell(row, item.parentCode || '-');
        appendCell(row, String(item.sortOrder));
        appendStatusCell(row, codeStatusLabel(item), codeStatusTone(item));
        appendCell(row, `${item.validFrom} ~ ${item.validTo}`);
        makeSelectableRow(row, 'commonCode', item.code, () => selectCode(item));
        rows.appendChild(row);
    });
    if (filtered.length === 0) {
        setTableMessage('commonCodeRows', 6, search || status !== 'ALL'
            ? '검색 조건에 맞는 공통코드가 없습니다.'
            : '선택 그룹에 등록된 공통코드가 없습니다.', 'empty');
    }
    document.getElementById('commonCodeResultCount').textContent = `${filtered.length}건`;
}

/** 선택한 코드 행을 편집 폼에 복사하고 자기 자신은 상위 코드 후보에서 제외한다. */
function selectCode(item) {
    document.getElementById('codeValue').value = item.code;
    document.getElementById('codeName').value = item.name;
    document.getElementById('codeSortInput').value = item.sortOrder;
    document.getElementById('codeActiveInput').checked = item.active;
    document.getElementById('codeValidFrom').value = item.validFrom;
    document.getElementById('codeValidTo').value = item.validTo;
    renderCodeParents(commonCodeItems, item.code);
    document.getElementById('codeParentSelect').value = item.parentCode || '';
}

/** 공통코드 검색 조건을 초기화한다. */
function resetCodeFilters() {
    document.getElementById('commonCodeSearch').value = '';
    document.getElementById('commonCodeStatusFilter').value = 'ALL';
    loadCommonCodePage(0).catch(showError);
}

/** 공통코드 편집값과 선택 행을 신규 코드 등록 상태로 되돌린다. */
function resetCodeForm() {
    selectedRows.commonCode = null;
    refreshSelectedRows('commonCode');
    document.getElementById('codeValue').value = '';
    document.getElementById('codeName').value = '';
    document.getElementById('codeSortInput').value = '10';
    document.getElementById('codeActiveInput').checked = true;
    document.getElementById('codeValidFrom').value = toLocalDateInput(new Date());
    document.getElementById('codeValidTo').value = '2099-12-31';
    renderCodeParents(commonCodeItems, '');
    document.getElementById('codeValue').focus();
}

function isCodeCurrent(item) {
    return Boolean(item.active && isDateInPeriod(item.validFrom, item.validTo));
}

function codeStatusLabel(item) {
    if (!item.active) {
        return '비활성';
    }
    if (!isDateInPeriod(item.validFrom, item.validTo)) {
        return String(item.validFrom) > toLocalDateInput(new Date()) ? '적용 예정' : '기간 만료';
    }
    return '현재 유효';
}

function codeStatusTone(item) {
    if (!item.active) {
        return 'muted';
    }
    return isDateInPeriod(item.validFrom, item.validTo) ? 'success' : 'warning';
}

/** 현재 편집 코드 외의 같은 그룹 항목을 상위 코드 후보로 구성한다. */
function renderCodeParents(items, currentCode) {
    const options = [{value: '', text: '상위 코드 없음'}];
    items.filter(item => item.code !== currentCode).forEach(item => options.push({
        value: item.code,
        text: `${item.code} - ${item.name}`
    }));
    fillSelect(document.getElementById('codeParentSelect'), options);
}

/** 현재 사용자가 접근할 수 있는 메뉴만 관심 메뉴로 등록한다. */
async function addFavorite() {
    const menuId = document.getElementById('favoriteCandidate').value;
    if (!menuId) {
        return;
    }
    try {
        await requestJson(`/api/me/favorite-menus/${encodeURIComponent(menuId)}`, {method: 'POST'});
        await refreshFavorites('관심 메뉴 등록 성공');
    } catch (error) {
        showError(error);
    }
}

/** 선택된 관심 메뉴를 삭제하고 서버의 최종 목록으로 화면을 다시 그린다. */
async function removeFavorite() {
    const menuId = document.getElementById('favoriteSelect').value;
    if (!menuId) {
        return;
    }
    try {
        await requestJson(`/api/me/favorite-menus/${encodeURIComponent(menuId)}`, {method: 'DELETE'});
        await refreshFavorites('관심 메뉴 삭제 성공');
    } catch (error) {
        showError(error);
    }
}

/** 관심 메뉴 변경 뒤 중복되는 재조회·렌더링·결과 출력을 한곳에서 처리한다. */
async function refreshFavorites(message) {
    const payload = await requestJson('/api/me/favorite-menus');
    renderFavorites(payload.data);
    showResult(message, payload);
}

/** Spring Security 로그아웃에 현재 세션 CSRF 파라미터를 담은 일반 폼을 제출한다. */
function logout() {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/logout';
    const tokenInput = document.createElement('input');
    tokenInput.type = 'hidden';
    tokenInput.name = csrf.parameterName;
    tokenInput.value = csrf.token;
    form.appendChild(tokenInput);
    document.body.appendChild(form);
    form.submit();
}

/** 지정한 버튼을 공통 JSON API 호출과 결과 출력 동작에 연결한다. */
function bindApiButton(id, url, method) {
    document.getElementById(id).addEventListener('click', async () => {
        try {
            const payload = await requestJson(url, {method});
            showResult(`${id} 호출 성공`, payload);
        } catch (error) {
            showError(error);
        }
    });
}

/**
 * 모든 JSON API 요청이 공유하는 보안·오류 처리 경계다.
 * 상태 변경 메서드에는 서버가 발급한 CSRF 헤더를 자동 첨부하고 세션 쿠키는 동일 출처로만 보낸다.
 */
async function requestJson(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(method);
    if (mutation && mutationInFlight) {
        throw new Error('다른 변경 작업을 처리 중입니다. 잠시 후 다시 시도해 주세요.');
    }
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    let body = options.body;
    if (body !== undefined && typeof body !== 'string') {
        headers.set('Content-Type', 'application/json');
        body = JSON.stringify(body);
    }
    if (mutation && csrf) {
        headers.set(csrf.headerName, csrf.token);
    }

    if (mutation) {
        mutationInFlight = true;
        document.body.classList.add('mutation-pending');
        document.body.setAttribute('aria-busy', 'true');
    }
    try {
        const response = await fetch(url, {
            method,
            headers,
            body,
            credentials: 'same-origin',
            redirect: 'follow'
        });
        if (response.status === 401) {
            window.location.assign('/login');
            throw new Error('로그인 세션이 만료되었습니다.');
        }
        const payload = await response.json().catch(() => null);
        if (!response.ok) {
            const error = new Error(payload?.error?.message || `HTTP ${response.status} 요청 실패`);
            error.status = response.status;
            error.payload = payload;
            throw error;
        }
        return payload;
    } finally {
        if (mutation) {
            mutationInFlight = false;
            document.body.classList.remove('mutation-pending');
            document.body.removeAttribute('aria-busy');
        }
    }
}

/** 서버가 계산한 현재 유효 업무 권한을 배지로 표시한다. */
function renderAuthorities(authorityIds) {
    const container = document.getElementById('authorityBadges');
    container.replaceChildren();
    authorityIds.forEach(authorityId => {
        const badge = document.createElement('span');
        badge.className = 'badge';
        badge.textContent = authorityId;
        container.appendChild(badge);
    });
    if (authorityIds.length === 0) {
        container.textContent = '유효한 업무 권한이 없습니다.';
    }
}

/** 서버가 가시성과 권한을 필터링한 메뉴 트리를 사이드바의 실제 앵커 링크로 렌더링한다. */
function renderMenuTree(menuNodes) {
    const nav = document.getElementById('menuTree');
    const overview = document.createElement('a');
    overview.href = '#home';
    overview.className = 'admin-overview-link';
    overview.textContent = '관리 현황';
    nav.replaceChildren(overview, buildMenuList(menuNodes));
}

/** 경로가 있는 메뉴는 클릭 가능한 링크로, 폴더 메뉴는 하위 항목을 묶는 레이블로 재귀 변환한다. */
function buildMenuList(nodes) {
    const list = document.createElement('ul');
    nodes.forEach(node => {
        const item = document.createElement('li');
        if (node.path) {
            const link = document.createElement('a');
            const portalOnly = node.id === 'MY_AUTH_REQUEST';
            link.href = portalOnly ? `/portal.html${node.path}` : node.path;
            link.textContent = node.name;
            link.dataset.menuId = node.id;
            link.addEventListener('click', event => {
                if (!portalOnly && !document.querySelector(node.path)) {
                    event.preventDefault();
                    showResult('메뉴 화면 연결 확인', {
                        menuId: node.id,
                        path: node.path,
                        message: '연결된 화면 영역이 없습니다. 메뉴 경로를 확인해 주세요.'
                    });
                }
                if (window.matchMedia('(max-width: 1024px)').matches) {
                    closeAdminMenu();
                }
            });
            item.appendChild(link);
        } else {
            const label = document.createElement('span');
            label.textContent = node.name;
            item.appendChild(label);
        }
        if (node.children?.length) {
            item.appendChild(buildMenuList(node.children));
        }
        list.appendChild(item);
    });
    return list;
}

function toggleAdminMenu() {
    if (adminMenuOpen) {
        closeAdminMenu();
    } else {
        openAdminMenu();
    }
}

function openAdminMenu() {
    if (!window.matchMedia('(max-width: 1024px)').matches) {
        return;
    }
    adminMenuOpen = true;
    const sidebar = document.getElementById('adminSidebar');
    const backdrop = document.getElementById('adminSidebarBackdrop');
    const toggle = document.getElementById('btnAdminMenu');
    sidebar.classList.add('open');
    sidebar.setAttribute('aria-hidden', 'false');
    backdrop.hidden = false;
    document.body.classList.add('drawer-open');
    document.getElementById('adminMain').inert = true;
    document.querySelector('.topbar-actions').inert = true;
    toggle.setAttribute('aria-expanded', 'true');
    toggle.setAttribute('aria-label', '관리 메뉴 닫기');
    window.requestAnimationFrame(() => sidebar.querySelector('a, button')?.focus());
}

function closeAdminMenu() {
    if (!adminMenuOpen) {
        return;
    }
    adminMenuOpen = false;
    const sidebar = document.getElementById('adminSidebar');
    const backdrop = document.getElementById('adminSidebarBackdrop');
    const toggle = document.getElementById('btnAdminMenu');
    sidebar.classList.remove('open');
    sidebar.setAttribute('aria-hidden', 'true');
    backdrop.hidden = true;
    document.body.classList.remove('drawer-open');
    document.getElementById('adminMain').inert = false;
    document.querySelector('.topbar-actions').inert = false;
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', '관리 메뉴 열기');
    toggle.focus();
}

function trapAdminMenuFocus(event) {
    if (!adminMenuOpen || event.key !== 'Tab') {
        return;
    }
    const focusable = Array.from(event.currentTarget.querySelectorAll(
        'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'));
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
    }
}

/** 현재 메뉴에 해당하는 작업 카드만 표시하고 해당 도메인의 데이터만 처음 한 번 조회한다. */
async function routeToHash(loadData = true) {
    const panels = Array.from(document.querySelectorAll('#adminMain > section.card'));
    const knownRoutes = new Set(['#home', ...panels.map(panel =>
        panel.dataset.adminRoute || (panel.id ? `#${panel.id}` : '#home'))]);
    let hash = window.location.hash || '#home';
    if (['#content', '#favorites'].includes(hash)) {
        window.location.replace('/portal.html' + (hash === '#content' ? hash : '#home'));
        return;
    }
    if (!knownRoutes.has(hash)) {
        hash = '#home';
        history.replaceState(null, '', hash);
    }
    const requiredAction = {
        '#authority-master': 'AUTHORITY_READ',
        '#authority': 'AUTHORITY_READ',
        '#authority-requests': 'AUTHORITY_REQUEST_READ',
        '#menu': 'MENU_READ',
        '#program': 'PROGRAM_READ',
        '#project': 'PROJECT_READ',
        '#common-code': 'COMMON_CODE_READ',
        '#audit': 'AUDIT_READ'
    }[hash];
    if (requiredAction && !allowedActionIds.has(requiredAction)) {
        hash = '#home';
        history.replaceState(null, '', hash);
        showAdminToast('현재 로그인 사용자에게 허용되지 않은 관리 화면입니다.', true);
    }

    document.querySelector('.hero').classList.toggle('admin-route-hidden', hash !== '#home');
    panels.forEach(panel => {
        const route = panel.dataset.adminRoute || (panel.id ? `#${panel.id}` : '#home');
        panel.classList.toggle('admin-route-hidden', route !== hash);
    });
    document.querySelectorAll('#menuTree a, .admin-utility-nav a').forEach(link => {
        if (link.getAttribute('href') === hash) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });
    if (loadData) {
        await loadAdminViewForRoute(hash);
        window.scrollTo({top: 0, behavior: 'smooth'});
        document.getElementById('adminMain').focus({preventScroll: true});
    }
}

/** 동일 데이터를 쓰는 메뉴는 한 조회 결과를 공유하고 재진입 시 불필요한 요청을 생략한다. */
async function loadAdminViewForRoute(hash) {
    if (hash === '#home') {
        await loadDashboardOverview();
        return;
    }
    const routeConfig = {
        '#authority-master': ['authority', 'btnAuthMasterRead', loadAuthorityView],
        '#authority': ['authority', 'btnAuthorityRead', loadAuthorityView],
        '#authority-requests': ['authority-requests', 'btnAuthorityRequestRead', () => loadAuthorityRequests(0)],
        '#menu': ['menu', 'btnMenuRead', loadMenuView],
        '#program': ['program', 'btnProgramRead', loadProgramView],
        '#project': ['project', 'btnProjectRead', loadProjectView],
        '#common-code': ['common-code', 'btnCommonCodeRead', loadCodeAdmin],
        '#audit': ['audit', 'btnAuditRead', () => loadAuditEvents(0)]
    }[hash];
    if (!routeConfig) {
        return;
    }
    const [key, permissionButtonId, loader] = routeConfig;
    if (loadedAdminViews.has(key)
            || document.getElementById(permissionButtonId).getAttribute('aria-disabled') === 'true') {
        return;
    }
    await loader(false);
    loadedAdminViews.add(key);
}

/** 관리자 조회 API를 병렬 호출해 관리 대상, 승인 대기와 최근 오류를 한 화면에 구성한다. */
async function loadDashboardOverview() {
    const sources = [
        ['authority', 'btnAuthMasterRead', loadAuthorityView],
        ['program', 'btnProgramRead', loadProgramView],
        ['project', 'btnProjectRead', loadProjectView],
        ['common-code', 'btnCommonCodeRead', loadCodeAdmin]
    ].filter(([, buttonId]) =>
        document.getElementById(buttonId).getAttribute('aria-disabled') !== 'true');
    if (sources.length === 0) {
        setLoadState(['dashboardLoadState'], 'restricted', '접근 제한');
        document.getElementById('dashboardSummary').textContent =
            '현재 계정에는 핵심 관리 현황을 조회할 권한이 없습니다.';
        return;
    }

    const pending = sources.filter(([key]) => !loadedAdminViews.has(key));
    if (pending.length > 0) {
        setLoadState(['dashboardLoadState'], 'loading', '현황 집계 중');
        const results = await Promise.allSettled(pending.map(([, , loader]) => loader(false)));
        results.forEach((result, index) => {
            if (result.status === 'fulfilled') {
                loadedAdminViews.add(pending[index][0]);
            }
        });
        const failed = results.filter(result => result.status === 'rejected').length;
        setLoadState(['dashboardLoadState'], failed > 0 ? 'warning' : 'ready',
            failed > 0 ? `${failed}개 영역 확인 필요` : '최신 현황');
    } else {
        setLoadState(['dashboardLoadState'], 'ready', '최신 현황');
    }
    await loadDashboardSignals();
    refreshDashboardSummary();
}

async function loadDashboardSignals() {
    const signalLoads = [];
    if (document.getElementById('btnAuthorityRequestRead').getAttribute('aria-disabled') !== 'true') {
        signalLoads.push(requestJson('/api/admin/authority-requests?page=0&size=10&status=REQUESTED')
            .then(payload => {
                dashboardPendingCount = payload.data.totalElements;
                document.getElementById('dashboardPendingCount').textContent = String(dashboardPendingCount);
            }));
    }
    if (document.getElementById('btnAuditRead').getAttribute('aria-disabled') !== 'true') {
        const from = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
        const query = new URLSearchParams({page: '0', size: '10', result: 'FAILED', from});
        signalLoads.push(requestJson(`/api/audit-events?${query}`)
            .then(payload => {
                dashboardErrorCount = payload.data.totalElements;
                document.getElementById('dashboardErrorCount').textContent = String(dashboardErrorCount);
            }));
    }
    await Promise.allSettled(signalLoads);
}

/** 조회가 끝난 도메인의 실제 원본 건수를 대시보드에 반영한다. */
function refreshDashboardSummary() {
    if (authorityAdmin) {
        document.getElementById('dashboardAuthorityCount').textContent = String(
            authorityAdmin.authorities.filter(authority => authority.active).length);
    }
    if (programAdmin) {
        document.getElementById('dashboardProgramCount').textContent = String(
            programAdmin.programs.filter(program => program.active).length);
    }
    if (projectAdmin) {
        document.getElementById('dashboardProjectCount').textContent = String(
            projectAdmin.projects.filter(item =>
                item.project.active && item.project.status === 'ACTIVE').length);
    }
    if (commonCodeAdmin) {
        document.getElementById('dashboardCodeCount').textContent = String(
            Object.values(commonCodeAdmin.groups).reduce((count, itemCount) => count + itemCount, 0));
    }
    const loadedCount = [authorityAdmin, programAdmin, projectAdmin, commonCodeAdmin]
        .filter(Boolean).length;
    const attentionCount = (dashboardPendingCount || 0) + (dashboardErrorCount || 0);
    document.getElementById('dashboardSummary').textContent = loadedCount > 0
        ? `${loadedCount}개 관리 영역 집계 완료${attentionCount ? ` · 확인 필요 ${attentionCount}건` : ' · 확인할 이상 없음'}`
        : '관리 권한이 확인되면 각 영역의 최신 현황을 표시합니다.';
}

/** 모든 보호 버튼을 기본 거부한 뒤 서버가 허용한 프로그램 기능만 활성화한다. */
function enableAuthorizedButtons(actions) {
    allowedActionIds.clear();
    document.querySelectorAll('[data-protected]').forEach(button => {
        button.disabled = false;
        button.dataset.authorized = 'false';
        button.setAttribute('aria-disabled', 'true');
        if (!button.hasAttribute('aria-describedby')) {
            button.setAttribute('aria-describedby', 'protectedActionHelp');
        }
        button.title = '현재 사용자에게 허용되지 않은 기능입니다.';
    });
    actions.forEach(action => {
        allowedActionIds.add(action.actionId);
        const button = document.getElementById(action.componentId);
        if (button) {
            enableButton(button, action.actionId);
        }
        document.querySelectorAll(`[data-action="${action.actionId}"]`)
            .forEach(target => enableButton(target, action.actionId));
    });
    syncAuthorizedNavigation();
}

/** 대시보드 바로가기도 서버가 허용한 조회 기능에 맞춰 노출해 빈 관리 화면 진입을 막는다. */
function syncAuthorizedNavigation() {
    document.querySelectorAll('[data-required-action]').forEach(link => {
        const allowed = allowedActionIds.has(link.dataset.requiredAction);
        link.hidden = !allowed;
        link.setAttribute('aria-hidden', allowed ? 'false' : 'true');
        link.tabIndex = allowed ? 0 : -1;
    });
}

/** 허용된 기능 버튼에 활성 상태와 확인용 기능 ID 툴팁을 적용한다. */
function enableButton(button, actionId) {
    button.disabled = false;
    button.dataset.authorized = 'true';
    button.setAttribute('aria-disabled', 'false');
    if (button.getAttribute('aria-describedby') === 'protectedActionHelp') {
        button.removeAttribute('aria-describedby');
    }
    button.title = `${actionId} 허용`;
}

/** 계층형 지역 코드 중 최상위 항목을 첫 선택지에 표시하고 전체 항목을 후속 필터용으로 보관한다. */
function renderRegions(items) {
    const parentSelect = document.getElementById('regionParentSelect');
    parentSelect.replaceChildren();
    items.filter(item => !item.parentCode).forEach(item => {
        const option = document.createElement('option');
        option.value = item.code;
        option.textContent = item.name;
        parentSelect.appendChild(option);
    });
    parentSelect.dataset.items = JSON.stringify(items);
    filterRegions();
}

/** 선택한 상위 지역 코드에 직접 속한 하위 지역만 두 번째 선택지에 표시한다. */
function filterRegions() {
    const parentSelect = document.getElementById('regionParentSelect');
    const childSelect = document.getElementById('regionChildSelect');
    const items = JSON.parse(parentSelect.dataset.items || '[]');
    childSelect.replaceChildren();
    items.filter(item => item.parentCode === parentSelect.value).forEach(item => {
        const option = document.createElement('option');
        option.value = item.code;
        option.textContent = item.name;
        childSelect.appendChild(option);
    });
}

/** 서버가 현재 접근 가능 여부를 다시 확인한 관심 메뉴만 선택지로 표시한다. */
function renderFavorites(favorites) {
    const select = document.getElementById('favoriteSelect');
    select.replaceChildren();
    favorites.forEach(favorite => {
        const option = document.createElement('option');
        option.value = favorite.menuId;
        option.textContent = favorite.name;
        select.appendChild(option);
    });
    if (favorites.length === 0) {
        fillSelect(select, [{value: '', text: '등록된 관심 메뉴 없음'}]);
    }
}

/** 현재 메뉴 트리에서 화면 경로가 있는 접근 가능 메뉴만 관심 메뉴 등록 후보로 제공한다. */
function renderFavoriteOptions(menuNodes) {
    const flatMenus = flattenMenus(menuNodes);
    fillSelect(
        document.getElementById('favoriteCandidate'),
        flatMenus.filter(menu => menu.path)
            .map(menu => ({value: menu.id, text: menu.name})));
}

/** 재귀 메뉴 트리를 선택지 생성에 사용하기 쉬운 단일 배열로 펼친다. */
function flattenMenus(menuNodes) {
    return menuNodes.flatMap(menu => [menu, ...flattenMenus(menu.children || [])]);
}

/** 선택지 배열을 select에 안전하게 적용하며 표시 문자열은 textContent로만 넣는다. */
function fillSelect(select, options) {
    const previous = select.value;
    select.replaceChildren();
    options.forEach(item => {
        const option = document.createElement('option');
        option.value = item.value;
        option.textContent = item.text;
        select.appendChild(option);
    });
    if (options.some(item => item.value === previous)) {
        select.value = previous;
    }
}

/** 검색 input은 입력 즉시, select는 값 변경 즉시 클라이언트 원본을 다시 필터링한다. */
function bindFilter(id, render) {
    const control = document.getElementById(id);
    if (!control) {
        return;
    }
    const eventName = control.tagName === 'SELECT' ? 'change' : 'input';
    let timer = null;
    control.addEventListener(eventName, () => {
        window.clearTimeout(timer);
        timer = window.setTimeout(render, eventName === 'input' ? 180 : 0);
    });
}

function normalizedSearchValue(id) {
    return document.getElementById(id).value.trim().toLocaleLowerCase('ko-KR');
}

function matchesSearch(values, search) {
    if (!search) {
        return true;
    }
    return values.some(value => String(value ?? '').toLocaleLowerCase('ko-KR').includes(search));
}

function matchesActiveFilter(active, filter) {
    return filter === 'ALL'
        || (filter === 'ACTIVE' && active)
        || (filter === 'INACTIVE' && !active);
}

/** 목록 조회 상태를 시각·스크린리더 양쪽에 동일한 문구로 전달한다. */
function setLoadState(ids, state, text) {
    ids.forEach(id => {
        const element = document.getElementById(id);
        if (!element) {
            return;
        }
        element.dataset.state = state;
        element.textContent = text;
    });
}

function setSectionBusy(ids, busy) {
    ids.forEach(id => {
        const section = document.getElementById(id);
        if (!section) {
            return;
        }
        if (busy) {
            section.setAttribute('aria-busy', 'true');
        } else {
            section.removeAttribute('aria-busy');
        }
    });
}

/** 빈 목록, 최초 로딩과 오류를 표 구조를 유지하는 한 행으로 출력한다. */
function setTableMessage(bodyId, colspan, message, state = 'empty') {
    const body = document.getElementById(bodyId);
    body.replaceChildren();
    const row = document.createElement('tr');
    row.className = `table-state-row table-state-${state}`;
    const cell = document.createElement('td');
    cell.colSpan = colspan;
    cell.textContent = message;
    row.appendChild(cell);
    body.appendChild(row);
}

/** 업무 상태를 색상만이 아니라 텍스트가 포함된 배지로 표현한다. */
function createStatusBadge(label, tone = 'neutral', title = '') {
    const badge = document.createElement('span');
    badge.className = `status-badge status-${tone}`;
    badge.textContent = label;
    if (title) {
        badge.title = title;
    }
    return badge;
}

function appendStatusCell(row, label, tone = 'neutral') {
    const cell = document.createElement('td');
    cell.appendChild(createStatusBadge(label, tone));
    row.appendChild(cell);
}

function appendBadgeListCell(row, values, emptyText) {
    const cell = document.createElement('td');
    const container = document.createElement('div');
    container.className = 'inline-badges';
    if (values.length === 0) {
        container.appendChild(createStatusBadge(emptyText, 'muted'));
    } else {
        values.forEach(value => container.appendChild(createStatusBadge(value, 'info')));
    }
    cell.appendChild(container);
    row.appendChild(cell);
}

/** 전체 권한 이력을 접을 수 있는 목록으로 제공해 표의 밀도와 추적성을 함께 유지한다. */
function appendAssignmentHistoryCell(row, assignments) {
    const cell = document.createElement('td');
    cell.className = 'history-cell';
    if (assignments.length === 0) {
        cell.appendChild(createStatusBadge('이력 없음', 'muted'));
        row.appendChild(cell);
        return;
    }
    const ordered = [...assignments].sort((left, right) => right.sequence - left.sequence);
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    summary.append(`${assignments.length}건 · 최근 `);
    summary.appendChild(createStatusBadge(assignmentStatusLabel(ordered[0]), assignmentStatusTone(ordered[0])));
    details.appendChild(summary);
    const list = document.createElement('ol');
    ordered.forEach(item => {
        const entry = document.createElement('li');
        const head = document.createElement('div');
        head.appendChild(createStatusBadge(assignmentStatusLabel(item), assignmentStatusTone(item)));
        const title = document.createElement('strong');
        title.textContent = `${item.authorityId} · ${item.type === 'DELEGATED' ? '위임' : '직접'}`;
        head.appendChild(title);
        entry.appendChild(head);
        const detail = document.createElement('span');
        detail.textContent = `${item.validFrom} ~ ${item.validTo}`
            + `${item.delegatedBy ? ` · 원천 ${item.delegatedBy}` : ''} · #${item.sequence}`;
        entry.appendChild(detail);
        list.appendChild(entry);
    });
    details.appendChild(list);
    details.addEventListener('click', event => event.stopPropagation());
    cell.appendChild(details);
    row.appendChild(cell);
}

function assignmentStatusLabel(item) {
    if (item.status === 'REVOKED') {
        return '회수';
    }
    if (item.status === 'PENDING') {
        return '승인 대기';
    }
    if (!isDateInPeriod(item.validFrom, item.validTo)) {
        return String(item.validFrom) > toLocalDateInput(new Date()) ? '적용 예정' : '기간 만료';
    }
    return '유효';
}

function assignmentStatusTone(item) {
    if (item.status === 'REVOKED') {
        return 'danger';
    }
    if (item.status === 'PENDING' || !isDateInPeriod(item.validFrom, item.validTo)) {
        return 'warning';
    }
    return 'success';
}

/** 마우스와 키보드로 같은 행 선택 동작을 수행하고 현재 선택을 시각적으로 유지한다. */
function makeSelectableRow(row, group, key, select) {
    row.classList.add('selectable-row');
    row.dataset.selectionGroup = group;
    row.dataset.rowKey = key;
    row.tabIndex = 0;
    const activate = () => {
        selectedRows[group] = key;
        refreshSelectedRows(group);
        select();
    };
    row.addEventListener('click', activate);
    row.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            activate();
        }
    });
    refreshSelectedRow(row, group);
}

function refreshSelectedRows(group) {
    document.querySelectorAll('[data-selection-group]').forEach(row => {
        if (row.dataset.selectionGroup === group) {
            refreshSelectedRow(row, group);
        }
    });
}

function refreshSelectedRow(row, group) {
    const selected = selectedRows[group] === row.dataset.rowKey;
    row.classList.toggle('is-selected', selected);
    row.setAttribute('aria-selected', String(selected));
}

function restoreSelectValue(id, value) {
    const select = document.getElementById(id);
    if (value && Array.from(select.options).some(option => option.value === value)) {
        select.value = value;
    }
}

function selectFirstOption(id) {
    const select = document.getElementById(id);
    if (select.options.length > 0) {
        select.selectedIndex = 0;
    }
}

function toLocalDateInput(date) {
    const adjusted = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
    return adjusted.toISOString().slice(0, 10);
}

function formatDateTime(value) {
    if (!value) {
        return '-';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('ko-KR');
}

function isDateInPeriod(validFrom, validTo) {
    const today = toLocalDateInput(new Date());
    return (!validFrom || validFrom <= today) && (!validTo || validTo >= today);
}

/** 표 셀을 innerHTML 없이 생성해 서버 데이터가 마크업으로 실행되지 않게 한다. */
function appendCell(row, value) {
    const cell = document.createElement('td');
    cell.textContent = value;
    row.appendChild(cell);
}

/** 입력값이 비어 있으면 JSON null을, 값이 있으면 원문 문자열을 반환한다. */
function nullableValue(id) {
    const value = document.getElementById(id).value.trim();
    return value || null;
}

/** 숫자 입력을 정수로 변환하며 빈 값이나 잘못된 값은 서버 검증이 가능한 null로 반환한다. */
function numberValue(id) {
    const value = document.getElementById(id).value;
    return value === '' || Number.isNaN(Number(value)) ? null : Number.parseInt(value, 10);
}

/** API 응답을 사람이 비교하기 쉬운 들여쓰기 JSON으로 하단 콘솔에 출력한다. */
function showResult(title, payload) {
    document.getElementById('resultLog').textContent =
        `${title}\n${JSON.stringify(payload, null, 2)}`;
    showAdminToast(title, title.startsWith('요청 실패'));
}

/** 오류 메시지, HTTP 상태와 서버 추적 ID를 함께 출력해 테스트와 장애 확인을 돕는다. */
function showError(error) {
    showResult(`요청 실패${error.status ? ` (HTTP ${error.status})` : ''}`, {
        message: error.message,
        traceId: error.payload?.error?.traceId || null,
        response: error.payload || null
    });
}

/** 긴 작업 화면에서도 저장 결과를 즉시 인지하도록 고정 토스트를 표시한다. */
function showAdminToast(message, error = false) {
    const toast = document.getElementById('adminToast');
    toast.textContent = message;
    toast.classList.toggle('error', error);
    toast.classList.add('show');
    if (adminToastTimer) {
        window.clearTimeout(adminToastTimer);
    }
    adminToastTimer = window.setTimeout(() => toast.classList.remove('show'), 3200);
}

function pagedQuery(state) {
    return new URLSearchParams({
        page: String(state.page),
        size: String(state.size),
        sort: state.sort,
        direction: state.direction
    });
}

/** 같은 표의 앞선 요청보다 나중에 시작된 응답만 화면에 반영한다. */
async function requestLatestList(key, url) {
    const token = Symbol(key);
    latestListRequests.set(key, token);
    try {
        const payload = await requestJson(url);
        return latestListRequests.get(key) === token ? payload : null;
    } catch (error) {
        if (latestListRequests.get(key) !== token) return null;
        throw error;
    }
}

function renderServerPager(key, bodyId, result, loader) {
    const state = adminListState[key];
    const tableHost = document.getElementById(bodyId).closest('.table-wrap, .table-scroll');
    if (!tableHost) return;
    let pager = document.getElementById(`${bodyId}Pager`);
    if (!pager) {
        pager = document.createElement('div');
        pager.id = `${bodyId}Pager`;
        pager.className = 'server-pager';
        tableHost.after(pager);
    }
    pager.replaceChildren();
    const summary = document.createElement('span');
    summary.textContent = `총 ${result.totalElements}건 · ${result.totalPages ? result.page + 1 : 0}/${result.totalPages}페이지`;
    const controls = document.createElement('div');
    const previous = document.createElement('button');
    previous.type = 'button';
    previous.textContent = '이전';
    previous.disabled = result.page <= 0;
    previous.addEventListener('click', () => loader(result.page - 1));
    const next = document.createElement('button');
    next.type = 'button';
    next.textContent = '다음';
    next.disabled = result.page + 1 >= result.totalPages;
    next.addEventListener('click', () => loader(result.page + 1));
    const current = document.createElement('span');
    current.className = 'current-page';
    current.setAttribute('aria-current', 'page');
    current.textContent = result.totalPages ? `${result.page + 1}페이지` : '0페이지';
    const size = document.createElement('select');
    size.setAttribute('aria-label', '페이지 크기');
    [10, 20, 50].forEach(value => {
        const option = document.createElement('option');
        option.value = String(value);
        option.textContent = `${value}건`;
        option.selected = value === state.size;
        size.appendChild(option);
    });
    size.addEventListener('change', () => {
        state.size = Number(size.value);
        loader(0);
    });
    controls.append(previous, current, next, size);
    pager.append(summary, controls);
}

function installServerTableSorting() {
    const configs = {
        authorityMasterRows: ['authorityMaster', loadAuthorityMasterPage, ['ID', 'NAME', 'ACTIVE', null]],
        authorityUserRows: ['authorityUser', loadAuthorityUserPage, ['USERNAME', 'ORGANIZATION', 'EFFECTIVE_AUTHORITY_COUNT', null]],
        menuRows: ['menu', loadMenuPage, ['ID', 'PARENT', 'NAME', null, 'ORDER', null]],
        programRows: ['program', loadProgramPage, ['ID', 'NAME', null, null, 'ACTIVE']],
        programActionRows: ['programAction', loadProgramActionPage, ['MENU', 'PROGRAM', 'ACTION', null, null, 'ORDER', null, null]],
        projectRows: ['project', loadProjectPage, ['ID', 'NAME', 'STATUS', 'START_DATE', 'OWNER', null, null]],
        commonCodeRows: ['commonCode', loadCommonCodePage, ['CODE', 'NAME', null, 'ORDER', null, 'VALID_FROM']]
    };
    Object.entries(configs).forEach(([bodyId, [key, loader, sorts]]) => {
        const body = document.getElementById(bodyId);
        const headers = body?.closest('table')?.querySelectorAll('thead th');
        if (!headers) return;
        const tableHost = body.closest('.table-wrap, .table-scroll');
        if (tableHost && !tableHost.dataset.scrollLabel) {
            tableHost.dataset.scrollLabel = '표가 화면보다 넓으면 좌우로 스크롤할 수 있습니다.';
        }
        headers.forEach((header, index) => {
            const sort = sorts[index];
            if (!sort) return;
            const label = header.textContent.trim();
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'sort-button';
            button.textContent = label;
            button.addEventListener('click', () => {
                const state = adminListState[key];
                state.direction = state.sort === sort && state.direction === 'ASC' ? 'DESC' : 'ASC';
                state.sort = sort;
                headers.forEach(item => item.removeAttribute('aria-sort'));
                header.setAttribute('aria-sort', state.direction === 'ASC' ? 'ascending' : 'descending');
                loader(0).catch(showError);
            });
            header.replaceChildren(button);
            if (adminListState[key].sort === sort) {
                header.setAttribute('aria-sort', adminListState[key].direction === 'ASC' ? 'ascending' : 'descending');
            }
        });
    });
}

function installAdminValidation() {
    const groups = {
        btnAuthMasterSave: ['authMasterId', 'authMasterName'],
        btnMenuSave: ['menuIdInput', 'menuNameInput', 'menuSortInput'],
        btnProgramSave: ['programIdInput', 'programNameInput'],
        btnActionSave: ['actionMenuSelect', 'actionProgramSelect', 'actionIdInput', 'actionLabelInput', 'actionSortInput'],
        btnProjectSave: ['projectIdInput', 'projectNameInput', 'projectStatusSelect', 'projectStartInput', 'projectEndInput', 'projectOwnerSelect', 'projectVersionInput'],
        btnCommonCodeGroup: ['commonCodeGroupId', 'commonCodeGroupName', 'commonCodeGroupSort'],
        btnCommonCodeSave: ['codeValue', 'codeName', 'codeSortInput', 'codeValidFrom', 'codeValidTo']
    };
    Object.values(groups).flat().forEach(id => {
        const control = document.getElementById(id);
        if (!control) return;
        control.required = true;
        const errorId = `${id}Error`;
        control.setAttribute('aria-describedby', errorId);
        if (!document.getElementById(errorId)) {
            const error = document.createElement('small');
            error.id = errorId;
            error.className = 'field-error';
            control.insertAdjacentElement('afterend', error);
        }
        control.addEventListener('input', () => clearAdminFieldError(control));
        control.addEventListener('change', () => clearAdminFieldError(control));
    });
    document.addEventListener('click', event => {
        const button = event.target.closest('button[id]');
        const ids = button && groups[button.id];
        if (!ids || validateAdminFields(ids)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
    }, true);
}

function validateAdminFields(ids) {
    const controls = ids.map(id => document.getElementById(id)).filter(Boolean);
    const start = document.getElementById('projectStartInput');
    const end = document.getElementById('projectEndInput');
    if (ids.includes('projectStartInput')) {
        end.setCustomValidity(start.value && end.value && end.value < start.value
            ? '종료일은 시작일보다 빠를 수 없습니다.' : '');
    }
    const codeFrom = document.getElementById('codeValidFrom');
    const codeTo = document.getElementById('codeValidTo');
    if (ids.includes('codeValidFrom')) {
        codeTo.setCustomValidity(codeFrom.value && codeTo.value && codeTo.value < codeFrom.value
            ? '종료일은 시작일보다 빠를 수 없습니다.' : '');
    }
    let first = null;
    controls.forEach(control => {
        const error = document.getElementById(`${control.id}Error`);
        if (!control.checkValidity()) {
            control.setAttribute('aria-invalid', 'true');
            if (error) error.textContent = control.validationMessage;
            first ||= control;
        } else {
            clearAdminFieldError(control);
        }
    });
    if (first) {
        first.focus();
        showAdminToast('입력값을 확인해 주세요.', true);
        return false;
    }
    return true;
}

function clearAdminFieldError(control) {
    control.removeAttribute('aria-invalid');
    const error = document.getElementById(`${control.id}Error`);
    if (error) error.textContent = '';
}
