'use strict';

let csrf = null;
let authorityAdmin = null;
let menuAdmin = null;
let programAdmin = null;
let commonCodeItems = [];
const openedWorkspacePages = new Map();
const workspacePageUrls = Object.freeze({
    '#home': '/admin/home',
    '#content': '/admin/content',
    '#authority-master': '/admin/authority-master',
    '#authority': '/admin/authority',
    '#menu': '/admin/menu',
    '#program': '/admin/program',
    '#common-code': '/admin/common-code'
});
const workspaceStateKey = 'permission-demo.opened-workspace-pages';

/**
 * DOM 구성이 끝나면 모든 버튼과 선택 이벤트를 먼저 연결한 뒤 화면 공통 데이터를 조회한다.
 * 초기화 중 발생한 인증·인가·검증 오류도 하단 응답 콘솔에 동일한 형식으로 표시한다.
 */
document.addEventListener('DOMContentLoaded', () => {
    configureWorkspacePages();
    bindEvents();
    initialize().catch(showError);
});

/**
 * 로그인 사용자의 공통 화면 컨텍스트를 적용하고, 관리자에게만 세 관리 화면의 원본 데이터를 추가 조회한다.
 * 버튼 활성 여부는 서버가 계산한 프로그램 기능 목록을 사용하므로 클라이언트가 관리자 여부를 추측하지 않는다.
 */
async function initialize() {
    const context = await refreshContext();
    const adminLoads = [];
    if (!document.getElementById('btnAuthorityRead').disabled) {
        adminLoads.push(loadAuthorityView(false), loadMenuView(false), loadProgramView(false));
    }
    if (!document.getElementById('btnCommonCodeSave').disabled) {
        adminLoads.push(loadCodeAdmin(false));
    }
    await Promise.all(adminLoads);
    showResult('공통 부트스트랩 및 관리 화면 초기화 완료', context);
}

/**
 * 부트스트랩 응답으로 현재 사용자, 유효 권한, 메뉴, 기능 버튼, 관심 메뉴와 지역 코드를 갱신한다.
 * 권한이나 메뉴를 변경한 직후에도 이 메서드를 다시 호출해 화면을 서버의 최종 판정과 일치시킨다.
 */
async function refreshContext() {
    const payload = await requestJson('/api/bootstrap');
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
    routeToHash();
    return context;
}

/** 정적인 화면 요소에 API 조회, 저장, 선택 연동과 해시 메뉴 탐색 동작을 연결한다. */
function bindEvents() {
    bindTableRowSelection();
    bindApiButton('btnContentRead', '/api/content/preview', 'GET');
    bindApiButton('btnContentSave', '/api/content/save', 'POST');
    bindApiButton('btnContentPublish', '/api/content/publish', 'POST');
    bindApiButton('btnForcePublish', '/api/content/publish', 'POST');
    bindApiButton('btnAuditRead', '/api/audit-events?page=0&size=20', 'GET');

    document.getElementById('btnAuthorityRead').addEventListener('click', () => loadAuthorityView());
    document.getElementById('btnAuthMasterRead').addEventListener('click', () => loadAuthorityView());
    document.getElementById('btnAuthMasterSave').addEventListener('click', saveAuthMaster);
    document.getElementById('btnAuthorityUpdate').addEventListener('click', assignAuthority);
    document.getElementById('btnAuthorityRevoke').addEventListener('click', revokeAuthority);
    document.getElementById('authorityTypeSelect').addEventListener('change', toggleAuthoritySource);

    document.getElementById('btnMenuRead').addEventListener('click', () => loadMenuView());
    document.getElementById('btnMenuSave').addEventListener('click', saveMenu);
    document.getElementById('btnMenuGrant').addEventListener('click', saveMenuGrant);
    document.getElementById('menuAuthoritySelect').addEventListener('change', syncMenuGrant);
    document.getElementById('menuGrantSelect').addEventListener('change', syncMenuGrant);

    document.getElementById('btnProgramRead').addEventListener('click', () => loadProgramView());
    document.getElementById('btnProgramSave').addEventListener('click', saveProgram);
    document.getElementById('btnActionSave').addEventListener('click', saveAction);
    document.getElementById('btnActionGrant').addEventListener('click', saveActionGrant);
    document.getElementById('actionAuthoritySelect').addEventListener('change', syncActionGrant);
    document.getElementById('actionGrantSelect').addEventListener('change', syncActionGrant);

    document.getElementById('commonGroupSelect').addEventListener('change', () => loadCodeGroup());
    document.getElementById('btnCommonCodeSave').addEventListener('click', saveCommonCode);
    document.getElementById('btnFavoriteAdd').addEventListener('click', addFavorite);
    document.getElementById('btnFavoriteRemove').addEventListener('click', removeFavorite);
    document.getElementById('regionParentSelect').addEventListener('change', filterRegions);
    document.getElementById('btnLogout').addEventListener('click', logout);
    document.getElementById('btnPortalHome').addEventListener('click', () => {
        window.location.assign('/portal.html');
    });
    document.querySelector('.tab-menu-toggle').addEventListener('click', () => {
        document.querySelector('.sidebar').scrollIntoView({behavior: 'smooth', block: 'start'});
    });
    window.addEventListener('hashchange', routeToHash);
}

/** 표 행을 클릭하면 같은 표 안의 이전 선택을 해제하고 현재 선택 행을 눈에 띄게 표시한다. */
function bindTableRowSelection() {
    document.addEventListener('click', event => {
        const row = event.target.closest('tbody tr');
        if (!row) {
            return;
        }
        row.parentElement.querySelectorAll('tr.is-selected').forEach(item => {
            item.classList.remove('is-selected');
        });
        row.classList.add('is-selected');
    });
}

/** 권한 마스터와 사용자별 유효 권한·이력을 조회해 표와 입력 선택지를 갱신한다. */
async function loadAuthorityView(show = true) {
    try {
        const payload = await requestJson('/api/admin/authority-view');
        authorityAdmin = payload.data;
        renderAuthorityView(authorityAdmin);
        if (show) {
            showResult('권한 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        showError(error);
        throw error;
    }
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
    renderAuthMaster(view);
    const rows = document.getElementById('authorityUserRows');
    rows.replaceChildren();
    view.users.forEach(user => {
        const row = document.createElement('tr');
        appendCell(row, user.username);
        appendCell(row, user.organizationId);
        appendCell(row, user.effectiveAuthorityIds.join(', ') || '없음');
        appendCell(row, user.assignments.map(formatAssignment).join('\n') || '이력 없음');
        row.addEventListener('click', () => {
            document.getElementById('authorityUserSelect').value = user.username;
        });
        rows.appendChild(row);
    });

    fillSelect(
        document.getElementById('authorityUserSelect'),
        view.users.map(user => ({value: user.username, text: user.username})));
    fillSelect(
        document.getElementById('authoritySourceSelect'),
        view.users.map(user => ({value: user.username, text: `${user.username} (${user.organizationId})`})));
    fillSelect(
        document.getElementById('authorityIdSelect'),
        view.authorities.filter(authority => authority.active)
            .map(authority => ({value: authority.id, text: `${authority.id} - ${authority.name}`})));
    toggleAuthoritySource();
}

/** 권한 마스터 목록, 현재 유효 사용자 수와 관리 버전을 별도 화면에 표시한다. */
function renderAuthMaster(view) {
    const rows = document.getElementById('authorityMasterRows');
    rows.replaceChildren();
    view.authorities.forEach(authority => {
        const userCount = view.users.filter(user =>
            user.effectiveAuthorityIds.includes(authority.id)).length;
        const row = document.createElement('tr');
        appendCell(row, authority.id);
        appendCell(row, authority.name);
        appendCell(row, authority.active ? '활성' : '비활성');
        appendCell(row, `${userCount}명`);
        row.addEventListener('click', () => selectAuthMaster(authority));
        rows.appendChild(row);
    });
    document.getElementById('authorityVersion').textContent = `권한 버전 ${view.version}`;
}

/** 선택한 권한 마스터 행의 수정 가능 값을 입력 폼으로 복사한다. */
function selectAuthMaster(authority) {
    document.getElementById('authMasterId').value = authority.id;
    document.getElementById('authMasterName').value = authority.name;
    document.getElementById('authMasterSystem').value = authority.systemId || 'INFO';
    document.getElementById('authMasterClassification').value = authority.classificationId || '';
    document.getElementById('authMasterDescription').value = authority.description || '';
    document.getElementById('authMasterActive').checked = authority.active;
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
                    active: document.getElementById('authMasterActive').checked,
                    systemId: document.getElementById('authMasterSystem').value,
                    classificationId: nullableValue('authMasterClassification'),
                    description: document.getElementById('authMasterDescription').value
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
                publicMenu: document.getElementById('menuPublicInput').checked
            }
        });
        await Promise.all([loadMenuView(false), refreshContext()]);
        showResult('메뉴 저장 성공', payload);
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

/** 선택한 메뉴 행의 모든 편집 가능 속성을 입력 폼에 복사한다. */
function selectMenu(menu) {
    document.getElementById('menuIdInput').value = menu.id;
    document.getElementById('menuParentSelect').value = menu.parentId || '';
    document.getElementById('menuNameInput').value = menu.name;
    document.getElementById('menuPathInput').value = menu.path || '';
    document.getElementById('menuSortInput').value = menu.sortOrder;
    document.getElementById('menuActiveInput').checked = menu.active;
    document.getElementById('menuDisplayedInput').checked = menu.displayed;
    document.getElementById('menuPublicInput').checked = menu.publicMenu;
    document.getElementById('menuGrantSelect').value = menu.id;
    syncMenuGrant();
}

/** 메뉴의 활성, 화면 표시와 공용 접근 속성을 한눈에 볼 수 있는 요약 문자열로 만든다. */
function menuState(menu) {
    return [
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
    try {
        const payload = await requestJson('/api/admin/program-view');
        programAdmin = payload.data;
        renderProgramView(programAdmin);
        if (show) {
            showResult('프로그램 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        showError(error);
        throw error;
    }
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
                    active: document.getElementById('programActiveInput').checked
                }
            });
        await Promise.all([loadProgramView(false), refreshContext()]);
        showResult('프로그램 마스터 저장 성공', payload);
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
    renderProgramRows(view.programs);
    renderActionRows(view.actions);
    document.getElementById('programVersion').textContent = `프로그램 버전 ${view.version}`;

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
function renderProgramRows(programs) {
    const rows = document.getElementById('programRows');
    rows.replaceChildren();
    programs.forEach(program => {
        const row = document.createElement('tr');
        appendCell(row, program.id);
        appendCell(row, program.name);
        appendCell(row, program.description || '-');
        appendCell(row, program.active ? '활성' : '비활성');
        row.addEventListener('click', () => selectProgram(program));
        rows.appendChild(row);
    });
}

/** 프로그램 기능 표를 만들고 행 선택 시 기능 편집과 권한 매핑 대상을 함께 선택한다. */
function renderActionRows(actions) {
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
        appendCell(row, action.active ? '활성' : '비활성');
        row.addEventListener('click', () => selectAction(action));
        rows.appendChild(row);
    });
}

/** 선택한 프로그램 마스터 행을 수정 폼으로 복사한다. */
function selectProgram(program) {
    document.getElementById('programIdInput').value = program.id;
    document.getElementById('programNameInput').value = program.name;
    document.getElementById('programDescInput').value = program.description;
    document.getElementById('programActiveInput').checked = program.active;
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

/** 공통코드 그룹별 건수·버전을 조회하고 선택된 그룹의 전체 항목 조회를 이어서 수행한다. */
async function loadCodeAdmin(show = true) {
    try {
        const payload = await requestJson('/api/admin/common-code-view');
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
        await loadCodeGroup(false);
        if (show) {
            showResult('공통코드 그룹 현황 조회 성공', payload);
        }
        return payload;
    } catch (error) {
        showError(error);
        throw error;
    }
}

/** 선택 그룹의 비활성 항목까지 포함한 전체 목록과 버전을 조회해 표와 상위 코드 선택지를 갱신한다. */
async function loadCodeGroup(show = true) {
    const groupCode = document.getElementById('commonGroupSelect').value;
    if (!groupCode) {
        return null;
    }
    try {
        const payload = await requestJson(
            `/api/common-codes/${encodeURIComponent(groupCode)}/view?activeOnly=false`);
        commonCodeItems = payload.data.items;
        renderCodeRows(commonCodeItems);
        renderCodeParents(commonCodeItems, document.getElementById('codeValue').value);
        document.getElementById('codeVersion').textContent =
            `${groupCode} 버전: ${payload.data.version}`;
        if (show) {
            showResult(`${groupCode} 전체 코드 조회 성공`, payload);
        }
        return payload;
    } catch (error) {
        showError(error);
        throw error;
    }
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

/** 공통코드 목록의 모든 관리 속성을 표로 만들고 행 선택 시 수정 폼을 채운다. */
function renderCodeRows(items) {
    const rows = document.getElementById('commonCodeRows');
    rows.replaceChildren();
    items.forEach(item => {
        const row = document.createElement('tr');
        appendCell(row, item.code);
        appendCell(row, item.name);
        appendCell(row, item.parentCode || '-');
        appendCell(row, String(item.sortOrder));
        appendCell(row, item.active ? '활성' : '비활성');
        appendCell(row, `${item.validFrom} ~ ${item.validTo}`);
        row.addEventListener('click', () => selectCode(item));
        rows.appendChild(row);
    });
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

/** 현재 편집 코드 외의 같은 그룹 항목을 상위 코드 후보로 구성한다. */
function renderCodeParents(items, currentCode) {
    const options = [{value: '', text: '상위 코드 없음'}];
    items.filter(item => !item.parentCode && item.code !== currentCode).forEach(item => options.push({
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
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    let body = options.body;
    if (body !== undefined && typeof body !== 'string') {
        headers.set('Content-Type', 'application/json');
        body = JSON.stringify(body);
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrf) {
        headers.set(csrf.headerName, csrf.token);
    }

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
    nav.replaceChildren(buildMenuList(menuNodes));
}

/** 경로가 있는 메뉴는 클릭 가능한 링크로, 폴더 메뉴는 하위 항목을 묶는 레이블로 재귀 변환한다. */
function buildMenuList(nodes) {
    const list = document.createElement('ul');
    nodes.forEach(node => {
        const item = document.createElement('li');
        if (node.path) {
            const link = document.createElement('a');
            link.href = node.path;
            link.textContent = node.name;
            link.dataset.menuId = node.id;
            link.dataset.menuName = node.name;
            link.addEventListener('click', event => {
                if (!document.querySelector(node.path)) {
                    event.preventDefault();
                    showResult('메뉴 화면 연결 확인', {
                        menuId: node.id,
                        path: node.path,
                        message: '연결된 화면 영역이 없습니다. 메뉴 경로를 확인해 주세요.'
                    });
                    return;
                }
                event.preventDefault();
                selectWorkspacePage(node.path);
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

/** 업무 메뉴마다 독립 화면처럼 표시할 수 있도록 카드 영역에 페이지 식별자를 준비한다. */
function configureWorkspacePages() {
    document.querySelectorAll('main > section[id]').forEach(section => {
        section.dataset.workspacePage = `#${section.id}`;
        section.classList.add('workspace-page');
    });
    // 메뉴가 없는 보조 정보는 별도 탭을 만들지 않고 홈 화면의 구성 요소로 둔다.
    document.querySelectorAll('main > section.favorite-card, main > section.accounts-card:not([id])')
        .forEach(section => {
            section.dataset.workspacePage = '#home';
            section.classList.add('workspace-page');
        });
    restoreWorkspaceTabs();
}

/** 현재 URL 해시에 해당하는 한 화면만 표시하고, 메뉴와 상단 업무 탭의 활성 상태를 함께 갱신한다. */
function routeToHash() {
    const requestedPath = workspacePagePath();
    const target = document.querySelector(requestedPath);
    const path = target?.matches('section[data-workspace-page]') ? requestedPath : '#home';
    const selectedMenu = document.querySelector(`#menuTree a[href="${CSS.escape(path)}"]`);
    const pageName = selectedMenu?.dataset.menuName || pageTitle(path);

    openWorkspacePage(path, pageName);
    document.querySelectorAll('#menuTree a').forEach(link => {
        link.toggleAttribute('aria-current', link.getAttribute('href') === path);
    });
}

/** 메뉴에서 연 화면을 중복 없이 탭 목록에 보관한다. */
function openWorkspacePage(path, name) {
    if (!openedWorkspacePages.has(path)) {
        openedWorkspacePages.set(path, {name, pinned: path === '#home'});
    }
    document.querySelectorAll('main > section[data-workspace-page]').forEach(section => {
        const active = section.dataset.workspacePage === path;
        section.hidden = !active;
        section.classList.toggle('is-page-active', active);
    });
    document.querySelector('.hero').hidden = path !== '#home';
    saveWorkspaceTabs();
    renderWorkspaceTabs(path);
}

/** 열려 있는 업무 화면을 상단 탭으로 다시 그린다. */
function renderWorkspaceTabs(activePath) {
    const container = document.getElementById('workspaceTabs');
    container.replaceChildren();
    openedWorkspacePages.forEach((page, path) => {
        const tab = document.createElement('div');
        tab.className = `workspace-tab${path === activePath ? ' is-active' : ''}`;
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', String(path === activePath));
        const activate = document.createElement('button');
        activate.type = 'button';
        activate.className = 'workspace-tab-label';
        activate.textContent = page.name;
        activate.addEventListener('click', () => selectWorkspacePage(path));
        tab.appendChild(activate);
        if (!page.pinned) {
            const close = document.createElement('button');
            close.type = 'button';
            close.className = 'workspace-tab-close';
            close.textContent = '×';
            close.setAttribute('aria-label', `${page.name} 탭 닫기`);
            close.addEventListener('click', () => closeWorkspacePage(path));
            tab.appendChild(close);
        }
        container.appendChild(tab);
    });
}

/** 상단 탭 선택을 URL 해시에도 반영해 새로고침과 뒤로 가기 동작을 보존한다. */
function selectWorkspacePage(path) {
    if (workspacePagePath() === path) {
        routeToHash();
        return;
    }
    window.location.assign(workspacePageUrls[path] || `/${path}`);
}

/** 선택 탭을 닫으면 가장 최근에 남은 업무 화면을 활성화한다. */
function closeWorkspacePage(path) {
    if (!openedWorkspacePages.has(path) || openedWorkspacePages.get(path).pinned) {
        return;
    }
    const wasActive = workspacePagePath() === path;
    openedWorkspacePages.delete(path);
    saveWorkspaceTabs();
    if (wasActive) {
        const remainingPaths = [...openedWorkspacePages.keys()];
        selectWorkspacePage(remainingPaths.at(-1) || '#home');
    } else {
        renderWorkspaceTabs(workspacePagePath());
    }
}

/** 현재 독립 관리자 URL을 기존 화면 식별자와 연결한다. */
function workspacePagePath() {
    const matched = Object.entries(workspacePageUrls)
        .find(([, url]) => window.location.pathname === url);
    return matched?.[0] || window.location.hash || '#home';
}

/** 새 페이지로 이동해도 열린 탭 목록을 세션 범위에서 보존한다. */
function saveWorkspaceTabs() {
    const pages = [...openedWorkspacePages.entries()]
        .map(([path, page]) => ({path, name: page.name, pinned: page.pinned}));
    sessionStorage.setItem(workspaceStateKey, JSON.stringify(pages));
}

/** 저장된 탭 중 현재 화면으로 실제 열 수 있는 항목만 복원한다. */
function restoreWorkspaceTabs() {
    try {
        const pages = JSON.parse(sessionStorage.getItem(workspaceStateKey) || '[]');
        pages.forEach(page => {
            if (workspacePageUrls[page.path] && typeof page.name === 'string') {
                openedWorkspacePages.set(page.path, {
                    name: page.name,
                    pinned: page.path === '#home'
                });
            }
        });
    } catch {
        sessionStorage.removeItem(workspaceStateKey);
    }
}

/** 메뉴 트리가 아직 로드되지 않은 초기 단계에도 화면 이름을 안정적으로 표시한다. */
function pageTitle(path) {
    return document.querySelector(`${path} h2`)?.textContent?.trim() || '홈';
}

/** 모든 보호 버튼을 기본 거부한 뒤 서버가 허용한 프로그램 기능만 활성화한다. */
function enableAuthorizedButtons(actions) {
    document.querySelectorAll('[data-protected]').forEach(button => {
        button.disabled = true;
        button.title = '현재 사용자에게 허용되지 않은 기능입니다.';
    });
    actions.forEach(action => {
        const button = document.getElementById(action.componentId);
        if (button) {
            enableButton(button, action.actionId);
        }
        document.querySelectorAll(`[data-action="${action.actionId}"]`)
            .forEach(target => enableButton(target, action.actionId));
    });
}

/** 허용된 기능 버튼에 활성 상태와 확인용 기능 ID 툴팁을 적용한다. */
function enableButton(button, actionId) {
    button.disabled = false;
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
}

/** 오류 메시지, HTTP 상태와 서버 추적 ID를 함께 출력해 테스트와 장애 확인을 돕는다. */
function showError(error) {
    showResult(`요청 실패${error.status ? ` (HTTP ${error.status})` : ''}`, {
        message: error.message,
        traceId: error.payload?.error?.traceId || null,
        response: error.payload || null
    });
}
