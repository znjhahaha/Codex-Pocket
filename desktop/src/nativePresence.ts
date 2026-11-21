import {freshHeartbeat, type RuntimeReadiness} from './domain';

export function nativePresence(report:RuntimeReadiness|undefined, now:number) {
 const current=report?.authoritative===true && freshHeartbeat(report.updatedAt,now);
 const native=current?report.nativeObservation:undefined;
 const local=report?.localDesktop;
 const localVersion=local && freshHeartbeat(local.observedAt,now)?local.version:undefined;
 const connected=(role:string)=>current && report.ready && report.runtimes.some(r=>r.role===role && r.ready);
 const desktopControl=connected('desktop'), cliControl=connected('cliDaemon');
 const desktop=Boolean(localVersion || desktopControl || ['detected','controllable'].includes(native?.desktop?.state??''));
 // A managed assistant host in older helpers can report the CLI as controllable.
 // Only a session endpoint can corroborate that claim.
 const cli=native?.cli?.state==='detected' || cliControl;
 return {current,desktop,cli,desktopControl,cliControl,desktopVersion:localVersion||native?.desktop?.version,
  history:current && report.historyReadable===true};
}
