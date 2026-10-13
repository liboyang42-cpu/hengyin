import { fail } from 'k6';

export default function () {
  if (__ENV.PERF_DRY_RUN !== 'false') {
    return;
  }
  if (__ENV.PERF_PAYMENT_MODE !== 'stub') {
    fail('PAYMENT_STUB_REQUIRED: real payment load is forbidden');
  }
  fail('WRITE_SCENARIO_NOT_BOUND: attach an approved shadow-environment scenario before executing write load');
}
