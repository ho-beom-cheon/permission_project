import {el, api, run, initialize, feedback, cell, button} from './work-ui.js';

async function load() {
    const view = await api('/api/me/authority-delegation');
    el('authority').replaceChildren(...view.authorities.map(item => new Option(`${item.name} (${item.id})`, item.id)));
    el('recipient').replaceChildren(...view.recipients.map(item => new Option(`${item.username} (${item.organizationId})`, item.username)));
    el('rows').replaceChildren();
    if (view.active) {
        const row = document.createElement('tr');
        cell(row, view.active.authorityId); cell(row, view.active.recipientUsername);
        cell(row, `${view.active.validFrom} ~ ${view.active.validTo}`); cell(row, view.active.reason);
        const action = cell(row, ''); action.append(button('위임 취소', async () => {
            await api('/api/me/authority-delegation', 'DELETE'); await load(); feedback('권한 위임을 취소했습니다.');
        })); el('rows').append(row);
    } else {
        const row = document.createElement('tr'); cell(row, '현재 위임이 없습니다.').colSpan = 5; el('rows').append(row);
    }
    el('save').disabled = Boolean(view.active) || !view.authorities.length || !view.recipients.length;
    feedback('현재 권한 위임을 조회했습니다.');
}

el('form').addEventListener('submit', event => { event.preventDefault(); run(async () => {
    await api('/api/me/authority-delegation', 'POST', {authorityId: el('authority').value,
        recipientUsername: el('recipient').value, reason: el('reason').value});
    el('reason').value = ''; await load(); feedback('권한을 위임했습니다.');
}); });
run(async () => { await initialize(); await load(); });
