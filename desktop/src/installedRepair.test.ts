import {describe,it,expect} from 'vitest';
import {createRepairFlight,REPAIR_STEPS} from './installedRepair';

describe('installed optional repair boundary',()=>{
 it('exposes only four fixed installer steps',()=>expect(REPAIR_STEPS.map(s=>s.step)).toEqual(['shortcut_0','shortcut_1','shortcut_2','startup']));
 it('sends one closed request while a previous operation is pending',async()=>{
  let finish!:(value:unknown)=>void;
  const calls:unknown[]=[];
  const flight=createRepairFlight((command,args)=>{calls.push({command,args});return new Promise(resolve=>{finish=resolve})},()=> '12345678-1234-4234-8234-123456789abc');
  const first=flight.run('startup','a'.repeat(64));
  expect(flight.busy()).toBe(true);
  await expect(flight.run('shortcut_0','a'.repeat(64))).rejects.toMatchObject({code:'operation_busy'});
  expect(calls).toEqual([{command:'repair_installed_integration',args:{request:{step:'startup',expectedGeneration:'a'.repeat(64),operationId:'12345678-1234-4234-8234-123456789abc'}}}]);
  finish({});await first;expect(flight.busy()).toBe(false);
 });
 it('keeps truthful backend failure and permits a fresh request after rejection',async()=>{
  const error={code:'repair_unsupported',operationId:'fixture-id',retryable:false};let count=0;
  const flight=createRepairFlight(async()=>{count++;throw error});
  await expect(flight.run('startup','a'.repeat(64))).rejects.toBe(error);
  expect(flight.busy()).toBe(false);
  await expect(flight.run('startup','a'.repeat(64))).rejects.toBe(error);
  expect(count).toBe(2);
 });
 it('refuses arbitrary commands or missing generation before invocation',async()=>{
  let count=0;const flight=createRepairFlight(async()=>{count++});
  await expect(flight.run('firewall' as 'startup','a'.repeat(64))).rejects.toMatchObject({code:'invalid_params'});
  await expect(flight.run('startup','')).rejects.toMatchObject({code:'invalid_params'});
  expect(count).toBe(0);
 });
 it('checks the same operation after an unknown result and blocks fresh repairs',async()=>{
  const calls:Record<string,unknown>[]=[];
  const flight=createRepairFlight(async(_command,args)=>{calls.push(args);return {state:calls.length===1?'unknown':'completed',code:calls.length===1?'operation_unknown':'integration_completed'}},()=> '12345678-1234-4234-8234-123456789abc');
  await flight.run('startup','a'.repeat(64));
  await expect(flight.run('shortcut_0','a'.repeat(64))).rejects.toMatchObject({code:'reconciliation_required'});
  await flight.check();
  expect(calls).toEqual([{request:{step:'startup',expectedGeneration:'a'.repeat(64),operationId:'12345678-1234-4234-8234-123456789abc'}},
   {request:{step:'startup',expectedGeneration:'a'.repeat(64),operationId:'12345678-1234-4234-8234-123456789abc',checkRepair:true}}]);
  expect(flight.current()).toBeNull();
 });
});
