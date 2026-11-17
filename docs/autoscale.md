# Stage 5 — 로컬 오토스케일 설계

치지직 만들기 ⑤(로컬 오토스케일)의 설계 문서다. ①~④의 기능과 화면은 바꾸지 않고, 같은 서버를 여러 인스턴스로 띄워 부하에 따라 늘리고 줄이는 데 필요한 것만 더했다. 실행·확인 절차와 관찰한 인스턴스 수 변화는 README "로컬 오토스케일 (Stage 5)"에 있다.

## 1. 요구사항과 대응

| 과제 요구 | 구현 |
| --- | --- |
| 서버를 컨테이너 이미지로 빌드 | `Dockerfile`(eclipse-temurin:25-jre, 실행 JAR 하나, 비root 사용자), `.dockerignore` |
| 로컬 클러스터·오케스트레이터에서 여러 인스턴스 실행 | Docker Compose: `deploy/compose.yaml`의 `app` 서비스를 `--scale app=N`으로 여러 개 실행 |
| CPU·요청 수 같은 지표로 최소~최대 범위에서 자동 스케일 아웃·인(자체 스케일러 허용) | `scripts/autoscale/autoscaler.ps1`: `docker stats`의 CPU 사용률(컨테이너 CPU 제한 대비)을 10초마다 보고 2~5개 사이에서 1개씩 늘리고 줄임 |
| 인스턴스 앞에서 요청 분산 | `deploy/nginx/nginx.conf`: nginx `least_conn`, Docker DNS로 `app` 이름을 5초마다 다시 조회(`resolve`) |
| 세션·업로드 영상·라이브 상태·댓글·도네이션을 공용 외부 저장소에 | 아래 2절. 로그인 세션·라이브 방·시그널링 중계·가짜 결제 장부는 Redis, 방송·회원·댓글·후원은 H2 TCP 서버, 업로드 영상은 공용 볼륨 |
| 부하 발생 스크립트 | `deploy/load/k6-load.js`(k6, 컨테이너로 실행) |
| 클라우드 배포 없음 | 모든 컨테이너는 이 PC의 Docker Desktop에서만 돌고, 부하 분산기 포트는 `127.0.0.1`에만 연다 |

## 2. 구성과 공용 상태

```text
브라우저 ──▶ lb (nginx, 127.0.0.1:18090)
               │ least_conn, app 이름을 5초마다 DNS 재조회
               ▼
            app × N (2~5, CPU 0.5개·메모리 768MB씩, cluster 프로파일)
               │                    │                   │
               ▼                    ▼                   ▼
        h2 (H2 TCP 서버)      redis (Redis 7.4)     uploads 볼륨
        방송·회원·댓글·후원    로그인 세션            업로드 영상(MP4)
                              라이브 방 등록부
                              시그널링 중계(pub/sub)
                              가짜 결제 장부
                 ▲
   autoscaler.ps1 ── docker stats(CPU) → docker compose up --scale app=N
```

| 상태 | 한 서버(기본, ①~④) | 여러 인스턴스(cluster 프로파일, ⑤) |
| --- | --- | --- |
| 로그인 세션 | 서블릿 컨테이너 메모리 | Spring Session Data Redis(`chzzk:session:*`, 30분). 세션 쿠키 이름은 `SESSION` |
| 방송·회원·댓글·후원 | H2 파일 DB | H2 TCP 서버 컨테이너 하나(`jdbc:h2:tcp://h2:9092/chzzk`)를 모든 인스턴스가 함께 씀 |
| 업로드 영상 | 업로드 폴더 | 모든 인스턴스가 같은 Docker 볼륨(`uploads`)을 `/data/uploads`에 붙임 |
| 라이브 방(송출자·시청자 연결) | `LiveSessionRegistry`(메모리) | `RedisLiveRoomStore`(Redis 해시 `chzzk:live:room:{방송 ID}`, Lua 스크립트로 원자적 처리) |
| 시그널링 메시지 전달 | 같은 서버의 WebSocket 연결로 바로 보냄 | 받는 연결이 다른 인스턴스에 있으면 `RedisLiveMessageRelay`가 Redis 채널 `chzzk:live:signal`로 보내고, 그 연결을 가진 인스턴스가 전달 |
| 가짜 결제 장부(결제창 인증 → 승인) | `FakePaymentLedger.InMemory` | `RedisFakePaymentLedger`(`chzzk:fakepay:{결제 키}`, 하루 뒤 만료, Lua로 승인 한 번만) |

- 전환 스위치는 `app.cluster.enabled` 하나다. `cluster` 프로파일(`application-cluster.yaml`)이 `true`로 켜고, 기본값은 `false`라 ①~④의 실행·테스트는 Redis 없이 그대로 돈다.
- 인스턴스는 상태를 갖지 않으므로 아무 인스턴스를 늘리거나 줄여도 로그인·방송·댓글·후원·영상이 그대로다.
- 응답 헤더 `X-Chzzk-Instance`(컨테이너 호스트 이름)로 요청을 처리한 인스턴스를 확인할 수 있다(`ClusterConfig`).
- 결제는 ⑤에서도 가짜 게이트웨이(`APP_PAYMENT_GATEWAY=fake`)만 쓴다. 결제창을 연 인스턴스와 성공 주소를 받은 인스턴스가 달라도 승인되도록 가짜 결제 장부만 Redis로 옮겼다.

### 2.1 공용 DB로 H2 TCP 서버를 쓴 이유

새 DB 드라이버(MySQL·PostgreSQL)를 더하지 않고 지금 쓰는 H2 2.5.252를 그대로 쓰기 위해서다. 앱 이미지 안의 H2로 TCP 서버만 띄운다(`PropertiesLauncher` + `-Dloader.main=org.h2.tools.Server`). 컨테이너 네트워크 안에서만 열리고, 처음 연결한 비밀번호(`CHZZK_DB_PASSWORD`)로 DB가 만들어진다. 개발용 H2 파일 DB(`chzzk_data`)와는 완전히 따로다.

여러 인스턴스가 빈 DB에 동시에 테이블을 만들지 않도록 `scripts/autoscale/cluster-up.ps1`은 앱을 1개 띄워 응답할 때까지 기다린 뒤 최소 개수로 늘린다.

## 3. 라이브 시그널링을 여러 인스턴스로

WebSocket 연결은 처음 붙은 인스턴스에 머문다. 송출자와 시청자가 다른 인스턴스에 붙을 수 있으므로 두 가지를 공용으로 옮겼다.

1. **라이브 방 등록부**(`LiveRoomStore`): 방송 ID → 송출자 연결 ID, 시청자 ID → 시청자 연결 ID. 시청 입장(최대 4명 확인 포함)·퇴장·송출자 교체·방 닫기는 Redis Lua 스크립트나 MULTI/EXEC로 한 번에 처리해, 여러 인스턴스가 동시에 불러도 정원을 넘지 않고 방은 한 번만 닫힌다.
2. **시그널링 중계**(`LiveMessageRelay`): 처리기가 보낼 연결이 자기 인스턴스에 없으면 Redis 채널에 `{"target": 연결 ID, "payload": 메시지}`를 올리고, 모든 인스턴스가 받아 자기 연결이면 보낸다. 연결 닫기도 같은 방법으로 전달한다.

영상·음성은 ②와 같이 WebRTC로 휴대폰에서 시청자 브라우저로 직접 가므로, 인스턴스 수와 상관이 없다.

### 3.1 스케일 인 때 라이브가 끊기지 않게

스케일 인으로 인스턴스가 내려가면 그 인스턴스의 WebSocket 연결은 끊긴다. 이때 방송이 끝나지 않도록 했다.

```text
docker compose (scale-in) ─SIGTERM─▶ app
  LiveSignalingHandler#onApplicationEvent(ContextClosedEvent)
    ├─ 이 인스턴스의 모든 연결에 {"type":"reconnect"} 전송 후 닫기 코드 1012(SERVICE_RESTARTED)로 닫음
    └─ 송출 연결이 닫혀도 방송을 끝내지 않음(방송 상태는 DB, 방 등록부는 Redis에 그대로)
송출 화면(live-broadcaster.js): 0.5·1·2·3·5초 간격으로 다시 연결 → {"type":"resume","broadcastId"} → "resumed"
  서버: 로그인한 방송 소유자이고 방송 중이며 방이 있을 때만 송출자 연결을 바꿈(시청자 목록 유지)
시청 화면(live-viewer.js): 다시 연결 → {"type":"watch"} → 새 시청자 ID로 다시 입장, 송출자와 연결을 다시 협상
```

- 내려가는 인스턴스의 시청자 연결이 먼저 닫히면 송출자는 `viewer-left`를 받는다. 그 시청자는 다시 입장하며 새 시청자 ID를 받는다.
- 다시 연결하지 못하면(5번 실패) 송출 화면은 방송 종료 안내를 보이고, 방송은 ②의 생존 신호 만료(30초)로 정리된다.
- 생존 신호 점검은 모든 인스턴스에서 돌지만, 방 닫기는 Redis에서 한 번만 성공하므로 종료 알림은 한 번만 간다.
- 송출자가 다시 연결하면 `resumed`에 지금 방에 있는 시청자 ID 목록(`viewerIds`)을 실어 보낸다. 송출자가 끊겨 있는 동안 다른 인스턴스로 옮겨 다시 입장한 시청자의 입장 알림은 사라진 연결로 가서 송출 화면에 닿지 않기 때문이다(클러스터 검증에서 실제로 확인한 문제). 송출 화면은 이 목록으로 연결이 없는 시청자에게 새 Offer를 보내고, 목록에 없는 시청자의 연결은 닫는다(`syncViewers`).
- nginx는 내려간 인스턴스를 DNS 재조회(최대 5초) 전까지 분산 대상에 남겨 둘 수 있다. 연결 대기 시간을 기본 60초에서 2초로 줄였다(`proxy_connect_timeout 2s`, 실패 시 `proxy_next_upstream`). 그래도 멈춘 직후의 첫 다시 연결은 502·504로 실패할 수 있었고, 화면은 정해진 간격(0.5·1·2·3·5초)으로 다시 시도해 검증에서는 멈춘 뒤 약 1~4초 안에 다시 연결됐다. 2초로 줄이기 전에는 첫 시도가 오래 기다린 뒤 504로 끝났다.
- `least_conn`은 열린 연결 수가 적은 인스턴스로 보낸다. 라이브 WebSocket처럼 오래 열린 연결이 한쪽에 있으면, 순서대로 하나씩 보내는 요청은 다른 쪽으로 몰릴 수 있다(동시 요청이 많으면 고르게 나뉜다).

## 4. 오토스케일러

`scripts/autoscale/autoscaler.ps1`(PowerShell, Docker CLI만 사용).

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `Min` / `Max` | 2 / 5 | 인스턴스 수 범위 |
| `ScaleOutPercent` × `ScaleOutAfter` | 60% × 2번 연속 | 평균 CPU가 이 이상이면 1개 늘림 |
| `ScaleInPercent` × `ScaleInAfter` | 20% × 3번 연속 | 평균 CPU가 이 미만이면 1개 줄임 |
| `IntervalSeconds` | 10 | 측정 간격 |
| `CooldownSeconds` | 30 | 바꾼 뒤 다시 바꾸지 않는 시간 |
| `CpuLimit` | 0.5 | `compose.yaml`의 `cpus`. `docker stats` CPU %를 이 값으로 나눠 제한 대비 사용률로 봄 |

- 평균 CPU는 `docker stats --no-stream`으로 잰 app 컨테이너들의 CPU %를 평균한 값이다.
- 인스턴스 수는 `docker compose up -d --no-build --no-recreate --scale app=N app`으로 바꾼다. 늘릴 때 기존 인스턴스는 다시 만들지 않고, 줄일 때는 Compose가 번호가 큰 컨테이너부터 SIGTERM으로 내린다(검증에서 app-5 → app-4 → app-3 순서로 내려감)(`stop_grace_period: 30s`, 앱은 `server.shutdown: graceful`).
- 늘리는 쪽은 2번, 줄이는 쪽은 3번 연속을 요구하고 쿨다운을 두어, 잠깐 튀는 값에 흔들리지 않게 했다.
- 판단마다 시각·인스턴스 수·CPU를 로그 파일(기본 `scripts/autoscale/autoscaler.log`, Git 제외)과 화면에 남긴다. 다른 프로그램이 로그 파일을 잠가 쓰지 못하면 경고만 하고 계속 돈다(검증 중 `tail -f`가 파일을 잡아 스케일러가 멈춘 것을 보고 고침).
- Windows PowerShell 5.1은 docker가 stderr에 쓰는 진행 메시지를 오류로 바꿀 수 있어, 두 스크립트는 docker 호출의 성공 여부를 종료 코드로만 판단한다. 한글 메시지가 깨지지 않도록 스크립트는 UTF-8(BOM)로 저장한다.

## 5. 범위와 한계

- 로컬 Docker Compose 전용이다. Kubernetes HPA, 클라우드 배포, 여러 PC에 걸친 클러스터는 다루지 않는다.
- 공용 저장소(H2 TCP 서버, Redis)는 각각 컨테이너 하나다. 이들의 이중화·백업은 범위 밖이다.
- 클러스터는 `127.0.0.1`의 HTTP로만 연다. PC 브라우저의 `localhost`는 보안 출처라 카메라를 쓸 수 있지만, 휴대폰 송출(HTTPS 필요)은 클러스터에서 확인하지 않았다(NOT_TESTED). 휴대폰 송출은 ②의 `https` 프로파일 단일 서버로 한다.
- 실제 토스페이먼츠 테스트 서버 호출은 ④와 같이 하지 않는다(NOT_TESTED).
