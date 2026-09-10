'use strict';
const lookupById=id=>document.getElementById(id);
function lookupMessage(text,error=false){lookupById('feedback').textContent=text;lookupById('feedback').classList.toggle('error',error);}
async function lookupCall(url){
    const response=await fetch(url,{headers:{Accept:'application/json'},credentials:'same-origin'});
    if(response.status===401){location.assign('/login');throw new Error('로그인이 필요합니다.');}
    const result=await response.json();
    if(!response.ok)throw new Error(result.error?.message||'조회하지 못했습니다.');
    return result.data;
}
async function loadColleagues(){
    lookupById('searchButton').disabled=true;
    lookupById('people').replaceChildren();lookupById('scope').textContent='';
    try{
        const params=new URLSearchParams({name:lookupById('name').value,officeCode:lookupById('officeCode').value,ssoDepartmentName:lookupById('ssoDepartmentName').value,
            authId:lookupById('authId').value,procStatus:lookupById('procStatus').value,systemPrefix:lookupById('systemPrefix').value});
        const result=await lookupCall(`/api/colleagues?${params}`);
        lookupById('officeCode').disabled=!result.headquarters;
        lookupById('officeCode').value=result.officeCode;
        lookupById('scope').textContent=`조회 범위: ${result.officeCode||'전체 사업소'} · ${result.people.length}명`;
        for(const person of result.people){
            const row=document.createElement('tr');
            for(const value of [person.username,person.name,person.officeName?`${person.officeName} (${person.officeCode})`:person.officeCode,person.ssoDepartmentName,person.active?'사용':'미사용']){
                const cell=document.createElement('td');cell.textContent=value;row.append(cell);
            }
            lookupById('people').append(row);
        }
        if(!result.people.length){const row=document.createElement('tr'),cell=document.createElement('td');cell.colSpan=5;cell.textContent='조회 조건에 해당하는 담당자가 없습니다.';row.append(cell);lookupById('people').append(row);}
        lookupMessage('담당자 목록을 불러왔습니다.');
    }catch(error){lookupMessage(error.message,true);}
    finally{lookupById('searchButton').disabled=false;}
}
document.addEventListener('DOMContentLoaded',async()=>{
    try{
        const boot=await lookupCall('/api/bootstrap');
        const allowed=boot.context.programActions.some(action=>action.menuId==='SYSTEM_AUTH'&&action.programId==='AUTHORITY'&&action.actionId==='DIRECTORY_LOOKUP');
        if(!allowed){lookupMessage('업무담당자 조회 권한이 없습니다.',true);return;}
        lookupById('lookup').addEventListener('submit',event=>{event.preventDefault();loadColleagues();});
        await loadColleagues();
    }catch(error){lookupMessage(error.message,true);}
});
