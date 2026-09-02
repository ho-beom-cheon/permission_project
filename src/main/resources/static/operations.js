import {el,api,run,feedback,initialize,allowed,cell,button,paging} from './work-ui.js';
let definitions=[], definition, selected=null, page=0, canWrite=false;
let requestFiles=[],responseFiles=[];
async function releasePending(){
    for(const file of [...requestFiles,...responseFiles].filter(f=>f.pending)) await api(`/api/attachments/${encodeURIComponent(file.id)}`,'DELETE');
    requestFiles=[];responseFiles=[];
}
async function list(){
    const result=await api(`/api/admin/operations?type=${definition.id}&query=${encodeURIComponent(el('query').value)}&page=${page}&size=20`);
    el('rows').replaceChildren();
    for(const work of result.content){
        const row=document.createElement('tr');
        const values=work.values;
        [work.number,values.taskTitle||values.issueTitle,values.procStatusCd||values.issueProcStatusSeCd,new Date(work.updatedAt).toLocaleString('ko-KR')].forEach(v=>cell(row,v));
        cell(row,'').append(button('상세',()=>edit(work)));el('rows').append(row);
    }
    if(!result.content.length){const row=document.createElement('tr');cell(row,'등록된 업무가 없습니다.').colSpan=5;el('rows').append(row);}
    paging(result,page);
}
async function edit(work){
    await releasePending();selected=work;
    el('fields').replaceChildren();el('editorTitle').textContent=`${definition.name} ${work?'수정 · '+work.number:'등록'}`;
    for(const field of definition.fields){
        const label=document.createElement('label');label.textContent=field.label;
        const input=document.createElement(field.type==='textarea'?'textarea':'input');
        if(field.type!=='textarea')input.type=['date','month','number'].includes(field.type)?field.type:'text';
        else {input.rows=4;label.style.gridColumn='1 / -1';}
        input.id='op-'+field.id;input.name=field.id;input.required=field.required;
        input.maxLength=field.type==='textarea'?20000:250;
        input.value=work?.values[field.id]||'';input.disabled=!canWrite||!!work&&definition.keyFields.includes(field.id);
        label.append(input);el('fields').append(label);
    }
    requestFiles=(work?.requestFiles||[]).map(id=>({id,name:`요청 첨부 ${id.slice(0,8)}`}));
    responseFiles=(work?.responseFiles||[]).map(id=>({id,name:`처리 첨부 ${id.slice(0,8)}`}));
    renderFiles();el('delete').hidden=!work||!canWrite;el('history').replaceChildren();
    if(work){for(const change of await api(`/api/admin/operations/${work.id}/history`)){
        const li=document.createElement('li');li.textContent=`버전 ${change.version} · ${change.action==='DELETED'?'삭제':'저장'} · 상태 ${change.status} · ${change.actor} · ${new Date(change.occurredAt).toLocaleString('ko-KR')}`;el('history').append(li);
    }}
}
function renderFiles(){
    for(const kind of ['request','response']){
        const files=kind==='request'?requestFiles:responseFiles;el(kind+'Files').replaceChildren();
        for(const file of files){
            const item=document.createElement('li'),link=document.createElement('a');
            link.href=`/api/attachments/${encodeURIComponent(file.id)}`;link.textContent=file.name;item.append(link);
            if(canWrite)item.append(button('첨부 해제',async()=>{
                if(file.pending)await api(`/api/attachments/${encodeURIComponent(file.id)}`,'DELETE');
                if(kind==='request')requestFiles=requestFiles.filter(f=>f.id!==file.id);else responseFiles=responseFiles.filter(f=>f.id!==file.id);renderFiles();
            }));
            el(kind+'Files').append(item);
        }
    }
}
async function choose(){await releasePending();definition=definitions.find(d=>d.id===el('type').value);page=0;await list();await edit(null);}
el('type').addEventListener('change',()=>run(choose));
el('new').addEventListener('click',()=>run(()=>edit(null)));
el('search').addEventListener('click',()=>run(async()=>{page=0;await list();}));
el('previous').addEventListener('click',()=>run(async()=>{page--;await list();}));
el('next').addEventListener('click',()=>run(async()=>{page++;await list();}));
for(const kind of ['request','response'])el(kind+'Upload').addEventListener('change',()=>run(async()=>{
    const input=el(kind+'Upload'),selectedFiles=[...input.files],existing=kind==='request'?requestFiles:responseFiles;
    if(existing.length+selectedFiles.length>10)throw new Error('각 구분에는 최대 10개 파일을 첨부할 수 있습니다.');
    el('save').disabled=true;
    try{for(const file of selectedFiles){
        if(!file.size||file.size>10*1024*1024)throw new Error('첨부파일 크기는 1바이트~10MB여야 합니다.');
        const form=new FormData();form.append('file',file);existing.push({...await api('/api/attachments','POST',form),pending:true});renderFiles();
    }}finally{input.value='';el('save').disabled=!canWrite;}
}));
el('editor').addEventListener('submit',event=>{event.preventDefault();run(async()=>{
    const values={};definition.fields.forEach(f=>values[f.id]=el('op-'+f.id).value);
    const payload={type:definition.id,values,requestFiles:requestFiles.map(f=>f.id),responseFiles:responseFiles.map(f=>f.id),version:selected?.version||0};
    el('save').disabled=true;
    try{const work=await api(selected?`/api/admin/operations/${selected.id}`:'/api/admin/operations',selected?'PUT':'POST',payload);requestFiles=[];responseFiles=[];await list();await edit(work);feedback('업무와 요청·처리 첨부를 저장했습니다.');}
    finally{el('save').disabled=!canWrite;}
});});
el('delete').addEventListener('click',()=>run(async()=>{if(!selected||!confirm('이 업무를 삭제할까요? 변경 이력은 보존됩니다.'))return;await api(`/api/admin/operations/${selected.id}?version=${selected.version}`,'DELETE');await list();await edit(null);feedback('업무를 삭제했습니다.');}));
run(async()=>{const context=await initialize();canWrite=allowed(context,'SYSTEM_AUTH','AUTHORITY','AUTHORITY_UPDATE');definitions=await api('/api/admin/operations/definitions');definitions.forEach(d=>el('type').add(new Option(d.name,d.id)));el('save').disabled=!canWrite;el('new').disabled=!canWrite;el('requestUpload').disabled=!canWrite;el('responseUpload').disabled=!canWrite;await choose();feedback('과업·이슈를 조회하거나 요청·처리 내용을 기록할 수 있습니다.');});
