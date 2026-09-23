'use strict';

const REFRESH_INTERVAL = 15000;

let csrf = null;
let portalContext = null;
let portalWorkspace = null;
let versionKey = null;
let refreshTimer = null;
let toastTimer = null;
let contentStatuses = [];
let contentPage = 1;
let myRequestPage = 0;
let requestOptionsLoaded = false;
let sidebarOpen = false;
const selectedContentIds = new Set();
const CONTENT_PAGE_SIZE = 4;
const contentItems = [
    {no: 108, type: '공지', title: '통합 업무포털 이용 안내', status: 'PUBLISHED', author: '운영관리자', date: '2026.09.01'},
    {no: 107, type: '업무', title: '주간 서비스 운영 현황', status: 'DRAFT', author: 'manager', date: '2026.08.31'},
    {no: 106, type: '자료', title: '정보보안 준수사항 안내', status: 'PUBLISHED', author: '보안담당자', date: '2026.08.28'},
    {no: 105, type: '소식', title: '시스템 기능 개선 결과', status: 'DRAFT', author: 'admin', date: '2026.08.25'},
    {no: 104, type: '업무', title: '프로젝트 권한관리 적용 안내', status: 'PUBLISHED', author: 'admin', date: '2026.08.22'},
    {no: 103, type: '자료', title: '업무 메뉴 이용 가이드', status: 'DRAFT', author: 'manager', date: '2026.08.20'},
    {no: 102, type: '공지', title: '개인정보 처리 유의사항', status: 'PUBLISHED', author: '보안담당자', date: '2026.08.18'},
    {no: 101, type: '소식', title: '서비스 오픈 안내', status: 'PUBLISHED', author: '운영관리자', date: '2026.08.14'}
];

/**
 * 사용자 포털의 이벤트를 먼저 연결하고 서버 기준 화면 컨텍스트를 초기화한다.
 * 자동 동기화는 탭이 보이는 동안만 수행해 불필요한 백그라운드 요청을 줄인다.
 */
document.addEventListener('DOMContentLoaded', () => {
    bindPortalEvents();
    loadPortal(false)
        .then(startAutoRefresh)
        .catch(showPortalError);
});

/** 메뉴, 업무 버튼, 검색, 모바일 탐색과 로그아웃에 필요한 정적 이벤트를 연결한다. */
function bindPortalEvents() {
    document.getElementById('btnSync').addEventListener('click', () => loadPortal(true).catch(() => undefined));
    document.getElementById('btnLogout').addEventListener('click', logout);
    document.getElementById('btnMenuToggle').addEventListener('click', toggleSidebar);
    document.getElementById('btnMobileSearch').addEventListener('click', toggleMobileSearch);
    document.getElementById('sidebarBackdrop').addEventListener('click', closeSidebar);
    document.getElementById('serviceSearch').addEventListener('input', filterServices);
    document.getElementById('btnShowAll').addEventListener('click', showAllServices);
    document.getElementById('btnContentRead').addEventListener('click', searchContents);
    document.getElementById('btnContentSave').addEventListener('click', openContentDialog);
    document.getElementById('btnContentPublish').addEventListener('click', publishSelectedContents);
    document.getElementById('contentSearchInput').addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            searchContents();
        }
    });
    document.getElementById('contentSelectAll').addEventListener('change', toggleCurrentPageSelection);
    document.getElementById('contentForm').addEventListener('submit', saveNewContent);
    document.getElementById('btnContentDialogClose').addEventListener('click', closeContentDialog);
    document.getElementById('btnContentCancel').addEventListener('click', closeContentDialog);
    document.getElementById('authorityRequestForm').addEventListener('submit', submitAuthorityRequest);
    document.getElementById('requestType').addEventListener('change', toggleRequestDelegator);
    document.getElementById('myRequestStatus').addEventListener('change', () => loadMyAuthorityRequests(0));
    document.querySelectorAll('[data-go-home]').forEach(button => {
        button.addEventListener('click', () => navigateTo('#home'));
    });
    window.addEventListener('hashchange', showCurrentPage);
    window.addEventListener('keydown', handlePortalKeyboard);
    window.addEventListener('resize', syncPortalResponsiveState);
    document.getElementById('portalSidebar').addEventListener('keydown', trapSidebarFocus);
    document.addEventListener('click', event => {
        const blocked = event.target.closest('[data-protected][aria-disabled="true"]');
        if (blocked) {
            event.preventDefault();
            event.stopImmediatePropagation();
            showToast('현재 사용자에게 허용되지 않은 기능입니다.');
        }
    }, true);
    syncPortalResponsiveState();
}

function syncPortalResponsiveState() {
    if (!window.matchMedia('(max-width: 1024px)').matches) {
        sidebarOpen = false;
        document.getElementById('portalSidebar').classList.remove('open');
        document.getElementById('portalSidebar').removeAttribute('aria-hidden');
        document.getElementById('sidebarBackdrop').classList.remove('show');
        document.getElementById('portalMain').inert = false;
        document.querySelector('.header-actions').inert = false;
    } else if (!sidebarOpen) {
        document.getElementById('portalSidebar').setAttribute('aria-hidden', 'true');
    }
}

/**
 * 부트스트랩과 사용자 업무 상태 공통코드를 함께 조회해 하나의 사용자 화면을 다시 구성한다.
 * 권한·메뉴·코드 버전이 바뀌면 관리 화면의 변경이 반영됐음을 사용자에게 알린다.
 */
async function loadPortal(notify) {
    setSyncState('최신 권한 확인 중', false);
    try {
        const [bootstrapPayload, statusPayload] = await Promise.all([
            requestJson('/api/portal/bootstrap'),
            requestJson('/api/common-codes/ARTICLE_STATUS/view')
        ]);
        const workspace = bootstrapPayload.data.workspace;
        const context = workspace.context;
        const statuses = statusPayload.data;
        // 날짜 경계에서 권한이 만료되거나 메뉴 가시성이 달라져도 실제 응답 변화로 갱신한다.
        const nextKey = JSON.stringify([workspace, statuses]);
        const initialLoad = versionKey === null;
        const changed = !initialLoad && versionKey !== nextKey;

        csrf = bootstrapPayload.data.csrf;
        portalContext = context;
        portalWorkspace = workspace;
        versionKey = nextKey;

        // 버전이 그대로인 자동 확인에서는 DOM을 다시 만들지 않아 스크롤·검색·선택 상태를 보존한다.
        if (initialLoad || changed || notify) {
            const scrollTop = window.scrollY;
            const searchValue = document.getElementById('serviceSearch').value;
            const selectedContents = new Set(Array.from(
                document.querySelectorAll('#contentRows input[type="checkbox"]:checked'),
                input => input.value));

            renderUser(context.user);
            renderPortalMenu(context.menus);
            renderQuickServices(workspace.services, context.favorites);
            renderDynamicPages(context.menus);
            enableActions(context.programActions);
            renderStatuses(statuses);
            renderContentRows(statuses.items, selectedContents);
            renderSummary(workspace);
            document.getElementById('serviceSearch').value = searchValue;
            filterServices();
            showCurrentPage({scroll: initialLoad});
            if (!initialLoad) {
                window.scrollTo({top: scrollTop, behavior: 'auto'});
            }
        } else {
            updateLastSync();
        }
        setSyncState('권한과 메뉴 최신 상태', true);
        if (flattenMenus(context.menus).some(menu => menu.path === '#authority-request')) {
            await initializeAuthorityRequest();
        }

        if (changed) {
            showToast('관리자가 변경한 권한·메뉴·공통코드가 반영되었습니다.');
        } else if (notify) {
            showToast('최신 권한과 메뉴를 확인했습니다.');
        }
        return context;
    } catch (error) {
        setSyncState('동기화 확인 필요', false);
        showPortalError(error);
        throw error;
    }
}

/** 자동 새로고침 타이머를 하나만 유지하고 화면이 보이는 경우에만 서버 버전을 확인한다. */
function startAutoRefresh() {
    if (refreshTimer) {
        window.clearInterval(refreshTimer);
    }
    refreshTimer = window.setInterval(() => {
        if (!document.hidden) {
            loadPortal(false).catch(() => undefined);
        }
    }, REFRESH_INTERVAL);
}

/** 현재 사용자명, 조직, 아바타와 유효 권한을 포털 헤더와 환영 영역에 반영한다. */
function renderUser(user) {
    const displayName = user.username;
    const initial = displayName.slice(0, 1).toUpperCase();
    document.getElementById('portalUsername').textContent = displayName;
    document.getElementById('portalOrg').textContent = `${user.organizationId} 소속`;
    document.getElementById('userAvatar').textContent = initial;
    document.getElementById('welcomeName').textContent = displayName;
    document.getElementById('sidebarGreeting').textContent = `${displayName}님의 업무공간`;

    const chips = document.getElementById('authorityChips');
    chips.replaceChildren();
    portalWorkspace.authorities.forEach(authority => {
        const chip = element('div', 'workspace-authority');
        chip.append(element('strong', '', authority.name), element('small', '', authority.id));
        chips.appendChild(chip);
    });
    if (user.effectiveAuthorityIds.length === 0) {
        const chip = document.createElement('span');
        chip.textContent = '부여된 업무 권한 없음';
        chips.appendChild(chip);
    }

    const today = new Date();
    const formatted = new Intl.DateTimeFormat('ko-KR', {
        year: 'numeric', month: 'long', day: 'numeric', weekday: 'long'
    }).format(today);
    document.getElementById('todayText').textContent =
        `${formatted} · 현재 권한으로 이용 가능한 업무를 확인하세요.`;

}

/** 서버가 허용한 메뉴 계층을 사용자 포털의 사이드바 메뉴로 변환한다. */
function renderPortalMenu(menuNodes) {
    const menu = document.getElementById('portalMenu');
    menu.replaceChildren(buildMenuList(menuNodes));
    document.getElementById('adminConsoleLink').hidden = !portalWorkspace.adminAvailable;
    document.getElementById('requestAccessLink').hidden =
        !flattenMenus(menuNodes).some(menu => menu.path === '#authority-request');
}

/** 경로 메뉴는 페이지 전환 링크로, 상위 폴더는 하위 메뉴를 설명하는 레이블로 재귀 생성한다. */
function buildMenuList(nodes) {
    const list = document.createElement('ul');
    nodes.forEach(node => {
        const item = document.createElement('li');
        if (node.path) {
            const link = document.createElement('a');
            link.href = node.path;
            link.dataset.menuId = node.id;
            link.dataset.searchText = `${node.name} ${node.id}`.toLowerCase();
            link.textContent = node.name;
            link.addEventListener('click', closeSidebar);
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

/** 관심 메뉴를 우선한 접근 가능 서비스를 홈의 빠른 서비스 카드로 만든다. */
function renderQuickServices(services, favorites = []) {
    const favoriteIds = new Set(favorites.map(favorite => favorite.menuId));
    const container = document.getElementById('quickServices');
    container.replaceChildren();
    [...services].sort((a, b) => Number(favoriteIds.has(b.id)) - Number(favoriteIds.has(a.id)))
        .forEach(service => container.appendChild(createServiceCard(service, true)));
    if (services.length === 0) {
        container.append(element('div', 'workspace-empty', '현재 이용 가능한 업무 메뉴가 없습니다. 필요한 업무 권한을 신청해 주세요.'));
    }
}

function element(tag, className = '', text = '') {
    const node = document.createElement(tag);
    node.className = className;
    node.textContent = text;
    return node;
}

/** 메뉴 등록과 프로그램 등록, 실제 기능 인가의 연결을 사용자 데이터로 표시한다. */
function createServiceCard(service, showNavigation) {
    const card = element('article', 'workspace-service');
    if (showNavigation) {
        card.dataset.searchText = JSON.stringify(service).toLowerCase();
    }
    const head = element('div', 'workspace-service-head');
    const name = element('div', 'workspace-service-name');
    name.append(element('span', 'workspace-service-icon', service.name.slice(0, 1)));
    const title = element('div');
    title.append(element('h3', '', service.name), element('small', 'workspace-id', service.id));
    name.append(title);
    head.append(name, element('span', 'menu-access-badge', '메뉴 사용 가능'));
    card.append(head);
    const grantText = service.publicMenu ? '모든 로그인 사용자' : service.grantedBy.map(item => item.name).join(' · ');
    card.append(element('p', 'workspace-grant', '접근 권한 · ' + (grantText || '현재 유효 권한')));
    if (service.programs.length === 0) {
        card.append(element('p', 'workspace-program-empty', '연결된 프로그램이 없습니다. 메뉴만 등록된 상태입니다.'));
    }
    service.programs.forEach(program => {
        const block = element('section', 'workspace-program');
        const programHead = element('div', 'workspace-program-head');
        const label = element('div');
        label.append(element('small', '', '연결 프로그램'), element('strong', '', program.name));
        programHead.append(label, element('span', 'workspace-id', program.id));
        block.append(programHead);
        if (!program.active) block.append(element('p', 'program-inactive', '이 프로그램은 현재 사용 중지 상태입니다.'));
        const actions = element('div', 'workspace-actions');
        program.actions.forEach(action => {
            const status = !action.active ? 'inactive' : (action.allowed ? 'allowed' : 'denied');
            const statusLabel = !action.active ? '사용 중지' : (action.allowed ? '허용됨' : '권한 없음');
            const pill = element('span', 'permission-pill ' + status, (action.allowed ? '✓ ' : '− ') + action.label + ' · ' + statusLabel);
            if (action.grantedBy.length) pill.title = '적용 권한: ' + action.grantedBy.map(item => item.name).join(', ');
            actions.append(pill);
        });
        block.append(actions);
        if (program.screen !== 'NONE') {
            const launch = element('div', 'workspace-program-launch');
            launch.append(element('small', '', `실행 화면 · ${program.screenName}`));
            const open = element('button', 'workspace-open', `${program.screenName} 열기 →`);
            open.type = 'button';
            open.disabled = !program.launchable;
            open.title = program.launchable ? '연결된 업무 화면으로 이동' : '프로그램 기능과 연결 화면의 사용 권한이 필요합니다.';
            open.addEventListener('click', () => navigateTo(program.launchPath));
            launch.append(open);
            if (!program.launchable) launch.append(element('small', '', '프로그램 기능과 연결 화면의 권한을 확인해 주세요.'));
            block.append(launch);
        } else {
            block.append(element('small', 'workspace-note', '실행 화면 미연결'));
        }
        card.append(block);
    });
    if (showNavigation) {
        const footer = element('div', 'workspace-service-footer');
        const implemented = service.programs.some(program => program.launchable && program.launchPath === service.path);
        footer.append(element('small', '', implemented ? '업무 화면 연결됨' : '메뉴·프로그램 연결 정보'));
        const open = element('button', 'workspace-open', implemented ? '업무 열기 →' : '연결 정보 보기 →');
        open.type = 'button';
        open.addEventListener('click', () => navigateTo(service.path));
        footer.append(open);
        card.append(footer);
    }
    return card;
}

/** 등록된 업무 메뉴의 연결 정보 화면을 구성한다. */

function renderDynamicPages(menuNodes) {
    const host = document.getElementById('dynamicPages');
    host.replaceChildren();
    flattenMenus(menuNodes)
        .filter(menu => menu.path && !['#home', '#content', '#authority-request'].includes(menu.path))
        .forEach(menu => {
            if (!Array.from(host.children).some(page => page.dataset.route === menu.path)) {
                host.appendChild(createDynamicPage(menu));
            }
        });
}

/** 한 메뉴 정의를 접근 가능한 독립 포털 페이지 요소로 변환한다. */
function createDynamicPage(menu) {
    const section = element('section', 'portal-page generic-page');
    section.id = menu.path.slice(1);
    section.dataset.route = menu.path;
    section.hidden = true;
    const pageHead = element('div', 'page-head');
    const copy = element('div');
    copy.append(element('p', '', '홈 / 내 업무 메뉴'), element('h1', '', menu.name),
        element('span', '', '등록된 메뉴와 프로그램의 연결 정보입니다.'));
    const home = element('button', 'secondary-button', '업무공간으로');
    home.type = 'button';
    home.addEventListener('click', () => navigateTo('#home'));
    pageHead.append(copy, home);
    section.append(pageHead);
    portalWorkspace.services.filter(service => service.path === menu.path)
        .forEach(service => section.append(createServiceCard(service, false)));
    section.append(element('p', 'workspace-note', '관리자가 지정한 실행 화면을 프로그램의 열기 버튼으로 이용할 수 있습니다.'));
    return section;
}

/** 서버가 반환한 기능 권한만 사용자 콘텐츠 화면의 조회·작성·게시 버튼에 활성화한다. */
function enableActions(actions) {
    document.querySelectorAll('[data-protected]').forEach(button => {
        button.dataset.authorized = 'false';
        button.disabled = false;
        button.setAttribute('aria-disabled', 'true');
        button.setAttribute('aria-describedby', 'portalProtectedHelp');
        button.title = '현재 사용자에게 허용되지 않은 기능입니다.';
    });
    actions.filter(action => action.menuId === 'CONTENT_LIST' && action.programId === 'CONTENT').forEach(action => {
        const button = document.getElementById(action.componentId);
        if (button) {
            button.dataset.authorized = 'true';
            button.disabled = false;
            button.setAttribute('aria-disabled', 'false');
            button.removeAttribute('aria-describedby');
            button.title = `${action.label} 권한 허용`;
        }
    });
    // 게시 버튼은 권한뿐 아니라 실제 선택 건수가 있어야 활성화한다.
    updateContentSelectionState();
}

/** 공통코드 관리에서 활성 상태인 게시 상태만 사용자 홈의 업무 선택지에 표시한다. */
function renderStatuses(groupView) {
    contentStatuses = groupView.items;
    const container = document.getElementById('statusCodes');
    container.replaceChildren();
    groupView.items.forEach(item => {
        const row = document.createElement('div');
        row.className = 'status-item';
        const dot = document.createElement('i');
        const name = document.createElement('strong');
        name.textContent = item.name;
        const code = document.createElement('code');
        code.textContent = item.code;
        row.append(dot, name, code);
        container.appendChild(row);
    });
    if (groupView.items.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'status-empty';
        empty.textContent = '현재 사용 가능한 업무 상태가 없습니다.';
        container.appendChild(empty);
    }
    document.getElementById('codeVersion').textContent = `ARTICLE_STATUS v${groupView.version}`;
    const statusSelect = document.getElementById('contentStatusInput');
    statusSelect.replaceChildren();
    contentStatuses.forEach(status => {
        const option = document.createElement('option');
        option.value = status.code;
        option.textContent = status.name;
        statusSelect.appendChild(option);
    });
    statusSelect.disabled = contentStatuses.length === 0;
    document.querySelector('#contentForm button[type="submit"]').disabled = contentStatuses.length === 0;
}

/** 사용자 콘텐츠 목록의 상태명을 서버 공통코드와 연결해 관리 변경 결과를 목록에도 반영한다. */
function renderContentRows(statuses, selectedContents = new Set()) {
    selectedContents.forEach(id => selectedContentIds.add(String(id)));
    contentStatuses = statuses;
    const items = filteredContents();
    const totalPages = Math.max(1, Math.ceil(items.length / CONTENT_PAGE_SIZE));
    contentPage = Math.min(contentPage, totalPages);
    const pageItems = items.slice((contentPage - 1) * CONTENT_PAGE_SIZE, contentPage * CONTENT_PAGE_SIZE);
    const body = document.getElementById('contentRows');
    body.replaceChildren();
    pageItems.forEach(item => {
        const status = contentStatuses.find(candidate => candidate.code === item.status)
            || {code: item.status, name: item.status};
        const row = document.createElement('tr');
        const checkCell = document.createElement('td');
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = String(item.no);
        checkbox.checked = selectedContentIds.has(String(item.no));
        checkbox.disabled = document.getElementById('btnContentPublish').dataset.authorized !== 'true';
        checkbox.setAttribute('aria-label', `${item.no}번 콘텐츠 선택`);
        checkbox.addEventListener('change', updateContentSelectionState);
        checkCell.appendChild(checkbox);
        appendCell(row, String(item.no));
        appendCell(row, item.type);
        appendCell(row, item.title);
        const statusCell = document.createElement('td');
        const badge = document.createElement('span');
        badge.className = `state-badge ${status.code.toLowerCase()}`;
        badge.textContent = status.name;
        statusCell.appendChild(badge);
        // 관리 화면에서 정한 ARTICLE_STATUS 이름을 제목 다음 상태 열에 실제로 삽입한다.
        row.appendChild(statusCell);
        appendCell(row, item.author);
        appendCell(row, item.date);
        row.prepend(checkCell);
        ['선택', '번호', '구분', '제목', '상태', '작성자', '등록일'].forEach((label, index) => {
            const cell = row.children[index];
            if (cell) cell.dataset.label = label;
        });
        body.appendChild(row);
    });
    if (pageItems.length === 0) {
        const row = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 7;
        cell.textContent = '검색 조건에 맞는 콘텐츠가 없습니다.';
        row.appendChild(cell);
        body.appendChild(row);
    }
    document.getElementById('contentResultSummary').textContent =
        `총 ${items.length}건 · ${contentPage}/${totalPages}페이지`;
    renderContentPagination(totalPages);
    updateContentSelectionState();
}

function filteredContents() {
    const field = document.getElementById('contentFilterField').value;
    const query = document.getElementById('contentSearchInput').value.trim().toLowerCase();
    if (!query) {
        return contentItems;
    }
    return contentItems.filter(item => {
        if (field === 'status') {
            const status = contentStatuses.find(candidate => candidate.code === item.status);
            return `${item.status} ${status?.name || ''}`.toLowerCase().includes(query);
        }
        return String(item[field] || '').toLowerCase().includes(query);
    });
}

function renderContentPagination(totalPages) {
    const pagination = document.getElementById('contentPagination');
    pagination.replaceChildren();
    const addButton = (label, page, disabled = false, current = false) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.disabled = disabled;
        button.classList.toggle('current', current);
        if (current) {
            button.setAttribute('aria-current', 'page');
        }
        button.addEventListener('click', () => {
            contentPage = page;
            renderContentRows(contentStatuses, selectedContentIds);
        });
        pagination.appendChild(button);
    };
    addButton('‹', contentPage - 1, contentPage === 1);
    for (let page = 1; page <= totalPages; page += 1) {
        addButton(String(page), page, false, page === contentPage);
    }
    addButton('›', contentPage + 1, contentPage === totalPages);
}

/** 현재 메뉴·권한·상태 코드 수와 마지막 동기화 시각을 홈 요약 카드에 표시한다. */
function renderSummary(workspace) {
    document.getElementById('menuCount').textContent = String(workspace.services.length);
    document.getElementById('authorityCount').textContent = String(workspace.authorities.length);
    document.getElementById('programCount').textContent = String(new Set(workspace.services.flatMap(menu => menu.programs.map(program => program.id))).size);
    document.getElementById('actionCount').textContent = String(workspace.services.flatMap(menu => menu.programs.flatMap(program => program.actions)).filter(action => action.allowed).length);
    updateLastSync();
}

/** 마지막 서버 확인 시각을 표시한다. */

function updateLastSync() {
    document.getElementById('lastSync').textContent = new Intl.DateTimeFormat('ko-KR', {
        hour: '2-digit', minute: '2-digit', hour12: false
    }).format(new Date());
}

/** URL 해시와 현재 허용 메뉴를 비교해 한 개 사용자 페이지와 활성 메뉴만 표시한다. */
function showCurrentPage({scroll = true} = {}) {
    if (!portalContext) {
        return;
    }
    const allowedPaths = new Set(flattenMenus(portalContext.menus)
        .filter(menu => menu.path)
        .map(menu => menu.path));
    let route = window.location.hash || '#home';
    const legacyAdminRoutes = ['#authority-master', '#authority', '#authority-requests', '#menu', '#program', '#project', '#common-code', '#audit'];
    if (window.location.pathname === '/' && portalWorkspace.adminAvailable
        && !allowedPaths.has(route) && legacyAdminRoutes.includes(route)) {
        window.location.replace('/admin' + route);
        return;
    }
    if (!allowedPaths.has(route) || !Array.from(document.querySelectorAll('[data-route]')).some(page => page.dataset.route === route)) {
        route = '#home';
        if (window.location.hash !== route) {
            history.replaceState(null, '', route);
        }
    }

    document.querySelectorAll('.portal-page').forEach(page => {
        const active = page.dataset.route === route;
        page.hidden = !active;
        page.classList.toggle('active', active);
    });
    document.querySelectorAll('#portalMenu a').forEach(link => {
        if (link.getAttribute('href') === route) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });
    if (scroll) {
        window.scrollTo({top: 0, behavior: 'smooth'});
    }
}

/** 빠른 서비스 또는 버튼이 선택한 권한 메뉴 경로로 이동하도록 URL 해시를 변경한다. */
function navigateTo(path) {
    if (window.location.hash === path) {
        showCurrentPage();
    } else {
        window.location.hash = path;
    }
}

/** 검색어와 메뉴명·ID를 비교해 빠른 서비스와 사이드바 경로 메뉴를 동시에 필터링한다. */
function filterServices() {
    const query = document.getElementById('serviceSearch').value.trim().toLowerCase();
    document.querySelectorAll('[data-search-text]').forEach(element => {
        const matches = !query || element.dataset.searchText.includes(query);
        if (element.classList.contains('workspace-service')) {
            element.hidden = !matches;
        } else {
            element.closest('li').hidden = !matches;
        }
    });
    Array.from(document.querySelectorAll('#portalMenu li')).reverse().forEach(item => {
        if (item.querySelector(':scope > span')) {
            const links = Array.from(item.querySelectorAll('a[data-search-text]'));
            item.hidden = Boolean(query) && links.length > 0
                && links.every(link => link.closest('li').hidden);
        }
    });
    const visibleTiles = Array.from(document.querySelectorAll('#quickServices .workspace-service'))
        .filter(element => !element.hidden).length;
    let status = document.getElementById('serviceSearchStatus');
    if (!status) {
        status = document.createElement('p');
        status.id = 'serviceSearchStatus';
        status.className = 'service-search-empty';
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        document.getElementById('quickServices').after(status);
    }
    const empty = Boolean(query) && visibleTiles === 0;
    status.hidden = !empty;
    status.replaceChildren();
    if (empty) {
        const text = document.createElement('span');
        text.textContent = `“${query}”에 해당하는 서비스가 없습니다.`;
        const reset = document.createElement('button');
        reset.type = 'button';
        reset.textContent = '검색 초기화';
        reset.addEventListener('click', () => {
            document.getElementById('serviceSearch').value = '';
            filterServices();
            document.getElementById('serviceSearch').focus();
        });
        status.append(text, reset);
    }
}

/** 서비스 검색어를 지우고 홈의 전체 빠른 서비스 카드를 다시 표시한다. */
function showAllServices() {
    document.getElementById('quickServices').classList.add('show-all');
    const search = document.getElementById('serviceSearch');
    search.value = '';
    filterServices();
    openMobileSearch();
    search.focus();
}

/** Ctrl+K 단축키가 브라우저 기본 동작 대신 포털 서비스 검색창으로 이동하게 한다. */
function focusSearch(event) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        openMobileSearch();
        document.getElementById('serviceSearch').focus();
    }
}

/** 권한 확인 API를 통과한 뒤 입력한 조건으로 콘텐츠 목록을 필터링한다. */
async function searchContents() {
    try {
        await requestJson('/api/content/preview');
        contentPage = 1;
        renderContentRows(contentStatuses, selectedContentIds);
        showToast(`${filteredContents().length}건의 콘텐츠를 조회했습니다.`);
    } catch (error) {
        showPortalError(error);
    }
}

function toggleCurrentPageSelection(event) {
    document.querySelectorAll('#contentRows input[type="checkbox"]').forEach(checkbox => {
        checkbox.checked = event.target.checked;
        if (checkbox.checked) {
            selectedContentIds.add(checkbox.value);
        } else {
            selectedContentIds.delete(checkbox.value);
        }
    });
    updateContentSelectionState();
}

function updateContentSelectionState() {
    const visible = Array.from(document.querySelectorAll('#contentRows input[type="checkbox"]'));
    visible.forEach(checkbox => {
        if (checkbox.checked) {
            selectedContentIds.add(checkbox.value);
        } else {
            selectedContentIds.delete(checkbox.value);
        }
    });
    const selectAll = document.getElementById('contentSelectAll');
    const hasPublishAuthority = document.getElementById('btnContentPublish').dataset.authorized === 'true';
    const hasPublishStatus = contentStatuses.some(status => status.code === 'PUBLISHED');
    const canPublish = hasPublishAuthority && hasPublishStatus;
    const saveButton = document.getElementById('btnContentSave');
    saveButton.setAttribute('aria-disabled', String(saveButton.dataset.authorized !== 'true' || contentStatuses.length === 0));
    if (contentStatuses.length === 0) saveButton.title = '관리자가 활성화한 업무 상태가 없습니다.';
    const selectedVisible = visible.filter(checkbox => checkbox.checked).length;
    selectAll.disabled = !canPublish || visible.length === 0;
    selectAll.checked = visible.length > 0 && selectedVisible === visible.length;
    selectAll.indeterminate = selectedVisible > 0 && selectedVisible < visible.length;
    const publishButton = document.getElementById('btnContentPublish');
    publishButton.disabled = false;
    publishButton.setAttribute('aria-disabled', String(!canPublish || selectedContentIds.size === 0));
    if (!canPublish || selectedContentIds.size === 0) {
        publishButton.setAttribute('aria-describedby', 'portalProtectedHelp');
    } else {
        publishButton.removeAttribute('aria-describedby');
    }
    publishButton.title = !hasPublishAuthority
        ? '현재 사용자에게 콘텐츠 게시 권한이 없습니다.'
        : (!hasPublishStatus ? '관리자가 게시 상태를 활성화하지 않았습니다.'
            : (selectedContentIds.size === 0 ? '게시할 콘텐츠를 먼저 선택해 주세요.' : '선택 콘텐츠 게시'));
    publishButton.textContent = selectedContentIds.size
        ? `선택 게시 (${selectedContentIds.size})`
        : '선택 게시';
}

function openContentDialog() {
    if (contentStatuses.length === 0) {
        showToast('사용 가능한 업무 상태가 없습니다. 관리자에게 문의해 주세요.');
        return;
    }
    document.getElementById('contentForm').reset();
    document.getElementById('contentDialog').showModal();
    document.getElementById('contentTitleInput').focus();
}

function closeContentDialog() {
    document.getElementById('contentDialog').close();
}

async function saveNewContent(event) {
    event.preventDefault();
    if (!contentStatuses.some(status => status.code === document.getElementById('contentStatusInput').value)) {
        showToast('현재 사용 가능한 업무 상태를 선택해 주세요.');
        return;
    }
    const title = document.getElementById('contentTitleInput').value.trim();
    if (!title) {
        document.getElementById('contentTitleInput').focus();
        return;
    }
    try {
        await requestJson('/api/content/save', {method: 'POST'});
        contentItems.unshift({
            no: Math.max(...contentItems.map(item => item.no), 0) + 1,
            type: document.getElementById('contentTypeInput').value,
            title,
            status: document.getElementById('contentStatusInput').value,
            author: portalContext.user.username,
            date: new Date().toISOString().slice(0, 10).replaceAll('-', '.')
        });
        contentPage = 1;
        closeContentDialog();
        renderContentRows(contentStatuses, selectedContentIds);
        showToast('새 콘텐츠를 저장했습니다.');
    } catch (error) {
        showPortalError(error);
    }
}

async function publishSelectedContents() {
    if (selectedContentIds.size === 0) {
        showToast('게시할 콘텐츠를 선택해 주세요.');
        return;
    }
    const publishStatus = contentStatuses.find(status => status.code === 'PUBLISHED');
    if (!publishStatus) {
        showToast('게시 상태가 비활성화되어 게시할 수 없습니다.');
        return;
    }
    try {
        await requestJson('/api/content/publish', {method: 'POST'});
        contentItems.forEach(item => {
            if (selectedContentIds.has(String(item.no))) {
                item.status = publishStatus.code;
            }
        });
        const publishedCount = selectedContentIds.size;
        selectedContentIds.clear();
        renderContentRows(contentStatuses, selectedContentIds);
        showToast(`${publishedCount}건의 콘텐츠를 게시했습니다.`);
    } catch (error) {
        showPortalError(error);
    }
}

async function initializeAuthorityRequest() {
    if (!requestOptionsLoaded) {
        const payload = await requestJson('/api/authority-requests/options');
        const select = document.getElementById('requestAuthority');
        select.replaceChildren();
        payload.data.authorities.forEach(authority => {
            const option = document.createElement('option');
            option.value = authority.id;
            option.textContent = `${authority.name} (${authority.id})`;
            select.appendChild(option);
        });
        requestOptionsLoaded = true;
        const today = new Date();
        document.getElementById('requestValidFrom').value = toDateInput(today);
        const end = new Date(today);
        end.setMonth(end.getMonth() + 3);
        document.getElementById('requestValidTo').value = toDateInput(end);
        toggleRequestDelegator();
    }
    await loadMyAuthorityRequests(myRequestPage);
}

function toDateInput(date) {
    const offset = date.getTimezoneOffset();
    return new Date(date.getTime() - offset * 60000).toISOString().slice(0, 10);
}

function toggleRequestDelegator() {
    const delegated = document.getElementById('requestType').value === 'DELEGATED';
    const field = document.getElementById('requestDelegatorField');
    const input = document.getElementById('requestDelegatedBy');
    field.hidden = !delegated;
    input.required = delegated;
    if (!delegated) input.value = '';
}

async function submitAuthorityRequest(event) {
    event.preventDefault();
    const form = event.currentTarget;
    clearFormErrors(form);
    const from = document.getElementById('requestValidFrom');
    const to = document.getElementById('requestValidTo');
    if (to.value && from.value && to.value < from.value) {
        to.setCustomValidity('종료일은 시작일보다 빠를 수 없습니다.');
    } else {
        to.setCustomValidity('');
    }
    if (!form.checkValidity()) {
        showFormErrors(form);
        return;
    }
    const button = document.getElementById('btnSubmitAuthorityRequest');
    button.disabled = true;
    try {
        await requestJson('/api/authority-requests', {
            method: 'POST',
            body: {
                authorityId: document.getElementById('requestAuthority').value,
                assignmentType: document.getElementById('requestType').value,
                validFrom: from.value,
                validTo: to.value,
                delegatedBy: document.getElementById('requestType').value === 'DELEGATED'
                    ? document.getElementById('requestDelegatedBy').value.trim() : null,
                reason: document.getElementById('requestReason').value.trim()
            }
        });
        document.getElementById('requestReason').value = '';
        showToast('권한 신청을 접수했습니다.');
        await loadMyAuthorityRequests(0);
    } catch (error) {
        showPortalError(error);
    } finally {
        button.disabled = false;
    }
}

function clearFormErrors(form) {
    form.querySelectorAll('[aria-invalid="true"]').forEach(control => control.removeAttribute('aria-invalid'));
    form.querySelectorAll('.field-error').forEach(error => { error.textContent = ''; });
}

function showFormErrors(form) {
    const invalid = Array.from(form.querySelectorAll(':invalid'));
    invalid.forEach(control => {
        control.setAttribute('aria-invalid', 'true');
        const error = control.parentElement.querySelector('.field-error');
        if (error) error.textContent = control.validationMessage;
    });
    invalid[0]?.focus();
}

async function loadMyAuthorityRequests(page = myRequestPage) {
    myRequestPage = page;
    const query = new URLSearchParams({page: String(page), size: '10', sort: 'REQUESTED_AT', direction: 'DESC'});
    const status = document.getElementById('myRequestStatus').value;
    if (status) query.set('status', status);
    const host = document.getElementById('myRequestList');
    host.innerHTML = '<p class="request-empty">신청 내역을 불러오는 중입니다.</p>';
    try {
        const payload = await requestJson(`/api/authority-requests/mine?${query}`);
        renderMyAuthorityRequests(payload.data);
    } catch (error) {
        host.innerHTML = '<p class="request-empty error">신청 내역을 불러오지 못했습니다.</p>';
        showPortalError(error);
    }
}

function renderMyAuthorityRequests(result) {
    const host = document.getElementById('myRequestList');
    host.replaceChildren();
    result.content.forEach(request => {
        const article = document.createElement('article');
        const heading = document.createElement('div');
        const title = document.createElement('strong');
        title.textContent = request.authorityId;
        const badge = document.createElement('span');
        badge.className = `request-state ${request.status.toLowerCase()}`;
        badge.textContent = requestStatusLabel(request.status);
        heading.append(title, badge);
        const period = document.createElement('p');
        period.textContent = `${request.assignmentType === 'DELEGATED' ? '위임' : '직접'} · ${request.validFrom} ~ ${request.validTo}`;
        const reason = document.createElement('p');
        reason.textContent = request.requestReason;
        article.append(heading, period, reason);
        if (request.decisionReason) {
            const decision = document.createElement('small');
            decision.textContent = `처리 사유: ${request.decisionReason}`;
            article.appendChild(decision);
        }
        if (request.status === 'REQUESTED') {
            const cancel = document.createElement('button');
            cancel.type = 'button';
            cancel.textContent = '신청 취소';
            cancel.addEventListener('click', () => cancelAuthorityRequest(request));
            article.appendChild(cancel);
        }
        host.appendChild(article);
    });
    if (!result.content.length) {
        const empty = document.createElement('p');
        empty.className = 'request-empty';
        empty.textContent = '조건에 맞는 신청 내역이 없습니다.';
        host.appendChild(empty);
    }
    document.getElementById('myRequestSummary').textContent = `총 ${result.totalElements}건`;
    renderPortalPagination('myRequestPagination', result, loadMyAuthorityRequests);
}

async function cancelAuthorityRequest(request) {
    if (!window.confirm(`${request.authorityId} 권한 신청을 취소하시겠습니까?`)) return;
    try {
        await requestJson(`/api/authority-requests/${request.id}/cancel`, {
            method: 'POST', body: {version: request.version, reason: '사용자 취소'}
        });
        showToast('권한 신청을 취소했습니다.');
        await loadMyAuthorityRequests(myRequestPage);
    } catch (error) {
        if (error.status === 409) {
            showToast('이미 처리된 신청입니다. 최신 내역을 불러옵니다.');
            await loadMyAuthorityRequests(myRequestPage);
        } else {
            showPortalError(error);
        }
    }
}

function requestStatusLabel(status) {
    return {REQUESTED: '승인 대기', APPROVED: '승인', REJECTED: '반려', CANCELLED: '취소'}[status] || status;
}

function renderPortalPagination(id, result, callback) {
    const host = document.getElementById(id);
    host.replaceChildren();
    [['이전', result.page - 1, result.page <= 0], ['다음', result.page + 1, result.page + 1 >= result.totalPages]]
        .forEach(([label, page, disabled]) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = label;
            button.disabled = disabled;
            button.addEventListener('click', () => callback(page));
            host.appendChild(button);
        });
}

/** 휴대전화에서 숨겨진 검색창을 헤더 아래 검색 패널로 열거나 닫는다. */
function toggleMobileSearch() {
    const search = document.getElementById('portalSearch');
    const willOpen = !search.classList.contains('mobile-open');
    search.classList.toggle('mobile-open', willOpen);
    document.getElementById('btnMobileSearch').setAttribute('aria-expanded', String(willOpen));
    if (willOpen) {
        document.getElementById('serviceSearch').focus();
    }
}

function closeMobileSearch() {
    const search = document.getElementById('portalSearch');
    if (!search.classList.contains('mobile-open')) return false;
    search.classList.remove('mobile-open');
    document.getElementById('btnMobileSearch').setAttribute('aria-expanded', 'false');
    document.getElementById('btnMobileSearch').focus();
    return true;
}

function openMobileSearch() {
    if (!window.matchMedia('(max-width: 1024px)').matches) {
        return;
    }
    document.getElementById('portalSearch').classList.add('mobile-open');
    document.getElementById('btnMobileSearch').setAttribute('aria-expanded', 'true');
}

/** 콘텐츠 조회·저장·게시 API를 호출하고 서버의 최종 인가 결과를 사용자 메시지로 표시한다. */
async function callContent(url, method) {
    try {
        const payload = await requestJson(url, {method});
        showToast(payload.data.message);
    } catch (error) {
        showPortalError(error);
    }
}

/** 작은 화면에서 권한 메뉴 사이드바와 배경을 함께 열거나 닫는다. */
function toggleSidebar() {
    const sidebar = document.getElementById('portalSidebar');
    const willOpen = !sidebar.classList.contains('open');
    sidebarOpen = willOpen;
    sidebar.classList.toggle('open', willOpen);
    sidebar.setAttribute('aria-hidden', String(!willOpen));
    document.getElementById('sidebarBackdrop').classList.toggle('show', willOpen);
    document.getElementById('btnMenuToggle').setAttribute('aria-expanded', String(willOpen));
    document.getElementById('btnMenuToggle').setAttribute('aria-label', willOpen ? '전체 메뉴 닫기' : '전체 메뉴 열기');
    document.getElementById('portalMain').inert = willOpen;
    document.querySelector('.header-actions').inert = willOpen;
    if (willOpen) window.requestAnimationFrame(() => sidebar.querySelector('a')?.focus());
}

/** 모바일 메뉴 선택 또는 배경 클릭 후 사이드바를 닫아 본문 탐색을 복원한다. */
function closeSidebar() {
    if (!sidebarOpen) return;
    sidebarOpen = false;
    document.getElementById('portalSidebar').classList.remove('open');
    document.getElementById('portalSidebar').setAttribute('aria-hidden', 'true');
    document.getElementById('sidebarBackdrop').classList.remove('show');
    document.getElementById('btnMenuToggle').setAttribute('aria-expanded', 'false');
    document.getElementById('btnMenuToggle').setAttribute('aria-label', '전체 메뉴 열기');
    document.getElementById('portalMain').inert = false;
    document.querySelector('.header-actions').inert = false;
    document.getElementById('btnMenuToggle').focus();
}

function handlePortalKeyboard(event) {
    focusSearch(event);
    if (event.key === 'Escape') {
        if (sidebarOpen) closeSidebar();
        else closeMobileSearch();
    }
}

function trapSidebarFocus(event) {
    if (!sidebarOpen || event.key !== 'Tab') return;
    const focusable = Array.from(event.currentTarget.querySelectorAll('a[href], button:not([disabled])'));
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
        event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
    }
}

/** 동기화 진행 또는 정상 상태를 사이드바의 상태 문구와 점 색상으로 표현한다. */
function setSyncState(message, healthy) {
    const state = document.getElementById('syncState');
    state.lastChild.textContent = message;
    state.querySelector('i').style.background = healthy ? '#36bd75' : '#e6a23c';
}

/** Spring Security 로그아웃에 현재 세션의 CSRF 파라미터를 포함한 POST 폼을 제출한다. */
function logout() {
    if (!csrf) {
        window.location.assign('/login');
        return;
    }
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

/**
 * 사용자 포털의 JSON 요청에 동일 출처 세션과 변경 요청용 CSRF 헤더를 공통 적용한다.
 * 세션 만료는 로그인 화면으로 이동하고 표준 오류 응답은 화면 메시지에 사용할 Error로 변환한다.
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

/** 표 데이터를 HTML 문자열 없이 td 텍스트 노드로 추가해 화면 데이터의 스크립트 실행을 차단한다. */
function appendCell(row, value) {
    const cell = document.createElement('td');
    cell.textContent = value;
    row.appendChild(cell);
}

/** 재귀 메뉴 트리를 요약 수치와 빠른 서비스 생성에 사용할 단일 배열로 펼친다. */
function flattenMenus(menuNodes) {
    return menuNodes.flatMap(menu => [menu, ...flattenMenus(menu.children || [])]);
}

/** 짧은 성공·동기화 메시지를 우측 하단에 표시하고 이전 타이머와 겹치지 않게 닫는다. */
function showToast(message) {
    const toast = document.getElementById('portalToast');
    toast.textContent = message;
    toast.classList.add('show');
    if (toastTimer) {
        window.clearTimeout(toastTimer);
    }
    toastTimer = window.setTimeout(() => toast.classList.remove('show'), 3200);
}

/** 사용자에게는 안전한 오류 메시지를 보여주고 개발자 확인용 상세는 브라우저 콘솔에 남긴다. */
function showPortalError(error) {
    showToast(error.message || '화면을 불러오지 못했습니다.');
    console.error('Portal request failed', error.payload || error);
}
