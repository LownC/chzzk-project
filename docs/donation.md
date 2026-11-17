# Stage 4 — 도네이션 설계

치지직 만들기 ④(도네이션)의 설계 문서다. ①~③의 설계는 바꾸지 않고, 그 위에 더한 내용만 적는다. 실행·확인 절차는 README "도네이션 (Stage 4)"에 있다.

## 1. 요구사항과 대응

| 과제 요구 | 구현 |
| --- | --- |
| 로그인한 사용자가 후원할 방송자와 금액을 선택 | 방송 상세의 후원 폼 → `DonationController#prepare` → `DonationService#prepare`(수신 대상 = 방송 소유자, 금액 1,000~1,000,000원) |
| 결제 서비스의 테스트·샌드박스 모드로 결제 요청과 승인 확인 흐름 | `donation-checkout.html` + `static/js/donation-checkout.js`(토스 결제창 SDK v2 `requestPayment`), `payment/TossPaymentsGateway`(승인 `POST /v1/payments/confirm`, 조회 `GET /v1/payments/{paymentKey}`), 테스트 키만 허용(`PaymentConfig`) |
| 서버에서 결제 승인과 금액·수신 대상을 확인한 뒤 확정 | `DonationService#confirm`: 주문 소유자 → 금액 → 수신 대상 → 승인 요청 → 승인 결과(상태·주문번호·금액·결제 키) 검증 → `DONE` |
| 같은 승인 통지가 반복돼도 중복 적립하지 않음 | 주문번호·결제 키 유일 제약 + 조건부 상태 변경(`DonationRepository#claim`·`complete`), 승인 통지 `PaymentWebhookController` → `DonationService#handleApprovalNotice` |
| 방송별 후원 내역과 합계 표시 | `DonationRepository#findByBroadcastIdAndStatusOrderByApprovedAtDescIdDesc`·`sumAmount`, `fragments/broadcast-extras.html`의 `donations` 조각 |

## 2. 결제 게이트웨이 분리

```text
DonationService ──▶ PaymentGateway (interface: name, confirm, find)
                        ├── TossPaymentsGateway  : 토스페이먼츠 테스트 모드. APP_PAYMENT_GATEWAY=toss + test_ 키일 때만 생성
                        └── FakePaymentGateway   : 기본값. 외부 호출 없음. authorize(인증)·confirm(승인)·find(조회)를 흉내 냄
```

- 키는 환경변수(`TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`)로만 받는다. `test_`로 시작하지 않으면 애플리케이션이 시작되지 않는다. 오류 메시지·`toString`에 키 값을 넣지 않는다.
- 시크릿 키는 서버에서만 쓰고(Basic 인증), 화면에는 클라이언트 키만 내려보낸다.
- 승인 요청에는 주문번호를 멱등 키(`Idempotency-Key`)로 붙여, 같은 주문의 재시도가 결제 서비스에서도 한 번으로 묶이게 한다.
- **검증 범위**: 자동 테스트·분리 서버는 `FakePaymentGateway`만 썼다. `TossPaymentsGateway`는 `MockRestServiceServer`(네트워크 없음)로 요청 형식과 응답·오류 해석만 확인했다. 실제 토스 테스트 서버 호출은 NOT_TESTED다.

## 3. 상태와 정합성

```text
READY ──claim (조건: status=READY)──▶ IN_PROGRESS ──complete (조건: status=IN_PROGRESS, paymentKey 일치)──▶ DONE
  │                                        └──finish (승인 거절·승인 결과 불일치)──▶ FAILED
  ├──finish (결제창 실패·금액 불일치·수신 대상 불일치·결제 키 중복)──▶ FAILED
  └──finish (결제창 취소 PAY_PROCESS_CANCELED)──▶ CANCELED
```

- 모든 상태 변경은 "현재 상태가 기대한 값일 때만" 바꾸는 조건부 UPDATE다. 바뀐 행이 0이면 다른 요청이 먼저 처리한 것이다.
  - 동시 요청: 같은 주문의 승인 요청이 여럿 와도 `claim`이 1을 돌려주는 요청 하나만 결제 서비스에 승인을 요청한다. 나머지는 현재 상태(`IN_PROGRESS`·`DONE`)를 돌려준다.
  - 반복 요청: `DONE`인 주문의 성공 주소·승인 통지는 아무것도 바꾸지 않는다.
- `order_id`, `payment_key`는 DB 유일 제약이다. 이미 다른 주문에 쓰인 결제 키로 승인을 맡으려 하면 제약 위반으로 `FAILED`(`DUPLICATE_PAYMENT_KEY`)가 된다.
- 결제 서비스 호출은 DB 트랜잭션 밖에서 한다(외부 호출 동안 DB 잠금을 잡지 않음). 그래서 승인 직후 서버가 멈추면 주문이 `IN_PROGRESS`로 남을 수 있고, 이후 승인 통지가 오면 결제 서비스 조회로 확인해 `DONE`으로 마무리한다.
- `@Transactional`만으로 중복을 막는다고 보지 않는다. 동시성은 조건부 UPDATE와 유일 제약으로 막고, 8개 스레드 동시 승인 테스트로 확인했다.

## 4. 승인 통지(웹훅)

- `POST /payments/toss/webhook`은 서버 간 요청이라 로그인·CSRF 검사 예외다(이 경로만).
- 본문은 주문번호·결제 키를 찾는 데만 쓴다. 승인 여부와 금액은 결제 서비스에 결제 키로 다시 조회한 결과로 판단한다. 따라서 위조된 통지(없는 결제 키, 승인되지 않은 결제, 다른 주문의 결제)는 확정되지 않는다(`IGNORED`).
- 응답: `CONFIRMED`(이번 통지로 확정), `ALREADY_DONE`(이미 확정, 반복 통지), `IGNORED`(확정하지 않음), 형식 오류는 400.

## 5. 권한과 공개 범위

| 대상 | 비로그인 | 주문한 회원 | 다른 회원 | 방송자 |
| --- | --- | --- | --- | --- |
| 방송별 후원 내역·합계 | 보기 | 보기 | 보기 | 보기 |
| 후원 주문 만들기 | 로그인 화면 | 가능 | 가능(본인 주문) | 자기 방송은 불가 |
| 결제 화면·성공/실패 주소·결과 | 로그인 화면 | 가능 | 403 | 403(남의 주문) |
| 승인 통지 | 결제 서비스(인증 대신 결제 서비스 재조회) | | | |

## 6. 한계

- 실제 토스페이먼츠 테스트 서버와의 결제창·승인 API 호출은 실행하지 않았다(NOT_TESTED). 사용자가 테스트 키로 직접 확인해야 한다(README 절차).
- 결제 취소·환불 API는 없다. 승인 결과가 주문과 다르면 확정하지 않고 `FAILED`로 기록하며 서버 로그에 "결제 서비스에서 확인 필요"로 남긴다(실제 결제였다면 수동 취소가 필요한 상황).
- 토스 웹훅 서명 검증 대신 결제 서비스 재조회로 진위를 확인한다. 로컬 PC는 외부에서 접근할 주소가 없으면 웹훅을 받을 수 없다.
- 실시간 후원 알림(방송 화면 팝업)은 과제 요구가 아니라 구현하지 않았다. 내역은 새로고침해야 갱신된다.
