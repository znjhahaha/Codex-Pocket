import {test,expect} from '@playwright/test';

test('same-version different-generation stays pending and control failure keeps its stage and exit code',async({page})=>{
 await page.addInitScript(()=>{
  (window as any).__TAURI_INTERNALS__={invoke:async(command:string)=>{
   if(command==='dashboard')return {computerName:'Fixture',desired:true,supervisor:{phase:'running',updatedAt:Date.now()/1000,failures:0},relay:{state:'ready',host:''},devices:[],processes:[],version:'0.6.3',startup:true,
    connection:{phase:'recovering',connected:false,available:false,helperUpdatedAt:Date.now()/1000},
    installation:{consistent:true,installedVersion:'0.6.3',installedGeneration:'a'.repeat(64),restartRequired:true,
     ui:{version:'0.6.3',generation:'a'.repeat(64),runningVerified:true,state:'current'},
     supervisor:{version:'0.6.3',generation:'b'.repeat(64),runningVerified:true,state:'restart_required'},
     helper:{version:'0.6.3',state:'unverified'}},
    issues:[{stage:'routes_read',code:'control_rejected',message:'本机助手拒绝了此请求',operationId:'fixture-operation',exitCode:5,retryable:true}]};
   if(command==='export_diagnostics')return 'fixture-diagnostics.json';
   throw new Error('Unexpected command');
  }};
 });
 await page.goto('/');
 await page.getByRole('button',{name:'查看详情',exact:true}).click();
 const panel=page.getByRole('region',{name:'安装与运行版本'});
 await expect(panel.getByText('当前安装已生效',{exact:true})).toHaveCount(1);
 await expect(panel.getByText('等待重启生效',{exact:true})).toHaveCount(1);
 await expect(panel.getByText('尚未核验',{exact:true})).toHaveCount(1);
 await expect(page.getByRole('alert').getByText('routes_read · control_rejected · 退出码 5 · 操作 fixture-operation',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'重新核验',exact:true}).click();
 await expect(panel.getByText('等待重启生效',{exact:true})).toBeVisible();
});

test('a newer installation can recheck an older waiting-restart operation',async({page})=>{
 await page.addInitScript(()=>{
  (window as any).__TAURI_INTERNALS__={invoke:async(command:string)=>{
   if(command==='dashboard')return {computerName:'Fixture',desired:true,supervisor:{phase:'waiting_restart',updatedAt:Date.now()/1000,failures:0},relay:{state:'ready',host:''},devices:[],processes:[],version:'0.6.3',startup:true,
    connection:{phase:'waiting_restart',connected:false,available:false,helperUpdatedAt:Date.now()/1000},issues:[],
    installation:{consistent:true,installedVersion:'0.6.3',installedGeneration:'c'.repeat(64),restartRequired:true,
     supervisor:{version:'0.6.3',generation:'a'.repeat(64),runningVerified:true,state:'restart_required'},
     upgrade:{operationId:'previous',phase:'waiting_restart',fromGeneration:'a'.repeat(64),toGeneration:'b'.repeat(64),updatedAt:1}}};
   throw new Error('Unexpected command');
  }};
 });
 await page.goto('/');
 await page.getByRole('button',{name:'查看详情',exact:true}).click();
 await expect(page.getByRole('button',{name:'检查安全交接',exact:true})).toBeEnabled();
});
