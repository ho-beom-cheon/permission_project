'use strict';
// 동일한 화면 구성 요소를 사용하되 실제 저장 계약과 참조 검증은 업무별 서버 서비스가 담당한다.
const mg = {csrf:null, directory:null, modules:[], current:null, selected:null, page:0, canDirectoryWrite:false, canReference:false};
const byId = id => document.getElementById(id);
const field = (id,label,type='text',required=false) => ({id,label,type,required});
const directoryModules = [
    {id:'offices',name:'사업소',keyFields:['code'],fields:[field('code','사업소 코드','text',true),field('name','사업소명','text',true),field('abbreviation','약어명'),field('address','주소'),field('telephone','전화번호'),field('active','사용','boolean')]},
    {id:'departments',name:'부서',keyFields:['officeCode','code'],fields:[field('officeCode','소속 사업소','office',true),field('code','부서 코드','text',true),field('name','부서명','text',true),field('description','설명'),field('active','사용','boolean')]},
    {id:'regions',name:'담당지역',keyFields:['code'],fields:[field('code','지역 코드','text',true),field('officeCode','소속 사업소','office',true),field('districtCode','구 코드'),field('districtName','구 이름'),field('name','동·지역명','text',true),field('active','사용','boolean')]},
    {id:'people',name:'사용자',keyFields:['username'],fields:[field('username','사용자 ID','text',true),field('name','사용자명','text',true),field('officeCode','소속 사업소','office',true),field('departmentCode','부서 코드'),field('rankCode','직급 코드'),field('telephone','전화번호'),field('mobile','휴대전화'),field('email','이메일','email'),field('jobDescription','담당 업무'),field('regionIds','담당지역 코드 (쉼표로 구분)','list'),field('active','사용','boolean'),field('initialPassword','신규 로그인 비밀번호 (12~72자)','password')]},
    {id:'jobs',name:'업무담당자',keyFields:['id'],fields:[field('id','업무 배정 ID','text',true),field('username','사용자 ID','text',true),field('jobCode','업무 코드','text',true),field('name','업무명','text',true),field('validFrom','시작일','date',true),field('validTo','종료일','date',true),field('active','사용','boolean')]}
].map(module => ({...module,kind:'directory'}));

function message(text,error=false){byId('feedback').textContent=text;byId('feedback').classList.toggle('error',error);}
async function call(url,method='GET',body){
    const headers={Accept:'application/json'};
    if(body!==undefined)headers['Content-Type']='application/json';
    if(method!=='GET')headers[mg.csrf.headerName]=mg.csrf.token;
    const response=await fetch(url,{method,headers,credentials:'same-origin',body:body===undefined?undefined:JSON.stringify(body)});
    if(response.status===401){location.assign('/login');throw new Error('로그인이 필요합니다.');}
    const result=await response.json();if(!response.ok)throw new Error(result.error?.message||'처리하지 못했습니다.');return result.data;
}
async function guarded(action){try{await action();}catch(error){message(error.message,true);}}
function canWrite(){return mg.current?.kind==='directory'?mg.canDirectoryWrite:mg.canReference;}
function edit(entry=null){
    mg.selected=entry;byId('fields').replaceChildren();byId('editorTitle').textContent=`${mg.current.name} ${entry?'수정':'등록'}`;
    const values=mg.current.kind==='directory'?(entry||{}):(entry?.values||{});
    for(const item of mg.current.fields){
        const label=document.createElement('label');label.textContent=item.label;
        let input;
        if(item.type==='office'||item.type==='flag'){
            input=document.createElement('select');
            if(item.type==='office'){
                input.add(new Option('선택하세요',''));mg.directory.offices.forEach(office=>input.add(new Option(`${office.name} (${office.code})`,office.code)));
            }else{input.add(new Option('사용','Y'));input.add(new Option('미사용','N'));}
        }else if(item.type==='textarea')input=document.createElement('textarea');
        else{input=document.createElement('input');input.type=['date','month','number','email','password'].includes(item.type)?item.type:item.type==='boolean'?'checkbox':'text';}
        input.id='field-'+item.id;input.name=item.id;input.required=item.required;
        if(item.type==='boolean')input.checked=values[item.id]??true;
        else input.value=Array.isArray(values[item.id])?values[item.id].join(', '):(values[item.id]??(item.type==='flag'?'Y':''));
        if(input.type==='password'){input.autocomplete='new-password';input.minLength=12;input.maxLength=72;input.disabled=!!entry;}
        if(entry&&mg.current.keyFields.includes(item.id))input.disabled=true;
        label.append(input);byId('fields').append(label);
    }
    byId('save').disabled=!canWrite();
    byId('remove').hidden=!entry||!canWrite()||(mg.current.kind==='directory'&&mg.current.id!=='jobs');
}
function cell(row,value){const td=document.createElement('td');td.textContent=value;row.append(td);}
async function load(){
    const query=byId('search').value.trim();let items,totalPages,totalElements;
    if(mg.current.kind==='directory'){
        mg.directory=await call('/api/admin/directory');
        const filtered=mg.directory[mg.current.id].filter(item=>!query||Object.values(item).some(value=>String(value).toLowerCase().includes(query.toLowerCase())));
        totalElements=filtered.length;totalPages=Math.ceil(totalElements/20);items=filtered.slice(mg.page*20,mg.page*20+20);
    }else{
        const page=await call(`/api/admin/reference-data/${mg.current.id}?page=${mg.page}&size=20&query=${encodeURIComponent(query)}`);
        items=page.content;totalElements=page.totalElements;totalPages=page.totalPages;
    }
    byId('tableHead').replaceChildren();const heading=document.createElement('tr');const shown=mg.current.fields.filter(f=>f.type!=='password').slice(0,5);
    for(const item of shown){const th=document.createElement('th');th.textContent=item.label;heading.append(th);}
    const actionHead=document.createElement('th');actionHead.textContent='작업';heading.append(actionHead);byId('tableHead').append(heading);
    byId('tableRows').replaceChildren();
    for(const entry of items){
        const row=document.createElement('tr'),values=mg.current.kind==='directory'?entry:entry.values;
        shown.forEach(item=>cell(row,typeof values[item.id]==='boolean'?(values[item.id]?'사용':'미사용'):String(values[item.id]??'')));
        const td=document.createElement('td'),button=document.createElement('button');button.type='button';button.textContent=canWrite()?'편집':'상세';
        button.addEventListener('click',()=>edit(entry));td.append(button);row.append(td);byId('tableRows').append(row);
    }
    if(!items.length){const row=document.createElement('tr');cell(row,'등록된 자료가 없습니다.');row.firstElementChild.colSpan=shown.length+1;byId('tableRows').append(row);}
    byId('pageInfo').textContent=`${totalElements?mg.page+1:0} / ${totalPages}쪽 (${totalElements}건)`;
    byId('previous').disabled=mg.page===0;byId('next').disabled=mg.page+1>=totalPages;
    byId('exportLink').hidden=mg.current.kind!=='reference';
    if(mg.current.kind==='reference')byId('exportLink').href=`/api/admin/reference-data/${mg.current.id}/export`;
}
async function choose(){mg.current=mg.modules.find(item=>item.kind+':'+item.id===byId('module').value);mg.page=0;await load();edit();}
document.addEventListener('DOMContentLoaded',()=>guarded(async()=>{
    const boot=await call('/api/bootstrap');mg.csrf=boot.csrf;
    const actions=boot.context.programActions;
    const allowed=id=>actions.some(a=>a.actionId===id&&a.menuId===(id==='COMMON_CODE_SAVE'?'SYSTEM_CODE':'SYSTEM_AUTH'));
    mg.canDirectoryWrite=allowed('AUTHORITY_UPDATE');mg.canReference=allowed('COMMON_CODE_SAVE');
    if(allowed('AUTHORITY_READ')){mg.directory=await call('/api/admin/directory');mg.modules.push(...directoryModules);}
    if(mg.canReference)mg.modules.push(...(await call('/api/admin/reference-data')).map(module=>({...module,kind:'reference'})));
    if(!mg.modules.length){message('관리 업무에 접근할 권한이 없습니다.',true);return;}
    mg.modules.forEach(module=>byId('module').add(new Option(module.name,module.kind+':'+module.id)));
    byId('module').addEventListener('change',()=>guarded(choose));
    byId('searchButton').addEventListener('click',()=>guarded(async()=>{mg.page=0;await load();}));
    byId('newButton').addEventListener('click',()=>edit());
    byId('previous').addEventListener('click',()=>guarded(async()=>{mg.page--;await load();}));
    byId('next').addEventListener('click',()=>guarded(async()=>{mg.page++;await load();}));
    byId('editor').addEventListener('submit',event=>{event.preventDefault();guarded(async()=>{
        const values={};mg.current.fields.forEach(item=>{const input=byId('field-'+item.id);values[item.id]=item.type==='boolean'?input.checked:item.type==='list'?input.value.split(',').map(v=>v.trim()).filter(Boolean):input.value;});
        let payload=values;
        if(mg.current.kind==='reference')payload={values,expectedVersion:mg.selected?.version||0};
        else if(mg.current.id==='people'){const initialPassword=values.initialPassword;delete values.initialPassword;payload={person:values,initialPassword:mg.selected?null:initialPassword};}
        const url=mg.current.kind==='directory'?'/api/admin/directory/':'/api/admin/reference-data/';
        byId('save').disabled=true;
        try{await call(url+mg.current.id,'POST',payload);await load();edit();message('저장했습니다.');}finally{byId('save').disabled=!canWrite();}
    });});
    byId('remove').addEventListener('click',()=>guarded(async()=>{
        if(!mg.selected||!confirm('선택한 자료를 삭제할까요?'))return;
        const url=mg.current.kind==='directory'?`/api/admin/directory/jobs/${encodeURIComponent(mg.selected.id)}`:`/api/admin/reference-data/${mg.current.id}/${encodeURIComponent(mg.selected.id)}?version=${mg.selected.version}`;
        await call(url,'DELETE');await load();edit();message('삭제했습니다.');
    }));
    await choose();message('관리 정보를 불러왔습니다.');
}));
