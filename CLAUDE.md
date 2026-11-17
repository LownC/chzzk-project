# 치지직 프로젝트 (chzzk)

프로그래머스 데브코스 치지직 서비스 구현 프로젝트.

## 프로젝트 개요

- **목적**: Spring Boot 기반 녹화 방송 등록·시청과 휴대폰 라이브 송출·시청 서비스
- **현재 구현 단계**: 2단계 — 인증·인가 없는 휴대폰 라이브 송출(1단계 녹화 방송 위에 추가). 검증 결과와 알려진 위험은 `docs/live-streaming.md`를 본다.
- **기술 스택**: Java 25, Spring Boot 4.1.2, Gradle Kotlin DSL, Spring MVC, Spring WebSocket, Thymeleaf, 브라우저 WebRTC API, Spring Data JPA, H2(2.5.252 고정, README "H2 버전 고정")
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

인증·인가, 댓글, 후원, 방송 수정·삭제, 썸네일, 라이브 녹화 저장 기능은 구현하지 않는다.

## 작업 규칙

- 작업 전에 관련 코드를 확인한다.
- 구현 전에 작업 계획을 제시한다.
- 한 번에 지나치게 많은 파일을 수정하지 않는다.
- 기능 구현 후 관련 테스트를 실행한다.
- 테스트 실패 시 원인을 분석하고 수정한다.
- 사용자가 요청하지 않은 의존성을 추가하지 않는다.
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
