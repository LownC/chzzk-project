# 치지직 프로젝트 (chzzk)

프로그래머스 데브코스 치지직 서비스 구현 프로젝트.

## 프로젝트 개요

- **목적**: Spring Boot 기반 녹화 방송 등록·시청, 휴대폰 라이브 송출·시청, 회원 인증·인가와 댓글, 도네이션 서비스
- **현재 구현 단계**: 6단계 — 도메인·OCI 배포와 Windows/macOS 클라이언트(1~5단계 위에 추가). 범위·절차·확인 기록은 README "도메인·OCI 배포와 데스크톱 클라이언트 (Stage 6)". 5단계 설계는 `docs/autoscale.md`, 실행·확인 절차와 관찰 결과는 README "로컬 오토스케일 (Stage 5)"를 본다. 4단계는 `docs/donation.md`, 3단계 정책은 README "인증·인가 정책", 라이브 변경은 `docs/live-streaming.md` 9절.
- **기술 스택**: Java 25, Spring Boot 4.1.2, Gradle Kotlin DSL, Spring MVC, Spring WebSocket, Spring Security(세션 폼 로그인·BCrypt·CSRF), Thymeleaf, 브라우저 WebRTC API, Spring Data JPA, H2(2.5.252 고정, README "H2 버전 고정"), 5단계 cluster 프로파일의 Spring Data Redis·Spring Session Data Redis, Docker Compose·nginx·k6
- **기본 패키지**: `com.programmers.chzzk`
- **빌드/테스트**: `./gradlew build`, `./gradlew test` (Windows: `gradlew.bat`)

Spring Boot 4.x는 3.x와 starter 이름과 테스트 모듈 구성이 다르다(예: `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`). 3.x 기준 예제를 옮길 때는 4.x 문서로 확인한다.

## 개발 원칙

- 현재 과제의 요구사항만 구현한다.
- 요구되지 않은 기능을 임의로 추가하지 않는다.
- 불필요한 추상화와 복잡한 설계를 피한다.
- 기존 프로젝트 구조와 코딩 스타일을 우선 존중한다.
- Lombok을 사용하지 않는다.
- 테스트 가능한 구조로 구현한다.
- 중요한 설계 변경은 먼저 사용자에게 설명하고 승인을 받는다.

## 구현 범위

아래 기능만 구현한다.

### 1단계 — 녹화 방송

- 방송 등록
- MP4 파일 업로드
- 방송 목록 조회
- 방송 상세 조회
- 동영상 재생
- 데이터 영속성

### 2단계 — 인증·인가 없는 휴대폰 라이브 송출

- 휴대폰 브라우저에서 라이브 방송 시작·종료(WebRTC, `/live/new`)
- 방송 목록에서 라이브 방송 시청(방송당 시청자 최대 4명)
- 방송 상태 표시(목록 배지, 시청 화면의 방송 중·방송 종료)
- 송출 연결 끊김·생존 신호 만료 시 방송 종료와 시청 화면 알림
- Android 카메라 사용을 위한 개발용 HTTPS 프로파일

라이브 연결의 검증 범위는 다음과 같다.

- 같은 Wi-Fi 환경의 Android → PC 실기기 검증은 통과했다.
- 서로 다른 네트워크 간 WebRTC 연결은 실기기로 검증하지 않았다.
- STUN/TURN 서버 URL은 `APP_LIVE_ICE_SERVERS`로 지정할 수 있지만 기본값은 비어 있고 TURN 인증 정보 설정은 없다. TURN 서버를 이용한 연결 보장은 현재 구현 범위에 포함되지 않는다.

### 3단계 — 인증·인가와 댓글

- 회원가입·로그인·로그아웃(Spring Security 세션 방식, BCrypt, CSRF, 로그인 시 세션 ID 교체)
- 방송 소유자 구분과 서버 권한 검사: 등록·송출은 로그인, 제목 수정·삭제·라이브 종료는 소유자만
- 방송별 댓글 작성(로그인)·목록 조회(공개)·수정·삭제(작성자만)
- 방송 목록·상세·영상 재생·라이브 시청·댓글 목록은 공개

권한 검사는 화면 버튼이 아니라 서버(서비스·시그널링 처리기)에서 회원 ID로 한다. CSRF 보호를 끄거나 모든 경로를 공개로 바꿔 테스트를 통과시키지 않는다.

### 4단계 — 도네이션

- 로그인 회원이 방송자(방송 소유자)와 금액을 골라 후원 주문을 만든다(자기 방송·소유자 없는 방송 불가)
- 토스페이먼츠 테스트 모드의 결제 요청·승인 확인 흐름. 결제 게이트웨이는 인터페이스로 분리하고 기본값은 외부 호출 없는 가짜 게이트웨이
- 서버가 주문 소유자·금액·수신 대상과 승인 결과를 확인한 뒤에만 확정, 주문번호·결제 키 유일 제약과 조건부 상태 변경으로 중복 확정 방지
- 방송별 후원 내역과 합계 표시

결제 관련 제한(사용자 결정):

- 실제 결제·금전 이동·라이브 키 사용, 토스페이먼츠 서버로의 외부 HTTP 요청은 하지 않는다. 자동 테스트와 검증은 가짜 게이트웨이로 한다.
- 실제 토스 테스트(샌드박스) 서버 호출은 NOT_TESTED로 기록하고, 실행하지 않은 검증을 통과로 보고하지 않는다.
- 결제 키는 환경변수로만 받고 코드·로그·저장소에 넣지 않는다. 테스트 키(`test_`)가 아니면 시작하지 않는다.

### 5단계 — 로컬 오토스케일

- 서버 컨테이너 이미지(`Dockerfile`)와 Docker Compose 다중 인스턴스(`deploy/compose.yaml`), nginx 부하 분산기
- 자체 스케일러(`scripts/autoscale/autoscaler.ps1`): CPU 사용률로 2~5개 사이 자동 스케일 아웃·인
- 공용 저장소: 로그인 세션·라이브 방 등록부·시그널링 중계·가짜 결제 장부는 Redis, 방송·회원·댓글·후원은 H2 TCP 서버, 업로드 영상은 공용 볼륨. `app.cluster.enabled`(cluster 프로파일)일 때만 Redis를 쓰고 기본 실행·테스트는 그대로다
- k6 부하 스크립트(`deploy/load/k6-load.js`), README에 관찰한 인스턴스 수 변화 기록
- 클라우드 배포는 하지 않는다. 결제는 계속 가짜 게이트웨이만 쓴다

### 6단계 — 도메인·OCI 배포와 Windows/macOS 클라이언트

- 백엔드: `GET /health`(DB 확인, 200/503), `GET /api/broadcasts`(데스크톱용 읽기 전용 목록), `application-deploy.yaml`(127.0.0.1 수신, 로컬 nginx만 신뢰하는 전달 헤더, Secure 쿠키, H2 콘솔 끔, 가짜 결제 고정, 500MB 업로드 유지), `/download` 페이지
- 배포 설정 `deploy/oci/`(nginx + Let's Encrypt, systemd, 환경 파일 예시, 백업). Cloudflare DNS는 DNS only(프록시 100MB 제한 때문에, 사용자 결정)
- 데스크톱 클라이언트 `client/`(JavaFX + jpackage, 독립 Gradle 빌드). Windows x64 ZIP, macOS arm64 DMG
- 사용자 결정: OCI VM 생성, Cloudflare DNS 변경, push, GitHub Releases 게시, 외부 배포는 별도 승인 후에만 한다. macOS 실행은 실제 Mac에서 확인하기 전까지 성공으로 기록하지 않는다

썸네일, 라이브 녹화 저장, 결제 취소·환불·정산, 실시간 후원 알림, 회원정보 수정·탈퇴, 비밀번호 찾기, 관리자 권한, 실시간 댓글 기능은 구현하지 않는다.

## 작업 규칙

- 작업 전에 관련 코드를 확인한다.
- 구현 전에 작업 계획을 제시한다.
- 한 번에 지나치게 많은 파일을 수정하지 않는다.
- 기능 구현 후 관련 테스트를 실행한다.
- 테스트 실패 시 원인을 분석하고 수정한다.
- 사용자가 요청하지 않은 의존성을 추가하지 않는다. (3단계에서 Spring Security와 그 테스트 의존성, 5단계에서 `spring-boot-starter-data-redis`·`spring-session-data-redis`만 사용자 승인을 받아 추가했다. 5단계 컨테이너 이미지 eclipse-temurin·nginx·redis·grafana/k6 사용도 승인받았다. 6단계에서 클라이언트(`client/`)의 OpenJFX와 jackson-jr, JUnit만 승인받아 추가했다.)
- 환경 변수와 파일 경로를 임의로 변경하지 않는다.
- 실제 영상 파일, H2 데이터베이스, 그 백업을 삭제하지 않는다.

### 로컬 데이터 위치

환경변수로 지정하고, 지정하지 않으면 설정 파일의 기본값(작성자 개발 PC 기준 절대 경로, 저장소 밖)을 쓴다. 이 경로와 안의 파일을 임의로 변경하거나 삭제하지 않는다.

- H2 파일 DB: `application-dev.yaml`의 `spring.datasource.url` = `jdbc:h2:file:${APP_DATA_DIR}/chzzk_db;MODE=MySQL`. `APP_DATA_DIR` 기본값은 `C:/Users/Wonseok/Desktop/Dev/programmers/chzzk_project/chzzk_data`
- 업로드 영상: `application.yaml`의 `app.file.upload-dir` = `${APP_UPLOAD_DIR}`. 기본값은 `C:/Users/Wonseok/Desktop/Dev/programmers/chzzk_project/chzzk_uploads/broadcasts`
- 테스트(test 프로파일)는 인메모리 H2와 임시 폴더를 쓰며 위 경로를 사용하지 않는다.
- 개발 DB 백업: `chzzk_data` 옆(같은 상위 폴더)의 `chzzk_data_backup_h2-2.4.240_20261117`(H2 2.4.240 시점 원본). 변경하거나 삭제하지 않는다.
- 개발용 HTTPS 인증서·키스토어: `scripts/dev-https/new-dev-certificate.ps1`이 저장소 밖(기본 `chzzk_project/chzzk_certs`)에 만든다. 경로와 비밀번호는 `DEV_SSL_KEY_STORE`, `DEV_SSL_KEY_STORE_PASSWORD` 환경변수로만 넘기고, 인증서·키·비밀번호를 저장소에 넣지 않는다. 임의로 다시 만들거나 삭제하지 않는다.

## Git 안전 규칙

- 사용자 승인 없이 commit하지 않는다.
- 사용자 승인 없이 push하지 않는다.
- 사용자 승인 없이 merge·tag하지 않는다.
- 기존 변경 사항을 임의로 되돌리지 않는다.
- `reset --hard`, `clean -fd`, 강제 체크아웃 등 데이터 손실 가능성이 있는 명령을 실행하지 않는다.
- `.gitignore`에 필요한 파일이 누락되어 있다면 수정안을 제안한다.

## 코드 품질

- 의미 있는 클래스 및 메서드 이름을 사용한다.
- 책임이 명확한 구조를 유지한다.
- 예외를 무분별하게 삼키지 않는다.
- 사용자에게 내부 예외 메시지를 노출하지 않는다.
- 테스트와 로그를 통해 문제를 추적할 수 있도록 한다.

## Agent skills

### Issue tracker

이슈와 스펙은 `.scratch/<feature>/` 아래 로컬 markdown 파일로 관리한다. `.scratch/`는 `.gitignore`로 제외한 로컬 작업 폴더라 공개 저장소에는 없다. See `docs/agents/issue-tracker.md`.

### Triage labels

기본 5개 트리아지 역할(`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`)을 그대로 사용한다. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: 루트의 `GLOSSARY.md`와 `docs/adr/`. See `docs/agents/domain.md`.
