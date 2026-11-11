import type {BackgroundStartupFailure,Snapshot} from './domain';

const reasons:Record<BackgroundStartupFailure['code'],string>={
 tray_verification_failed:'托盘启动文件未通过核验',tray_spawn_failed:'托盘窗口启动失败',helper_spawn_failed:'电脑助手启动失败',
};
export function BackgroundFailureDetails({supervisor}:{supervisor:Snapshot['supervisor']}) {
 const records=[supervisor.trayStartupFailure,supervisor.helperSpawnFailure].filter((record):record is BackgroundStartupFailure=>
  !!record&&record.schema===1&&Object.hasOwn(reasons,record.code));
 if(!records.length)return null;
 return <section className="background-failure-history section" aria-label="最近后台启动记录">
  <h3>最近后台启动记录</h3>
  {records.map(record=><div className="background-failure-record" key={`${record.code}-${record.operationId}`}>
   <p>{reasons[record.code]}{typeof record.osCode==='number'?` · OS ${record.osCode}`:''}</p>
   {Number.isFinite(record.time)&&record.time>0&&<small>记录于 <time dateTime={new Date(record.time).toISOString()}>{new Date(record.time).toLocaleString('zh-CN')}</time></small>}
  </div>)}
  <p className="subtle">当前后台状态以上方实时核验为准。</p>
 </section>;
}
