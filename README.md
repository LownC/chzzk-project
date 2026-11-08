# 치지직 (chzzk) — Stage 1 녹화 방송 등록 및 시청

프로그래머스 데브코스 치지직 서비스 구현 프로젝트입니다.
로그인 없이 녹화해 둔 MP4 영상을 방송으로 등록하고, 목록에서 방송을 골라 브라우저에서 바로 시청할 수 있습니다.

## Stage 1 구현 범위

포함

- 방송 등록: 제목과 MP4 영상 파일을 한 번에 제출, 업로드 진행률(막대와 퍼센트) 표시
- 방송 목록: 최신 등록 순, 한 페이지 10개, 페이지 번호 10개 단위 그룹
- 방송 상세: 제목, 등록 시각, 영상 플레이어
- 영상 재생: HTTP Range 요청 지원(구간 이동 가능)
- 데이터 영속성: 방송 정보는 H2 파일 DB, 영상은 업로드 폴더에 저장되어 서버를 재시작해도 유지
- 공통 헤더, 404·500 오류 화면

제외

- 인증·인가, 라이브 송출, 댓글, 후원
- 방송 수정·삭제, 썸네일
- 업로드 취소, 파일 시그니처·코덱 검사, 트랜스코딩

## 주요 기능

| 기능 | 경로 | 설명 |
| --- | --- | --- |
| 방송 목록 | `GET /broadcasts?page=N` | 페이지 번호는 1부터. 잘못된 번호는 1페이지로 처리. `/`는 목록으로 이동 |
| 방송 등록 화면 | `GET /broadcasts/new` | 제목 입력란, 영상 파일 선택란, 진행 막대와 퍼센트 |
| 방송 등록 | `POST /broadcasts` | `multipart/form-data`. 성공하면 상세 화면으로 이동 |
| 방송 상세 | `GET /broadcasts/{id}` | 없는 방송·잘못된 ID는 404 화면 |
| 영상 재생 | `GET /videos/{파일명}` | 업로드 폴더의 영상을 Range 요청과 함께 제공 |

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
| 화면 | Thymeleaf(서버 렌더링), JavaScript(업로드 진행률) |
| 데이터 | Spring Data JPA, H2 Database(개발: 파일 DB, 테스트: 인메모리 DB) |
| 검증 | Spring Boot Validation |
| 빌드 | Gradle(Wrapper 9.8.2), Kotlin DSL(`build.gradle.kts`) |
| 테스트 | JUnit 5, Spring Boot Test(MockMvc) |

- 라이브러리 버전은 Spring Boot 의존성 관리를 따릅니다. Lombok은 사용하지 않습니다.

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

## 저장 디렉터리 구조

소스 저장소, 영상 업로드 폴더, H2 데이터 폴더는 서로 분리되어 있습니다. 기본값 기준으로 영상과 DB는 Git 저장소 밖에 저장되므로 커밋 대상이 아닙니다.

```text
chzzk_project/
├── chzzk/                      # 소스 저장소(Git)
├── chzzk_uploads/
│   └── broadcasts/             # 업로드 영상 (APP_UPLOAD_DIR), 파일명은 서버가 만든 UUID.mp4
└── chzzk_data/
    └── chzzk_db.mv.db          # H2 파일 DB (APP_DATA_DIR)
```

## 테스트

```powershell
.\gradlew.bat clean test
```

- 테스트는 `test` 프로파일로 실행됩니다(빌드 설정에서 지정).
- DB는 인메모리 H2(`jdbc:h2:mem:testdb`)를 쓰고, 업로드 폴더는 테스트마다 만든 임시 폴더를 씁니다.
- 따라서 테스트를 실행해도 개발용 H2 파일 DB와 업로드 폴더는 바뀌지 않습니다.

## 수동 검증 절차

최신 Chrome 또는 Edge에서 확인합니다. 기존 데이터를 건드리지 않으려면 검증용 폴더를 환경변수로 지정해 실행하세요.

1. 애플리케이션을 실행하고 <http://localhost:8080/broadcasts/new> 에서 제목과 MP4 파일을 선택해 등록합니다. 업로드 중 진행 막대와 퍼센트가 보여야 합니다.
2. 등록이 끝나면 상세 화면으로 이동하는지 확인합니다.
3. <http://localhost:8080/broadcasts> 목록에 방금 등록한 방송이 맨 위에 보이는지 확인합니다.
4. 상세 화면에서 영상이 재생되고, 재생 막대로 구간 이동이 되는지 확인합니다.
5. 서버를 종료(PowerShell에서 `Ctrl+C`)한 뒤 같은 환경변수로 다시 실행하고, 목록과 상세에 방송이 남아 있으며 영상이 재생되는지 확인합니다.
