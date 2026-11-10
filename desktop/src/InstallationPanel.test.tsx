import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {InstallationPanel} from './InstallationPanel';
import type {InstallationStatus} from './domain';

const render=(optional?:unknown)=>renderToStaticMarkup(<InstallationPanel status={{consistent:true,optionalIntegrations:optional} as InstallationStatus} onRefresh={()=>{}} onDiagnostics={()=>{}}/>);
describe('installed optional integration presentation',()=>{
 it('shows recorded pending failure separately from completed steps and restart',()=>{
  const html=render({recordState:'available',installationState:'installed_incomplete',repairSupported:false,recoveryState:'clear',steps:[
   {step:'shortcut_0',state:'completed'}, {step:'shortcut_1',state:'pending',stage:'shortcut_verify',code:'shortcut_integration_failed',call:'IPersistFile.Load/GetPath/GetArguments/GetIconLocation',hresult:'0x80004005'},
   {step:'shortcut_2',state:'completed'}, {step:'startup',state:'pending',stage:'startup_read',code:'startup_snapshot_unknown',hresult:'0x80070005'}]});
  expect(html).toContain('已记录完成');expect(html).toContain('待补全');
  expect(html).toContain('shortcut_integration_failed');expect(html).toContain('0x80004005');
  expect(html).toContain('旧桌面入口清理');expect(html).toContain('旧登录快捷方式清理');
  expect(html).toContain('当前安装器尚不支持分项修复');
  expect((html.match(/disabled=""/g)||[]).length).toBe(4);
  expect(html).not.toContain('重新登录 Windows');
 });
 it('keeps missing legacy snapshots unverified with no enabled repair',()=>{
  const html=render();expect(html).toContain('尚未取得安装器分项记录');
  expect((html.match(/尚未核验/g)||[]).length).toBeGreaterThanOrEqual(4);
  expect(html).not.toContain('已记录完成');expect((html.match(/disabled=""/g)||[]).length).toBe(4);
 });
 it('shows a recovery warning even when every recorded step completed',()=>{
  const html=render({recordState:'available',repairSupported:false,recoveryState:'pending',steps:['shortcut_0','shortcut_1','shortcut_2','startup'].map(step=>({step,state:'completed'}))});
  expect(html).toContain('安装事务恢复待处理');expect(html).toContain('已记录完成');
 });
 it('never enables repair from an untrusted supported flag or unknown step state',()=>{
  const html=render({recordState:'available',repairSupported:true,recoveryState:'unknown',steps:[{step:'startup',state:'success'}]});
  expect((html.match(/disabled=""/g)||[]).length).toBe(4);
  expect(html).toContain('恢复状态尚未核验');expect(html).not.toContain('已记录完成');
 });
 it('enables only pending fixed steps with a verified generation and capability',()=>{
  const html=renderToStaticMarkup(<InstallationPanel status={{consistent:true,installedGeneration:'a'.repeat(64),optionalIntegrations:{recordState:'available',repairSupported:true,recoveryState:'clear',steps:[
   {step:'shortcut_0',state:'completed'}, {step:'shortcut_1',state:'pending'}, {step:'shortcut_2',state:'completed'}, {step:'startup',state:'pending'}]}}} onRefresh={()=>{}} onDiagnostics={()=>{}}/>);
  expect((html.match(/disabled=""/g)||[]).length).toBe(2);
  expect(html).toContain('仅重试所选步骤');expect(html).not.toContain('当前安装器尚不支持分项修复');
 });
});
