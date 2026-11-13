import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {StartupControl,startupDescription} from './StartupControl';

describe('user-driven logon status verification',()=>{
 it.each(['unknown',undefined] as const)('offers a check while keeping unknown logon state disabled (%s)',status=>{
  let calls=0;
  for(let i=0;i<3;i++){
   const html=renderToStaticMarkup(<StartupControl status={status} enabled={null} disabled={false} onCheck={()=>calls++} onToggle={()=>calls++}/>);
   expect(html).toContain('核验登录任务');expect((html.match(/disabled=""/g)||[]).length).toBe(1);
   expect(html).not.toContain('switch on');
  }
  expect(calls).toBe(0);expect(startupDescription(status,null)).toContain('尚未核验或核验已过期');
 });
 it('never treats a missing boolean as known even if the status claims known',()=>{
  const html=renderToStaticMarkup(<StartupControl status="known" enabled={null} disabled={false} onCheck={()=>{}} onToggle={()=>{}}/>);
  expect(html).toContain('disabled=""');expect(startupDescription('known',null)).toContain('尚未核验');
 });
 it('disables duplicate user actions during an explicit check',()=>{
  const html=renderToStaticMarkup(<StartupControl status="pending" enabled={null} disabled={false} onCheck={()=>{}} onToggle={()=>{}}/>);
  expect((html.match(/disabled=""/g)||[]).length).toBe(2);
 });
 it.each([true,false])('enables the switch only with fresh explicit boolean evidence (%s)',enabled=>{
  const html=renderToStaticMarkup(<StartupControl status="known" enabled={enabled} disabled={false} onCheck={()=>{}} onToggle={()=>{}}/>);
  expect(html).not.toContain('disabled=""');expect(html).toContain(`aria-checked="${enabled}"`);
 });
});
