import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {InstallationPanel} from './InstallationPanel';
import type {InstallationStatus} from './domain';

const render=(optional?:unknown)=>renderToStaticMarkup(<InstallationPanel status={{consistent:true,optionalIntegrations:optional} as InstallationStatus} onRefresh={()=>{}} onDiagnostics={()=>{}}/>);
describe('installed optional integration presentation',()=>{
 it('allows an explicit pending repair from native integrity evidence without claiming protocol support',()=>{
  const html=renderToStaticMarkup(<InstallationPanel status={{consistent:true,installedGeneration:'a'.repeat(64),optionalIntegrations:{recordState:'available',repairSupported:false,repairCheckAvailable:true,recoveryState:'clear',steps:[
   {step:'shortcut_0',state:'completed'}, {step:'shortcut_1',state:'pending'}, {step:'shortcut_2',state:'completed'}, {step:'startup',state:'pending'}]}}} onRefresh={()=>{}} onDiagnostics={()=>{}}/>);
  expect((html.match(/disabled=""/g)||[]).length).toBe(2);
  expect(html).toContain('修复能力待核验');expect(html).toContain('点击重试时将核验安装器能力');
  expect(html).not.toContain('修复能力已核验');
 });
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

const renderStatus=(status:InstallationStatus)=>renderToStaticMarkup(<InstallationPanel status={status} onRefresh={()=>{}} onDiagnostics={()=>{}} onUpgrade={()=>{}}/>);
const handoffStatus=(phase:string,failure:string|null=null,bridgeRestartRequired:boolean|undefined=true)=>({
 consistent:true,installedGeneration:'b'.repeat(64),restartRequired:false,
 ui:{state:'current',runningVerified:true},supervisor:{state:'current',runningVerified:true},helper:{state:'current',runningVerified:true},
 upgrade:{operationId:'11111111-1111-4111-8111-111111111111',phase,failure,updatedAt:100,fromGeneration:'a'.repeat(64),toGeneration:'b'.repeat(64),bridgeRestartRequired},
} as InstallationStatus);

describe('background handoff and running Codex bridge presentation',()=>{
 it('keeps a verified background success separate from the bridge awaiting restart',()=>{
  const html=renderStatus(handoffStatus('succeeded'));
  expect(html).toContain('后台交接已核验');
  expect(html).toContain('正在运行的 Codex bridge 仍等待重启生效');
  expect(html).toContain('完成当前Codex任务后重新打开Codex');
  expect(html).not.toContain('后台交接尚未确认完成');
  expect(html).not.toContain('重新登录 Windows');
 });
 it('does not let an older projection missing the bridge field imply a bridge upgrade',()=>{
  const status=handoffStatus('succeeded');
  delete (status.upgrade as {bridgeRestartRequired?:boolean}).bridgeRestartRequired;
  const html=renderStatus(status);
  expect(html).toContain('Codex bridge 的运行版本尚未核验');
  expect(html).toContain('完成当前Codex任务后重新打开Codex');
 });
 it('does not imply the bridge was verified from an explicit false flag',()=>{
  const html=renderStatus(handoffStatus('succeeded',null,false));
  expect(html).toContain('后台交接只核验后台监督和电脑助手');
  expect(html).not.toContain('正在运行的 Codex bridge 仍等待重启生效');
  expect(html).not.toContain('bridge 已更新');
 });
 it.each([
  ['quiescing','暂停新写入并保存状态'],
  ['checkpointed','状态已保存并核验'],
  ['releasing','释放旧后台并等待自然退出'],
  ['starting','启动新后台并恢复状态'],
  ['verifying','核验新后台身份、线路与状态'],
  ['rolling_back','正在恢复旧后台'],
 ])('shows the actual %s phase without claiming completion', (phase,label)=>{
  const html=renderStatus(handoffStatus(phase));
  expect(html).toContain(label);
  expect(html).toContain('11111111-1111-4111-8111-111111111111');
  expect(html).not.toContain('后台交接已核验');
 });
 it('identifies legacy unsupported only from its actual failure classification',()=>{
  const html=renderStatus({...handoffStatus('waiting_restart','handoff_unsupported'),restartRequired:true});
  expect(html).toContain('后台等待重启生效');
  expect(html).toContain('当前后台不支持安全交接');
  expect(html).toContain('handoff_unsupported');
  expect(html).toContain('完成当前Codex任务后重新打开Codex');
  expect(html).toMatch(/disabled=""[^>]*>检查安全交接/);
 });
 it('distinguishes failed capability verification from a legacy unsupported backend',()=>{
  const html=renderStatus(handoffStatus('waiting_restart','capability_failed'));
  expect(html).toContain('未能核验后台安全交接能力');
  expect(html).toContain('capability_failed');
  expect(html).not.toContain('当前后台不支持安全交接');
 });
 it('keeps unknown waiting reasons honest',()=>{
  const html=renderStatus(handoffStatus('waiting_restart'));
  expect(html).toContain('等待重启的原因尚未核验');
  expect(html).not.toContain('当前后台不支持安全交接');
 });
 it('shows a verified rollback with the original failure and bridge pending restart',()=>{
  const html=renderStatus(handoffStatus('rolled_back','verification_failed'));
  expect(html).toContain('旧后台已恢复并核验');
  expect(html).toContain('新后台身份、线路或状态核验未通过');
  expect(html).toContain('verification_failed');
  expect(html).toContain('完成当前Codex任务后重新打开Codex');
  expect(html).not.toContain('后台交接已核验');
 });
 it('shows a fenced startup failure without claiming a successful recovery',()=>{
  const html=renderStatus(handoffStatus('failed','startup_context_invalid'));
  expect(html).toContain('后台恢复未完成');
  expect(html).toContain('启动交接记录未通过核验');
  expect(html).toContain('startup_context_invalid');
  expect(html).not.toContain('旧后台已恢复并核验');
 });
 it('keeps future phase and failure classifications unverified',()=>{
  const html=renderStatus(handoffStatus('future_phase','future_failure'));
  expect(html).toContain('状态待核验');
  expect(html).toContain('原因尚未识别，请导出诊断');
  expect(html).not.toContain('后台交接已核验');
 });
 it('does not claim background handoff is pending just because the management UI is old',()=>{
  const html=renderStatus({...handoffStatus('succeeded'),restartRequired:true,ui:{state:'restart_required',runningVerified:true}});
  expect(html).toContain('后台交接已核验');
  expect(html).not.toContain('后台交接尚未确认完成');
  expect(html).not.toContain('重新登录 Windows');
 });
 it('does not add a bridge restart warning without a handoff record',()=>{
  expect(renderStatus({consistent:true})).not.toContain('完成当前Codex任务后重新打开Codex');
 });
 it('labels an old waiting-restart record as history after verified startup',()=>{
  const html=renderToStaticMarkup(<InstallationPanel status={handoffStatus('waiting_restart','handoff_unsupported')} upgradeHistorical onRefresh={()=>{}} onDiagnostics={()=>{}}/>);
  expect(html).toContain('上次后台更新记录');expect(html).toContain('此为历史交接记录');
  expect(html).not.toContain('重新登录 Windows');
 });
});
