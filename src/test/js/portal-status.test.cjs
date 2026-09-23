const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/portal.js'), 'utf8');

function fixture() {
    function node() {
        return {children: [], attributes: {}, dataset: {authorized: 'true'}, value: '',
            replaceChildren() { this.children = []; },
            appendChild(child) { this.children.push(child); },
            append(...children) { this.children.push(...children); },
            setAttribute(key, value) { this.attributes[key] = value; },
            removeAttribute(key) { delete this.attributes[key]; }};
    }
    const nodes = new Map();
    const get = id => { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); };
    const context = vm.createContext({URLSearchParams, document: {addEventListener() {}, createElement: node,
        getElementById: get, querySelector: () => get('submit'), querySelectorAll: () => []}});
    vm.runInContext(source, context);
    vm.runInContext('showToast = () => {}; requestJson = async () => { throw new Error("unexpected request"); };', context);
    return {get, run: script => vm.runInContext(script, context)};
}

test('no active codes means no selectable fallback, and save and publish stay disabled', () => {
    const {get, run} = fixture();
    run('renderStatuses({items: [], version: 7}); updateContentSelectionState();');
    assert.equal(get('contentStatusInput').children.length, 0);
    assert.equal(get('contentStatusInput').disabled, true);
    assert.equal(get('submit').disabled, true);
    assert.equal(get('btnContentSave').attributes['aria-disabled'], 'true');
    assert.equal(get('btnContentPublish').attributes['aria-disabled'], 'true');
    assert.match(get('statusCodes').children[0].textContent, /사용 가능한 업무 상태가 없습니다/);
});

test('reactivating codes restores only the choices and actions actually enabled', () => {
    const {get, run} = fixture();
    run('renderStatuses({items: [], version: 7}); updateContentSelectionState();');
    run('renderStatuses({items: [{code:"REVIEW",name:"검토"}], version: 8}); updateContentSelectionState();');
    assert.deepEqual(get('contentStatusInput').children.map(option => option.value), ['REVIEW']);
    assert.equal(get('submit').disabled, false);
    assert.equal(get('btnContentSave').attributes['aria-disabled'], 'false');
    assert.equal(get('btnContentSave').title, '새 콘텐츠 작성');
    assert.equal(get('btnContentPublish').attributes['aria-disabled'], 'true');
});

test('a removed publish status prevents the request and never substitutes another status', async () => {
    const {run} = fixture();
    run('contentItems = [{id:"108",status:"DRAFT",version:"1"}]; contentStatuses = [{code:"DRAFT",name:"작성 중"}]; selectedContentIds.add("108");');
    await run('publishSelectedContents()');
    assert.equal(run('contentItems[0].status'), 'DRAFT');
    assert.equal(run('selectedContentIds.size'), 1);
});

test('a slower previous search cannot overwrite the latest server result', async () => {
    const {run} = fixture();
    run('hasContentAction = () => true; renderContentRows = () => {}; const pending = []; requestJson = () => new Promise(resolve => pending.push(resolve));');
    const first = run('loadContents(false)');
    const second = run('loadContents(false)');
    run('pending[1]({data:{content:[{id:"2",title:"최신 결과"}], totalElements:1,totalPages:1}})');
    await second;
    run('pending[0]({data:{content:[{id:"1",title:"이전 결과"}], totalElements:1,totalPages:1}})');
    await first;
    assert.equal(run('contentItems[0].title'), '최신 결과');
});

test('revoking read permission discards an already pending content response', async () => {
    const {run} = fixture();
    run('hasContentAction = () => true; renderContentRows = () => {}; let finish; requestJson = () => new Promise(resolve => { finish = resolve; });');
    const pending = run('loadContents(false)');
    run('hasContentAction = () => false');
    await run('loadContents(false)');
    run('finish({data:{content:[{id:"1",title:"권한 회수 전 결과"}], totalElements:1,totalPages:1}})');
    await pending;
    assert.equal(run('contentItems.length'), 0);
});
