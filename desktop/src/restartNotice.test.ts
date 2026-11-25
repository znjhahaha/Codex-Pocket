import {afterAll,afterEach,beforeAll,beforeEach,describe,expect,it} from 'vitest';
import {chromium,type Browser,type Page} from '@playwright/test';
import {createServer,type ViteDevServer} from 'vite';
import type {Snapshot} from './domain';
import type {NoticeFixture} from './restartNoticeTestHarness';

declare global {interface Window {noticeFixture:NoticeFixture}}
// The app's browser tsconfig does not include Node ambient declarations.
declare const process:{platform:string};
const operationId='b85e78a5-4874-4217-9b56-cf77315a6f36',noticeId='notice-1';
function snapshot():Snapshot {
 return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:100,failures:0},relay:{state:'ready',host:''},devices:[],processes:[],version:'0.7.1',startup:true,
  updates:{automatic:false,restartRequested:true,operationId,phase:'waiting_cold_start',generation:'b'.repeat(64),downloadedBytes:1,totalBytes:1,coordinator:{phase:'waiting',reason:'restart_lock_owner_active',noticeId}},
  installation:{consistent:true,installedVersion:'0.7.0',installedGeneration:'a'.repeat(64),restartRequired:true,
   ui:{state:'staged_dashboard',generation:'b'.repeat(64),runningVerified:true}}};
}
let server:ViteDevServer,browser:Browser,page:Page;
beforeAll(async()=>{
 server=await createServer({configFile:false,cacheDir:'src/node_modules/.vite-restart-notice-test',
  server:{host:'127.0.0.1',port:0},optimizeDeps:{entries:['src/restartNoticeTestHarness.tsx']}});
 await server.listen();
 browser=await chromium.launch(process.platform==='win32'?{channel:'msedge'}:{});
},30_000);
afterAll(async()=>{await browser?.close();await server?.close();});
beforeEach(async()=>{
 page=await browser.newPage({viewport:{width:1120,height:850}});
 await page.route('**/notice-test',route=>route.fulfill({contentType:'text/html',body:'<div id="root"></div><script type="module" src="/src/restartNoticeTestHarness.tsx"></script>'}));
 await page.goto(`${server.resolvedUrls!.local[0]}notice-test`);
 await page.waitForFunction(()=>!!window.noticeFixture);
});
afterEach(async()=>{await page?.close();});
const render=(s=snapshot(),view:'updates'|'recovery'|'both'|'none'='updates',hidden=false)=>page.evaluate(({s,view,hidden})=>window.noticeFixture.render(s,view,hidden),{s,view,hidden});
const frame=()=>page.evaluate(()=>window.noticeFixture.frame());
const calls=()=>page.evaluate(()=>window.noticeFixture.calls);
const painted=async()=>{await frame();await frame();};

describe('painted restart notice receipt',()=>{
 it.each(['updates','recovery'] as const)('acknowledges only after %s paints the lock warning',async view=>{
  await render(snapshot(),view);
  expect(await page.getByText('检测到旧 Pocket 后台占用',{exact:false}).isVisible()).toBe(true);
  expect(await calls()).toEqual([]);await frame();expect(await calls()).toEqual([]);
  await frame();expect(await calls()).toEqual([{operationId,noticeId}]);
 });
 it.each(['window','document','region'] as const)('does not acknowledge a hidden %s and retries when shown',async kind=>{
  await page.evaluate(kind=>{if(kind==='window')window.noticeFixture.visible=false;if(kind==='document')window.noticeFixture.visibility='hidden';},kind);
  await render(snapshot(),'updates',kind==='region');await painted();expect(await calls()).toEqual([]);
  await page.evaluate(()=>{window.noticeFixture.visible=true;window.noticeFixture.visibility='visible';document.dispatchEvent(new Event('visibilitychange'));});
  await render();await painted();expect(await calls()).toEqual([{operationId,noticeId}]);
 });
 it('does not acknowledge an unmounted panel',async()=>{
  await render(snapshot(),'none');await painted();expect(await calls()).toEqual([]);
 });
 it.each(['inconsistent','handoff','current'] as const)('does not acknowledge a recovery card showing %s instead of the warning',async state=>{
  const s=snapshot();
  if(state==='inconsistent')s.installation!.consistent=false;
  if(state==='handoff')s.installation!.upgrade={phase:'verifying',operationId:'handoff',updatedAt:100,fromGeneration:'a'.repeat(64),toGeneration:'b'.repeat(64)};
  if(state==='current')s.installation!.ui={state:'current',generation:'a'.repeat(64),runningVerified:true};
  await render(s,'recovery');await painted();expect(await calls()).toEqual([]);
 });
 it('deduplicates both mounted panels, in-flight snapshots and tab changes',async()=>{
  await page.evaluate(()=>{window.noticeFixture.holdAck=true;});
  await render(snapshot(),'both');await painted();expect(await calls()).toHaveLength(1);
  await render(snapshot(),'both');await painted();expect(await calls()).toHaveLength(1);
  await page.evaluate(()=>{window.noticeFixture.resolveAck!();});
  await render(snapshot(),'none');await render(snapshot(),'recovery');await painted();expect(await calls()).toHaveLength(1);
 });
 it('retries a failed receipt on the next snapshot',async()=>{
  await page.evaluate(()=>{window.noticeFixture.fail=true;});
  await render();await painted();expect(await calls()).toHaveLength(1);
  await page.evaluate(()=>{window.noticeFixture.fail=false;});
  await render();await painted();expect(await calls()).toHaveLength(2);
  await render();await painted();expect(await calls()).toHaveLength(2);
 });
 it('keys receipts by both operation and notice',async()=>{
  await render();await painted();
  const s=snapshot();s.updates!.coordinator!.noticeId='notice-2';await render(s);await painted();
  s.updates!.operationId='operation-2';await render(s);await painted();
  expect(await calls()).toEqual([{operationId,noticeId},{operationId,noticeId:'notice-2'},{operationId:'operation-2',noticeId:'notice-2'}]);
 });
 it.each(['missing_notice','missing_operation','other_reason','finished','succeeded'] as const)('does not acknowledge %s',async state=>{
  const s=snapshot();
  if(state==='missing_notice')delete s.updates!.coordinator!.noticeId;
  if(state==='missing_operation')delete s.updates!.operationId;
  if(state==='other_reason')s.updates!.coordinator!.reason='restart_write_in_flight';
  if(state==='finished')s.updates!.coordinator!.phase='finished';
  if(state==='succeeded')s.updates!.phase='succeeded';
  await render(s,'both');await painted();expect(await calls()).toEqual([]);
 });
 it('cancels a pending receipt when the warning unmounts',async()=>{
  await render();await frame();await render(snapshot(),'none');await painted();expect(await calls()).toEqual([]);
 });
 it('rechecks the mounted warning after native visibility resolves',async()=>{
  await page.evaluate(()=>{window.noticeFixture.holdVisibility=true;});
  await render();await painted();
  await page.waitForFunction(()=>!!window.noticeFixture.resolveVisibility);
  await render(snapshot(),'none');await page.evaluate(()=>{window.noticeFixture.resolveVisibility!(true);});
  await painted();expect(await calls()).toEqual([]);
 });
 it('allows the same notice to be acknowledged after the window reloads',async()=>{
  await render();await painted();expect(await calls()).toHaveLength(1);
  await page.reload();await page.waitForFunction(()=>!!window.noticeFixture);await render();await painted();
  expect(await calls()).toEqual([{operationId,noticeId}]);
 });
});
