import {el, api, run, feedback, initialize, allowed, cell, button, paging} from './work-ui.js';
let context, page = 0, selected, editing, files = [], canWrite = false, canPublish = false;
let boards=[];
const currentBoard=()=>boards.find(board=>board.id===el('board').value);
const names = {NOTICE:'공지사항', FAQ:'자주 묻는 질문', QNA:'질문과 답변', DOCUMENT:'자료실', TERMS:'이용약관'};
async function list() {
    const board = el('board').value, definition=currentBoard();
    const result = await api(`/api/boards/${board}?query=${encodeURIComponent(el('query').value)}&page=${page}&size=15`);
    el('rows').replaceChildren();
    for (const post of result.content) {
        const row = document.createElement('tr');
        cell(row, `${post.pinned ? '[고정] ' : ''}${post.title}`); cell(row, post.author);
        cell(row, `${post.published ? '게시' : '임시 저장'} · ${post.publicRead ? '공개' : '비공개'}`);
        cell(row, new Date(post.createdAt).toLocaleString('ko-KR')); cell(row, '').append(button('열기', () => show(post.id)));
        el('rows').append(row);
    }
    if (!result.content.length) { const row = document.createElement('tr'); cell(row, '등록된 게시글이 없습니다.').colSpan = 5; el('rows').append(row); }
    paging(result, page); el('new').disabled = !definition?.active || !(canWrite || definition.type === 'QNA');
    el('agreementsSection').hidden = definition?.type !== 'TERMS';
    if (definition?.type === 'TERMS') await agreements();
}
async function show(id) {
    selected = await api(`/api/posts/${id}`);
    if(el('board').value!==selected.boardId){el('board').value=selected.boardId;page=0;await list();}
    const definition=currentBoard(),viewCount=await api(`/api/posts/${id}/views`,'POST');
    el('detail').hidden = false; el('editorSection').hidden = true;
    el('postTitle').textContent = selected.title;
    el('postMeta').textContent = `${definition?.name||names[selected.board]} · ${selected.author} · 조회 ${viewCount}회 · ${selected.startDate || '시작 제한 없음'} ~ ${selected.endDate || '종료 제한 없음'}${selected.versionLabel ? ' · 버전 ' + selected.versionLabel : ''}`;
    el('postBody').textContent = selected.body;
    el('postFiles').replaceChildren();
    for (const id of selected.attachmentIds) {
        const item = document.createElement('li'), link = document.createElement('a');
        link.href = `/api/attachments/${encodeURIComponent(id)}`; link.textContent = `첨부파일 내려받기 (${id.slice(0, 8)})`; item.append(link); el('postFiles').append(item);
    }
    const owner = selected.author === context.user.username;
    const immutable = selected.board === 'TERMS' && selected.published;
    el('editPost').hidden = immutable || !definition?.active || !(canPublish || owner && (canWrite || selected.board === 'QNA'));
    el('deletePost').hidden = immutable || !(canPublish || owner && selected.board === 'QNA');
    el('answerView').hidden = !selected.answer; el('answerBody').textContent = selected.answer;
    el('answerForm').hidden = !(definition?.active && definition.answerEnabled && selected.board === 'QNA' && canPublish); el('answer').value = selected.answer || '';
    el('agree').hidden = !(definition?.active && selected.board === 'TERMS' && selected.published);
}
async function discardUploads() {
    for (const file of files.filter(f => f.pending)) await api(`/api/attachments/${encodeURIComponent(file.id)}`, 'DELETE');
    files = [];
}
async function edit(post) {
    await discardUploads(); editing = post; el('editorSection').hidden = false; el('detail').hidden = true;
    const definition=currentBoard();
    el('editor').reset(); el('editorTitle').textContent = `${definition.name} · ${post ? '글 수정' : '새 글 작성'}`;
    for (const key of ['title','body','startDate','endDate','versionLabel']) el(key).value = post?.[key] || '';
    for (const key of ['published','publicRead','pinned']) el(key).checked = post ? post[key] : key === 'publicRead' || key === 'published' && definition.type === 'QNA';
    el('published').disabled = !canPublish && definition.type !== 'QNA'; el('pinned').disabled = !canPublish||!definition.noticeEnabled;
    if(!definition.noticeEnabled)el('pinned').checked=false;
    el('versionField').hidden = definition.type !== 'TERMS'; el('versionLabel').required = definition.type === 'TERMS';
    el('fileLimit').textContent=`첨부파일 (각 10MB, 최대 ${definition.maxAttachments}개)`;
    files = (post?.attachmentIds || []).map(id => ({id, name:`기존 첨부 ${id.slice(0,8)}`, pending:false})); renderFiles();
}
function renderFiles() {
    el('editingFiles').replaceChildren();
    for (const file of files) {
        const item = document.createElement('li'); item.textContent = file.name + ' ';
        item.append(button('첨부 해제', async () => { if(file.pending) await api(`/api/attachments/${encodeURIComponent(file.id)}`, 'DELETE'); files = files.filter(f => f.id !== file.id); renderFiles(); }));
        el('editingFiles').append(item);
    }
}
async function agreements() {
    el('agreements').replaceChildren();
    for (const value of await api('/api/me/agreements')) { const item=document.createElement('li');item.textContent=`글 ${value.postId} · 버전 ${value.versionLabel} · ${new Date(value.agreedAt).toLocaleString('ko-KR')}`;el('agreements').append(item); }
    if(!el('agreements').children.length)el('agreements').textContent='동의 이력이 없습니다.';
}
el('board').addEventListener('change', () => run(async () => { await discardUploads(); page=0; selected=null;el('detail').hidden=true;el('editorSection').hidden=true;await list(); }));
el('search').addEventListener('click', () => run(async () => {page=0;await list();}));
el('previous').addEventListener('click', () => run(async () => {page--;await list();}));
el('next').addEventListener('click', () => run(async () => {page++;await list();}));
el('new').addEventListener('click', () => run(() => edit(null)));
el('editPost').addEventListener('click', () => run(() => edit(selected)));
el('cancelEdit').addEventListener('click', () => run(async () => {await discardUploads();el('editorSection').hidden=true;}));
el('file').addEventListener('change', () => run(async () => {
    const selectedFiles=[...el('file').files],limit=currentBoard().maxAttachments; if(files.length+selectedFiles.length>limit)throw new Error(`첨부파일은 최대 ${limit}개입니다.`);
    el('savePost').disabled=true;
    try { for(const file of selectedFiles) { if(file.size>10*1024*1024 || !file.size)throw new Error('파일 크기는 1바이트~10MB여야 합니다.'); const body=new FormData();body.append('file',file);files.push({...await api('/api/attachments','POST',body),pending:true});renderFiles(); } }
    finally {el('savePost').disabled=false;el('file').value='';}
}));
el('editor').addEventListener('submit', event => {event.preventDefault();run(async () => {
    const body={board:currentBoard().type,boardId:el('board').value,title:el('title').value,body:el('body').value,startDate:el('startDate').value||null,endDate:el('endDate').value||null,
        versionLabel:el('versionLabel').value,published:el('published').checked,publicRead:el('publicRead').checked,pinned:el('pinned').checked,attachmentIds:files.map(f=>f.id),expectedVersion:editing?.version||0};
    el('savePost').disabled=true;
    try { const post=await api(editing?`/api/posts/${editing.id}`:'/api/posts',editing?'PUT':'POST',body);files=[];await list();await show(post.id);feedback('게시글을 저장했습니다.'); }
    finally {el('savePost').disabled=false;}
});});
el('deletePost').addEventListener('click', () => run(async () => {if(!confirm('이 게시글을 삭제할까요?'))return;await api(`/api/posts/${selected.id}?version=${selected.version}`,'DELETE');el('detail').hidden=true;await list();feedback('게시글을 삭제했습니다.');}));
el('answerForm').addEventListener('submit',event=>{event.preventDefault();run(async()=>{await api(`/api/posts/${selected.id}/answer`,'POST',{answer:el('answer').value,version:selected.version});await show(selected.id);feedback('답변을 저장했습니다.');});});
el('agree').addEventListener('click',()=>run(async()=>{await api(`/api/terms/${selected.id}/agreement`,'POST');await agreements();feedback('약관 동의를 기록했습니다.');}));
run(async () => {
    context=await initialize();canWrite=allowed(context,'CONTENT_LIST','CONTENT','CONTENT_SAVE');canPublish=allowed(context,'CONTENT_LIST','CONTENT','CONTENT_PUBLISH');
    boards=await api('/api/boards');el('board').replaceChildren();
    for(const board of boards)el('board').add(new Option(`${board.name}${board.active?'':' (사용 중지)'}`,board.id));
    if(!boards.length){el('new').disabled=true;throw new Error('사용 가능한 게시판이 없습니다.');}
    const params=new URLSearchParams(location.search),requested=params.get('board')||'NOTICE';if(boards.some(board=>board.id===requested))el('board').value=requested;
    await list();if(/^\d+$/.test(params.get('post')||''))await show(params.get('post'));feedback('게시글을 조회하거나 새 글을 작성할 수 있습니다.');
});
