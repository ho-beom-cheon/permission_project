/** 로그인 화면에서 CSRF 토큰 준비, 상태 메시지, 비밀번호 표시만 담당한다. */
document.addEventListener('DOMContentLoaded', () => {
    bindLogin();
    showLoginState();
    loadLoginCsrf();
});

/** 비밀번호 표시 버튼과 폼 제출 중복 방지 동작을 연결한다. */
function bindLogin() {
    const password = document.getElementById('password');
    const toggle = document.getElementById('togglePassword');
    const form = document.getElementById('loginForm');
    const button = document.getElementById('loginButton');

    toggle.addEventListener('click', () => {
        const hidden = password.type === 'password';
        password.type = hidden ? 'text' : 'password';
        toggle.textContent = hidden ? '숨김' : '보기';
        toggle.setAttribute('aria-label', hidden ? '비밀번호 숨기기' : '비밀번호 표시');
    });

    form.addEventListener('submit', () => {
        button.disabled = true;
        button.firstElementChild.textContent = '로그인 확인 중';
    });
}

/** 실패·로그아웃 쿼리를 사람이 이해할 수 있는 안내 문구로 바꿔 표시한다. */
function showLoginState() {
    const params = new URLSearchParams(window.location.search);
    if (params.has('error')) {
        showMessage('아이디 또는 비밀번호가 올바르지 않습니다.', 'error');
    } else if (params.has('logout')) {
        showMessage('안전하게 로그아웃되었습니다.', 'success');
    }
}

/** 익명 접근이 허용된 CSRF API에서 로그인 POST 요청에 필요한 토큰을 준비한다. */
async function loadLoginCsrf() {
    const button = document.getElementById('loginButton');
    try {
        const response = await fetch('/api/csrf', { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error('CSRF 토큰을 준비하지 못했습니다.');
        }
        const payload = await response.json();
        const csrf = payload.data;
        const input = document.getElementById('csrfToken');
        input.name = csrf.parameterName;
        input.value = csrf.token;
        button.disabled = false;
    } catch (error) {
        showMessage('로그인 보안 정보를 불러오지 못했습니다. 잠시 후 새로고침해 주세요.', 'error');
        console.error(error);
    }
}

/** 로그인 결과 또는 초기화 오류를 한 곳에서 접근성 상태 메시지로 표시한다. */
function showMessage(message, type) {
    const area = document.getElementById('loginMessage');
    area.textContent = message;
    area.className = `login-message ${type}`;
    area.hidden = false;
}
