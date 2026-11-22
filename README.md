# 치지직 (chzzk) — 녹화방송, 휴대폰 라이브 송출, 회원·댓글, 도네이션, 로컬 오토스케일

프로그래머스 데브코스 치지직 서비스 구현 프로젝트입니다.
녹화해 둔 MP4 영상을 방송으로 등록해 시청하고(Stage 1), 휴대폰 카메라·마이크로 라이브 방송을 송출해 다른 화면에서 시청할 수 있습니다(Stage 2).
Stage 3에서 회원가입·로그인과 방송 소유자 권한, 방송별 댓글을 더했습니다. 방송 목록·시청·댓글 읽기는 로그인 없이 누구나 할 수 있고, 등록·송출·수정·삭제·댓글 작성은 로그인한 회원만 할 수 있습니다([인증·인가 정책](#인증인가-정책-stage-3)).
Stage 4에서 방송자를 후원하는 도네이션을 더했습니다. 결제는 토스페이먼츠 **테스트 모드** 흐름(결제 요청 → 서버 승인 확인)으로 구현했고 실제 돈은 오가지 않습니다. 기본 실행은 외부 결제 서비스를 부르지 않는 가짜 게이트웨이로 같은 흐름을 확인합니다([도네이션](#도네이션-stage-4)).
Stage 5에서 서버를 컨테이너 이미지로 만들어 이 PC의 Docker Compose로 여러 인스턴스를 띄우고, 자체 스케일러가 CPU 사용률에 따라 2~5개 사이에서 자동으로 늘리고 줄이게 했습니다. 로그인 세션·업로드 영상·라이브 상태·댓글·도네이션은 인스턴스 밖의 공용 저장소에 둡니다([로컬 오토스케일](#로컬-오토스케일-stage-5)).
Stage 6에서 백엔드를 OCI Always Free VM에 배포해 `https://chzzk.lowndev.com`으로 제공하기 위한 설정(상태 확인 경로, 배포 프로파일, nginx·systemd)과, 방송 목록을 보고 녹화 방송을 재생하는 Windows·macOS 데스크톱 클라이언트를 더했습니다. 실제 VM 생성·공개 배포는 아직이며 현재 상태는 [Stage 6 절](#도메인oci-배포와-데스크톱-클라이언트-stage-6)에 적었습니다.

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
- Stage 2 당시에는 로그인 없이 누구나 송출·시청했습니다. Stage 3부터 송출(방송 시작)은 로그인한 회원만 할 수 있고, 시청은 계속 공개입니다.
- 설계 문서: [docs/live-streaming.md](docs/live-streaming.md)

### Stage 3 — 인증·인가와 댓글

- 회원가입·로그인·로그아웃: Spring Security 세션 방식(폼 로그인). 비밀번호는 BCrypt 해시로만 저장
- 방송 소유자: 녹화방송을 등록하거나 라이브 방송을 시작한 회원이 그 방송의 소유자
- 서버 권한 검사: 방송 등록·라이브 송출은 로그인 필요, 방송 제목 수정·삭제·라이브 종료는 소유자만
- 댓글: 로그인한 회원이 방송(녹화·라이브 모두)에 댓글 작성, 방송별 댓글 목록은 누구나 조회, 댓글 수정·삭제는 작성자만
- 공개 범위: 방송 목록·상세·영상 재생·라이브 시청·댓글 목록은 로그인 없이 공개
- 보안: CSRF 보호(모든 폼·업로드 요청), 로그인 시 세션 ID 교체(세션 고정 공격 방어), 30분 동안 요청이 없으면 세션 만료

### Stage 4 — 도네이션

- 후원 대상·금액 선택: 로그인한 회원이 방송 상세 화면에서 금액(1,000~1,000,000원)과 메시지를 골라 그 방송의 방송자(소유자)를 후원
- 테스트 결제 요청·승인 확인: 토스페이먼츠 결제창(테스트 모드) → 성공 주소로 돌아오면 서버가 승인 API를 호출. 외부 호출 없는 가짜 게이트웨이도 같은 흐름
- 서버 확정: 주문 소유자·금액·수신 대상과 승인 결과(상태·주문번호·금액·결제 키)를 서버가 확인한 뒤에만 후원 확정. 화면의 성공 표시만으로는 확정되지 않음
- 중복 방지: 주문번호·결제 키 유일 제약과 조건부 상태 변경으로, 같은 승인 통지나 성공 요청이 반복·동시에 와도 한 번만 적립
- 방송별 후원 내역(후원자·금액·메시지·시각)과 합계 표시
- 설계 문서: [docs/donation.md](docs/donation.md)

### Stage 5 — 로컬 오토스케일

- 컨테이너 이미지(`Dockerfile`)와 Docker Compose 다중 인스턴스(`deploy/compose.yaml`), nginx 부하 분산기
- 자체 스케일러(`scripts/autoscale/autoscaler.ps1`): 인스턴스 CPU 사용률로 최소 2개~최대 5개 사이 자동 스케일 아웃·인
- 공용 저장소: 로그인 세션·라이브 방 등록부·시그널링 중계·가짜 결제 장부는 Redis, 방송·회원·댓글·후원은 H2 TCP 서버, 업로드 영상은 공용 볼륨
- 인스턴스가 줄어도 라이브 방송이 끝나지 않음: 송출·시청 화면이 다른 인스턴스로 다시 연결해 이어 감
- k6 부하 스크립트(`deploy/load/k6-load.js`), 관찰한 인스턴스 수 변화 기록([로컬 오토스케일](#로컬-오토스케일-stage-5))
- 설계 문서: [docs/autoscale.md](docs/autoscale.md)

### Stage 6 — 도메인·OCI 배포와 Windows/macOS 클라이언트

- 도메인 `lowndev.com`(Cloudflare Registrar)의 하위 도메인 `chzzk.lowndev.com`을 OCI Always Free VM 한 대로 연결, nginx + Let's Encrypt로 HTTPS 제공
- 백엔드: 상태 확인 경로 `GET /health`, 데스크톱용 방송 목록 API `GET /api/broadcasts`, 배포 프로파일(`application-deploy.yaml`), 다운로드 페이지 `/download`
- 배포 설정: `deploy/oci/`(nginx 사이트, systemd 서비스, 환경 파일 예시, 백업 스크립트)
- 데스크톱 클라이언트: `client/`(JavaFX). 방송 목록 조회와 녹화 MP4 재생·정지·탐색. Windows x64 ZIP·macOS arm64 DMG(jpackage, Java 런타임 포함)
- 자세한 내용과 확인 기록: [도메인·OCI 배포와 데스크톱 클라이언트](#도메인oci-배포와-데스크톱-클라이언트-stage-6)

### 제외(이후 단계)

- 실제 결제(라이브 키), 결제 취소·환불, 정산, 실시간 후원 알림
- 썸네일, 라이브 녹화 저장
- 회원정보 수정·탈퇴, 비밀번호 찾기, 관리자 권한, 소셜 로그인
- 실시간 댓글 갱신(새 댓글은 화면을 새로고침해야 보임), 댓글 페이지 나누기
- 업로드 취소, 파일 시그니처·코덱 검사, 트랜스코딩
- TURN 인증 정보 설정과 서로 다른 네트워크 사이 라이브 연결의 성공 보장(실기기 미검증), 대규모 시청(방송당 시청자 4명까지)

## 주요 기능

| 기능 | 경로 | 접근 | 설명 |
| --- | --- | --- | --- |
| 방송 목록 | `GET /broadcasts?page=N` | 공개 | 페이지 번호는 1부터. 잘못된 번호는 1페이지로 처리. `/`는 목록으로 이동 |
| 방송 등록 화면 | `GET /broadcasts/new` | 로그인 | 제목 입력란, 영상 파일 선택란, 진행 막대와 퍼센트 |
| 방송 등록 | `POST /broadcasts` | 로그인 | `multipart/form-data`. 등록한 회원이 소유자. 성공하면 상세 화면으로 이동 |
| 방송 상세 | `GET /broadcasts/{id}` | 공개 | 녹화방송은 영상 플레이어, 라이브 방송은 실시간 시청 화면(종료된 방송은 종료 안내). 방송자 이름과 댓글 목록. 없는 방송·잘못된 ID는 404 화면 |
| 방송 제목 수정 | `GET`·`POST /broadcasts/{id}/edit` | 소유자 | 제목 규칙은 등록과 같음 |
| 방송 삭제 | `POST /broadcasts/{id}/delete` | 소유자 | 댓글과 녹화 영상 파일도 함께 삭제. 방송 중인 라이브는 먼저 종료해야 함 |
| 라이브 종료 | `POST /broadcasts/{id}/end` | 소유자 | 송출 화면 밖(방송 상세)에서 종료. 시청 화면에 종료가 전달됨 |
| 영상 재생 | `GET /videos/{파일명}` | 공개 | 업로드 폴더의 영상을 Range 요청과 함께 제공 |
| 라이브 송출 화면 | `GET /live/new` | 로그인 | 제목, 카메라 미리보기, 방송 시작·종료 버튼, 시청자 수 |
| 라이브 시그널링 | `WebSocket /ws/live` | 시청 공개, 시작은 로그인 | 송출자·시청자 사이 WebRTC 연결 협상 메시지(SDP·ICE) 중계. 영상은 서버를 거치지 않음 |
| 회원가입 | `GET`·`POST /signup` | 공개 | 아이디(영문 소문자·숫자·밑줄 4~20자), 비밀번호(8~64자), 비밀번호 확인 |
| 로그인 | `GET`·`POST /login` | 공개 | 실패하면 `/login?error`. 로그인 전에 가려던 화면이 있으면 그 화면으로 돌아감 |
| 로그아웃 | `POST /logout` | 로그인 | 세션을 없애고 `/login?logout`으로 이동. 헤더의 로그아웃 버튼 |
| 댓글 작성 | `POST /broadcasts/{id}/comments` | 로그인 | 내용 필수, 앞뒤 공백 제거 후 최대 500자 |
| 댓글 수정 | `POST /comments/{id}/edit` | 작성자 | 수정된 댓글에는 "(수정됨)" 표시 |
| 댓글 삭제 | `POST /comments/{id}/delete` | 작성자 | |
| 후원 주문 | `POST /broadcasts/{id}/donations` | 로그인(방송자 본인 제외) | 금액·메시지로 주문(READY)을 만들고 결제 화면으로 이동 |
| 결제 화면 | `GET /donations/{orderId}/checkout` | 주문자 | 토스 결제창(테스트 모드) 또는 가짜 결제창 |
| 결제 성공 복귀 | `GET /donations/success?paymentKey&orderId&amount` | 주문자 | 서버가 금액·주문·수신 대상 확인 후 승인 API 호출, 승인 결과 검증 후 확정 |
| 결제 실패·취소 복귀 | `GET /donations/fail?code&message&orderId` | 주문자 | 실패(FAILED)·취소(CANCELED)로 기록, 후원되지 않음 |
| 후원 결과 | `GET /donations/{orderId}` | 주문자 | 완료·실패·취소 안내, 주문번호·결제 키 |
| 승인 통지 | `POST /payments/toss/webhook` | 결제 서비스(로그인·CSRF 예외) | 본문을 믿지 않고 결제 서비스에 다시 조회해 확인. 반복 통지는 무시 |
| 가짜 결제창 버튼 | `POST /donations/{orderId}/fake-pay` | 주문자, `fake` 게이트웨이에서만 | 승인·승인 단계 거절·실패·취소를 흉내 냄 |

모든 `POST` 요청은 CSRF 토큰이 있어야 합니다(승인 통지만 예외). 화면의 폼에는 토큰이 자동으로 들어가고, 업로드 스크립트는 토큰을 `X-CSRF-TOKEN` 헤더로 보냅니다.

등록 규칙

- 제목: 필수, 앞뒤 공백 제거 후 최대 100자
- 영상 파일: 확장자 `.mp4`(대소문자 무시), Content-Type `video/mp4`, 최대 500MB(524,288,000바이트)
- 브라우저에서는 500MB를 넘는 파일을 전송 전에 막고, 그 밖의 검증은 서버에서 합니다.
- JavaScript가 동작하지 않아도 일반 폼 제출로 등록할 수 있습니다.

## 인증·인가 정책 (Stage 3)

### 공개 범위

| 대상 | 비로그인 사용자 | 로그인한 회원 |
| --- | --- | --- |
| 방송 목록·상세, 녹화 영상 재생 | 가능 | 가능 |
| 라이브 시청(시그널링 `watch`) | 가능 | 가능 |
| 방송별 댓글 목록 읽기 | 가능 | 가능 |
| 방송 등록, 라이브 송출(방송 시작) | 불가(로그인 화면으로 이동) | 가능, 본인이 소유자가 됨 |
| 댓글 작성 | 불가(로그인 화면으로 이동) | 가능 |

### 권한 규칙과 응답

| 요청 | 비로그인 | 방송 소유자·댓글 작성자 | 다른 회원 |
| --- | --- | --- | --- |
| 방송 제목 수정·삭제, 라이브 종료 | 로그인 화면으로 이동(302) | 허용 | 거절(403 화면) |
| 댓글 수정·삭제 | 로그인 화면으로 이동(302) | 허용 | 거절(403 화면) |
| 라이브 방송 시작(WebSocket `start`) | 거절(`UNAUTHENTICATED` 오류 메시지) | 허용 | 허용(본인 방송) |

- 권한 검사는 서버가 합니다. 수정·삭제·종료 버튼은 소유자·작성자에게만 보이지만, 버튼을 숨긴 것과 상관없이 요청을 직접 보내도 서버가 회원 ID로 소유자·작성자인지 확인해 거절합니다(`BroadcastService`, `LiveBroadcastService`, `CommentService`, `LiveSignalingHandler`).
- 업로드처럼 스크립트(XHR)로 보낸 요청은 로그인 화면으로 보내지 않고 `401`(로그인 필요)·`403`(CSRF 토큰 없음) JSON으로 응답하며, 화면에 그 문구를 보여 줍니다.
- 없는 방송·댓글이나 잘못된 ID는 `404` 화면입니다. CSRF 토큰이 없거나 틀린 `POST`는 `403`입니다.
- Stage 3 이전에 만든 방송은 소유자가 없어 누구도 수정·삭제·종료할 수 없습니다(조회와 댓글은 가능).

### 세션과 비밀번호

- 로그인 상태는 서버 세션(`JSESSIONID` 쿠키, `HttpOnly`, `SameSite=Lax`)으로 관리합니다. 30분 동안 요청이 없으면 세션이 만료되어 다시 로그인해야 합니다.
- 로그인에 성공하면 세션 ID를 새로 발급합니다(세션 고정 공격 방어). 로그아웃하면 세션을 없애고 쿠키를 지웁니다.
- 비밀번호는 BCrypt 해시로만 저장하며 로그에 남기지 않습니다. 로그인 실패 시에는 아이디와 비밀번호 중 무엇이 틀렸는지 구분하지 않고 같은 문구를 보여 줍니다.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| 언어 | Java 25 |
| 프레임워크 | Spring Boot 4.1.2, Spring MVC |
| 화면 | Thymeleaf(서버 렌더링), JavaScript(업로드 진행률, 라이브 송출·시청) |
| 라이브 | 브라우저 WebRTC API(`getUserMedia`, `RTCPeerConnection`), Spring WebSocket(시그널링) |
| 인증·인가 | Spring Security(세션 기반 폼 로그인, BCrypt, CSRF) |
| 결제 | 토스페이먼츠 결제창 SDK v2·결제 승인 API(테스트 모드, Spring `RestClient`), 외부 호출 없는 가짜 게이트웨이 |
| 데이터 | Spring Data JPA, H2 Database 2.5.252(개발: 파일 DB, 테스트: 인메모리 DB) |
| 검증 | Spring Boot Validation |
| 빌드 | Gradle(Wrapper 9.8.2), Kotlin DSL(`build.gradle.kts`) |
| 테스트 | JUnit 5, Spring Boot Test(MockMvc), Spring Security Test |
| 여러 인스턴스(Stage 5) | Spring Data Redis(Lettuce)·Spring Session Data Redis(`cluster` 프로파일에서만), Docker Compose, nginx 1.28, Redis 7.4, k6 1.0 |

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
  - H2 Console은 자체 로그인을 쓰므로, 개발용 콘솔 경로(`/h2-console/**`)에 한해서만 Spring Security의 CSRF 검사에서 빼고 같은 출처 frame을 허용했습니다. 콘솔이 꺼진 프로파일(test 등)에서는 이 예외가 만들어지지 않습니다.
- 처음 실행하면 회원이 없으므로 <http://localhost:8080/signup> 에서 회원가입한 뒤 로그인합니다.

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
| `APP_PAYMENT_GATEWAY` | 후원 결제 게이트웨이: `fake`(외부 호출 없음) 또는 `toss`(토스페이먼츠 테스트 모드) | `fake` |
| `TOSS_CLIENT_KEY` | 토스페이먼츠 테스트 클라이언트 키(`test_ck_`·`test_gck_`로 시작). `toss`일 때 필수 | 없음 |
| `TOSS_SECRET_KEY` | 토스페이먼츠 테스트 시크릿 키(`test_sk_`·`test_gsk_`로 시작). `toss`일 때 필수. 화면·로그에 내보내지 않음 | 없음 |

- Stage 3에서 새로 필요한 환경변수는 없습니다. 회원 정보와 댓글은 같은 H2 DB에 저장되며, 별도의 비밀 키나 토큰을 쓰지 않습니다.
- Stage 4의 결제 환경변수는 지정하지 않아도 됩니다. 기본값 `fake`는 외부 결제 서비스를 호출하지 않습니다. `toss`로 바꾸면 **테스트 키만** 받으며(`test_`로 시작하지 않으면 시작 실패), 실제 결제용 키는 지원하지 않습니다. 키는 저장소에 넣지 말고 실행 전에 환경변수로만 넣으세요.

```powershell
# 토스페이먼츠 테스트 모드로 실행(키는 토스페이먼츠 개발자센터의 테스트 키, 값은 예시 형식)
$env:APP_PAYMENT_GATEWAY = "toss"
$env:TOSS_CLIENT_KEY = "test_ck_<테스트 클라이언트 키>"
$env:TOSS_SECRET_KEY = "test_sk_<테스트 시크릿 키>"
.\gradlew.bat bootRun
```
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

## 도네이션 (Stage 4)

### 흐름

1. **후원 대상·금액 선택**: 로그인한 회원이 방송 상세 화면의 "후원" 영역에서 금액과 메시지를 입력하고 **후원하기**를 누릅니다. 서버가 주문(`READY`)을 만들고, 이때 금액과 수신 대상(그 방송의 방송자)을 서버가 정해 저장합니다.
2. **테스트 결제 요청**: 결제 화면에서 결제창을 엽니다.
   - `APP_PAYMENT_GATEWAY=toss`: 토스페이먼츠 결제창 SDK v2(`requestPayment`, 테스트 클라이언트 키)
   - `APP_PAYMENT_GATEWAY=fake`(기본): 외부 호출 없는 가짜 결제창. 승인·승인 단계 거절·실패·취소 버튼이 토스 결제창이 돌려주는 결과를 흉내 냅니다.
3. **서버 승인 확인**: 결제창이 `GET /donations/success?paymentKey=…&orderId=…&amount=…`로 돌아오면 서버가
   1) 주문이 지금 로그인한 회원의 것인지, 2) 주소의 금액이 저장된 금액과 같은지, 3) 수신 대상이 아직 그 방송의 방송자인지 확인하고,
   4) 주문 하나당 한 요청만 승인을 맡아(`READY → IN_PROGRESS`, 조건부 UPDATE) 결제 서비스에 승인을 요청합니다(토스 `POST /v1/payments/confirm`, 테스트 시크릿 키 Basic 인증, 주문번호를 멱등 키로 사용).
   5) 승인 응답의 상태가 `DONE`이고 주문번호·금액·결제 키가 주문과 같을 때만 `DONE`으로 확정합니다. 하나라도 다르면 `FAILED`입니다.
4. **실패·취소**: 결제창이 `GET /donations/fail?code=…&orderId=…`로 돌아오면 `FAILED`(사용자 취소 `PAY_PROCESS_CANCELED`는 `CANCELED`)로 기록하고 후원되지 않습니다.
5. **승인 통지(웹훅)**: `POST /payments/toss/webhook`(토스 `PAYMENT_STATUS_CHANGED` 형식)은 본문을 믿지 않고, 결제 키로 결제 서비스에 다시 조회해 `DONE`이고 주문과 같을 때만 확정합니다. 이미 확정된 주문이면 아무것도 하지 않고 `ALREADY_DONE`으로 응답합니다.
6. **내역과 합계**: 방송 상세 화면에 확정(`DONE`)된 후원만 후원자·금액·메시지·승인 시각과 합계·건수로 보여 줍니다. 누구나 볼 수 있습니다.

### 규칙

| 항목 | 규칙 |
| --- | --- |
| 후원할 수 있는 사람 | 로그인한 회원. 자기 방송에는 후원할 수 없음 |
| 받는 사람 | 방송자(방송 소유자). Stage 3 이전에 만든 소유자 없는 방송에는 후원할 수 없음 |
| 방송 상태 | 녹화방송·라이브 방송(방송 중·종료) 모두 가능 |
| 금액 | 1,000원 이상 1,000,000원 이하의 정수(원). 그 밖은 입력 오류 |
| 메시지 | 선택, 앞뒤 공백 제거 후 최대 100자. 화면에는 HTML 이스케이프해서 표시 |
| 주문 접근 | 결제 화면·성공/실패 주소·결과는 주문한 회원만(다른 회원 403, 비로그인은 로그인 화면) |
| 확정 기준 | 서버가 승인 결과를 검증한 `DONE`만 내역·합계에 포함. 화면의 성공 표시나 성공 주소 도착만으로는 확정되지 않음 |
| 중복 방지 | 주문번호·결제 키 DB 유일 제약, `READY→IN_PROGRESS→DONE` 조건부 UPDATE. 성공 주소 새로고침·반복 통지·동시 요청에도 한 건만 적립. 다른 주문에 이미 쓰인 결제 키는 거절 |
| 방송 삭제 | 확정됐거나 승인 처리 중인 후원이 있는 방송은 삭제할 수 없음(결제 기록 보존). 확정되지 않은 주문만 있으면 함께 삭제 |

### 결제 연동 범위 (실제 결제 없음)

- 토스페이먼츠 테스트 모드용 코드(결제창 SDK 호출, 승인·조회 API 클라이언트, 테스트 키 검사)를 포함합니다. `test_`로 시작하지 않는 키는 거부하므로 실제 결제는 일어나지 않습니다.
- 자동 테스트와 분리 서버 검증은 모두 가짜 게이트웨이(`fake`)로 했습니다. 토스 API 클라이언트의 요청 형식(경로·Basic 인증·멱등 키·본문)과 응답·오류 해석은 네트워크 없는 가짜 HTTP 서버(`MockRestServiceServer`)로 확인했습니다.
- **실제 토스페이먼츠 테스트(샌드박스) 서버에 대한 결제창·승인 API 호출은 실행하지 않았습니다(NOT_TESTED).**
- 실제 돈, 카드 정보, 라이브 키는 쓰지 않으며 저장소에도 키가 없습니다. 결제 취소·환불 API는 구현하지 않았습니다(승인 결과가 주문과 다르면 확정하지 않고 실패로 기록하며 서버 로그에 확인 필요로 남김).

### 직접 확인 절차 (테스트 결제)

기본 실행(`fake`)으로 확인합니다. 회원 두 명(방송자 `owner01`, 후원자 `fan01`)을 만들고, `owner01`로 방송을 하나 등록해 둡니다(Stage 3 절차).

1. **테스트 결제 성공**: `fan01`로 그 방송 상세 화면을 엽니다. "후원" 영역에 `5000`과 메시지를 넣고 **후원하기** → 결제 화면에서 금액·받는 사람을 확인하고 **결제 승인(테스트)** → "후원이 완료되었습니다." 결과 화면이 나옵니다. 방송 상세로 돌아가면 합계 `5,000원`, 1건, 후원자·메시지가 보입니다.
2. **테스트 결제 실패**: 다시 후원하기 → **결제 실패(테스트)** → "결제가 승인되지 않아 후원되지 않았습니다."와 사유 코드 `REJECT_CARD_COMPANY`. **승인 단계에서 거절(테스트)**는 결제창 인증은 됐지만 서버 승인 요청에서 거절되는 경우로, 같은 실패 결과가 나옵니다. 합계는 그대로입니다.
3. **테스트 결제 취소**: 후원하기 → **결제 취소(테스트)** → "결제를 취소했습니다. 후원되지 않았습니다." 합계는 그대로입니다.
4. **화면 성공만으로 확정되지 않음 / 금액 위조**: 후원하기로 `50000`원 주문을 만들고, 결제 화면 주소(`/donations/<주문번호>/checkout`)에서 주문번호를 복사합니다. 결제창 버튼을 누르지 말고 주소창에 성공 주소를 직접 입력합니다.
   - 금액 위조: `/donations/success?paymentKey=fake_test&orderId=<주문번호>&amount=1000` → "사유 코드: AMOUNT_MISMATCH"로 실패하고 합계에 들어가지 않습니다(결제 서비스를 부르기 전에 서버가 금액을 비교).
   - 결제하지 않은 성공 주소: 새 주문으로 `/donations/success?paymentKey=fake_test&orderId=<주문번호>&amount=50000` → 결제 서비스에 그런 결제가 없으므로 "사유 코드: NOT_FOUND_PAYMENT"로 실패합니다. 성공 주소에 도착한 것만으로는 확정되지 않습니다.
5. **성공 요청 반복**: 1번의 결과 화면에서 브라우저 뒤로 가기 후 성공 주소를 다시 열거나 새로고침해도 합계와 건수가 늘지 않습니다.
6. **승인 통지 중복**: 1번 결과 화면의 **주문번호**와 **결제 키**를 복사해 같은 통지를 세 번 보냅니다. 세 번 모두 `{"result":"ALREADY_DONE"}`이고 합계·건수가 그대로여야 합니다.

   ```powershell
   $body = '{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"<결제 키>","orderId":"<주문번호>","status":"DONE"}}'
   1..3 | ForEach-Object { Invoke-RestMethod -Method Post -Uri http://localhost:8080/payments/toss/webhook -ContentType 'application/json' -Body $body }
   ```

   결제 키를 `forged`처럼 바꿔 보내면 결제 서비스 조회가 실패하므로 `{"result":"IGNORED"}`이고 아무것도 확정되지 않습니다.
7. **권한**: 로그아웃하면 "후원" 영역에 입력란 대신 로그인 안내가 나오고, `owner01`로 로그인하면 "내 방송에는 후원할 수 없습니다."가 나옵니다. 다른 회원으로 `fan01`의 결과 주소(`/donations/<주문번호>`)를 열면 403입니다. 후원 내역이 있는 방송을 `owner01`이 삭제하면 "후원 내역이 있는 방송은 삭제할 수 없습니다."가 나옵니다.

**토스페이먼츠 테스트 모드로 확인하려면**(이 저장소의 검증에서는 실행하지 않음, NOT_TESTED): 위 환경변수 예시처럼 `APP_PAYMENT_GATEWAY=toss`와 토스페이먼츠 개발자센터의 테스트 키 두 개를 넣고 실행합니다. 결제 화면의 **토스페이먼츠로 결제하기**를 누르면 테스트 결제창이 열리고, 결제창 안내에 따라 진행한 결과가 위와 같은 성공·실패 주소로 돌아옵니다. 결제창을 닫으면 취소(`PAY_PROCESS_CANCELED`)로 기록됩니다. 승인 통지는 토스페이먼츠 개발자센터에서 웹훅 주소를 `https://<외부에서 접근 가능한 주소>/payments/toss/webhook`으로 등록해야 받을 수 있습니다(로컬 PC는 외부 접근 주소가 없으면 받을 수 없으며, 6번 명령으로 같은 형식의 통지를 직접 보내 확인할 수 있습니다).

### Stage 4 검증 결과 (2026-11-18)

| 항목 | 결과 |
| --- | --- |
| 자동 테스트 전체 | 39개 클래스, 239개 통과(실패·건너뜀 0). Stage 3까지의 199개 포함 |
| 도네이션 자동 테스트 | `DonationServiceTests` 15, `DonationWebTests` 14, `DonationApprovalVerificationTests` 4, `TossPaymentsGatewayTests` 4, `PaymentConfigTests` 3 |
| 동시 요청(사례 B) | 같은 주문의 승인 요청 8개를 동시에 보내도 결제 서비스 승인 호출 1회, 확정 1건(`concurrentConfirmRequestsConfirmExactlyOnce`) |
| 중복 요청(사례 A) | 성공 주소 3회 반복·승인 통지 3회 반복에도 확정 1건, 합계 변화 없음 |
| 분리 서버 HTTP 시나리오(별도 포트·DB·업로드 폴더, 가짜 게이트웨이, `curl`) | 42/42 통과(최종 빌드 기준). 비로그인 거절, CSRF 없는 요청 403, 다른 회원 403, 결제창 성공 직후 미확정, 서버 승인 후 확정, 성공 주소 반복, 금액 위조 `AMOUNT_MISMATCH`, 취소·거절, 승인 통지 3회 반복 `ALREADY_DONE`, 위조 통지 `IGNORED`, 형식 오류 400, 자기 방송 후원 거절, 후원 있는 방송 삭제 거절 |
| 분리 서버 실제 브라우저 | 14/14 통과(최종 빌드 기준). 방송자·후원자 화면 구분, 후원 폼 → 결제 화면(금액·받는 사람) → 가짜 결제 승인 → 결과 → 합계·건수·후원자·메시지 표시, 취소 뒤 합계 유지, 가짜 모드에서 토스 SDK 미로드 |
| 실제 토스페이먼츠 테스트 서버 호출(결제창·승인 API) | NOT_TESTED(실행하지 않음) |
| 실제 개발 DB를 Stage 4 코드로 열기 | 확인하지 않음. 열면 `donation` 테이블이 추가되므로 먼저 백업 |

## 로컬 오토스케일 (Stage 5)

같은 서버를 Docker Compose로 여러 인스턴스 띄우고, 자체 스케일러가 CPU 사용률을 보고 2~5개 사이에서 자동으로 늘리고 줄입니다. 클라우드에 배포하지 않으며 모든 것이 이 PC의 Docker Desktop에서만 돕니다. 설계는 [docs/autoscale.md](docs/autoscale.md)를 참고하세요.

```text
브라우저 ──▶ lb (nginx, 127.0.0.1:18090) ──least_conn──▶ app × N (2~5, CPU 0.5개씩)
                                                          │
             공용 저장소: h2 (H2 TCP 서버: 방송·회원·댓글·후원)
                         redis (로그인 세션·라이브 방 등록부·시그널링 중계·가짜 결제 장부)
                         uploads 볼륨 (업로드 영상)
autoscaler.ps1 ── docker stats(CPU) ──▶ 늘림: docker compose up --scale app=N
                                        줄임: lb 대상에서 먼저 빼고(nginx reload) 5초 뒤 docker stop
```

### 구현 범위와 파일

| 과제 요구 | 파일 |
| --- | --- |
| 컨테이너 이미지 | `Dockerfile`, `.dockerignore` |
| 여러 인스턴스 배포 구성 | `deploy/compose.yaml`(h2·redis·app·lb), `scripts/autoscale/cluster-up.ps1` |
| 자동 스케일 아웃·인(최소 2, 최대 5, CPU 지표) | `scripts/autoscale/autoscaler.ps1` |
| 요청 분산 | `deploy/nginx/nginx.conf`, `scripts/autoscale/lb-upstream.ps1`(분산 대상 목록 관리: 준비된 인스턴스만 넣고, 내릴 인스턴스는 먼저 뺌) |
| 공용 상태 | `src/main/resources/application-cluster.yaml`, `global/cluster/ClusterConfig.java`(Redis 로그인 세션), `live/RedisLiveRoomStore.java`·`live/RedisLiveMessageRelay.java`(라이브 상태), `payment/RedisFakePaymentLedger.java`(가짜 결제 장부), H2 TCP 서버(방송·댓글·후원), `uploads` 볼륨(영상) |
| 부하 스크립트 | `deploy/load/k6-load.js` |
| 환경변수 예시 | `deploy/.env.example` |

- `cluster` 프로파일에서만 Redis를 씁니다(`app.cluster.enabled=true`). 기본 실행(`.\gradlew.bat bootRun`)과 테스트는 Stage 4까지와 같고 Redis가 필요 없습니다.
- 응답 헤더 `X-Chzzk-Instance`에 요청을 처리한 인스턴스(컨테이너 이름)가 나옵니다.
- 인스턴스가 줄어 라이브 송출·시청 연결이 끊기면 화면이 다른 인스턴스로 다시 연결하고, 송출 화면은 같은 방송을 이어 갑니다(`resume`). 방송은 끝나지 않습니다.
- 결제는 계속 가짜 게이트웨이(`APP_PAYMENT_GATEWAY=fake`)만 씁니다. 실제 결제나 외부 결제 서버 호출은 없습니다.

### 준비

- Docker Desktop(Compose v2 포함), JDK 25. 처음 실행할 때 아래 공개 이미지를 내려받습니다. 다시 받아도 같은 이미지가 되도록 태그와 digest(여러 아키텍처 목록 digest)를 함께 고정했습니다. 2026-11-18에 레지스트리에서 digest와 `linux/amd64` 포함을 확인했습니다.

  | 이미지 | 쓰는 곳 | 출처 | digest |
  | --- | --- | --- | --- |
  | `eclipse-temurin:25-jre` | `Dockerfile` 기반 이미지 | Docker 공식 이미지(`library/`, Eclipse Adoptium) | `sha256:945247f9d092703e0c6be92243ef3c51640229e23ab34d353c8dcdc73e6cd4e6` |
  | `redis:7.4-alpine` | `compose.yaml` redis | Docker 공식 이미지(`library/`) | `sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe` |
  | `nginx:1.28-alpine` | `compose.yaml` lb | Docker 공식 이미지(`library/`) | `sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236` |
  | `grafana/k6:1.0.0` | 부하 발생(`docker run`) | Grafana Labs 배포 이미지(`grafana/`) | `sha256:f21270290d702cbf0a7d6ba5d7ed100b63bcb233b558b885ed787547b3488279` |
- 포트는 `127.0.0.1:18090`(바꾸려면 `CHZZK_HTTP_PORT`) 하나만 엽니다. H2·Redis·앱 포트는 컨테이너 네트워크 안에서만 열립니다.
- 클러스터의 DB·영상은 Docker 볼륨(`chzzk-autoscale_h2data`, `_redisdata`, `_uploads`)에 저장되며, 개발용 H2 파일 DB(`chzzk_data`)와 업로드 폴더와는 따로입니다.

### 환경변수 (`deploy/.env`)

`deploy/.env.example`을 `deploy/.env`로 복사하고 값을 바꿉니다. `deploy/.env`는 Git에 올라가지 않습니다.

| 이름 | 의미 | 예시 |
| --- | --- | --- |
| `CHZZK_DB_PASSWORD` | 공용 H2 DB(`sa`) 비밀번호. 처음 띄울 때의 값으로 DB가 만들어지므로 이후에도 같은 값을 씁니다. 필수 | `change-me-local-only` |
| `CHZZK_HTTP_PORT` | 부하 분산기를 열 로컬 포트(127.0.0.1) | `18090` |
| `APP_LIVE_ICE_SERVERS` | 라이브 STUN/TURN 주소(쉼표 구분). 같은 PC·같은 Wi-Fi면 비워 둠 | 비어 있음 |

컨테이너 안에서는 `deploy/compose.yaml`이 `SPRING_PROFILES_ACTIVE=cluster`, `APP_PAYMENT_GATEWAY=fake`를 지정합니다. 앱은 `CHZZK_DB_HOST`(기본 `h2`), `CHZZK_REDIS_HOST`(기본 `redis`), `CHZZK_REDIS_PORT`(기본 `6379`), `APP_UPLOAD_DIR`(기본 `/data/uploads`)도 읽지만 Compose에서는 바꿀 필요가 없습니다.

### 실행 방법

```powershell
Copy-Item deploy\.env.example deploy\.env    # 처음 한 번, 비밀번호를 바꿉니다
.\scripts\autoscale\cluster-up.ps1           # JAR·이미지 빌드 → h2·redis → 앱 1개(테이블 생성) → 앱 2개 → lb
```

- 준비되면 <http://127.0.0.1:18090> 으로 접속합니다. 회원가입부터 후원까지 Stage 1~4 화면을 그대로 씁니다.
- 이미지를 이미 만들었다면 `-SkipBuild`로 빌드를 건너뜁니다.
- 상태 확인: `docker compose -f deploy/compose.yaml --env-file deploy/.env ps`

### 스케일 아웃·인 확인 절차

1. **스케일러 실행**(PowerShell 창 A, 저장소 루트): 10초마다 인스턴스 수와 CPU를 한 줄씩 보여 주고 `scripts/autoscale/autoscaler.log`에도 남깁니다. 멈출 때는 `Ctrl+C`입니다.

   ```powershell
   .\scripts\autoscale\autoscaler.ps1      # 기본: -Min 2 -Max 5 -ScaleOutPercent 60 -ScaleInPercent 20
   ```

2. **부하 걸기**(PowerShell 창 B, 저장소 루트): 가상 사용자 60명이 30초 동안 늘어나 3분 동안 목록·로그인 화면을 읽고(데이터를 바꾸지 않음) 20초 동안 줄어듭니다.

   ```powershell
   docker run --rm --network chzzk-autoscale_default -e BASE_URL=http://lb -v "${PWD}\deploy\load:/scripts:ro" grafana/k6:1.0.0@sha256:f21270290d702cbf0a7d6ba5d7ed100b63bcb233b558b885ed787547b3488279 run /scripts/k6-load.js
   ```

3. **늘어나는지 확인**: 창 A에 `cpu=`가 60% 이상으로 두 번 연속 찍히면 `-> scale-out to 3`이 나오고, 30초 쿨다운 뒤에도 높으면 4, 5까지 늘어납니다. `docker compose -f deploy/compose.yaml --env-file deploy/.env ps app`에 `app-3`~`app-5`가 보입니다.
4. **줄어드는지 확인**: 부하가 끝나 CPU가 20% 미만으로 세 번 연속 찍히면 `-> scale-in to 4`, 이어서 3, 2까지 줄고 2에서 멈춥니다. 줄일 때마다 `lb drain chzzk-autoscale-app-N ...` 줄이 먼저 찍힙니다. 내려갈 인스턴스를 부하 분산기에서 먼저 빼고 5초 뒤에 내리므로 새 요청은 그 인스턴스로 가지 않고, 이미 받은 요청은 마치고 종료됩니다.
5. **공용 상태 확인**(선택): 여러 번 새로고침하며 개발자 도구의 응답 헤더 `X-Chzzk-Instance`가 바뀌어도 로그인이 유지되고, 다른 인스턴스에서 올린 영상·댓글·후원이 그대로 보이는지 확인합니다.

### 관찰한 인스턴스 수 변화 (2026-11-18)

위 절차를 그대로 실행한 결과입니다(스케일러 기본값, k6 `HOLD=4m`). 스케일러 로그(`autoscaler.log`) 그대로입니다. `cpu`는 인스턴스 CPU 제한(0.5개) 대비 평균 사용률입니다.

```text
2026-11-18T03:10:13 start min=2 max=5 out>=60% x2 in<20% x3 interval=10s
2026-11-18T03:10:39 instances=2 cpu=0.4% (high=0 low=3 cooldown=False)
2026-11-18T03:10:50 instances=2 cpu=99.7% (high=1 low=0 cooldown=False)      ← k6 부하 시작
2026-11-18T03:11:04 instances=2 cpu=98.6% -> scale-out to 3
2026-11-18T03:11:16 instances=3 cpu=99.2% (high=1 low=0 cooldown=True)
2026-11-18T03:11:29 instances=3 cpu=98% (high=2 low=0 cooldown=True)
2026-11-18T03:11:42 instances=3 cpu=99.5% -> scale-out to 4
2026-11-18T03:11:55 instances=4 cpu=101.1% (high=1 low=0 cooldown=True)
2026-11-18T03:12:08 instances=4 cpu=101.6% (high=2 low=0 cooldown=True)
2026-11-18T03:12:21 instances=4 cpu=101.5% -> scale-out to 5
2026-11-18T03:12:34 instances=5 cpu=100% (high=1 low=0 cooldown=True)
...(03:12:47 ~ 03:15:07 instances=5, cpu=102~107%, 최대 5개라 더 늘리지 않음)
2026-11-18T03:15:19 instances=5 cpu=90.9% (high=14 low=0 cooldown=False)
2026-11-18T03:15:32 instances=5 cpu=0.8% (high=0 low=1 cooldown=False)       ← k6 부하 끝
2026-11-18T03:15:45 instances=5 cpu=0.3% (high=0 low=2 cooldown=False)
2026-11-18T03:15:59 instances=5 cpu=0.2% -> scale-in to 4
2026-11-18T03:16:11 instances=4 cpu=0.2% (high=0 low=1 cooldown=True)
2026-11-18T03:16:24 instances=4 cpu=0.2% (high=0 low=2 cooldown=True)
2026-11-18T03:16:38 instances=4 cpu=0.3% -> scale-in to 3
2026-11-18T03:16:51 instances=3 cpu=0.3% (high=0 low=1 cooldown=True)
2026-11-18T03:17:03 instances=3 cpu=0.2% (high=0 low=2 cooldown=True)
2026-11-18T03:17:17 instances=3 cpu=0.3% -> scale-in to 2
2026-11-18T03:17:29 instances=2 cpu=0.2% (high=0 low=1 cooldown=True)
2026-11-18T03:18:19 instances=2 cpu=0.2% (high=0 low=5 cooldown=False)       ← 최소 2개에서 유지
```

`docker compose ps` 출력(이름·상태):

```text
# 부하가 끝난 직후(03:15:32)                  # 스케일 인 뒤(03:18)
chzzk-autoscale-app-1 Up 5 minutes           chzzk-autoscale-app-1 Up 8 minutes
chzzk-autoscale-app-2 Up 6 minutes           chzzk-autoscale-app-2 Up 9 minutes
chzzk-autoscale-app-3 Up 4 minutes           chzzk-autoscale-h2-1 Up 16 minutes
chzzk-autoscale-app-4 Up 3 minutes           chzzk-autoscale-lb-1 Up 9 minutes
chzzk-autoscale-app-5 Up 3 minutes           chzzk-autoscale-redis-1 Up 16 minutes
chzzk-autoscale-h2-1 Up 13 minutes
chzzk-autoscale-lb-1 Up 6 minutes
chzzk-autoscale-redis-1 Up 13 minutes
```

k6 결과: 요청 428,535개(초당 약 1,478개), 실패 0%(`http_req_failed 0.00%`), 확인 항목 100% 통과, 응답 시간 중앙값 2.02ms·p95 96.06ms. 인스턴스가 늘어나는 동안에도 실패한 요청이 없었습니다.

**최종 커밋 코드로 다시 실행(03:39~03:45)**: 위 실행 뒤 라이브 다시 연결 부분(`resume` 시청자 목록, 시청 화면 재시도)과 스케일러 로그 쓰기를 고쳤으므로, 최종 이미지로 같은 절차를 한 번 더 실행했습니다(k6 `HOLD=2m`, README의 볼륨 연결 명령). 판단 줄만 옮깁니다.

```text
2026-11-18T03:39:50 instances=2 cpu=0.4% (high=0 low=2 cooldown=True)
2026-11-18T03:40:15 instances=2 cpu=102.1% (high=1 low=0 cooldown=False)    ← k6 부하 시작
2026-11-18T03:40:28 instances=2 cpu=101.1% -> scale-out to 3
2026-11-18T03:41:07 instances=3 cpu=103.7% -> scale-out to 4
2026-11-18T03:41:46 instances=4 cpu=104.4% -> scale-out to 5
2026-11-18T03:42:49 instances=5 cpu=23% (high=0 low=0 cooldown=False)       ← k6 부하 끝
2026-11-18T03:43:28 instances=5 cpu=0.2% -> scale-in to 4
2026-11-18T03:44:07 instances=4 cpu=0.3% -> scale-in to 3
2026-11-18T03:44:45 instances=3 cpu=0.2% -> scale-in to 2
2026-11-18T03:45:11 instances=2 cpu=0.2% (high=0 low=2 cooldown=True)
```

k6 결과: 요청 210,594개(초당 약 1,239개), 실패 0%, 확인 항목 100% 통과. `docker compose ps`는 부하 직후 `app-1`~`app-5`, 스케일 인 뒤 `app-1`·`app-2`였습니다.

전체 로그와 출력은 [docs/autoscale-run-2026-11-18.txt](docs/autoscale-run-2026-11-18.txt)에 있습니다.

### 여러 인스턴스 공용 상태 확인 결과 (2026-11-18)

클러스터(앱 2개)에 대해 HTTP 클라이언트와 WebSocket 클라이언트(Node 스크립트)로 확인했습니다. 어느 인스턴스가 처리했는지는 `X-Chzzk-Instance`로 구분했습니다.

| 항목 | 결과 |
| --- | --- |
| 요청 분산 | 목록 30번 요청이 두 인스턴스에 15:15로 나뉨 |
| 로그인 세션(Redis) | 로그인 뒤 등록 화면 20번 요청이 두 인스턴스 모두에서 200(로그인 유지) |
| 업로드 영상(공용 볼륨) | 한 인스턴스에 올린 영상을 두 인스턴스가 모두 같은 내용(SHA-256 일치)으로 내려줌 |
| 댓글(공용 DB) | 한 인스턴스에서 쓴 댓글이 두 인스턴스 모두에서 보임 |
| 도네이션(가짜 결제 장부, Redis) | 결제창 인증을 한 인스턴스가, 성공 주소 승인 확인을 다른 인스턴스가 처리해도 4건 모두 확정, 합계 4,000원 |
| 라이브 방·시그널링 중계(Redis) | 송출자와 다른 인스턴스에 붙은 시청자가 입장하고 Offer·Answer가 인스턴스 사이로 전달됨 |
| 인스턴스 종료 중 라이브 | 송출자가 있던 인스턴스를 멈추면 그 인스턴스의 송출·시청 연결이 `reconnect` 메시지와 닫기 코드 1012를 받고, 송출자는 다른 인스턴스에서 `resume`해 방송이 계속됨(방송 중 유지). 남은 인스턴스의 시청자는 그대로, 끊긴 시청자는 다시 입장. 종료(`end`)는 모든 인스턴스의 시청자에게 전달됨(6/6) |
| 실제 브라우저 시청 화면(데스크톱 Chromium) | 시청 화면이 붙은 인스턴스를 멈추자 화면이 스스로 다시 연결해 재입장(첫 시도 실패 뒤 재시도로 성공, 오류 표시 없음). 송출자 인스턴스를 멈추면 송출자가 `resume` 응답으로 현재 시청자 목록을 받음 |

- 이 확인은 스케일러를 거치지 않고 `docker stop`으로 인스턴스를 직접 멈춰 갑작스러운 장애를 흉내 냅니다. 그래서 멈춘 직후의 첫 다시 연결은 nginx가 아직 멈춘 인스턴스로 보내 502·504로 실패할 수 있습니다(스케일러의 스케일 인은 먼저 대상에서 빼므로 해당하지 않음). 화면은 0.5·1·2·3·5초 간격으로 다시 시도하며, 검증에서는 멈춘 뒤 약 1~4초 안에 다시 연결됐습니다.
- 처음 브라우저 확인에서 "송출자가 끊겨 있는 동안 다시 입장한 시청자를 송출자가 모르는" 문제를 찾아, `resume` 응답에 시청자 목록을 넣고 송출 화면이 이 목록으로 연결을 맞추도록 고친 뒤 다시 확인했습니다.

### 2차 검증: 동시 쓰기, Redis 장애, 자동 스케일 중 연결 (2026-11-18)

digest를 고정한 이미지와 아래 수정이 들어간 최종 이미지로 다시 확인했습니다. 위 HTTP 5개·라이브 6개 확인도 이 이미지에서 다시 모두 통과했습니다.

| 항목 | 결과 |
| --- | --- |
| 동시 댓글(H2 TCP) | 같은 방송에 댓글 20개를 동시에 보내 두 인스턴스가 나눠 처리, 20개가 한 번씩 저장됨 |
| 동시 승인 요청 | 같은 결제의 성공 주소를 10번 동시에(두 인스턴스로 나뉨) 보내도 후원은 한 번만 확정. 주문 3건 합계 3,000원 |
| Redis 장애 중 | 로그인 없는 목록은 200. 로그인 세션을 읽는 요청은 약 2.0초 뒤 공통 오류 화면(500, 내부 예외 문구 없음). 새 시청 입장은 `SERVER_ERROR` 안내를 받고 연결 유지 |
| Redis 복구 뒤 | 같은 로그인으로 두 인스턴스 모두 200(세션 유지). 다른 인스턴스 사이 Offer·Answer 전달이 다시 됨(구독 회복) |
| 자동 스케일 중 세션·방송 | 오토스케일러로 2→3→4→5→4→3→2(k6 부하 124,587 요청, 실패 0%). 3초마다 확인한 로그인 요청 105번 중 104번 200(5개 인스턴스 모두 거침), 방송은 105번 모두 목록에서 LIVE |
| 스케일 인 중 라이브 연결 | 5개로 늘었을 때 새로 생긴 인스턴스에 시청자 3명을 붙임. 그 인스턴스가 내려가자 3명 모두 `reconnect`·1012를 받고 1~3초 안에 남은 인스턴스로 다시 입장, 송출자에게서 새 Offer를 받음. 종료(`end`) 안내는 시청자 4명 모두에게 도착 |

- **찾아서 고친 문제**: 인스턴스 사이로 넘긴 메시지의 순서가 바뀌어, 스케일 인을 거친 방송을 끝낼 때 한 시청자가 종료 안내(`ended`) 없이 연결만 닫혔습니다. 중계 메시지를 한 스레드에서 받은 순서대로 전달하도록 고쳤습니다(`docs/autoscale.md` 3.2절, `RedisLiveMessageRelayOrderTests`).
- **이때 남았던 한계(아래 3차 검증에서 해결)**: 스케일 인으로 인스턴스가 빠진 직후, nginx가 아직 대상 목록에 남은 그 주소로 보낸 요청이 502로 끝날 수 있었습니다(nginx 로그 `no live upstreams`, 스케일 인 한 번마다 1~2건). nginx `max_fails=0`으로는 고쳐지지 않았습니다.
- k6 부하는 스케일 인 전에 끝나므로 k6 실패 0%에는 스케일 인 순간이 들어 있지 않습니다. 위 502는 따로 돌린 확인 스크립트가 잡았습니다.
- 다른 Docker 프로젝트의 컨테이너(시작·종료 시각 포함)·볼륨·사용자 네트워크·이미지는 검증 전후 목록이 같았습니다. Docker 기본 `bridge` 네트워크의 ID만 바뀌었는데, 검증 중 Docker Desktop VM이 다시 시작됐기 때문입니다(재시작 명령은 실행하지 않음, 그때 실행 중이던 다른 컨테이너 없음).
- 전체 로그는 [docs/autoscale-run-2026-11-18.txt](docs/autoscale-run-2026-11-18.txt) 뒤쪽 "2차 검증"에 있습니다.

### 3차 검증: 스케일 인 드레이닝으로 502 없애기 (2026-11-18)

**원인.** 옛 구성(nginx가 Docker DNS로 `app`을 5초마다 다시 조회)으로 5→2 스케일 인을 재현했습니다. Docker DNS는 내려간 인스턴스를 곧바로 뺐지만(nginx 컨테이너 안에서 0.1초 간격으로 조회), nginx는 내려간 인스턴스 주소로 12초 넘게 연결을 시도했습니다(연결 거부). `least_conn`은 연결이 0개인 그 인스턴스들을 먼저 골라, 한 요청이 다시 시도 3번을 모두 내려간 인스턴스에 쓰고 502로 끝났습니다(18,135건 중 1건). 자세한 내용은 `docs/autoscale.md` 3.1절 "스케일 인 드레이닝"에 있습니다.

**고친 방법.** 스케일러가 부하 분산 대상 목록을 직접 관리합니다(`scripts/autoscale/lb-upstream.ps1`).
- 새 인스턴스는 요청에 답한 뒤에야 목록에 넣습니다.
- 줄일 때는 내릴 인스턴스를 먼저 목록에서 빼고 nginx를 다시 읽게 한 뒤, 5초 기다렸다가 `docker stop` 합니다.
- nginx를 다시 읽어도 처리 중인 요청과 열린 WebSocket은 끊기지 않습니다.

**결과.** 모두 이 PC에서 실행했습니다. 부하 컨테이너는 CPU 2개·메모리 512MB로 제한했습니다.

| 항목 | 결과 |
| --- | --- |
| 자동 스케일 2→3→4→5→4→3→2 (기본 설정, k6 60명) | k6 요청 132,750건 실패 0%. 스케일 인 세 번 모두 `lb drain` 뒤 내림. 이 동안 nginx가 처리한 요청 135,644건 중 5xx 0건, nginx 오류 로그 0줄 |
| 같은 동안 계속 보낸 확인 요청 | 목록 요청 2,648건 모두 200(5개 인스턴스 모두 거침). 3초마다 로그인 요청 113건 모두 200, 방송은 113번 모두 LIVE |
| 같은 동안 라이브 | 스케일 아웃 때 생긴 인스턴스에 붙인 시청자 2명이 그 인스턴스가 빠질 때 `reconnect`·1012를 받고 같은 초 안에 다시 입장해 새 Offer를 받음. 종료(`end`) 안내는 시청자 3명과 송출자 모두에게 도착 |
| 부하 중 스케일 인 (CPU 약 100%에서 5→4→3→2를 강제로) | k6 요청 143,892건 실패 0%, 확인 요청 4,542건 모두 200. nginx가 처리한 요청 148,434건 중 5xx 0건, 오류 로그 0줄 |
| 공용 상태·라이브·동시 쓰기·Redis 장애 (위 1·2차와 같은 스크립트) | 17/17 통과 |
| 자동 테스트 | 276개 모두 통과(Redis 지정). Redis 없이 260개 통과·16개 건너뜀 |

- 스케일러를 거치지 않고 인스턴스가 갑자기 멈추는 경우(위 "인스턴스 종료 중 라이브" 확인처럼 `docker stop`을 직접 실행)에는 nginx의 다시 시도에 기대므로, 첫 다시 연결이 502·504로 실패할 수 있습니다.
- 다른 Docker 프로젝트의 컨테이너(시작·종료 시각 포함)·볼륨·네트워크·이미지는 검증 전후 목록이 같았습니다(기본 `bridge` 네트워크 ID 포함).
- 전체 로그는 [docs/autoscale-run-2026-11-18.txt](docs/autoscale-run-2026-11-18.txt) 뒤쪽 "3차 검증"에 있습니다.

### 정리(종료)

```powershell
docker compose -f deploy/compose.yaml --env-file deploy/.env down      # 컨테이너만 지움(DB·Redis·영상 볼륨은 남음)
docker compose -f deploy/compose.yaml --env-file deploy/.env down -v   # 클러스터 볼륨까지 지움(클러스터 데이터 삭제)
```

### 범위와 한계 (Stage 5)

- 로컬 Docker Compose 전용입니다. Kubernetes HPA, 클라우드 배포는 하지 않습니다.
- 공용 저장소(H2 TCP 서버, Redis)는 각각 컨테이너 하나이며 이중화·백업은 범위 밖입니다.
- 인스턴스 사이 시그널링 중계는 Redis Pub/Sub라 메시지를 저장하거나 다시 보내지 않습니다. 받는 인스턴스가 내려가는 중이거나 Redis가 멈춘 순간의 메시지는 사라질 수 있고, 화면의 다시 연결·재입장(`resume`, `watch`)으로 회복합니다. Redis가 멈추면 메모리로 대신하지 않고 실패합니다(로그인 필요한 요청은 2초 안에 공통 오류 화면, 시그널링은 `SERVER_ERROR` 안내). 자세한 동작은 `docs/autoscale.md` 3.2절입니다.
- 클러스터는 `127.0.0.1`의 HTTP로만 엽니다. 휴대폰 송출은 HTTPS가 필요하므로 클러스터에서 확인하지 않았습니다(**NOT_TESTED**). 휴대폰 송출은 Stage 2의 `https` 프로파일 단일 서버로 합니다.
- 실제 카메라 영상이 오가는 WebRTC 연결은 클러스터에서 확인하지 않았습니다(**NOT_TESTED**). 시그널링 메시지 전달과 다시 연결만 확인했습니다.
- 실제 토스페이먼츠 테스트 서버 호출은 하지 않았습니다(**NOT_TESTED**).

## 도메인·OCI 배포와 데스크톱 클라이언트 (Stage 6)

백엔드를 OCI Always Free VM 한 대에 배포해 `https://chzzk.lowndev.com`으로 제공하고, 같은 백엔드의 방송 목록을 보고 녹화 방송을 재생하는 Windows·macOS 앱을 내려받을 수 있게 합니다.

> **현재 상태(2026-11-22):** 소스·배포 설정·클라이언트 빌드 설정과 로컬 확인까지 끝났습니다. OCI VM 생성, DNS 연결, 공개 배포, 패키지 게시는 아직 하지 않았습니다. 그래서 아래 공개 주소는 아직 열리지 않으며, 외부 네트워크·macOS 실행 확인은 `NOT_TESTED`입니다.

### 구현 범위와 파일

| 과제 요구 | 파일 |
| --- | --- |
| 상태 확인 경로 | `global/health/HealthController.java`(`GET /health`: 정상 200 `{"status":"UP"}`, DB 연결 실패 503 `{"status":"DOWN"}`, 내부 정보 없음), `DatabaseHealthCheck.java` |
| 데스크톱용 방송 목록 | `broadcast/BroadcastApiController.java`(`GET /api/broadcasts?page=n`, 20개씩, 제목·유형·라이브 상태·재생 주소·웹 주소만. 소유자 정보 없음) |
| 배포 실행 설정 | `src/main/resources/application-deploy.yaml`(아래 "배포 프로파일") |
| OCI 배포 설정 | `deploy/oci/nginx-chzzk.conf`, `deploy/oci/chzzk.service`, `deploy/oci/chzzk.env.example`, `deploy/oci/backup.sh` |
| 데스크톱 클라이언트 | `client/`(JavaFX 25, 독립 Gradle 빌드), `.github/workflows/client-build.yml`(OS별 패키지 빌드) |
| 다운로드 페이지 | `download/DownloadController.java`, `templates/download.html`(`/download`) |

기존 방송·계정·댓글·후원·라이브 기능과 화면은 바꾸지 않았습니다. 헤더에 "앱 다운로드" 링크만 더했습니다.

### 도메인과 비용

| 항목 | 내용 |
| --- | --- |
| 도메인 | `lowndev.com` (등록: 2026-11-22, 만료: 2027-11-22, 등록기관 Cloudflare Registrar) |
| 서비스 주소 | `https://chzzk.lowndev.com` (다운로드 페이지 `https://chzzk.lowndev.com/download`) |
| 최초 등록 비용 | 미기재 — Cloudflare 결제 내역 확인 후 기재 예정 |
| 갱신 비용 | 연 $11.17 (Cloudflare 대시보드 표시 가격, 자동 갱신 꺼짐) |
| 보호 | 이전 잠금(`clientTransferProhibited`), WHOIS 등록자 정보 비공개(RDAP `DATA REDACTED`) |
| VM·인증서 비용 | 0원 (OCI Always Free 범위, Let's Encrypt 무료 인증서, Cloudflare Free 플랜) |

### DNS와 HTTPS 구성

```text
브라우저 ──https──▶ chzzk.lowndev.com (Cloudflare DNS: A 레코드, DNS only)
                        │ (Cloudflare 프록시를 거치지 않고 VM 공인 IP로 바로)
                        ▼
                  OCI VM: nginx :443 (Let's Encrypt 인증서, TLS 종료, 80은 https로 이동)
                        │ X-Forwarded-For / X-Forwarded-Proto, Host 유지
                        ▼
                  Spring Boot 127.0.0.1:8080 (deploy 프로파일, systemd)
                        │
                  /data/chzzk/db (H2 파일 DB)   /data/chzzk/uploads (영상)
```

1. **DNS**: Cloudflare 대시보드 → `lowndev.com` → DNS → 레코드 추가: 유형 `A`, 이름 `chzzk`, 값 = VM 공인 IPv4, **프록시 상태 DNS only(회색 구름)**.
   - DNS only를 고른 이유: Cloudflare Free 프록시는 요청 본문을 100MB까지만 받아, 기존 500MB 영상 업로드가 도메인으로는 실패합니다. DNS only면 브라우저가 VM의 nginx로 바로 접속해 500MB 업로드가 그대로 됩니다. 대신 VM 공인 IP가 공개되고 Cloudflare의 프록시 보호를 받지 않으므로, VM은 80·443(과 내 IP의 22)만 엽니다.
   - DNS only에서는 Cloudflare의 SSL/TLS 모드(Full (strict) 등)가 이 하위 도메인에 적용되지 않고, 인증서는 VM에서 Let's Encrypt로 받습니다.
2. **인증서**: VM에서 nginx를 잠시 멈추고 certbot standalone으로 받습니다. 갱신 때도 같은 방식이 자동으로 쓰입니다(certbot 타이머).

   ```bash
   sudo certbot certonly --standalone -d chzzk.lowndev.com --pre-hook "systemctl stop nginx" --post-hook "systemctl start nginx"
   ```

3. **nginx**: `deploy/oci/nginx-chzzk.conf` — 443 TLS(1.2·1.3), 80→443 이동, 업로드 510MB, WebSocket(`/ws/live`) 업그레이드, 영상 Range 요청 전달, `X-Forwarded-Proto`·`Host` 전달.
   - 공개 서비스 남용 방지(최소 구성): 같은 IP의 회원가입·로그인 POST는 분당 10번, 영상 업로드 POST는 시간당 20번까지(넘으면 429). 파일 하나의 크기 한도 500MB는 그대로이고, 조회(GET)는 제한하지 않습니다. 디스크 사용량은 `df -h /data`로 확인합니다.

### 배포 프로파일 (`application-deploy.yaml`)

| 설정 | 값 | 이유 |
| --- | --- | --- |
| DB·업로드 경로, DB 비밀번호 | `APP_DATA_DIR`, `APP_UPLOAD_DIR`, `CHZZK_DB_PASSWORD` (기본값 없음) | 비밀 값·경로는 VM의 환경 파일로만. 없으면 시작하지 않음 |
| `server.address` | `127.0.0.1` | 앱에 외부에서 바로 접속하지 못하게 함(nginx만 공개) |
| `server.forward-headers-strategy` | `native`, 신뢰 프록시 `127.0.0.1`만 | https 주소·로그인 이동·WebSocket 같은 출처 검사가 프록시 뒤에서 맞게 됨. 다른 주소가 보낸 `X-Forwarded-*`는 무시 |
| 세션 쿠키 | `Secure` | https에서만 전송 |
| H2 콘솔 | 끔 | 운영 DB 노출 방지 |
| 결제 | `fake` 고정 | 배포 서버도 외부 결제 서비스를 부르지 않음(④ 결정) |
| 종료 | graceful(20초) | 재시작 때 처리 중인 요청을 마침 |
| 업로드 한도 | 500MB 유지(`application.yaml`) | 기존 기능 그대로 |

프록시 뒤 동작은 `DeployProfileServerTests`가 실제 Tomcat으로 확인합니다. 같은 https 페이지의 WebSocket 연결이 전달 헤더가 있으면 101, 없으면 403이 됩니다(이 설정이 필요한 이유).

### OCI VM 선택 근거

| 항목 | 값 |
| --- | --- |
| 리전 | 홈 리전(콘솔 로그인 후 확인해 기재 예정. Always Free 리소스는 홈 리전에서만 만들 수 있음) |
| shape | `VM.Standard.A1.Flex` (Arm64, Ampere A1) |
| OCPU / 메모리 | 2 OCPU / 12 GB |
| 이미지 | Ubuntu 24.04 (aarch64), 부트 볼륨 50 GB + 데이터 블록 볼륨 약 100 GB(`/data`) |

- Oracle 공식 문서(Always Free Resources)는 A1에 월 1,500 OCPU시간·9,000 GB시간(= 2 OCPU·12 GB 상시), 블록 스토리지 합계 200 GB(부트 볼륨 포함, 최소 47 GB), 백업 5개, 아웃바운드 월 10 TB를 무료로 줍니다. 위 구성은 이 범위 안입니다.
- A1을 고른 이유: Java 서버(H2 내장, 업로드 처리)에 메모리가 충분하고, 앱 이미지·JAR가 Arm64에서 그대로 돌기 때문입니다(Temurin 25 aarch64, 공식 이미지 모두 `linux/arm64` 포함 확인). 무료 대안 `VM.Standard.E2.1.Micro`(AMD, 메모리 1 GB)는 Spring Boot에 메모리가 부족합니다.
- 재고·리전 제한: A1 생성은 재고가 없으면 "Out of host capacity"로 실패할 수 있고, 문서에 춘천(South Korea North) 리전의 A1 제약 문구가 있습니다. 생성하지 못하면 무료 대안과 이유를 여기에 기록합니다.

**계정 확인과 생성 시도 기록 (2026-11-22, 콘솔 직접 확인)**

| 항목 | 내용 |
| --- | --- |
| 계정 | Free Tier(Free Trial 진행 중). 종량제(Pay As You Go)로 바꾸지 않음 |
| 홈 리전 | Indonesia North (Batam), `ap-batam-1`, 가용성 도메인 1개(`AP-BATAM-1-AD-1`) |
| 생성 전 사용량 | 인스턴스·부트 볼륨·블록 볼륨·VCN·예약 공인 IP 모두 0, Always Free 블록 스토리지 200 GB 중 0 GB, 이번 달 비용 0 |
| 생성 화면 표시 | `VM.Standard.A1.Flex` "Always Free-eligible", Ubuntu 24.04 이미지 "Free". 예상 비용 패널은 "Boot volume $2.93/month"였고 같은 패널에 무료 제공량(tier pricing)을 반영하지 않는다고 적혀 있음 |
| 1차 시도 | A1 2 OCPU / 12 GB, 부트 50 GB → `Out of capacity for shape VM.Standard.A1.Flex in availability domain AD-1.` |
| 2차 시도 | A1 1 OCPU / 6 GB(무료 대안, 한 번만) → 같은 오류 |
| 시도 뒤 상태 | 인스턴스·VCN·부트 볼륨 모두 0(남은 리소스 없음). 재시도·다른 shape·유료 전환은 하지 않음 |

홈 리전(Batam)은 가용성 도메인이 하나라 다른 곳에서 만들 수 없습니다. A1 재고가 생기면 위 구성(2 OCPU·12 GB 우선, 안 되면 1 OCPU·6 GB)으로 다시 만들고 이 표에 결과를 더합니다. 그 전까지 공개 서버(`chzzk.lowndev.com`)는 열리지 않습니다.
- 주의: Always Free 인스턴스는 7일 동안 CPU(95번째 백분위수)·네트워크·메모리(A1)가 모두 20% 미만이면 회수될 수 있습니다(공식 문서). 종량제(Pay As You Go)로 바꾸지 않았고, Always Free 표시가 없는 리소스는 만들지 않습니다.

### 서버 설치와 배포 (재현 절차)

1. **VM 만들기**(콘솔): 홈 리전, `VM.Standard.A1.Flex` 2 OCPU·12 GB, Ubuntu 24.04 aarch64, 공인 IPv4, SSH 공개 키 등록(비밀번호 로그인 없음). 블록 볼륨 약 100 GB를 붙입니다.
2. **네트워크 보안**: VCN 보안 목록(또는 NSG) 수신 규칙은 TCP 22(내 공인 IP/32만), 80·443(0.0.0.0/0)만. Ubuntu 이미지의 iptables에도 80·443 허용을 더합니다. SSH는 키 인증만 쓰고 `PasswordAuthentication no`를 유지합니다.
3. **패키지**: Temurin 25(aarch64, Adoptium APT 저장소), nginx, certbot.

   ```bash
   sudo apt-get update && sudo apt-get install -y nginx certbot
   # Temurin 25: https://adoptium.net/installation/linux/ 의 APT 저장소 등록 후
   sudo apt-get install -y temurin-25-jdk
   ```

4. **데이터 볼륨과 사용자**: 블록 볼륨을 `/data`에 마운트(`/etc/fstab`에 UUID로 등록)하고 앱 사용자와 폴더를 만듭니다.

   ```bash
   sudo useradd --system --home /opt/chzzk --shell /usr/sbin/nologin chzzk
   sudo mkdir -p /opt/chzzk /data/chzzk/db /data/chzzk/uploads /etc/chzzk
   sudo chown -R chzzk:chzzk /opt/chzzk /data/chzzk
   ```

5. **앱 올리기**: PC에서 `.\gradlew.bat bootJar`로 만든 `build/libs/chzzk-0.0.1-SNAPSHOT.jar`를 VM의 `/opt/chzzk/chzzk.jar`로 복사합니다(`scp`). JAR는 아키텍처와 무관합니다.
6. **환경 파일**: `deploy/oci/chzzk.env.example`을 `/etc/chzzk/chzzk.env`로 복사해 값을 넣고 `sudo chmod 600 /etc/chzzk/chzzk.env`.
7. **서비스**: `deploy/oci/chzzk.service`를 `/etc/systemd/system/`에 복사 → `sudo systemctl daemon-reload && sudo systemctl enable --now chzzk` → `curl -s http://127.0.0.1:8080/health`가 `{"status":"UP"}`.
8. **인증서와 nginx**: 위 certbot 명령 → `deploy/oci/nginx-chzzk.conf`를 `/etc/nginx/sites-available/chzzk`로 복사, `sites-enabled`에 연결(기본 사이트 제거) → `sudo nginx -t && sudo systemctl reload nginx`.
9. **DNS**: Cloudflare에 `chzzk` A 레코드(DNS only) → 외부에서 `https://chzzk.lowndev.com/health` 확인.
10. **패키지 게시 후**: 환경 파일에 `APP_DOWNLOAD_WINDOWS_X64_URL`, `APP_DOWNLOAD_MACOS_ARM64_URL`을 넣고 `sudo systemctl restart chzzk`. 비어 있으면 다운로드 페이지는 "아직 게시되지 않음"으로 보입니다.

**업데이트**: 새 JAR로 바꾼 뒤 `sudo systemctl restart chzzk`. **롤백**: 이전 JAR를 `/opt/chzzk/chzzk.jar`로 되돌리고 재시작합니다.

### 백업과 복구

- `deploy/oci/backup.sh`: 서비스를 잠시 멈추고 `/data/chzzk/db`와 `uploads`를 `/data/backup/chzzk-날짜.tar.gz`로 묶은 뒤 다시 시작합니다(H2 파일을 열린 채 복사하지 않음). 최근 7개를 남깁니다. 정기 실행은 root crontab(예: 매일 새벽 4시)으로 둡니다.
- 디스크 장애 대비로 OCI 블록 볼륨 백업(Always Free 5개 이내)을 함께 씁니다.
- 복구: `sudo systemctl stop chzzk` → `/data/chzzk`에 백업을 풀기(`tar -xzf ... -C /data/chzzk`) → 소유자 `chzzk`로 맞춤 → `sudo systemctl start chzzk` → `/health` 확인.

### 데스크톱 클라이언트

- JavaFX 25 + jpackage. 왼쪽 방송 목록(이전·다음 페이지, 새로고침), 가운데 플레이어(재생·일시정지, 정지, 탐색 막대, 시간). 녹화방송만 재생하고, 라이브 방송은 "웹 화면에서 시청" 안내와 웹 주소를 보여 줍니다. 목록 요청은 화면 스레드가 아닌 배경 작업으로 하고, 네트워크·재생 오류는 화면에 안내합니다.
- 서버 주소: 기본 `https://chzzk.lowndev.com`. `--server=주소` 인자나 `CHZZK_SERVER_URL` 환경변수로 바꿉니다(로컬 확인용).
- 패키지에 Java 런타임이 들어 있어 사용자는 Java를 설치하지 않아도 됩니다. 코드 서명은 하지 않아 Windows SmartScreen·macOS Gatekeeper 안내가 뜹니다(다운로드 페이지에 우회 방법 안내).

| 대상 | 패키지 | 만드는 곳 |
| --- | --- | --- |
| Windows 10·11 x64 | `chzzk-client-windows-x64.zip` (app-image, 압축 풀고 `chzzk-client.exe`) | Windows PC 또는 Actions `windows-latest` |
| macOS 13+ Apple Silicon(arm64) | `chzzk-client-macos-arm64.dmg` | Mac 또는 Actions `macos-latest` |
| Intel Mac, Windows on ARM | 제공하지 않음 | — |

```powershell
.\gradlew.bat -p client test                    # 단위 테스트
.\gradlew.bat -p client run --args="--server=http://127.0.0.1:8080"   # 개발 실행
.\gradlew.bat -p client packageWindowsZip       # → client\build\dist\chzzk-client-windows-x64.zip
```

```bash
./gradlew -p client packageMacDmg               # macOS에서 → client/build/dist/chzzk-client-macos-arm64.dmg
```

- `--smoke-test`(선택 `--smoke-log=파일`): 화면 없이 목록 조회 → 첫 녹화방송 2초 재생 → 중간 탐색을 확인하고 결과를 남깁니다. 로컬 확인과 Actions에서 씁니다. 실제 화면 표시·소리 확인을 대신하지 않습니다.
- `.github/workflows/client-build.yml`(GitHub 무료 표준 러너 `windows-latest`, `macos-latest`)은 Windows ZIP·macOS DMG를 만든 뒤, **만든 패키지 파일 자체**를 풀거나(ZIP) 마운트해(DMG) 시스템 Java 없이 `--smoke-test`로 실행합니다. 상대 서버는 러너 안의 작은 HTTP 서버(`client/smoke/SmokeServer.java`: 목록 API 모양의 응답, 실제 H.264/AAC MP4 `client/smoke/sample-h264-aac.mp4` 10초, Range 206 지원)입니다. 확인 실패는 작업 실패가 되고, 작업 요약에 "패키지 빌드"와 "재생 확인"을 따로 적습니다. 결과는 실행의 Artifacts에만 올립니다(Releases 게시는 따로).
- 이 확인은 GitHub 러너(가상 머신)에서의 실행입니다. 실제 사용자 PC·Mac 실기기에서 화면을 보며 확인한 것과는 다르며 별도로 기록합니다.

### OS별 설치·실행 확인 절차

1. 외부 네트워크(예: 휴대폰 데이터 테더링)에서 `https://chzzk.lowndev.com/download`를 엽니다. 브라우저 주소창의 자물쇠(Let's Encrypt 인증서)를 확인합니다.
2. **Windows**: ZIP을 받아 압축을 풀고 `chzzk-client.exe` 실행 → SmartScreen이 뜨면 "추가 정보 → 실행" → 목록에 방송이 보이는지, 녹화 방송을 골라 재생·일시정지·탐색이 되는지 확인합니다.
3. **macOS(Apple Silicon)**: DMG를 열어 앱을 응용 프로그램으로 옮김 → Control-클릭 → 열기 → 같은 방법으로 목록·재생을 확인합니다.
4. 결과를 아래 표에 OS·버전·아키텍처와 함께 기록합니다.

### 확인 기록

| 항목 | 환경 | 결과 |
| --- | --- | --- |
| 백엔드 자동 테스트 | Windows 11, JDK 25 | 292개 중 276개 통과·Redis 구현 16개 건너뜀·실패 0(Redis 지정 실행은 이날 미실시, 아래 "테스트") |
| 로컬 배포 프로파일 실행 | 같은 PC, `deploy` 프로파일, `127.0.0.1:18081` | `/health` 200 UP, `127.0.0.1`에서만 수신, 실제 MP4(H.264/AAC 1280×720 20초) 업로드 → `/api/broadcasts`에 절대 재생 주소, Range 요청 206 |
| Windows 패키지(로컬 빌드) | Windows 11 x64 | `chzzk-client-windows-x64.zip`(약 36 MB) 생성. 새 폴더에 풀고 `JAVA_HOME` 없이·PATH에 Java 없이 `chzzk-client.exe --smoke-test` → 패키지 안 런타임으로 목록 조회, 재생 2.1초, 10.6초로 탐색 성공(로컬 서버) |
| Windows 앱 화면 | Windows 11 x64 | 창이 열림(제목 "치지직 - 서버 주소"). 화면 내용의 육안 확인 기록은 아직 없음 |
| Windows 패키지(로컬, 로컬 확인 서버) | Windows 11 x64 | ZIP을 새로 풀어 시스템 Java 없이 `--smoke-test` → 목록 2개, 라이브 항목 재생 불가 분류, 640×360 MP4 2.1초 재생, 5.5초 탐색 성공 |
| GitHub Actions 1차(2026-11-22, 실행 `45000450144`) | `windows-latest`(Windows Server 2025 x64), `macos-latest`(macOS 26 arm64) | 두 패키지 빌드 성공(`chzzk-client-windows-x64.zip`, `chzzk-client-macos-arm64.dmg`), 단위 테스트 통과. 두 앱 모두 패키지 안 런타임으로 실행됐지만 배포 서버가 없어 목록 조회에서 `ConnectException`(재생 확인 안 됨) |
| macOS 실기기 | — | `NOT_TESTED`(Mac 없음) |
| 공개 주소·HTTPS·외부 네트워크 다운로드 | — | `NOT_TESTED`(OCI A1 재고 부족으로 VM 미생성, 위 "생성 시도 기록") |

## 라이브 송출(②) — 휴대폰 연결과 확인 절차

휴대폰에서 송출하고 PC 등 다른 화면에서 시청합니다. Stage 3부터 송출하려면 휴대폰에서 먼저 로그인해야 하고, 시청은 로그인 없이 됩니다. 영상·음성은 WebRTC로 휴대폰에서 시청자 브라우저로 직접 전달되고, 서버는 연결 협상 메시지만 중계합니다. 자세한 설계는 [docs/live-streaming.md](docs/live-streaming.md)를 참고하세요.

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

1. 휴대폰 Chrome에서 `https://<PC IP>:8443/live/new` 에 접속합니다. 주소창에 인증서 경고가 없어야 합니다. 로그인 화면이 나오면 로그인(처음이면 회원가입)한 뒤 송출 화면으로 돌아옵니다.
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

Stage 3 코드로 기존 DB를 열면 dev 프로파일의 `ddl-auto: update`가 `member`·`comment` 테이블과 `broadcast.owner_id` 컬럼(비어 있어도 됨)을 추가합니다. 기존 방송 데이터는 지워지지 않지만 소유자가 없으므로 누구도 수정·삭제·종료할 수 없습니다. 이 경우에도 열기 전에 백업해 두세요.

Stage 4 코드로 열면 `donation` 테이블이 추가됩니다. 소유자 없는 기존 방송에는 후원할 수 없습니다.

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
- Stage 5에서 공용 저장소 규칙 테스트를 더했습니다. 라이브 방 등록부(`LiveRoomStoreContract`: 입장·정원·동시 입장·퇴장·송출자 교체·방 닫기)와 가짜 결제 장부(`FakePaymentLedgerContract`: 한 번만 승인·불일치·거절·동시 승인)는 같은 테스트를 메모리 구현과 Redis 구현에 모두 돌립니다. `LiveSignalingResumeServerTests`는 송출 이어 가기(`resume`)의 허용·거절과 인스턴스 종료 때의 `reconnect` 안내를 실제 서버로 확인합니다. `RedisLiveMessageRelayOrderTests`(Redis 필요)는 다른 인스턴스로 넘긴 메시지와 연결 닫기가 보낸 순서대로 전달되는지 확인합니다. `RedisOutageTests`는 Redis에 닿지 못할 때 방 등록부·중계·가짜 결제 장부가 짐작한 값을 돌려주지 않고 곧바로 실패하는지 Redis 없이 확인합니다.
- Redis 구현 테스트(`RedisLiveRoomStoreTests`, `RedisFakePaymentLedgerTests`, `RedisLiveMessageRelayOrderTests`)는 Redis가 있어야 하므로 환경변수 `CHZZK_TEST_REDIS_PORT`를 지정했을 때만 실행되고, 지정하지 않으면 건너뜁니다. 실행 방법:

  ```powershell
  docker run --rm -d --name chzzk-test-redis -p 127.0.0.1:16379:6379 redis:7.4-alpine@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe
  $env:CHZZK_TEST_REDIS_PORT = "16379"
  .\gradlew.bat clean test
  docker stop chzzk-test-redis
  ```

- Stage 6 기준 53개 테스트 클래스, 292개 테스트입니다. Redis 없이 실행하면 276개 통과·Redis 구현 16개 건너뜀(실패 0, 2026-11-22 확인). Stage 6에서 더한 테스트는 `HealthCheckTests`(정상 200·DB 실패 503, 내부 정보 없음), `BroadcastApiTests`(빈 목록·녹화/라이브 구분·소유자 정보 없음·잘못된 페이지·전달 헤더 미신뢰), `DeployProfileServerTests`(배포 프로파일 실제 서버: 전달 헤더로 https 주소, WebSocket 101/403, Secure 쿠키, H2 콘솔 꺼짐, 500MB·가짜 결제·루프백), `DownloadPageTests`(게시 전 링크 없음, 게시 후 OS별 링크) 16개입니다. 클라이언트 단위 테스트는 `.\gradlew.bat -p client test`(4개)입니다. 이날은 Docker Desktop이 꺼져 있어 Redis 지정 실행은 다시 하지 않았습니다.
- Stage 5 기준 46개 테스트 클래스, 276개 테스트입니다. Redis 없이 실행하면 260개 통과·Redis 구현 16개 건너뜀(실패 0), `CHZZK_TEST_REDIS_PORT`를 지정하면 276개 모두 통과(실패·건너뜀 0)합니다(2026-11-18 확인).
- Stage 4 기준 39개 테스트 클래스, 239개 테스트가 통과했습니다(실패·건너뜀 0). Stage 4 도네이션 테스트는 [Stage 4 검증 결과](#stage-4-검증-결과-2026-11-18)에 정리했습니다. 결제 서비스는 가짜 게이트웨이와 네트워크 없는 가짜 HTTP 서버만 쓰며 외부로 나가는 요청이 없습니다.
- Stage 3 기준 34개 테스트 클래스, 199개 테스트가 통과했습니다(실패·건너뜀 0). Stage 3에서 추가·변경한 주요 테스트는 다음과 같습니다.
  - `MemberAuthenticationTests`: 회원가입(BCrypt 저장·입력 검증·중복·CSRF), 로그인 성공·실패, 로그아웃과 세션 폐기, 헤더 로그인 상태
  - `SessionServerTests`(실제 서버): 로그인 전 세션 ID로는 로그인 상태가 아님(세션 고정 방어), 만료·알 수 없는 세션은 비로그인 처리, 로그아웃 뒤 세션 무효
  - `BroadcastAuthorizationTests`: 비로그인·소유자·다른 회원별 등록·수정·삭제·라이브 종료의 허용·거절, CSRF 없는 요청 거절, 삭제 시 댓글·영상 파일 정리, 소유자 없는 옛 방송
  - `CommentTests`: 댓글 작성·목록·수정·삭제의 허용·거절, 입력 검증, 없는 방송·댓글 404, 내용 HTML 이스케이프
  - `LiveSignalingServerTests`: 비로그인 연결의 방송 시작 거절(`UNAUTHENTICATED`), 시작한 회원이 소유자, 비로그인 시청 가능
  - 기존 등록·라이브 테스트는 로그인(테스트용 회원)과 CSRF 토큰을 붙여 그대로 회귀 확인합니다. 실제 서버 테스트는 로그인 화면으로 실제 폼 로그인을 해 세션 쿠키를 얻습니다(`TestHttpLogin`).
  - `VideoDeliveryTests`의 `..` 경로 요청은 Stage 3부터 Spring Security 요청 방화벽이 400으로 먼저 거절합니다(이전 404). 영상이 내려가지 않는 것은 같습니다.

## 수동 검증 절차

최신 Chrome 또는 Edge에서 확인합니다. 기존 데이터를 건드리지 않으려면 검증용 폴더를 환경변수로 지정해 실행하세요. Stage 3부터 등록·송출은 로그인이 필요하므로 먼저 <http://localhost:8080/signup> 에서 회원가입하고 로그인합니다.

1. 로그인한 상태로 <http://localhost:8080/broadcasts/new> 에서 제목과 MP4 파일을 선택해 등록합니다. 업로드 중 진행 막대와 퍼센트가 보여야 합니다.
2. 등록이 끝나면 상세 화면으로 이동하는지 확인합니다.
3. <http://localhost:8080/broadcasts> 목록에 방금 등록한 방송이 맨 위에 보이는지 확인합니다.
4. 상세 화면에서 영상이 재생되고, 재생 막대로 구간 이동이 되는지 확인합니다.
5. 서버를 종료(PowerShell에서 `Ctrl+C`)한 뒤 같은 환경변수로 다시 실행하고, 목록과 상세에 방송이 남아 있으며 영상이 재생되는지 확인합니다.
6. 라이브 송출은 [라이브 송출(②) — 휴대폰 연결과 확인 절차](#라이브-송출②--휴대폰-연결과-확인-절차)의 4단계를 따릅니다.

### 인증·인가와 댓글 직접 확인 (Stage 3)

비로그인 사용자, 방송 소유자, 다른 회원으로 나눠 확인합니다. 브라우저 두 개(또는 일반 창과 시크릿 창)를 쓰면 두 회원을 동시에 로그인해 둘 수 있습니다.

1. **회원 준비**: 창 A에서 `owner01`, 창 B에서 `viewer01`로 회원가입한 뒤 각각 로그인합니다. 틀린 비밀번호로 로그인하면 "아이디 또는 비밀번호가 올바르지 않습니다."가 보여야 합니다.
2. **비로그인 사용자**: 시크릿 창(창 C)에서 확인합니다.
   - 목록·상세·영상 재생은 됩니다.
   - 방송 등록·라이브 시작 메뉴를 누르면 로그인 화면으로 이동합니다.
   - 상세 화면의 댓글 영역에는 입력란 대신 "댓글을 쓰려면 로그인해 주세요."가 보입니다.
3. **방송 소유자**: 창 A(`owner01`)에서 방송을 등록합니다. 상세 화면에 "방송자: owner01"과 **제목 수정**·**방송 삭제** 버튼이 보이고, 제목 수정이 됩니다.
4. **다른 회원**: 창 B(`viewer01`)로 같은 방송을 엽니다. 수정·삭제 버튼이 보이지 않습니다. 주소창에 `/broadcasts/<방송 ID>/edit`를 직접 입력하면 "이 작업을 할 권한이 없습니다." 화면(403)이 나옵니다.
5. **버튼을 숨긴 것만이 아님을 확인**: 창 B의 상세 화면에서 개발자 도구(F12) → Console에 아래를 붙여 넣습니다(`<방송 ID>`를 바꿈). 창 B 자신의 세션과 CSRF 토큰으로 삭제 요청을 직접 보내는 것입니다.

   ```js
   const token = document.querySelector('input[name=_csrf]').value;
   fetch('/broadcasts/<방송 ID>/delete', {
     method: 'POST',
     headers: {'Content-Type': 'application/x-www-form-urlencoded'},
     body: '_csrf=' + encodeURIComponent(token)
   }).then(response => console.log(response.status));
   ```

   `403`이 찍히고 방송은 남아 있어야 합니다. 같은 요청의 경로를 `/edit`(본문에 `&title=...` 추가), `/end`로 바꿔도 403입니다. 창 C(비로그인)에서는 로그인 화면(`/login`)을 연 뒤 같은 코드를 실행하면 응답이 로그인 화면으로 이동합니다(`response.url`이 `/login`).
6. **댓글**: 창 B에서 댓글을 쓰고, 창 C에서 새로고침하면 그 댓글이 보입니다. 창 B에는 그 댓글의 수정·삭제 버튼이 보이고, 창 A에는 보이지 않습니다. 창 A의 Console에서 5번 코드의 경로를 `/comments/<댓글 ID>/delete`로 바꿔 보내면 `403`입니다(댓글 ID는 창 B 화면의 댓글 `id="comment-<ID>"`). 창 B에서 수정하면 "(수정됨)"이 붙고, 삭제하면 목록에서 사라집니다.
7. **라이브 송출 제어**: 휴대폰에서 로그인한 뒤 송출합니다(위 라이브 확인 절차). 다른 회원 화면에는 **방송 종료** 버튼이 보이지 않고 `/broadcasts/<ID>/end`로 직접 보내도 403입니다. 소유자의 상세 화면에서 **방송 종료**를 누르면 시청 화면에 종료가 전달됩니다.
8. **로그아웃·세션**: 헤더의 **로그아웃**을 누르면 로그인 화면으로 이동하고, 뒤로 가기로 등록 화면을 다시 열면 로그인 화면으로 이동합니다. 30분 동안 아무 요청도 하지 않으면 세션이 만료되어 같은 결과가 됩니다.
9. **삭제 정리**: 창 A에서 방송을 삭제하면 목록에서 사라지고, 그 방송의 댓글과 업로드 폴더의 영상 파일도 지워집니다. 방송 중인 라이브는 먼저 종료하라는 안내가 나옵니다.

### Stage 3 검증 결과 (2026-11-18)

| 항목 | 결과 |
| --- | --- |
| 자동 테스트 전체 | 34개 클래스, 199개 통과(실패·건너뜀 0) |
| 분리 서버 HTTP 시나리오(별도 포트·DB·업로드 폴더, `curl`) | 46/46 통과. 비로그인·소유자·다른 회원별 등록·수정·삭제·댓글 허용·거절, CSRF 없는 요청 403, XHR 401 JSON, 로그인 시 세션 ID 교체, 로그아웃 뒤 세션 무효, 알 수 없는 세션 비로그인 처리, 삭제 시 영상 파일 정리 |
| 분리 서버 실제 브라우저(데스크톱 Chrome 계열) | 14/14 통과. 회원가입·로그인·헤더 상태, 업로드 스크립트(XHR + CSRF 헤더)로 등록, 댓글 작성·수정·삭제, 송출 화면 진입, 로그아웃 뒤 접근 제한, 비로그인 상세 조회 |
| Android 실기기 라이브 송출(로그인 후) | 확인하지 않음. Stage 2의 13/13 실기기 결과는 로그인이 없던 코드 기준이며, Stage 3에서 송출 시작에 로그인이 필요해졌으므로 위 7번 절차로 다시 확인해야 함 |
| 실제 개발 DB를 Stage 3 코드로 열기 | 확인하지 않음(열기 전 백업 필요) |
