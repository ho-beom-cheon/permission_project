import {el,api,run,initialize,feedback,cell,button,paging} from './work-ui.js';
let page=0;
async function list(){const result=await api(`/api/me/notices?unreadOnly=${el('unreadOnly').checked}&page=${page}&size=20`);el('rows').replaceChildren();
    for(const item of result.content){const row=document.createElement('tr');[item.title,item.body,new Date(item.createdAt).toLocaleString('ko-KR'),item.readAt?'읽음':'안 읽음'].forEach(v=>cell(row,v));cell(row,'').append(button('업무 열기',async()=>{await api(`/api/me/notices/${item.id}/read`,'POST');location.assign(item.path);}));el('rows').append(row);}
    if(!result.content.length){const row=document.createElement('tr');cell(row,'알림이 없습니다.').colSpan=5;el('rows').append(row);}paging(result,page);feedback(`읽지 않은 알림 ${(await api('/api/me/notices/unread-count')).count}건`);
}
el('unreadOnly').addEventListener('change',()=>run(async()=>{page=0;await list();}));el('previous').addEventListener('click',()=>run(async()=>{page--;await list();}));el('next').addEventListener('click',()=>run(async()=>{page++;await list();}));run(async()=>{await initialize();await list();});
