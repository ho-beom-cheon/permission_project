'use strict';

const MANAGE_MENUS = new Set(['SYSTEM_AUTH', 'SYSTEM_MENU', 'SYSTEM_CODE']);
const REFRESH_INTERVAL = 15000;

let csrf = null;
let portalContext = null;
let versionKey = null;
let refreshTimer = null;
let toastTimer = null;

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
    document.getElementById('btnSync').addEventListener('click', () => loadPortal(true));
    document.getElementById('btnLogout').addEventListener('click', logout);
    document.getElementById('btnMenuToggle').addEventListener('click', toggleSidebar);
    document.getElementById('sidebarBackdrop').addEventListener('click', closeSidebar);
    document.getElementById('serviceSearch').addEventListener('input', filterServices);
    document.getElementById('btnShowAll').addEventListener('click', showAllServices);
    document.getElementById('btnContentRead').addEventListener('click', () => callContent('/api/content/preview', 'GET'));
    document.getElementById('btnContentSave').addEventListener('click', () => callContent('/api/content/save', 'POST'));
    document.getElementById('btnContentPublish').addEventListener('click', () => callContent('/api/content/publish', 'POST'));
    document.querySelectorAll('[data-go-home]').forEach(button => {
        button.addEventListener('click', () => navigateTo('#home'));
    });
    window.addEventListener('hashchange', showCurrentPage);
    window.addEventListener('keydown', focusSearch);
}

/**
 * 부트스트랩과 사용자 업무 상태 공통코드를 함께 조회해 하나의 사용자 화면을 다시 구성한다.
 * 권한·메뉴·코드 버전이 바뀌면 관리 화면의 변경이 반영됐음을 사용자에게 알린다.
 */
async function loadPortal(notify) {
    setSyncState('최신 권한 확인 중', false);
    try {
        const [bootstrapPayload, statusPayload] = await Promise.all([
            requestJson('/api/bootstrap'),
            requestJson('/api/common-codes/ARTICLE_STATUS/view')
        ]);
        const context = bootstrapPayload.data.context;
        const statuses = statusPayload.data;
        const nextKey = buildVersionKey(context.versions, statuses.version);
        const changed = versionKey !== null && versionKey !== nextKey;

        csrf = bootstrapPayload.data.csrf;
        portalContext = context;
        versionKey = nextKey;

        renderUser(context.user);
        renderPortalMenu(context.menus);
        renderQuickServices(context.menus);
        renderDynamicPages(context.menus);
        enableActions(context.programActions);
        renderStatuses(statuses);
        renderContentRows(statuses.items);
        renderSummary(context, statuses.items);
        showCurrentPage();
        setSyncState('권한과 메뉴 최신 상태', true);

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

/** 권한·메뉴·프로그램·공통코드 버전을 비교 가능한 안정적인 문자열로 변환한다. */
function buildVersionKey(versions, articleVersion) {
    const codeVersions = Object.entries(versions.commonCodeVersions)
        .sort(([left], [right]) => left.localeCompare(right));
    return JSON.stringify({
        authority: versions.authorityVersion,
        menu: versions.menuVersion,
        program: versions.programVersion,
        commonCodes: codeVersions,
        article: articleVersion
    });
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
    user.effectiveAuthorityIds.forEach(authorityId => {
        const chip = document.createElement('span');
        chip.textContent = authorityId;
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
    document.getElementById('todayDay').textContent = String(today.getDate()).padStart(2, '0');
}

/** 서버가 허용한 메뉴 계층을 사용자 포털의 사이드바 메뉴로 변환한다. */
function renderPortalMenu(menuNodes) {
    const menu = document.getElementById('portalMenu');
    menu.replaceChildren(buildMenuList(menuNodes));
    const flatMenus = flattenMenus(menuNodes);
    document.getElementById('adminConsoleLink').hidden =
        !flatMenus.some(item => MANAGE_MENUS.has(item.id));
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

/** 접근 가능한 경로 메뉴를 홈의 빠른 서비스 카드로 만들어 권한 변경 결과를 즉시 보여준다. */
function renderQuickServices(menuNodes) {
    const services = flattenMenus(menuNodes).filter(menu => menu.path && menu.path !== '#home');
    const container = document.getElementById('quickServices');
    container.replaceChildren();
    services.slice(0, 8).forEach((menu, index) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'service-tile';
        button.dataset.searchText = `${menu.name} ${menu.id}`.toLowerCase();
        button.dataset.path = menu.path;

        const icon = document.createElement('i');
        icon.textContent = String(index + 1).padStart(2, '0');
        const name = document.createElement('strong');
        name.textContent = menu.name;
        const description = document.createElement('small');
        description.textContent = MANAGE_MENUS.has(menu.id) ? '시스템 관리 서비스' : '나에게 허용된 업무 서비스';
        const arrow = document.createElement('mark');
        arrow.textContent = '→';
        button.append(icon, name, description, arrow);
        button.addEventListener('click', () => navigateTo(menu.path));
        container.appendChild(button);
    });
    if (services.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'empty-service';
        empty.textContent = '현재 이용 가능한 추가 업무 서비스가 없습니다.';
        container.appendChild(empty);
    }
}

/**
 * 홈과 콘텐츠 외 메뉴에 대응하는 사용자 페이지를 동적으로 만든다.
 * 관리 메뉴는 콘솔 연결 화면을, 새 업무 메뉴는 권한 반영 상태를 보여주는 일반 서비스 화면을 제공한다.
 */
function renderDynamicPages(menuNodes) {
    const host = document.getElementById('dynamicPages');
    host.replaceChildren();
    flattenMenus(menuNodes)
        .filter(menu => menu.path && !['#home', '#content'].includes(menu.path))
        .forEach(menu => host.appendChild(createDynamicPage(menu)));
}

/** 한 메뉴 정의를 접근 가능한 독립 포털 페이지 요소로 변환한다. */
function createDynamicPage(menu) {
    const section = document.createElement('section');
    section.id = menu.path.slice(1);
    section.className = `portal-page generic-page${MANAGE_MENUS.has(menu.id) ? ' manage-page' : ''}`;
    section.dataset.route = menu.path;
    section.hidden = true;

    const pageHead = document.createElement('div');
    pageHead.className = 'page-head';
    const headCopy = document.createElement('div');
    const breadcrumb = document.createElement('p');
    breadcrumb.textContent = `홈 / ${MANAGE_MENUS.has(menu.id) ? '시스템 관리' : '업무 서비스'} / ${menu.name}`;
    const title = document.createElement('h1');
    title.textContent = menu.name;
    const subtitle = document.createElement('span');
    subtitle.textContent = '현재 로그인 사용자의 서버 권한으로 접근이 허용된 메뉴입니다.';
    headCopy.append(breadcrumb, title, subtitle);
    const homeButton = document.createElement('button');
    homeButton.type = 'button';
    homeButton.className = 'secondary-button';
    homeButton.textContent = '홈으로';
    homeButton.addEventListener('click', () => navigateTo('#home'));
    pageHead.append(headCopy, homeButton);

    const card = document.createElement('div');
    card.className = 'generic-hero';
    const cardCopy = document.createElement('div');
    const cardTitle = document.createElement('h2');
    cardTitle.textContent = MANAGE_MENUS.has(menu.id)
        ? `${menu.name} 관리자 기능` : `${menu.name} 서비스`;
    const cardText = document.createElement('p');
    cardText.textContent = MANAGE_MENUS.has(menu.id)
        ? '이 기능은 시스템 관리 권한이 있는 사용자에게만 표시됩니다. 상세 데이터 변경은 관리 콘솔에서 수행합니다.'
        : '메뉴 관리에서 등록하고 현재 사용자 권한에 연결한 서비스입니다. 실제 업무 화면을 이 영역에 확장할 수 있습니다.';
    cardCopy.append(cardTitle, cardText);
    if (MANAGE_MENUS.has(menu.id)) {
        const manageLink = document.createElement('a');
        manageLink.className = 'manage-link';
        manageLink.href = `/${menu.path}`;
        manageLink.textContent = '관리 콘솔에서 열기';
        cardCopy.appendChild(manageLink);
    }
    const icon = document.createElement('i');
    icon.textContent = MANAGE_MENUS.has(menu.id) ? '⚙' : '✓';
    card.append(cardCopy, icon);

    const info = document.createElement('div');
    info.className = 'generic-info';
    info.append(
        createInfo('메뉴 ID', menu.id),
        createInfo('화면 경로', menu.path),
        createInfo('접근 상태', '현재 사용자에게 허용됨'));
    section.append(pageHead, card, info);
    return section;
}

/** 동적 업무 화면의 라벨과 값을 안전한 텍스트 노드로 구성한다. */
function createInfo(label, value) {
    const box = document.createElement('div');
    const small = document.createElement('small');
    small.textContent = label;
    const strong = document.createElement('strong');
    strong.textContent = value;
    box.append(small, strong);
    return box;
}

/** 서버가 반환한 기능 권한만 사용자 콘텐츠 화면의 조회·작성·게시 버튼에 활성화한다. */
function enableActions(actions) {
    document.querySelectorAll('[data-protected]').forEach(button => {
        button.disabled = true;
        button.title = '현재 사용자에게 허용되지 않은 기능입니다.';
    });
    actions.forEach(action => {
        const button = document.getElementById(action.componentId);
        if (button) {
            button.disabled = false;
            button.title = `${action.label} 권한 허용`;
        }
    });
}

/** 공통코드 관리에서 활성 상태인 게시 상태만 사용자 홈의 업무 선택지에 표시한다. */
function renderStatuses(groupView) {
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
}

/** 사용자 콘텐츠 목록의 상태명을 서버 공통코드와 연결해 관리 변경 결과를 목록에도 반영한다. */
function renderContentRows(statuses) {
    const items = [
        {no: 104, type: '공지', title: '통합 업무포털 이용 안내', status: 1, author: '운영관리자', date: '2026.09.01'},
        {no: 103, type: '업무', title: '주간 서비스 운영 현황', status: 0, author: 'manager', date: '2026.08.31'},
        {no: 102, type: '자료', title: '정보보안 준수사항 안내', status: 1, author: '보안담당자', date: '2026.08.28'},
        {no: 101, type: '소식', title: '시스템 기능 개선 결과', status: 0, author: 'admin', date: '2026.08.25'}
    ];
    const fallback = [{code: 'DRAFT', name: '작성 중'}, {code: 'PUBLISHED', name: '게시'}];
    const available = statuses.length ? statuses : fallback;
    const body = document.getElementById('contentRows');
    body.replaceChildren();
    items.forEach(item => {
        const status = available[item.status % available.length];
        const row = document.createElement('tr');
        const checkCell = document.createElement('td');
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.setAttribute('aria-label', `${item.no}번 콘텐츠 선택`);
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
        body.appendChild(row);
    });
}

/** 현재 메뉴·권한·상태 코드 수와 마지막 동기화 시각을 홈 요약 카드에 표시한다. */
function renderSummary(context, statuses) {
    const pathMenus = flattenMenus(context.menus).filter(menu => menu.path);
    document.getElementById('menuCount').textContent = String(pathMenus.length);
    document.getElementById('authorityCount').textContent =
        String(context.user.effectiveAuthorityIds.length);
    document.getElementById('statusCount').textContent = String(statuses.length);
    document.getElementById('lastSync').textContent = new Intl.DateTimeFormat('ko-KR', {
        hour: '2-digit', minute: '2-digit', hour12: false
    }).format(new Date());
}

/** URL 해시와 현재 허용 메뉴를 비교해 한 개 사용자 페이지와 활성 메뉴만 표시한다. */
function showCurrentPage() {
    if (!portalContext) {
        return;
    }
    const allowedPaths = new Set(flattenMenus(portalContext.menus)
        .filter(menu => menu.path)
        .map(menu => menu.path));
    let route = window.location.hash || '#home';
    if (!allowedPaths.has(route) || !document.querySelector(`[data-route="${route}"]`)) {
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
    window.scrollTo({top: 0, behavior: 'smooth'});
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
        if (element.classList.contains('service-tile')) {
            element.hidden = !matches;
        } else {
            element.closest('li').hidden = !matches;
        }
    });
}

/** 서비스 검색어를 지우고 홈의 전체 빠른 서비스 카드를 다시 표시한다. */
function showAllServices() {
    const search = document.getElementById('serviceSearch');
    search.value = '';
    filterServices();
    search.focus();
}

/** Ctrl+K 단축키가 브라우저 기본 동작 대신 포털 서비스 검색창으로 이동하게 한다. */
function focusSearch(event) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        document.getElementById('serviceSearch').focus();
    }
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
    sidebar.classList.toggle('open', willOpen);
    document.getElementById('sidebarBackdrop').classList.toggle('show', willOpen);
    document.getElementById('btnMenuToggle').setAttribute('aria-expanded', String(willOpen));
}

/** 모바일 메뉴 선택 또는 배경 클릭 후 사이드바를 닫아 본문 탐색을 복원한다. */
function closeSidebar() {
    document.getElementById('portalSidebar').classList.remove('open');
    document.getElementById('sidebarBackdrop').classList.remove('show');
    document.getElementById('btnMenuToggle').setAttribute('aria-expanded', 'false');
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
