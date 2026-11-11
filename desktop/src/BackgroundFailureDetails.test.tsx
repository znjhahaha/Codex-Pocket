import {describe,expect,it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import type {BackgroundStartupFailure,Snapshot} from './domain';
import {BackgroundFailureDetails} from './BackgroundFailureDetails';

const record=(code:BackgroundStartupFailure['code'],osCode?:number):BackgroundStartupFailure=>({
 schema:1,operationId:'fixture-history',supervisorPid:42,runtimeGeneration:'a'.repeat(64),time:1794376800000,
 phase:code==='helper_spawn_failed'?'helper_spawn':code==='tray_spawn_failed'?'tray_spawn':'tray_verify',code,osCode,retryable:true,
});
describe('projected background startup history',()=>{
 it('does not require the optional diagnostic fields',()=>{
  expect(renderToStaticMarkup(<BackgroundFailureDetails supervisor={{phase:'running',updatedAt:100,failures:0}}/>)).toBe('');
 });
 it.each([
  ['tray_verification_failed','托盘启动文件未通过核验'],['tray_spawn_failed','托盘窗口启动失败'],['helper_spawn_failed','电脑助手启动失败'],
 ] as const)('shows a fixed reason for %s as a past record', (code,label)=>{
  const failure=record(code,5),supervisor:Snapshot['supervisor']={phase:'running',updatedAt:100,failures:0,
   ...(code==='helper_spawn_failed'?{helperSpawnFailure:failure}:{trayStartupFailure:failure})};
  const html=renderToStaticMarkup(<BackgroundFailureDetails supervisor={supervisor}/>);
  expect(html).toContain('最近后台启动记录');expect(html).toContain(label);expect(html).toContain('OS 5');
  expect(html).toContain('记录于');expect(html).not.toContain('role="alert"');expect(html).not.toContain('后台当前启动失败');
 });
 it('preserves OS zero and shows tray and helper records independently',()=>{
  const html=renderToStaticMarkup(<BackgroundFailureDetails supervisor={{phase:'running',updatedAt:100,failures:0,
   trayStartupFailure:record('tray_spawn_failed',0),helperSpawnFailure:record('helper_spawn_failed',2)}}/>);
  expect(html).toContain('OS 0');expect(html).toContain('OS 2');expect(html).toContain('托盘窗口启动失败');expect(html).toContain('电脑助手启动失败');
 });
});
