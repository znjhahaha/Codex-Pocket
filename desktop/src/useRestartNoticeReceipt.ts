import {useEffect,useRef} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {getCurrentWindow} from '@tauri-apps/api/window';
import type {RestartNotice,UpdateState} from './domain';

// Shared by both panels in this window, including StrictMode and tab remounts.
// A new window can retry; the backend keeps the first acknowledged shownAt.
const receipts=new Map<string,{snapshot:UpdateState|undefined;phase:'pending'|'acknowledged'|'failed'}>();

export function useRestartNoticeReceipt(notice:RestartNotice|undefined,snapshot:UpdateState|undefined){
 const ref=useRef<HTMLParagraphElement>(null);
 const operationId=notice?.operationId,noticeId=notice?.noticeId;
 useEffect(()=>{
  if(!operationId||!noticeId)return;
  const key=JSON.stringify([operationId,noticeId]);
  let disposed=false,frame=0,checking=false;
  const available=()=>{
   const receipt=receipts.get(key);
   return !receipt||receipt.phase==='failed'&&receipt.snapshot!==snapshot;
  };
  const shown=()=>{
   const node=ref.current;
   if(disposed||document.visibilityState!=='visible'||!node?.isConnected||!node.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}))return false;
   const rect=node.getBoundingClientRect();
   return rect.width>0&&rect.height>0&&rect.bottom>0&&rect.right>0&&rect.top<window.innerHeight&&rect.left<window.innerWidth;
  };
  const acknowledge=async()=>{
   try{
    if(!shown()||!available()||!await getCurrentWindow().isVisible()||!shown()||!available())return;
    const receipt={snapshot,phase:'pending' as 'pending'|'acknowledged'|'failed'};
    receipts.set(key,receipt);
    try{await invoke<void>('acknowledge_restart_notice',{operationId,noticeId});receipt.phase='acknowledged';}
    catch{receipt.phase='failed';} // Retry with the next snapshot, without another dialog.
   }catch{/* Visibility could not be verified; a later snapshot or show event can retry. */}
  };
  const schedule=async()=>{
   if(frame){cancelAnimationFrame(frame);frame=0;}
   if(checking||!shown()||!available())return;
   checking=true;
   try{
    if(await getCurrentWindow().isVisible()&&shown()&&available()){
     // rAF runs before paint. A second frame leaves a paint between the callbacks.
     frame=requestAnimationFrame(()=>{frame=requestAnimationFrame(()=>{frame=0;void acknowledge();});});
    }
   }catch{/* Leave this notice unacknowledged until visibility can be verified. */}
   finally{checking=false;}
  };
  void schedule();
  document.addEventListener('visibilitychange',schedule);
  window.addEventListener('focus',schedule);
  window.addEventListener('scroll',schedule,true);
  return ()=>{
   disposed=true;cancelAnimationFrame(frame);
   document.removeEventListener('visibilitychange',schedule);
   window.removeEventListener('focus',schedule);
   window.removeEventListener('scroll',schedule,true);
  };
 },[operationId,noticeId,snapshot]);
 return ref;
}
