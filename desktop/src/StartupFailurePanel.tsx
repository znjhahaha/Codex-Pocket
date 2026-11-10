import type {StartupFailure} from './domain';
import {operationDetails} from './domain';
export function StartupFailurePanel({failure}:{failure?:StartupFailure|null}) {
 if(!failure)return null;
 return <section className="section" aria-label="最近一次助手启动失败"><div className="section-heading"><h2>最近一次助手启动失败</h2></div>
  <p>这是一次启动操作的失败记录，当前运行状态和连接仍需分别核验。</p>
  <p role="status">{operationDetails({stage:failure.phase,code:failure.code,operationId:failure.operationId,osCode:failure.osCode})}</p>
  <small>关联启动进程 {failure.pid} · 代次 {failure.runtimeGeneration?.slice(0,12)||'未取得'} · {new Date(failure.time).toLocaleString('zh-CN')}</small>
 </section>;
}
