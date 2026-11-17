# 치지직 (chzzk) — 녹화방송과 휴대폰 라이브 송출

프로그래머스 데브코스 치지직 서비스 구현 프로젝트입니다.
로그인 없이 녹화해 둔 MP4 영상을 방송으로 등록해 시청하고(Stage 1), 휴대폰 카메라·마이크로 라이브 방송을 송출해 다른 화면에서 시청할 수 있습니다(Stage 2).

## 구현 범위

### Stage 1 — 녹화방송

- 방송 등록: 제목과 MP4 영상 파일을 한 번에 제출, 업로드 진행률(막대와 퍼센트) 표시
- 방송 목록: 최신 등록 순, 한 페이지 10개, 페이지 번호 10개 단위 그룹
- 방송 상세: 제목, 등록 시각, 영상 플레이어
- 영상 재생: HTTP Range 요청 지원(구간 이동 가능)
- 데이터 영속성: 방송 정보는 H2 파일 DB, 영상은 업로드 폴더에 저장되어 서버를 재시작해도 유지
- 공통 헤더, 404·500 오류 화면

### Stage 2 — 인증·인가 없는 휴대폰 라이브 송출

- 휴대폰 브라우저에서 카메라·마이크로 라이브 방송 시작·종료(WebRTC, 화면: `/live/new`)
- 방송 목록에서 라이브 방송을 골라 시청(같은 목록에 녹화방송과 함께 표시)
- 방송 상태 표시: 목록 배지 `LIVE`·`종료`·`녹화`, 시청 화면의 "방송 중"·"방송 종료"
- 송출 화면이 닫히면 방송이 바로 종료됩니다. 송출 휴대폰의 Wi-Fi가 끊기거나 서버가 재시작되어 생존 신호(heartbeat, 10초 주기)가 멈춘 방송은, 마지막 신호 뒤 30초(timeout)가 지나면 서버의 10초 주기 점검에서 종료 상태로 정리되고 시청 중인 화면에도 종료가 전달됩니다
- 로그인 없이 누구나 송출·시청합니다(이번 단계는 로그인·권한 검사를 요구하지 않음).
- 설계 문서: [docs/live-streaming.md](docs/live-streaming.md)

### 제외(이후 단계)

- 인증·인가, 댓글, 후원
- 방송 수정·삭제, 썸네일, 라이브 녹화 저장
- 업로드 취소, 파일 시그니처·코덱 검사, 트랜스코딩
- TURN 인증 정보 설정과 서로 다른 네트워크 사이 라이브 연결의 성공 보장(실기기 미검증), 대규모 시청(방송당 시청자 4명까지)

## 주요 기능

| 기능 | 경로 | 설명 |
| --- | --- | --- |
| 방송 목록 | `GET /broadcasts?page=N` | 페이지 번호는 1부터. 잘못된 번호는 1페이지로 처리. `/`는 목록으로 이동 |
| 방송 등록 화면 | `GET /broadcasts/new` | 제목 입력란, 영상 파일 선택란, 진행 막대와 퍼센트 |
| 방송 등록 | `POST /broadcasts` | `multipart/form-data`. 성공하면 상세 화면으로 이동 |
| 방송 상세 | `GET /broadcasts/{id}` | 녹화방송은 영상 플레이어, 라이브 방송은 실시간 시청 화면(종료된 방송은 종료 안내). 없는 방송·잘못된 ID는 404 화면 |
| 영상 재생 | `GET /videos/{파일명}` | 업로드 폴더의 영상을 Range 요청과 함께 제공 |
| 라이브 송출 화면 | `GET /live/new` | 제목, 카메라 미리보기, 방송 시작·종료 버튼, 시청자 수 |
| 라이브 시그널링 | `WebSocket /ws/live` | 송출자·시청자 사이 WebRTC 연결 협상 메시지(SDP·ICE) 중계. 영상은 서버를 거치지 않음 |

등록 규칙

- 제목: 필수, 앞뒤 공백 제거 후 최대 100자
- 영상 파일: 확장자 `.mp4`(대소문자 무시), Content-Type `video/mp4`, 최대 500MB(524,288,000바이트)
- 브라우저에서는 500MB를 넘는 파일을 전송 전에 막고, 그 밖의 검증은 서버에서 합니다.
- JavaScript가 동작하지 않아도 일반 폼 제출로 등록할 수 있습니다.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| 언어 | Java 25 |
| 프레임워크 | Spring Boot 4.1.2, Spring MVC |
| 화면 | Thymeleaf(서버 렌더링), JavaScript(업로드 진행률, 라이브 송출·시청) |
| 라이브 | 브라우저 WebRTC API(`getUserMedia`, `RTCPeerConnection`), Spring WebSocket(시그널링) |
| 데이터 | Spring Data JPA, H2 Database 2.5.252(개발: 파일 DB, 테스트: 인메모리 DB) |
| 검증 | Spring Boot Validation |
| 빌드 | Gradle(Wrapper 9.8.2), Kotlin DSL(`build.gradle.kts`) |
| 테스트 | JUnit 5, Spring Boot Test(MockMvc) |

- 라이브러리 버전은 Spring Boot 의존성 관리를 따릅니다. 예외로 H2만 2.5.252로 고정했습니다([H2 버전 고정](#h2-버전-고정-25252) 참고). Lombok은 사용하지 않습니다.

## 실행 환경

- JDK 25가 필요합니다. 빌드는 Gradle 툴체인으로 Java 25를 사용하며, 툴체인 자동 다운로드는 설정되어 있지 않으므로 JDK 25를 직접 설치해 두어야 합니다.
- Gradle은 저장소에 포함된 Gradle Wrapper(`gradlew.bat`)를 사용하므로 따로 설치하지 않아도 됩니다.
- 아래 명령은 Windows PowerShell에서 저장소 루트(`chzzk` 폴더) 기준입니다. macOS·Linux는 [macOS·Linux에서 실행하기](#macoslinux에서-실행하기)를 참고하세요.

## 실행 방법

```powershell
.\gradlew.bat bootRun
```

- 기본 포트는 `8080`입니다. 실행 후 <http://localhost:8080> 으로 접속합니다.
- 프로파일을 지정하지 않으면 `dev` 프로파일이 기본으로 활성화됩니다.
- dev 프로파일에서는 H2 Console을 <http://localhost:8080/h2-console> 에서 열 수 있습니다.
  - JDBC URL: `jdbc:h2:file:<APP_DATA_DIR 값>/chzzk_db;MODE=MySQL`
  - User Name: `sa`, Password: 비워 둠

## 환경변수

| 이름 | 의미 | 미설정 시 기본값 |
| --- | --- | --- |
| `APP_UPLOAD_DIR` | 업로드한 영상 파일을 저장하는 폴더 | `C:/Users/Wonseok/Desktop/Dev/programmers/chzzk_project/chzzk_uploads/broadcasts` |
| `APP_DATA_DIR` | H2 DB 파일(`chzzk_db.mv.db`)을 저장하는 폴더 | `C:/Users/Wonseok/Desktop/Dev/programmers/chzzk_project/chzzk_data` |
| `APP_LIVE_ICE_SERVERS` | 라이브 WebRTC STUN/TURN 서버 URL(쉼표 구분) | 비어 있음(같은 Wi-Fi에서는 필요 없음) |
| `SPRING_PROFILES_ACTIVE` | 휴대폰 송출용 HTTPS를 켤 때 `dev,https` | `dev` |
| `DEV_SSL_KEY_STORE` | `https` 프로파일의 서버 키스토어(.p12) 경로 | 없음(`https` 프로파일에서 필수) |
| `DEV_SSL_KEY_STORE_PASSWORD` | 위 키스토어의 비밀번호 | 없음(`https` 프로파일에서 필수) |
| `HTTPS_PORT` | `https` 프로파일의 포트 | `8443` |

- 기본값은 `application.yaml`(`APP_UPLOAD_DIR`)과 `application-dev.yaml`(`APP_DATA_DIR`)에 적힌 개발 기본 경로입니다. 작성자 개발 PC 기준의 절대 경로이므로, 다른 PC에서는 두 환경변수를 직접 지정하는 것을 권장합니다.
- 업로드 폴더는 애플리케이션 시작 시 확인하며, 없으면 만듭니다. 폴더를 준비할 수 없으면 시작에 실패합니다.

### PowerShell에서 환경변수를 지정해 실행하기

```powershell
$env:APP_UPLOAD_DIR = "D:/chzzk/uploads"
$env:APP_DATA_DIR = "D:/chzzk/data"
.\gradlew.bat bootRun
```

- `$env:`로 지정한 값은 현재 PowerShell 창에서만 유지됩니다. 새 창을 열면 다시 지정해야 합니다.
- 경로는 위 예시처럼 `/` 구분자를 사용하는 것을 권장합니다.

### macOS·Linux에서 실행하기

기본값이 Windows 경로이므로 macOS·Linux에서는 두 환경변수를 반드시 지정합니다. 검증은 Windows에서만 했습니다.

```bash
chmod +x gradlew
APP_UPLOAD_DIR="$HOME/chzzk/uploads" APP_DATA_DIR="$HOME/chzzk/data" ./gradlew bootRun
```

- `chmod +x gradlew`는 Wrapper 스크립트에 실행 권한이 없을 때 한 번만 실행합니다.
- 테스트는 `./gradlew clean test`로 실행합니다. 테스트는 환경변수 없이도 동작합니다.

### `.env` 파일에 대하여

Spring Boot는 `.env`나 `.env.example` 파일을 자동으로 읽지 않습니다. 이 프로젝트는 `.env` 파일을 사용하지 않으며, 설정 값은 위와 같이 실행 전에 환경변수로 주입합니다.

## 라이브 송출(②) — 휴대폰 연결과 확인 절차

로그인 없이 휴대폰에서 송출하고 PC 등 다른 화면에서 시청합니다. 영상·음성은 WebRTC로 휴대폰에서 시청자 브라우저로 직접 전달되고, 서버는 연결 협상 메시지만 중계합니다. 자세한 설계는 [docs/live-streaming.md](docs/live-streaming.md)를 참고하세요.

### 접속 환경

- 휴대폰과 PC를 **같은 공유기(같은 Wi-Fi/LAN)** 에 연결해 사용합니다. Android → PC 실기기 검증(13/13 PASS)은 이 환경에서 했습니다. 서로 다른 네트워크 사이의 WebRTC 연결은 실기기로 검증하지 않았습니다. STUN/TURN 서버 URL은 `APP_LIVE_ICE_SERVERS`로 지정할 수 있지만 기본값은 비어 있고, TURN 인증 정보 설정과 연결 성공 보장은 현재 구현 범위에 없습니다.
- 휴대폰 브라우저는 **HTTPS 주소에서만** 카메라·마이크를 켤 수 있습니다(`localhost` 제외). 그래서 휴대폰은 `https://<PC의 LAN IP>:8443` 으로 접속합니다.
- 확인한 환경: Android Chrome(송출), Windows Chrome(시청).
- 방송당 동시 시청자는 4명까지입니다(P2P 방식이라 송출 휴대폰이 시청자마다 영상을 따로 보냄).

### 1. 개발용 인증서 만들기 (처음 한 번)

PC의 LAN IP가 들어간 인증서와, 그 인증서를 발급한 개발용 CA를 만듭니다. 파일은 저장소 밖(`../chzzk_certs`)에 생기며 Git에 올리지 않습니다.

```powershell
.\scripts\dev-https\new-dev-certificate.ps1
```

- 키스토어 비밀번호를 묻습니다(6자 이상). 이 값은 어디에도 저장되지 않으니 기억해 두세요.
- LAN IP를 자동으로 찾습니다. 다르면 `-IpAddress 192.168.0.10`으로 지정합니다. PC IP가 바뀌면 `-Force`로 다시 만듭니다.

### 2. 휴대폰에 개발용 CA 설치 (처음 한 번)

1. `../chzzk_certs/chzzk-dev-ca.crt` 파일을 휴대폰으로 옮깁니다(USB, 메신저 등).
2. Android 설정 → 보안 및 개인정보 보호 → 기타 보안 설정 → 암호화 및 사용자 인증 정보 → 인증서 설치 → **CA 인증서**에서 파일을 고릅니다(메뉴 이름은 기종마다 조금 다릅니다. 화면 잠금이 설정되어 있어야 합니다).
3. 휴대폰 Chrome을 완전히 닫았다가 다시 엽니다.

인증서 경고를 "무시하고 계속"하는 방식은 기본 방법으로 쓰지 않습니다. 진단이 꼭 필요할 때만 휴대폰 Chrome의 `chrome://flags` → "Insecure origins treated as secure"에 `http://<PC IP>:8080` 을 넣는 방법을 쓸 수 있으며, 확인 후 원래대로 되돌립니다.

### 3. HTTPS로 서버 실행

```powershell
$env:DEV_SSL_KEY_STORE = "C:/Users/<사용자>/Desktop/Dev/programmers/chzzk_project/chzzk_certs/chzzk-dev.p12"
$env:DEV_SSL_KEY_STORE_PASSWORD = "<1단계에서 입력한 비밀번호>"
$env:SPRING_PROFILES_ACTIVE = "dev,https"
.\gradlew.bat bootRun
```

- 로그에 `Tomcat started on port 8443 (https)`가 보이면 준비된 것입니다.
- Windows 방화벽이 `java.exe`를 막으면 휴대폰에서 접속되지 않습니다. 처음 실행 때 뜨는 허용 창에서 **개인 네트워크만** 허용하세요. 창이 뜨지 않았다면 관리자 PowerShell에서 다음 규칙을 추가합니다(공용 네트워크는 열지 않음).

```powershell
New-NetFirewallRule -DisplayName "chzzk dev https 8443" -Direction Inbound -Protocol TCP -LocalPort 8443 -Profile Private -Action Allow
```

- PC의 네트워크 프로필이 "개인"인지 확인하세요(`Get-NetConnectionProfile`). 공유기 밖 인터넷에는 이 포트가 열리지 않습니다.

### 4. 송출·시청 확인 절차

1. 휴대폰 Chrome에서 `https://<PC IP>:8443/live/new` 에 접속합니다. 주소창에 인증서 경고가 없어야 합니다.
2. **카메라·마이크 켜기**를 누르고 권한을 허용합니다. 미리보기에 카메라 화면이 나와야 합니다.
3. 제목을 입력하고 **방송 시작**을 누릅니다. 상태가 "방송 중"으로 바뀌고 시청 주소가 표시됩니다.
4. PC Chrome에서 `https://<PC IP>:8443/broadcasts` (또는 `https://localhost:8443/broadcasts`)를 열면 방금 방송에 `LIVE` 배지가 보입니다. 제목을 눌러 시청 화면으로 들어갑니다.
5. 영상이 나오는지 보고, **소리 켜기**를 눌러 음성을 확인합니다. 휴대폰 화면의 시청자 수가 1명이 됩니다.
6. 휴대폰에서 **방송 종료**를 누릅니다. PC 화면이 "방송 종료"로 바뀌고, 목록의 배지가 `종료`로 바뀌어야 합니다.

### 실패 상황에서 보이는 안내

| 상황 | 화면 안내 |
| --- | --- |
| HTTP 주소로 접속 | "카메라는 HTTPS 주소에서만 사용할 수 있습니다." |
| 카메라·마이크 권한 거부 | "카메라·마이크 권한이 거부되었습니다. …" (사이트 설정에서 다시 허용) |
| 카메라·마이크 없음 | "사용할 수 있는 카메라나 마이크를 찾지 못했습니다." |
| 송출 화면을 닫음(앱 강제 종료 포함) | 시청 화면에 "방송이 종료되었습니다." |
| 송출 휴대폰의 Wi-Fi가 갑자기 끊김 | 영상이 먼저 멈추고, 생존 신호가 30초 넘게 오지 않으면 다음 서버 점검(10초 주기)에서 방송이 종료되어 시청 화면에 "방송이 종료되었습니다."가 전달됩니다. 그 사이에는 "송출 기기와의 연결이 불안정합니다." 같은 연결 상태 안내가 보일 수 있습니다 |
| 시청 화면 새로고침 | 같은 방송에 다시 연결됩니다(방송 중일 때) |
| 종료된 방송 시청 | "방송이 종료되었습니다." |
| 시청자 4명 초과 | "시청자가 가득 찼습니다. …" |

### Stage 1에서 쓰던 DB를 그대로 쓰는 경우

Stage 1에서 만든 H2 파일 DB는 `video_file_name` 컬럼이 NOT NULL로 만들어져 있어 라이브 방송 저장이 실패합니다. 새 DB에서는 문제가 없습니다. 기존 DB를 계속 쓰려면 H2 Console에서 한 번만 다음을 실행합니다(기존 데이터는 바뀌지 않습니다).

```sql
ALTER TABLE broadcast ALTER COLUMN video_file_name SET NULL;
```

기존 DB 파일을 처음 H2 2.5.252로 열기 전에는 서버를 끄고 `chzzk_data` 폴더 전체를 복사해 백업해 두세요(아래 참고).

### H2 버전 고정 (2.5.252)

Spring Boot 4.1.2가 관리하는 H2 2.4.240에는 다음 문제가 있어 `build.gradle.kts`에서 `extra["h2.version"] = "2.5.252"`로 올렸습니다.

- **증상**: 새 DB로 서버를 켜고 시간이 지난 뒤(실제 사례는 약 3시간 반 뒤) 라이브 방송 시작이 "일시적인 오류가 발생했습니다."로 실패했습니다. 서버 로그에는 `Check constraint invalid`(SQLState 23514)와 원인 `The database has been closed`(90098)가 남았습니다. DB가 실제로 닫힌 것은 아닙니다.
- **원인**: Hibernate는 enum 컬럼(`broadcast_type`, `live_status`)에 `CHECK (... IN (...))` 제약을 만듭니다. H2 2.4.240에서는 이 제약이 자신을 만든 DB 연결(세션)을 계속 참조합니다. 그래서 커넥션 풀(Hikari)이 수명(기본 30분)이 다 된 그 연결을 닫으면, 이후 `broadcast` 테이블의 INSERT·UPDATE가 모두 실패합니다. 라이브 시작, heartbeat, 종료, 녹화방송 등록이 모두 영향을 받습니다. 스키마를 같은 실행 중에 만들거나 바꾼 경우에만 생기며, 서버를 재시작해 제약을 파일에서 다시 읽으면 사라집니다.
- **해결**: 수정이 들어간 H2 2.5.252를 씁니다. CHECK 제약은 그대로 두므로 허용되지 않은 값은 계속 DB가 막습니다.
- **회귀 테스트**: `H2SchemaConnectionRetirementTests`는 스키마를 만든 연결을 실제로 교체한 뒤 라이브 시작, heartbeat, 종료, 녹화방송 등록, CHECK 제약 위반 거부를 확인합니다. H2 세션 번호로 교체 전 연결이 모두 닫혔는지도 확인합니다. H2 2.4.240에서는 5개 모두 23514로 실패했고, 2.5.252에서는 모두 통과합니다.
- **참고 자료**: H2 [issue #4302](https://github.com/h2database/h2database/issues/4302), [PR #4311](https://github.com/h2database/h2database/pull/4311), [릴리스 노트](https://github.com/h2database/h2database/releases)

검증 범위는 다음과 같습니다.

| 항목 | 상태 |
| --- | --- |
| 자동 테스트 전체(H2 회귀 테스트 5개 포함 158개) | 통과 |
| 합성 파일 DB 호환성: 2.4.240에서 만든 DB를 2.5.252로 읽기·쓰기, 2.5.252가 쓴 DB를 2.4.240으로 다시 열기 | 확인함(파일 형식 `format:3` 유지) |
| 실제 개발 DB를 2.5.252로 열기 | 확인하지 않음. 처음 열기 전에 백업 필요 |
| H2 Console 실제 브라우저 접속 | 확인하지 않음 |
| Android 실기기 재검증 | 2.5.252 서버를 켜고 커넥션 풀 연결이 모두 교체된 뒤(시작 63분 뒤) 방송 시작·시청·종료 확인, 90098 재발 없음 |

## 저장 디렉터리 구조

소스 저장소, 영상 업로드 폴더, H2 데이터 폴더는 서로 분리되어 있습니다. 기본값 기준으로 영상과 DB는 Git 저장소 밖에 저장되므로 커밋 대상이 아닙니다.

```text
chzzk_project/
├── chzzk/                      # 소스 저장소(Git)
├── chzzk_uploads/
│   └── broadcasts/             # 업로드 영상 (APP_UPLOAD_DIR), 파일명은 서버가 만든 UUID.mp4
├── chzzk_data/
│   └── chzzk_db.mv.db          # H2 파일 DB (APP_DATA_DIR)
└── chzzk_certs/                # 개발용 HTTPS 인증서·키스토어(②, 스크립트가 생성, Git 밖)
```

라이브 방송은 영상을 서버에 저장하지 않습니다. DB에는 제목과 상태(방송 중/종료, 시작·종료 시각)만 남습니다.

## 테스트

```powershell
.\gradlew.bat clean test
```

- 테스트는 `test` 프로파일로 실행됩니다(빌드 설정에서 지정).
- DB는 인메모리 H2(`jdbc:h2:mem:testdb`)를 쓰고, 업로드 폴더는 테스트마다 만든 임시 폴더를 씁니다.
- 따라서 테스트를 실행해도 개발용 H2 파일 DB와 업로드 폴더는 바뀌지 않습니다.
- `H2SchemaConnectionRetirementTests`만 다른 테스트와 겹치지 않는 인메모리 DB(`jdbc:h2:mem:h2-schema-retirement-regression`)를 따로 씁니다. 커넥션 풀의 연결을 강제로 교체하기 때문입니다.
- 라이브 시그널링은 실제 서버를 띄워 WebSocket 클라이언트로 검증합니다(메시지 전달·검증·방송 간 격리·연결 종료). 실제 카메라와 WebRTC 영상 연결은 자동 테스트로 확인하지 않으므로 위 "송출·시청 확인 절차"로 직접 확인합니다.

## 수동 검증 절차

최신 Chrome 또는 Edge에서 확인합니다. 기존 데이터를 건드리지 않으려면 검증용 폴더를 환경변수로 지정해 실행하세요.

1. 애플리케이션을 실행하고 <http://localhost:8080/broadcasts/new> 에서 제목과 MP4 파일을 선택해 등록합니다. 업로드 중 진행 막대와 퍼센트가 보여야 합니다.
2. 등록이 끝나면 상세 화면으로 이동하는지 확인합니다.
3. <http://localhost:8080/broadcasts> 목록에 방금 등록한 방송이 맨 위에 보이는지 확인합니다.
4. 상세 화면에서 영상이 재생되고, 재생 막대로 구간 이동이 되는지 확인합니다.
5. 서버를 종료(PowerShell에서 `Ctrl+C`)한 뒤 같은 환경변수로 다시 실행하고, 목록과 상세에 방송이 남아 있으며 영상이 재생되는지 확인합니다.
6. 라이브 송출은 [라이브 송출(②) — 휴대폰 연결과 확인 절차](#라이브-송출②--휴대폰-연결과-확인-절차)의 4단계를 따릅니다.
