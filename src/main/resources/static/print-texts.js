import {el,api,run,feedback,initialize,allowed,cell,button,paging} from './work-ui.js';

let page=0, selected=null, options={offices:[],jobs:[],details:[]};
const base='/api/admin/print-texts';
const label=(items,code)=>items.find(item=>item.code===code)?.name||code;
function choices(id,items,emptyLabel){
    const select=el(id),old=select.value; select.replaceChildren(new Option(emptyLabel,''));
    for(const item of items)select.add(new Option(`${item.name} (${item.code})`,item.code));
    if(items.some(item=>item.code===old))select.value=old;
}
function detailChoices(){choices('jobSeDetailCd',options.details.filter(item=>item.code.slice(0,2)===el('jobSeCd').value),'선택');}
function includePrevious(id,value,name){
    const select=el(id);
    if(![...select.options].some(option=>option.value===value))select.add(new Option(`${name} (${value}, 현재 사용 불가)`,value));
    select.value=value;
}
async function list(){
    options=await api(`${base}/options`);
    choices('officeFilter',options.offices,'전체'); choices('jobFilter',options.jobs,'전체');
    const params=new URLSearchParams({officeCd:el('officeFilter').value,jobSeCd:el('jobFilter').value,page:String(page),size:'20'});
    const result=await api(`${base}?${params}`); el('rows').replaceChildren();
    for(const group of result.content){
        const row=document.createElement('tr');
        [label(options.offices,group.officeCd),label(options.jobs,group.jobSeCd),label(options.details,group.jobSeDetailCd),group.contentCount,group.version].forEach(value=>cell(row,value));
        cell(row,'').append(button('편집',async()=>edit(await api(`${base}/${encodeURIComponent(group.id)}`)))); el('rows').append(row);
    }
    paging(result,page);
}
function addLine(line){
    const row=document.createElement('tr'),seq=document.createElement('input'),cn=document.createElement('textarea');
    const highest=Math.max(0,...[...el('lines').rows].map(r=>Number(r.querySelector('input').value)));
    seq.type='number'; seq.min='1'; seq.step='1'; seq.max='2147483647'; seq.required=true; seq.value=line?.seq??highest+1; seq.setAttribute('aria-label','문구 순번');
    cn.rows=2; cn.maxLength=100; cn.value=line?.cn||''; cn.setAttribute('aria-label','출력문구');
    cell(row,'').append(seq); cell(row,'').append(cn); cell(row,'').append(button('행 삭제',()=>row.remove())); el('lines').append(row);
}
function edit(group){
    selected=group; el('editorSection').hidden=false; el('editorTitle').textContent=group?`출력문구 편집 · 버전 ${group.version}`:'새 출력문구 묶음';
    choices('officeCd',options.offices,'선택'); choices('jobSeCd',options.jobs,'선택');
    el('officeCd').value=group?.officeCd||''; el('jobSeCd').value=group?.jobSeCd||'';
    if(group){includePrevious('officeCd',group.officeCd,group.officeCd);includePrevious('jobSeCd',group.jobSeCd,group.jobSeCd);}
    detailChoices(); if(group)includePrevious('jobSeDetailCd',group.jobSeDetailCd,group.jobSeDetailCd);
    for(const id of ['officeCd','jobSeCd','jobSeDetailCd'])el(id).disabled=!!group;
    el('lines').replaceChildren(); for(const line of group?.lines||[{seq:1,cn:''}])addLine(line);
    el('delete').hidden=!group;
}
el('jobSeCd').addEventListener('change',detailChoices);
el('new').addEventListener('click',()=>edit(null));
el('addLine').addEventListener('click',()=>run(()=>{if(el('lines').rows.length>=200)throw new Error('문구는 200행까지 입력할 수 있습니다.');addLine();}));
el('cancel').addEventListener('click',()=>{el('editorSection').hidden=true;selected=null;});
el('editor').addEventListener('submit',event=>{event.preventDefault();run(async()=>{
    const value={officeCd:el('officeCd').value,jobSeCd:el('jobSeCd').value,jobSeDetailCd:el('jobSeDetailCd').value,version:selected?.version||0,
        lines:[...el('lines').rows].map(row=>({seq:Number(row.querySelector('input').value),cn:row.querySelector('textarea').value}))};
    el('save').disabled=true;
    try{const saved=await api(base,'POST',value);await list();edit(saved);feedback('출력문구 묶음을 저장했습니다.');}finally{el('save').disabled=false;}
});});
el('delete').addEventListener('click',()=>run(async()=>{
    if(!selected||!confirm('이 묶음의 출력문구를 모두 삭제할까요?'))return;
    await api(`${base}/${encodeURIComponent(selected.id)}?version=${selected.version}`,'DELETE');selected=null;el('editorSection').hidden=true;await list();feedback('출력문구 묶음을 삭제했습니다.');
}));
el('search').addEventListener('click',()=>run(async()=>{page=0;await list();}));
el('previous').addEventListener('click',()=>run(async()=>{page--;await list();}));
el('next').addEventListener('click',()=>run(async()=>{page++;await list();}));
run(async()=>{
    const context=await initialize();
    if(!allowed(context,'SYSTEM_CODE','COMMON_CODE','COMMON_CODE_SAVE')){document.querySelectorAll('section').forEach(node=>node.hidden=true);throw new Error('공통코드 관리 권한이 필요합니다.');}
    await list();feedback('사업소·업무별 출력문구를 관리할 수 있습니다.');
});
