# Stage 2 — 휴대폰 라이브 송출 설계

치지직 만들기 ②(인증·인가 없는 휴대폰 라이브 송출)의 설계 문서다. ①(녹화방송)의 확정 설계는 바꾸지 않고, 그 위에 추가한 내용만 적는다.

## 1. 요구사항과 대응

과제 원문 체크리스트와 이를 충족하는 코드:

| 체크리스트 | 대응 |
| --- | --- |
| 1단계의 녹화방송 등록·목록·상세·재생 기능 코드가 유지되어 있다. | `broadcast` 패키지의 ① 코드·테스트를 그대로 유지. 녹화방송은 `BroadcastType.RECORDED` |
| 휴대폰 카메라·마이크 입력을 받아 라이브 송출을 시작하고 종료하는 코드가 있다. | `static/js/live-broadcaster.js`(`getUserMedia`, `RTCPeerConnection`), `live/LiveSignalingHandler`(`start`, `end`), `live/LiveBroadcastService` |
| 라이브 방송 목록·시청 화면과 방송 상태를 처리하는 코드가 있다. | `templates/list.html`(LIVE·종료 표시), `templates/live-watch.html`, `static/js/live-viewer.js`, `Broadcast.liveStatus` |
| 로그인 없이 송출·시청하는 구성과 휴대폰 연결·권한 설정·확인 절차가 README에 있다. | README "라이브 송출(②)" 절 |
| README에 실행 방법·환경변수 예시·구현 범위·직접 확인 절차가 있다. | README |

로그인·권한 검사, 댓글, 도네이션은 ② 범위가 아니다(원문 "이번 단계에서는 로그인과 권한 검사를 요구하지 않습니다").

## 2. 구조 한눈에 보기

```
[Android Chrome: 송출 화면]                         [PC Chrome: 시청 화면]
  getUserMedia(카메라·마이크)                             <video>
  RTCPeerConnection (시청자마다 1개) ◀── 영상·음성(P2P, 같은 Wi-Fi) ──▶ RTCPeerConnection
        │                                                          │
        └──── WebSocket /ws/live (SDP·ICE만 오감) ──── 서버 ───────┘
                                          │
                               LiveSignalingHandler ── LiveSessionRegistry(이 서버의 연결)
                                          │
                               LiveBroadcastService ── Broadcast(DB: LIVE/ENDED, heartbeat)
```

- **서버는 영상을 받지 않는다.** 서버는 WebSocket으로 연결 협상 메시지(SDP Offer/Answer, ICE Candidate)만 중계하고, 영상·음성은 휴대폰에서 시청자 브라우저로 직접 간다(WebRTC P2P).
- 방송의 상태(방송 중/종료)는 DB에 저장한다. 어느 WebSocket이 어느 방송에 붙어 있는지는 이 서버 메모리(`LiveSessionRegistry`)에만 있다.

## 3. 도메인: `Broadcast` 확장

| 컬럼 | 녹화방송 | 라이브 방송 | 비고 |
| --- | --- | --- | --- |
| `broadcast_type` | `RECORDED` | `LIVE` | NOT NULL, DB 기본값 `'RECORDED'`(①의 기존 행 호환) |
| `video_file_name` | 필수 | 없음(null) | ①에서는 NOT NULL이었음. 아래 "기존 DB" 참고 |
| `live_status` | null | `LIVE` → `ENDED` | |
| `started_at` | null | 시작 시각 | |
| `ended_at` | null | 종료 시각 | |
| `last_heartbeat_at` | null | 마지막 생존 신호 시각 | 서버 재시작 복구에 사용 |

### 상태 전이

```
(start) ──▶ LIVE ──(end / 송출자 연결 끊김 / heartbeat 만료)──▶ ENDED
```

| 현재 | 동작 | 결과 |
| --- | --- | --- |
| 없음 | 송출자가 `start` | LIVE 생성, `started_at`·`last_heartbeat_at` = 지금 |
| LIVE | heartbeat | `last_heartbeat_at` 갱신 |
| LIVE | `end`, 송출자 WebSocket 종료, heartbeat 만료 | ENDED, `ended_at` = 지금 |
| ENDED | `end` 또는 연결 종료 | 변화 없음(멱등) |
| ENDED | heartbeat | 거절(`IllegalStateException`) → 송출자 연결 종료 |
| ENDED | 다시 LIVE로 | **불가.** 다시 방송하려면 새 방송을 시작한다 |
| 녹화방송 | 라이브 동작 전부 | 거절(`IllegalStateException`) |

### 서버 재시작·연결 불일치 복구

- 송출 화면은 10초마다 heartbeat를 보내고, 서버는 `last_heartbeat_at`을 갱신한다.
- 서버는 10초마다 `last_heartbeat_at`이 30초(`app.live.heartbeat-timeout`)보다 오래된 LIVE 방송을 ENDED로 바꾼다.
- 서버가 재시작되면 모든 WebSocket이 끊기므로 heartbeat가 멈추고, 마지막 heartbeat 뒤 timeout이 지난 다음 점검에서 그 방송들은 ENDED가 된다. 휴대폰의 Wi-Fi가 끊겨 TCP 종료가 늦게 감지되는 경우도 같은 방식으로 정리된다.
- heartbeat 만료로 끝난 방송은 송출 WebSocket이 서버에 아직 남아 있을 수 있다(Wi-Fi 끊김). 그래서 `LiveBroadcastExpiryScheduler`는 DB 종료를 커밋한 뒤, 트랜잭션 밖에서 `LiveSignalingHandler.closeEndedBroadcast`로 그 방송의 방을 닫고 시청자·송출자에게 `ended`를 보낸 다음 연결을 닫는다. 방송 하나의 정리가 실패해도 나머지 방송은 계속 정리한다.
- 방 닫기(`LiveSessionRegistry.closeRoom`)는 송출자·시청자 목록을 한 번에 꺼내고 방을 지운다. `end` 요청, 송출 연결 종료, heartbeat 만료가 겹쳐도 방을 받는 쪽은 하나뿐이어서 `ended`는 한 번만 간다.
- "서버 시작 시 LIVE를 모두 ENDED로" 방식은 쓰지 않았다. ⑤처럼 서버가 여러 대일 때 다른 서버의 정상 방송까지 끝내 버리기 때문이다. heartbeat 만료는 서버 수와 무관하게 동작한다.

### 기존 DB(①에서 만든 H2 파일 DB)와의 호환

- 새 컬럼은 모두 null 허용이거나 DB 기본값이 있어 `ddl-auto: update`로 추가된다. ①의 녹화방송 행은 `RECORDED`로 읽힌다.
- 단, Hibernate `update`는 기존 NOT NULL 제약을 풀지 않는다. ①에서 만든 DB의 `video_file_name`은 NOT NULL로 남아 있어 **라이브 방송 저장이 실패한다.** 새로 만든 DB에서는 문제없다.
- ①에서 쓰던 DB를 그대로 쓰려면 한 번만 다음 SQL을 실행한다(데이터는 바뀌지 않음). H2 Console에서 실행할 수 있다.

```sql
ALTER TABLE broadcast ALTER COLUMN video_file_name SET NULL;
```

## 4. 시그널링 프로토콜 (`/ws/live`, JSON 텍스트 메시지)

| 방향 | 메시지 | 조건 | 서버 동작 |
| --- | --- | --- | --- |
| 송출자 → 서버 | `{"type":"start","title":"..."}` | 아직 역할 없음 | 제목 검증 → LIVE 방송 저장 → 이 연결을 그 방송의 송출자로 등록 → `{"type":"started","broadcastId":1,"watchUrl":"/broadcasts/1"}` |
| 시청자 → 서버 | `{"type":"watch","broadcastId":1}` | 역할 없음 | 방송이 있고 LIVE이며 송출자가 연결되어 있고 정원 미만인지 확인 → 서버가 `viewerId` 발급 → 시청자에게 `watching`, 송출자에게 `{"type":"viewer-joined","viewerId":"..."}` |
| 송출자 → 서버 | `{"type":"offer","viewerId":"...","sdp":"..."}` | 송출자 | 그 `viewerId`가 **이 송출자 방송의 시청자**일 때만 해당 시청자에게 `{"type":"offer","sdp":"..."}` |
| 시청자 → 서버 | `{"type":"answer","sdp":"..."}` | 시청자 | 서버가 기억한 방송의 송출자에게 `{"type":"answer","viewerId":(서버가 기억한 값),"sdp":"..."}` |
| 양쪽 | `{"type":"ice","candidate":{...}}` (송출자는 `viewerId` 포함) | 역할 있음 | 위와 같은 규칙으로 상대에게 전달 |
| 송출자 → 서버 | `{"type":"heartbeat"}` | 송출자 | `last_heartbeat_at` 갱신. 방송이 이미 ENDED면 송출 종료 처리 |
| 송출자 → 서버 | `{"type":"end"}` | 송출자 | ENDED 저장 → 시청자 전원에게 `{"type":"ended"}` 후 연결 종료 |
| 서버 → 송출자 | `{"type":"viewer-left","viewerId":"..."}` | | 시청자 연결이 끊김 |
| 서버 → 양쪽 | `{"type":"error","code":"...","message":"..."}` | | 아래 오류 코드 |

### 검증과 격리 (클라이언트 값을 믿지 않음)

- **방송 ID와 역할은 연결(WebSocket 세션)에 서버가 기록한 값만 쓴다.** `offer`·`answer`·`ice`에 들어 있는 `broadcastId`는 무시한다. 시청자가 보낸 `viewerId`도 무시하고 서버가 발급한 값을 붙여 전달한다.
- 송출자가 보낸 `viewerId`는 그 송출자의 방송에 붙은 시청자일 때만 받아들인다. 다른 방송의 시청자에게는 절대 전달되지 않는다.
- 한 연결은 송출자 또는 시청자 중 하나만 될 수 있다. 이미 역할이 있는 연결의 `start`·`watch`는 거절한다(송출자 중복 방지).
- 메시지 최대 64KB(컨테이너 설정), SDP 최대 32KB, ICE `candidate` 최대 1KB. JSON 객체가 아니거나 `type`이 없거나 알 수 없으면 거절한다. 전달할 때는 받은 JSON을 그대로 넘기지 않고 허용된 필드만으로 다시 만든다.
- WebSocket 핸드셰이크는 같은 출처(Origin)만 허용한다(Spring 기본값).

| 오류 코드 | 상황 |
| --- | --- |
| `INVALID_MESSAGE` | JSON 형식 오류, 필드 누락·형식 오류, 크기 초과 |
| `INVALID_TITLE` | 제목 없음 또는 100자 초과 |
| `INVALID_ROLE` | 역할에 맞지 않는 메시지(예: 시청자가 `offer`), 이미 역할이 있는 연결의 `start`·`watch` |
| `NOT_FOUND` | 없는 방송 |
| `NOT_LIVE` | 녹화방송이거나 종료된 방송이거나 송출자가 연결되어 있지 않음 |
| `FULL` | 시청자 정원 초과 |
| `UNKNOWN_VIEWER` | 송출자가 자기 방송의 시청자가 아닌 `viewerId`를 지정(시청자가 막 나간 경우에도 생기므로 송출 화면은 표시하지 않음) |
| `SERVER_ERROR` | DB 장애 등 예상하지 못한 서버 오류. 연결은 유지하고 서버 로그에 ERROR로 남김 |

## 5. P2P 방식의 한계와 정책

- **단일 송출자**: 방송 하나에 송출 연결은 하나다. 송출 연결이 끊기면 그 방송은 끝나고, 다시 하려면 새 방송을 시작한다.
- **시청자 정원**: P2P(mesh)에서는 송출 휴대폰이 시청자마다 영상을 따로 인코딩·업로드한다. 시청자가 늘수록 휴대폰 업로드 대역폭·CPU·배터리가 선형으로 늘어나므로 방송당 시청자를 `app.live.max-viewers`(기본 4명)로 제한한다.
- **연결 정리**: 시청자가 나가면 송출자에게 `viewer-left`를 보내 해당 `RTCPeerConnection`을 닫는다. 방송이 끝나면 시청자 전원에게 `ended`를 보내고 연결을 닫는다.
- **STUN/TURN**: 같은 Wi-Fi에서는 STUN/TURN 없이 기기의 LAN 주소로 연결된다(Android → PC 실기기 검증 13/13 PASS). 서로 다른 네트워크 사이 연결은 실기기로 검증하지 않았다. 필요하면 `APP_LIVE_ICE_SERVERS`(쉼표로 구분한 `stun:`/`turn:` URL)로 지정할 수 있고, 기본값은 비어 있다(외부 서비스를 쓰지 않음). URL만 전달하므로 TURN 인증 정보(username/credential)는 설정할 수 없고, TURN을 이용한 연결 성공 보장은 현재 구현 범위에 없다.
- **시그널링 경로와 미디어 경로는 다르다.** 서버를 늘리면 시그널링 처리량은 늘지만 영상 전송 능력은 늘지 않는다. 영상은 여전히 송출 휴대폰 한 대가 감당한다.

## 6. ⑤(다중 인스턴스) 대비 기술 부채

| 항목 | ② 현재 | ⑤에서 필요한 것 |
| --- | --- | --- |
| 방송 상태(LIVE/ENDED, heartbeat) | DB | 그대로 사용 가능(공용 DB로 바꾸면 됨) |
| 상태 복구 | heartbeat 만료(서버 수 무관) | 그대로 사용 가능 |
| 연결 등록부(`LiveSessionRegistry`) | 서버 메모리 | 송출자와 시청자가 다른 서버에 붙을 수 있으므로 서버 간 메시지 전달 필요(예: Redis pub/sub로 `broadcastId`·`viewerId` 채널 중계). 또는 Ingress에서 방송 ID 기준 고정 라우팅 |
| 시청자 정원 | 서버 메모리 기준 | 공용 저장소에서 원자적으로 계산 |
| 미디어 확장 | P2P(정원 4명) | **Redis를 도입해도 영상 전송은 확장되지 않는다.** 많은 시청자가 필요하면 SFU(예: mediasoup, LiveKit, Janus) 또는 HLS 변환 서버가 필요하다. ⑤ 과제 원문은 이를 요구하지 않으므로 도입하지 않는다 |

## 7. 개발용 HTTPS (Android 카메라 권한)

브라우저는 HTTPS(또는 `localhost`)에서만 `getUserMedia`를 허용한다. 휴대폰은 PC의 LAN IP로 접속하므로 HTTPS가 필요하다.

- **권장: 개발용 로컬 CA를 휴대폰에 설치.** `scripts/dev-https/new-dev-certificate.ps1`이 로컬 CA와 "PC LAN IP를 SAN에 넣은" 서버 인증서를 만든다. CA 인증서(`chzzk-dev-ca.crt`)만 휴대폰에 설치하면 Chrome이 서버 인증서를 신뢰하고, 경고 없이 보안 컨텍스트가 된다.
- 인증서 경고를 "무시하고 계속"하는 방식은 기본 방법으로 쓰지 않는다. 브라우저가 그 페이지를 보안 컨텍스트로 취급한다는 보장이 없다.
- `chrome://flags`의 "Insecure origins treated as secure"는 진단용 대안으로만 적는다.
- 인증서·개인키·키스토어 비밀번호는 저장소 밖(기본 `../chzzk_certs`)에 만들고 Git에 넣지 않는다.
- 실행: `SPRING_PROFILES_ACTIVE=dev,https`로 `application-https.yaml`을 켜고, 키스토어 경로·비밀번호는 환경변수로 준다(README 참고).

## 8. 시험 범위 구분

| 구분 | 확인 방법 |
| --- | --- |
| 자동 테스트 | 상태 전이(도메인), 서비스(시작·종료·heartbeat 만료), 시그널링(실제 서버 + WebSocket 클라이언트: 메시지 전달·검증·격리·연결 종료), 화면(MockMvc) |
| 사람이 실제 기기로 | 카메라·마이크 권한, 미리보기, Android → PC 영상·음성 수신, 종료 표시, 실패 시나리오 |

자동 테스트는 실제 카메라와 WebRTC 미디어 연결을 검증하지 않는다.

### 테스트 결과 (2026-11-17)

`gradlew.bat test`: 30개 클래스, 158개 테스트 통과(①의 100개 포함, 실패·건너뜀 0). 테스트는 인메모리 H2와 임시 폴더만 쓴다.

| 테스트 | 개수 | 확인 내용 |
| --- | --- | --- |
| `BroadcastTests` | 7 | 상태 전이(시작, heartbeat, 종료 멱등, 종료 후 heartbeat 거절, 녹화방송의 라이브 동작 거절) |
| `BroadcastRepositoryTests`(추가분) | 3 | 1단계 형태의 행이 RECORDED로 읽힘, 라이브 저장, heartbeat 만료 조회 |
| `LiveBroadcastServiceTests` | 6 | 시작·종료·heartbeat, 만료된 방송만 종료(서버 재시작 복구) |
| `LiveSignalingServerTests` | 17 | 실제 서버 WebSocket: 메시지 전달·위조 무시·방송 간 격리·오류 코드·연결 종료·Origin 검사 |
| `LiveSignalingFailureServerTests` | 1 | 예상하지 못한 서버 오류 시 `SERVER_ERROR` 후 연결 유지 |
| `LiveBroadcastExpiryServerTests` | 3 | 실제 서버 WebSocket: heartbeat 만료 시 DB 종료·시청자와 송출자에게 `ended`·연결과 방 정리, 반복 점검 시 재알림 없음, 만료와 `end` 요청이 겹쳐도 `ended` 한 번 |
| `LiveBroadcastExpirySchedulerTests` | 1 | 만료 방송 하나의 연결 정리가 실패해도 나머지 방송은 계속 정리 |
| `LiveSessionRegistryTests` | 2 | 방 닫기는 송출자·시청자를 한 번만 돌려줌, 동시에 닫아도 한 호출만 방을 받음 |
| `H2SchemaConnectionRetirementTests` | 5 | 스키마를 만든 DB 연결이 교체된 뒤에도 라이브 시작·heartbeat·종료·녹화 등록 저장, CHECK 제약 유지(README "H2 버전 고정") |
| `LivePagesTests` | 6 | 송출·시청·종료 화면, 목록 배지, 헤더 링크, 스크립트 제공 |
| `HttpsProfileConfigurationTests` | 3 | `https` 프로파일: 8443, PKCS12, 키스토어는 환경변수로만 |
| `VideoRequestAbortServerTests`, `GlobalExceptionHandlerTests` | 1 + 3 | 영상 요청 중단은 ERROR가 아님, 실제 서버 오류는 계속 ERROR |

PC에서 확인한 것(실기기 아님): 생성한 인증서의 SAN·체인, `dev,https` 실행 시 LAN IP로 TLS 1.3 접속(우리 CA만 신뢰하는 클라이언트), `wss` 업그레이드 101, 다른 Origin 403, 1단계 DB 업그레이드 경로와 `ALTER` 해결.

### 실기기 검증 결과 (2026-11-17, Android Chrome → PC Chrome, 같은 Wi-Fi)

확인 방법은 README "송출·시청 확인 절차"를 따랐다. 13개 항목 모두 기능상 통과했다.

| 항목 | 결과 |
| --- | --- |
| HTTPS 접속, 카메라·마이크 권한, 미리보기, 방송 시작 | 통과 |
| PC 목록 LIVE, 영상, 음성 | 통과 |
| 방송 종료, 종료 표시 | 통과 |
| 시청 화면 새로고침 후 재접속 | 통과 |
| 송출 Chrome 강제 종료 | 통과: 시청 화면 자동 종료 확인, 정확한 지연 시간은 미측정 |
| 송출 휴대폰 Wi-Fi 끊김 | 수정 후 통과(아래) |

- **Wi-Fi 끊김(첫 검증 실패 → 수정)**: 처음에는 목록은 `종료`가 되었지만 시청 화면이 종료로 바뀌지 않았다. heartbeat 만료 처리가 DB만 바꾸고 시청자에게 알리지 않았기 때문이다. 3절의 방식으로 고친 뒤(테스트: `LiveBroadcastExpiryServerTests` 수정 전 실패 → 수정 후 통과) 다시 확인했다. 서버 로그 기준으로 방송 시작 64초 뒤 만료로 종료되었고, 0.8초 뒤 시청 연결이 닫혔으며, 시청 화면이 자동으로 종료 화면으로 바뀌었다(사용자 확인).
- `ended` 메시지 전송 자체는 로그에 남지 않는다. 시청 화면은 `ended`를 받으면 종료 화면을, 메시지 없이 연결만 끊기면 "서버와 연결이 끊어졌습니다"를 보여 준다. 실제로 종료 화면이 나왔으므로 `ended`가 전달된 것으로 판단했다.
- **측정하지 않은 것**: Wi-Fi를 끈 순간부터 종료 화면까지의 정확한 시간(체감상 1분 이내). 설계상 마지막 heartbeat 뒤 30초 timeout과 10초 점검 주기를 거친다. 40초는 과제 요구사항이 아니라 검증 때 정한 관찰 목표였다.
- **H2 2.5.252**: 서버 시작 63분 뒤에도 방송 시작·heartbeat·종료가 오류 없이 저장되었고, 관찰 구간에서 90098·23514·`Check constraint invalid`는 나오지 않았다. 그 사이 커넥션 풀 연결이 모두 교체되었다는 판단은 직접 확인한 것이 아니라 풀 로그(새 연결 추가, 기존 연결의 keepalive 중단)로 내린 간접 판단이다.

#### 알려진 위험과 한계

- heartbeat 저장과 만료 처리가 같은 순간 겹치면, JPA가 모든 컬럼을 UPDATE하므로 DB가 LIVE로 되돌아갈 수 있다(코드상 가능, 재현하지 않음, 수정하지 않음). 연결은 이미 닫혀 있으므로 이후 만료 점검에서 다시 ENDED가 될 것으로 보지만, 실제 발생 여부와 복구 시간은 검증하지 않았다. 고친다면 heartbeat를 `live_status = 'LIVE'` 조건부 UPDATE로 바꾼다.
- ①에서 쓰던 H2 파일 DB를 H2 2.5.252로 처음 열기 전에는 데이터 폴더를 백업한다(README "H2 버전 고정").
- 위 158개 테스트 결과는 실행 로그와 당시 집계 기준이다. 이후 일부 테스트만 다시 실행하면서 개별 결과 파일이 덮어써졌다.
