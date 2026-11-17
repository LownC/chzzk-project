// ⑤ 부하 발생 스크립트(k6). 로컬 설치 없이 k6 컨테이너로, 클러스터 네트워크 안에서 부하 분산기(lb)로 보낸다(README "로컬 오토스케일").
//   PowerShell(저장소 루트):
//   docker run --rm --network chzzk-autoscale_default -e BASE_URL=http://lb -v "${PWD}\deploy\load:/scripts:ro" grafana/k6:1.0.0@sha256:f21270290d702cbf0a7d6ba5d7ed100b63bcb233b558b885ed787547b3488279 run /scripts/k6-load.js
//   환경변수: VUS(가상 사용자 수, 기본 60), RAMP(늘리는 시간, 기본 30s), HOLD(유지 시간, 기본 3m)
// 방송 목록·상세·로그인 화면을 읽기만 한다(데이터를 만들거나 바꾸지 않음). 단계별로 가상 사용자를 늘렸다가 0으로 줄인다.
import http from 'k6/http';
import {check, sleep} from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://lb';

export const options = {
	stages: [
		{duration: __ENV.RAMP || '30s', target: Number(__ENV.VUS || 60)},
		{duration: __ENV.HOLD || '3m', target: Number(__ENV.VUS || 60)},
		{duration: '20s', target: 0},
	],
	thresholds: {
		http_req_failed: ['rate<0.05'],
	},
};

export default function () {
	const list = http.get(`${BASE_URL}/broadcasts`);
	check(list, {'목록 200': response => response.status === 200});
	const page = http.get(`${BASE_URL}/broadcasts?page=2`);
	check(page, {'목록 2페이지 200': response => response.status === 200});
	const login = http.get(`${BASE_URL}/login`);
	check(login, {'로그인 화면 200': response => response.status === 200});
	sleep(0.05);
}
