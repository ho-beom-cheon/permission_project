// 공통 업무 화면의 요청·오류·텍스트 출력을 한곳에서 처리한다.
let csrf;
export const el = id => document.getElementById(id);
export function feedback(message, error = false) {
    el('feedback').textContent = message;
    el('feedback').classList.toggle('error', error);
}
export async function run(action) {
    try { await action(); } catch (error) { feedback(error.message, true); }
}
export async function api(url, method = 'GET', body) {
    const headers = {};
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrf) headers[csrf.headerName] = csrf.token;
    const response = await fetch(url, {method, headers, credentials: 'same-origin',
        body: body === undefined ? undefined : multipart ? body : JSON.stringify(body)});
    if (response.status === 401) { location.assign('/login'); throw new Error('다시 로그인해 주세요.'); }
    const result = await response.json();
    if (!response.ok) throw new Error(result.error?.message || '요청을 처리하지 못했습니다.');
    return result.data;
}
export async function initialize() {
    const result = await api('/api/bootstrap'); csrf = result.csrf; return result.context;
}
export function cell(row, value) {
    const td = document.createElement('td'); td.textContent = value ?? ''; row.append(td); return td;
}
export function button(label, action) {
    const node = document.createElement('button'); node.type = 'button'; node.textContent = label;
    node.addEventListener('click', () => run(action)); return node;
}
export function allowed(context, menu, program, action) {
    return context.programActions.some(p => p.menuId === menu && p.programId === program && p.actionId === action);
}
export function paging(data, page) {
    el('pageInfo').textContent = `${data.totalElements ? page + 1 : 0} / ${data.totalPages}쪽 · ${data.totalElements}건`;
    el('previous').disabled = page === 0; el('next').disabled = page + 1 >= data.totalPages;
}
