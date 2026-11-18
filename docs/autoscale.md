# Stage 5 — 로컬 오토스케일 설계

치지직 만들기 ⑤(로컬 오토스케일)의 설계 문서다. ①~④의 기능과 화면은 바꾸지 않고, 같은 서버를 여러 인스턴스로 띄워 부하에 따라 늘리고 줄이는 데 필요한 것만 더했다. 실행·확인 절차와 관찰한 인스턴스 수 변화는 README "로컬 오토스케일 (Stage 5)"에 있다.

## 1. 요구사항과 대응

| 과제 요구 | 구현 |
| --- | --- |
| 서버를 컨테이너 이미지로 빌드 | `Dockerfile`(eclipse-temurin:25-jre, digest 고정, 실행 JAR 하나, 비root 사용자), `.dockerignore`. 공개 이미지 4개는 모두 태그와 digest를 함께 고정(README "준비") |
| 로컬 클러스터·오케스트레이터에서 여러 인스턴스 실행 | Docker Compose: `deploy/compose.yaml`의 `app` 서비스를 `--scale app=N`으로 여러 개 실행 |
| CPU·요청 수 같은 지표로 최소~최대 범위에서 자동 스케일 아웃·인(자체 스케일러 허용) | `scripts/autoscale/autoscaler.ps1`: `docker stats`의 CPU 사용률(컨테이너 CPU 제한 대비)을 10초마다 보고 2~5개 사이에서 1개씩 늘리고 줄임. 줄일 때는 부하 분산기에서 먼저 빼고 내림(3.1) |
| 인스턴스 앞에서 요청 분산 | `deploy/nginx/nginx.conf`: nginx `least_conn`. 분산 대상은 스케일러가 쓰는 `deploy/nginx/upstream.d/app-servers.conf`(생성 파일, `scripts/autoscale/lb-upstream.ps1`) |
| 세션·업로드 영상·라이브 상태·댓글·도네이션을 공용 외부 저장소에 | 아래 2절. 로그인 세션·라이브 방·시그널링 중계·가짜 결제 장부는 Redis, 방송·회원·댓글·후원은 H2 TCP 서버, 업로드 영상은 공용 볼륨 |
| 부하 발생 스크립트 | `deploy/load/k6-load.js`(k6, 컨테이너로 실행) |
| 클라우드 배포 없음 | 모든 컨테이너는 이 PC의 Docker Desktop에서만 돌고, 부하 분산기 포트는 `127.0.0.1`에만 연다 |

## 2. 구성과 공용 상태

```text
브라우저 ──▶ lb (nginx, 127.0.0.1:18090)
               │ least_conn, 대상 목록은 스케일러가 관리(준비된 인스턴스만, 내리기 전에 먼저 뺌)
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
   autoscaler.ps1 ── docker stats(CPU) → 늘림: docker compose up --scale app=N / 줄임: lb에서 빼고 docker stop
                  └─ lb 대상 목록(upstream.d/app-servers.conf) 갱신 → nginx reload
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
autoscaler.ps1 (scale-in): 대상 목록에서 뺌 → nginx reload → 5초 대기 → docker stop ─SIGTERM─▶ app
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
- **스케일 인 드레이닝.** 처음에는 nginx가 Docker DNS로 `app` 이름을 5초마다 다시 조회(`resolve`)해 분산 대상을 바꿨다. 이 방식에서는 스케일 인 직후 502가 났다(2차 검증에서 스케일 인 한 번마다 1~2건, nginx `no live upstreams`).
  - **원인(재현으로 확인).** 컨테이너가 내려가면 Docker DNS는 곧바로 그 주소를 뺐지만(nginx 컨테이너 안에서 0.1초 간격으로 조회해 확인), nginx의 대상 목록에는 내려간 인스턴스가 12초 넘게 남아 연결 거부(111)가 이어졌다. `least_conn`은 열린 연결이 0개인 그 인스턴스들을 먼저 고르므로, 5→2로 한꺼번에 줄였을 때 한 요청이 다시 시도 3번을 모두 내려간 인스턴스에 써서 502로 끝났다. 부하 중에는 남은 인스턴스도 연결 대기(2초) 실패로 잠시 빠져 모든 대상이 빠진 상태(`no live upstreams`)가 됐다. `max_fails=0`으로는 고쳐지지 않았다(대상 목록 자체가 늦게 바뀌는 문제라서).
  - **해결.** DNS 재조회를 없애고 스케일러가 대상 목록 파일을 직접 쓴다(`scripts/autoscale/lb-upstream.ps1`, nginx는 `upstream.d/app-servers.conf`를 `include`). 새 인스턴스는 부하 분산기 컨테이너 안에서 직접 요청해 200이 나온 뒤에야 넣는다. 내릴 인스턴스(아직 대상에 못 든 것, 없으면 가장 나중에 만든 것)는 먼저 목록에서 빼고 `nginx -t` 뒤 `nginx -s reload` 하고, 5초(`-DrainSeconds`) 기다린 다음 `docker stop -t 30`·`docker rm` 한다. reload는 처리 중인 요청과 열린 WebSocket을 끊지 않으므로(옛 worker가 끝날 때까지 맡음), 새 요청은 내려갈 인스턴스로 가지 않고 이미 받은 요청은 앱의 graceful shutdown으로 마친다. 남은 대상이 없게 되는 스케일 인은 하지 않는다.
  - 목록 파일은 매 회차 실행 중인 컨테이너와 맞춘다(없어진 것은 빼고, 답하는 새 것은 넣음). 스케일러 밖에서 인스턴스가 갑자기 죽는 경우에는 그대로 `max_fails=1`·`proxy_next_upstream`(연결 대기 2초, 멱등 요청 최대 3번)이 다른 인스턴스로 보낸다. 이때(예: `docker stop`으로 직접 멈춘 경우) 첫 다시 연결은 502·504로 실패할 수 있고, 라이브 화면은 정해진 간격(0.5·1·2·3·5초)으로 다시 시도해 약 1~4초 안에 다시 연결됐다.
- `least_conn`은 열린 연결 수가 적은 인스턴스로 보낸다. 라이브 WebSocket처럼 오래 열린 연결이 한쪽에 있으면, 순서대로 하나씩 보내는 요청은 다른 쪽으로 몰릴 수 있다(동시 요청이 많으면 고르게 나뉜다).

### 3.2 연결 소유, 중복, Redis 장애

**연결 소유.** WebSocket 연결은 그 연결을 받은 인스턴스만 가진다(인스턴스 메모리의 연결 표). 방 등록부에는 연결 ID(UUID)만 두고, 메시지를 보낼 때 자기 표에 있으면 바로 보내고 없을 때만 중계 채널에 올린다. 채널은 모든 인스턴스가 받지만 그 ID의 연결을 가진 인스턴스 하나만 전달하고 나머지는 버린다. 연결 ID는 UUID라 인스턴스 사이에서 겹치지 않는다.

**중복 처리.** 한 메시지는 보낸 쪽에서 한 번만 발행하고(자기 연결이면 발행하지 않음), 받을 연결을 가진 인스턴스는 하나뿐이라 같은 메시지가 두 번 전달되지 않는다. Redis Pub/Sub는 다시 보내지 않으므로 재전송으로 생기는 중복도 없다. 상태를 바꾸는 일(시청 입장·정원 확인, 송출자 교체, 방 닫기, 가짜 결제 승인)은 Redis Lua 스크립트·MULTI/EXEC로 한 번에 처리하고, 후원 확정은 ④의 유일 제약·조건부 UPDATE로 한 번만 일어난다.

**순서.** Redis는 한 채널의 메시지를 발행 순서대로 보내지만, `RedisMessageListenerContainer`의 기본 실행기는 받은 메시지마다 새 스레드로 처리해 순서가 바뀔 수 있다. 클러스터 검증(스케일 2→5→2)에서 방송 종료 때 한 시청자가 `ended` 없이 연결 닫기만 받은 것을 찾았다(서버는 `ended`를 보낸 뒤 연결을 닫는데, 닫기가 먼저 처리됨). 같은 이유로 Offer와 ICE 후보의 순서도 바뀔 수 있었다. 그래서 인스턴스마다 중계 메시지를 한 스레드(`live-signal-relay`)에서 받은 순서대로 전달하게 했다(`RedisLiveMessageRelayOrderTests`: 200개 메시지와 닫기 요청이 보낸 순서대로 도착. 고치기 전에는 실패했다). 받은 메시지 처리는 연결에 텍스트를 보내는 일뿐이라 한 스레드로 충분하다.

**Pub/Sub의 한계(문서화한 제한).** Redis Pub/Sub는 메시지를 저장하지 않고, 받는 쪽이 그 순간 구독 중이 아니면 메시지는 사라진다(최대 한 번 전달, 재전달 없음). 그래서 다음 경우의 시그널링 메시지(Offer·Answer·ICE 후보, 입장·퇴장 알림, 연결 닫기 요청)는 잃을 수 있다.

- 받을 연결이 있는 인스턴스가 막 내려가는 중이거나 구독이 끊긴 순간
- Redis가 멈춰 있거나 다시 연결하는 동안

잃은 메시지는 서버가 다시 보내지 않는다. 대신 화면이 다시 맞춘다: 시청 화면은 연결이 끊기면 다시 입장해 새 협상을 받고, 송출 화면은 다시 연결하면 `resumed`의 시청자 목록으로 빠진 연결을 다시 만든다(3.1). 이 두 경로로 회복되지 않는 메시지 손실(예: 연결은 살아 있는데 ICE 후보 하나만 잃음)은 그 시청자의 영상 연결이 맺어지지 않을 수 있고, 시청자가 새로고침하면 다시 협상한다. 메시지 저장·재전달(Redis Streams 등)은 범위 밖이다.

**구독 실패.** 각 인스턴스의 `RedisMessageListenerContainer`가 채널을 구독한다. Redis 연결이 끊기면 컨테이너가 5초 간격으로 다시 구독하고, 그동안 이 인스턴스로 오는 중계 메시지는 위와 같이 사라진다. 검증에서 Redis를 멈췄다가 다시 켠 뒤 서로 다른 인스턴스 사이의 Offer·Answer 전달이 다시 되는 것을 확인했다(README "로컬 오토스케일" 검증 표).

**Redis 장애 때의 동작(정의).** Redis가 멈추면 메모리로 대신하지 않고 실패한다. 인스턴스마다 다른 메모리 상태로 나뉘면 로그인·정원·결제 승인이 인스턴스마다 달라지기 때문이다.

| 기능 | Redis가 멈췄을 때 | Redis가 돌아오면 |
| --- | --- | --- |
| 로그인이 필요 없는 화면(목록·상세·영상) | 세션을 쓰지 않으면 그대로 동작(검증에서 200) | — |
| 로그인 세션을 읽는 요청 | 2초 안에 실패해 ③의 공통 오류 화면("일시적인 오류…", 500). 내부 예외 문구는 보이지 않음 | 같은 로그인으로 그대로 이어짐(Redis AOF로 세션 보존) |
| 시그널링(입장·송출·중계) | 그 요청에 `SERVER_ERROR` 안내, WebSocket 연결은 유지(②의 처리 방식) | 다시 시도하면 동작, 인스턴스 사이 중계도 다시 구독 뒤 동작 |
| 가짜 결제 승인 | 승인하지 않음(장부를 확인할 수 없으면 확정하지 않음). 그 주문은 `IN_PROGRESS`로 남고 결제 취소·정산은 범위 밖 | 새 주문은 정상 |

- 기다리는 시간은 `spring.data.redis.timeout`·`connect-timeout` 2초(`application-cluster.yaml`)다. 기본값(60초)이면 요청이 오래 매달린다.
- Redis 장애로 공용 저장소가 짐작한 값(빈 방, 결제 없음)을 돌려주지 않고 실패하는지는 `RedisOutageTests`가 Redis 없이 확인한다.
- Redis는 컨테이너 하나이고 이중화하지 않는다(5절).

### 3.3 공용 DB의 데이터 구분과 동시 접근

- 클러스터 DB는 `h2data` 볼륨 안의 새 DB(`chzzk`)이고, 개발 DB(`chzzk_data`)·백업은 마운트하지 않는다. 처음 띄우면 빈 DB에 ①~④의 스키마만 만들어지고 초기 데이터는 없다. 검증 데이터는 검증 스크립트가 만든 회원·방송(이름에 실행 시각 표시)뿐이며, `docker compose ... down -v`로 볼륨째 지운다.
- 여러 인스턴스가 같은 H2 TCP 서버에 동시에 쓴다. 검증에서 같은 방송에 댓글 20개를 동시에 보내 두 인스턴스가 나눠 처리해도 20개가 한 번씩 저장됐고, 같은 결제의 성공 요청 10개를 동시에 보내도 후원은 한 번만 확정됐다(주문 3건, 합계 3,000원).

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
| `DrainSeconds` | 5 | 줄일 때 부하 분산기에서 뺀 뒤 인스턴스를 내리기까지 기다리는 시간 |
| `UpstreamFile` | `deploy/nginx/upstream.d/app-servers.conf` | 부하 분산 대상 목록(생성 파일, Git 제외) |

- 평균 CPU는 `docker stats --no-stream`으로 잰 app 컨테이너들의 CPU %를 평균한 값이다.
- 늘릴 때는 `docker compose up -d --no-build --no-recreate --scale app=N app`으로 기존 인스턴스는 그대로 두고 하나를 더 띄운다. 새 인스턴스는 요청에 답하면 다음 회차에 부하 분산 대상에 들어간다.
- 줄일 때는 Compose에 맡기지 않고 스케일러가 고른 인스턴스를 부하 분산기에서 먼저 뺀 뒤 `docker stop -t 30`(SIGTERM, 앱은 `server.shutdown: graceful`)·`docker rm` 한다(3.1 "스케일 인 드레이닝"). 다음에 늘릴 때 Compose는 비어 있는 번호로 새 컨테이너를 만든다.
- 늘리는 쪽은 2번, 줄이는 쪽은 3번 연속을 요구하고 쿨다운을 두어, 잠깐 튀는 값에 흔들리지 않게 했다.
- 판단마다 시각·인스턴스 수·CPU를 로그 파일(기본 `scripts/autoscale/autoscaler.log`, Git 제외)과 화면에 남긴다. 다른 프로그램이 로그 파일을 잠가 쓰지 못하면 경고만 하고 계속 돈다(검증 중 `tail -f`가 파일을 잡아 스케일러가 멈춘 것을 보고 고침).
- Windows PowerShell 5.1은 docker가 stderr에 쓰는 진행 메시지를 오류로 바꿀 수 있어, 두 스크립트는 docker 호출의 성공 여부를 종료 코드로만 판단한다. 한글 메시지가 깨지지 않도록 스크립트는 UTF-8(BOM)로 저장한다.

## 5. 범위와 한계

- 로컬 Docker Compose 전용이다. Kubernetes HPA, 클라우드 배포, 여러 PC에 걸친 클러스터는 다루지 않는다.
- 공용 저장소(H2 TCP 서버, Redis)는 각각 컨테이너 하나다. 이들의 이중화·백업은 범위 밖이다.
- 클러스터는 `127.0.0.1`의 HTTP로만 연다. PC 브라우저의 `localhost`는 보안 출처라 카메라를 쓸 수 있지만, 휴대폰 송출(HTTPS 필요)은 클러스터에서 확인하지 않았다(NOT_TESTED). 휴대폰 송출은 ②의 `https` 프로파일 단일 서버로 한다.
- 실제 토스페이먼츠 테스트 서버 호출은 ④와 같이 하지 않는다(NOT_TESTED).
