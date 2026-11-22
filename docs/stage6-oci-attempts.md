# Stage 6 — OCI 무료 VM 생성 시도 기록 (2026-11-22)

치지직 ⑥ 과제의 OCI Always Free VM 생성 시도와 결과를 남긴 증빙이다. 모든 값은 OCI 콘솔에서 직접 확인했다. 계정 식별자(OCID 등)와 개인 정보는 적지 않았다.

## 1. 계정과 사용량 (생성 전, 콘솔 읽기 전용 확인)

| 항목 | 값 |
| --- | --- |
| 계정 유형 | Free Tier, Free Trial 진행 중 (Pay As You Go 아님, 바꾸지 않음) |
| 홈 리전 | Indonesia North (Batam), `ap-batam-1` |
| 가용성 도메인 | 1개 (`AP-BATAM-1-AD-1`) |
| A1 서비스 한도 | Standard.A1 코어 16 / 사용 0, 메모리 96 GB / 사용 0 (서비스 한도이며 무료 제공량은 2 OCPU·12 GB) |
| Always Free 블록 스토리지 | 200 GB / 사용 0 GB |
| 기존 리소스 | 인스턴스 0, 부트 볼륨 0, 블록 볼륨 0, VCN 0, 예약 공인 IPv4 0 |
| 비용 | 2026-11-01 ~ 11-22 비용 없음 (Cost Analysis) |

## 2. 생성 화면에서 확인한 값 (두 시도 공통)

| 항목 | 값 |
| --- | --- |
| 이름 / 배치 | `chzzk-stage6` / AD-1, on-demand |
| 이미지 | Canonical Ubuntu 24.04, 빌드 2026.11.05-0, 가격 표시 "Free" |
| Shape | `VM.Standard.A1.Flex`, 표시 "Always Free-eligible" |
| 네트워크 | 새 VCN·새 퍼블릭 서브넷(10.0.0.0/24), SSH 공개 키(이 VM 전용, 개인 키는 사용자 PC에만) |
| 부트 볼륨 | 50 GB, 10 VPU(기본) |
| 예상 비용 패널 | "Boot volume $2.93/month, Estimated total $2.93/month". 같은 패널에 "does not reflect any tier unit pricing"(무료 제공량 미반영)이라고 적혀 있음. 사용자가 이 표시를 확인한 뒤 조건부로 생성을 승인함 |

## 3. 시도와 결과

| # | 시각(KST, 약) | 사양 | 결과 |
| --- | --- | --- | --- |
| 1 | 17:02 | A1 2 OCPU / 12 GB | 실패: `API Error — Out of capacity for shape VM.Standard.A1.Flex in availability domain AD-1. Create the instance in a different availability domain or try again later.` |
| 2 | 17:11 | A1 1 OCPU / 6 GB (사용자 승인 무료 대안, 한 번만) | 실패: 위와 같은 오류 |

- 두 시도 모두 재시도하지 않았고, 다른 shape·유료 전환은 하지 않았다.
- 시도 직후 확인: 인스턴스 0, VCN 0, 부트 볼륨 0(남은 리소스 없음). 생성 마법사의 새 VCN도 만들어지지 않았다.
- 홈 리전의 가용성 도메인이 하나뿐이라 다른 가용성 도메인으로 옮길 수 없다.

## 4. E2.1.Micro 대안 확인 (사용자가 생성 1회를 조건부 승인, 생성 화면까지 진행)

| 확인 | 결과 |
| --- | --- |
| 이미지 | Canonical Ubuntu 24.04 Minimal(x86-64), 빌드 2026.11.05-0, 가격 표시 "Free" |
| Shape 화면 "Specialty and previous generation"(E2.1.Micro가 있는 분류) | "No items to display"(기다린 뒤 다시 확인해도 같음) |
| Shape 화면 "AMD" | `VM.Standard.E5.Flex`만 표시(Always Free 표시 없음, 유료) |
| 이미지의 "호환 shape" 목록 | `BM.Standard.E5.192`, `VM.Standard3.Flex`, `VM.Standard.E5.Flex` 세 가지뿐(모두 유료) |
| 결론 | 홈 리전(Batam)에서는 `VM.Standard.E2.1.Micro`를 고를 수 없음. 콘솔 한도 표의 E2 Micro 항목 "Deprecated" 표시와도 맞음 |
| 조치 | Create를 누르지 않음(생성 요청 0회). 이미지를 바꾸자 콘솔이 자동으로 고른 유료 shape(E5.Flex)도 쓰지 않음 |
| 확인 뒤 상태 | 인스턴스 0, VCN 0, 부트 볼륨 0 |

## 5. 이후 선택지 (사용자 결정 대기)

- A1 재고가 생길 때 다시 시도(2 OCPU·12 GB 우선).
- 무료 대안 `VM.Standard.E2.1.Micro`(AMD x86-64, 1/8 OCPU 버스트, 메모리 1 GB, 인터넷 대역폭 최대 50 Mbps): 적합성 검토는 README "E2.1.Micro 대안 검토"에 있다. 생성은 아직 승인되지 않았다.
