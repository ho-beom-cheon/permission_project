import {el,api,run,feedback,initialize,allowed,cell,button} from './work-ui.js';
let selected=null,boards=[];
const names={NOTICE:'공지',FAQ:'FAQ',QNA:'질문답변',DOCUMENT:'자료실',TERMS:'약관'};
function render(){
    const query=el('query').value.trim().toLowerCase();el('rows').replaceChildren();
    for(const board of boards.filter(b=>`${b.id} ${b.name}`.toLowerCase().includes(query))){
        const row=document.createElement('tr');[board.id,board.name,names[board.type],board.active?'사용':'중지',board.maxAttachments].forEach(value=>cell(row,value));
        cell(row,'').append(button('편집',()=>edit(board)));el('rows').append(row);
    }
}
async function load(){boards=await api('/api/boards');render();}
function typeChanged(){el('answerEnabled').disabled=el('type').value!=='QNA';if(el('answerEnabled').disabled)el('answerEnabled').checked=false;}
function edit(board){
    selected=board;el('editorSection').hidden=false;el('editor').reset();
    for(const field of ['id','type','name','description','systemId','maxAttachments'])el(field).value=board?.[field]??({type:'NOTICE',systemId:'COMMON',maxAttachments:10}[field]??'');
    for(const field of ['active','noticeEnabled','answerEnabled'])el(field).checked=board?.[field]??field==='active';
    el('id').disabled=!!board;el('type').disabled=!!board;el('deactivate').hidden=!board||!board.active;typeChanged();
}
el('type').addEventListener('change',typeChanged);el('search').addEventListener('click',()=>run(load));el('query').addEventListener('input',render);
el('new').addEventListener('click',()=>edit(null));el('cancel').addEventListener('click',()=>{selected=null;el('editorSection').hidden=true;});
el('editor').addEventListener('submit',event=>{event.preventDefault();run(async()=>{
    const value={version:selected?.version||0,maxAttachments:Number(el('maxAttachments').value)};
    for(const field of ['id','type','name','description','systemId'])value[field]=el(field).value;
    for(const field of ['active','noticeEnabled','answerEnabled'])value[field]=el(field).checked;
    el('save').disabled=true;try{const saved=await api('/api/boards','POST',value);await load();edit(saved);feedback('게시판 설정을 저장했습니다.');}finally{el('save').disabled=false;}
});});
el('deactivate').addEventListener('click',()=>run(async()=>{if(!selected||!confirm('이 게시판의 사용을 중지할까요?'))return;await api(`/api/boards/${encodeURIComponent(selected.id)}?version=${selected.version}`,'DELETE');await load();edit(boards.find(b=>b.id===selected.id));feedback('게시판 사용을 중지했습니다.');}));
run(async()=>{const context=await initialize();if(!allowed(context,'CONTENT_LIST','CONTENT','CONTENT_PUBLISH')){document.querySelectorAll('section').forEach(section=>section.hidden=true);throw new Error('콘텐츠 게시 관리 권한이 필요합니다.');}await load();feedback('게시판 종류와 게시 정책을 관리할 수 있습니다.');});
