import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {diagnosticRuntimeStatus,type Snapshot} from './domain';
import {StartupFailurePanel} from './StartupFailurePanel';
const base:Snapshot={computerName:'Fixture',desired:false,supervisor:{phase:'stopped',updatedAt:0,failures:0},relay:{state:'stopped',host:''},devices:[],processes:[],version:'fixture',startup:false};
describe('startup and liveness evidence',()=>{
 it('does not call absent default stopped state a stopped process',()=>{
  expect(diagnosticRuntimeStatus(base,'supervisor',100).label).toBe('尚未取得状态');
  expect(diagnosticRuntimeStatus(base,'helper',100).label).toBe('尚未取得状态');
 });
 it('distinguishes read failure from absent evidence',()=>{
  const s={...base,issues:[{stage:'supervisor_read',code:'helper_unavailable',message:'Fixture',operationId:'1-2-3',retryable:true}]};
  expect(diagnosticRuntimeStatus(s,'supervisor',100).label).toBe('状态读取失败');
 });
 it('requires verified process plus fresh heartbeat for running display',()=>{
  const s={...base,supervisor:{...base.supervisor,phase:'running',updatedAt:100},installation:{consistent:true,supervisor:{runningVerified:true,state:'current' as const}}};
  expect(diagnosticRuntimeStatus(s,'supervisor',100)).toEqual({label:'运行中',good:true});
 });
 it('shows startup failure as historical operation evidence and supports old snapshots',()=>{
  const html=renderToStaticMarkup(<StartupFailurePanel failure={{schema:1,operationId:'12345678-1234-4234-8234-123456789abc',pid:42,runtimeGeneration:'a'.repeat(64),time:1794316209000,phase:'config',code:'config_load_failed',osCode:5}}/>);
  expect(html).toContain('最近一次助手启动失败');expect(html).toContain('config_load_failed');expect(html).toContain('OS 5');
  expect(html).toContain('当前运行状态和连接仍需分别核验');
  expect(renderToStaticMarkup(<StartupFailurePanel/>)).toBe('');
 });
});
