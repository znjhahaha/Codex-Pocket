import {describe,it,expect} from 'vitest';
import {operationDetails,operationError} from './domain';
describe('operation failure correlation',()=>{
 it('keeps stage ID OS code and exit code in the visible detail',()=>{
  const error=operationError({stage:'route_lock',code:'route_operation_busy',message:'Fixture busy',operationId:'1794316209-20576-24',retryable:true,osCode:33,exitCode:5});
  expect(operationDetails(error)).toBe('route_lock · route_operation_busy · OS 33 · 退出码 5 · 操作 1794316209-20576-24');
 });
 it('omits absent numeric codes without guessing the cause',()=>{
  expect(operationDetails({stage:'state_read',code:'state_config_missing',operationId:'1-2-3'})).toBe('state_read · state_config_missing · 操作 1-2-3');
 });
});
