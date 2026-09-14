# 피부 보정 검증

Status: 구현 시 수행할 검증. 현재 결과/성능 측정치는 없다.
Related: [product](skin_retouch.md), [pipeline](skin_retouch_pipeline.md)

## 1. 완료 판정

일반 테스트와 실제 품질은 별도 통과 조건이다. fake/버튼/전체 얼굴 blur만으로 완료를 선언하지 않는다. 네 기능 각각 통과가 필요하다. 수동 정답 mask로 복원한 결과는 자동 보정 전체 경로의 증거가 아니다.

### 1.1 평가 순서와 단계별 완료

1. **SR1-A, 복원 분리 평가:** 동일한 수동 정답 결함 mask/원본 context patch로 MI-GAN FFHQ 256, Places2 512, OpenCV 기준 구현을 비교한다. 모델 crop/resize 차이를 기록하고 결함 크기별 질감/경계 품질을 본다. 정답 mask는 평가 전용이며 제품의 수동 도구를 의미하지 않는다.
2. **SR1-B, 자동 잡티 보정:** 실제 검출기를 연결한다. 검출 정밀도/재현율, 점·주근깨 오검출, 복원 후 품질을 분리 기록한다. 검출 누락과 복원 실패를 한 점수에 숨기지 않는다. NoChange가 많은 모델은 양성 재현율로 드러나야 한다. §3의 잡티 gate 통과 후 잡티의 SR2/SR3를 진행할 수 있다.
3. **SR1-C, 다른 기능과 조합:** 유분광/다크서클의 색조 보정과 면도자국 전용 경로를 평가한다. 잡티 성공으로 다른 세 기능을 통과 처리하지 않는다. 네 기능의 독립성과 overlap을 검증한다.
4. **StyleRetoucher 선택 비교:** 실행 가능한 코드/가중치/사용 조건을 확보한 경우에만 같은 세트로 비교한다. 미확보는 기록하고 MI-GAN 실험은 계속한다. 논문 수치와 우리 MI-GAN 실측을 직접 우열 비교하지 않는다. attention이나 전후 차이를 네 종류의 정답 mask로 간주하지 않는다.

SR1-A 성공은 복원 가능성, SR1-B 성공은 자동 잡티 기능의 진입 조건, SR1-C와 SR4 성공은 네 기능 전체 완료 조건이다. 미완료 기능은 비활성으로 남기며 결과 인계에 단계별 상태를 명시한다.

## 2. 데이터와 기록

사용 권한이 있는 최소 24장 세트를 고정한다. 기능별 양성 6장 이상과 음성 6장 이상이며 한 사진이 여러 항목을 커버할 수 있다.

밝은/중간/어두운 피부톤, 피부 질감, 점/주근깨, 안경/메이크업, 기른 수염, 정면/기울기/옆얼굴, 작은 얼굴/가림/얼굴 없음, 여러 사람, JPEG/저조도/강한 조명을 포함한다. 피부톤/성별을 자동 추론해 기본 강도를 다르게 설정하지 않는다.

각 기능 0/25/50/75/100과 조합으로 평가한다. 원본/결과, 변경/protection mask, 모델 정보, 명령, 기기/서버, 지연/메모리를 기록한다. 원본 무단 커밋 금지, fixture 출처/허용 범위 기록, 외부 전송 금지 데이터의 서버 평가 금지다.

work/retouch_evaluation.md에 다음 표와 재현 명령을 남긴다.

| 기능 | 후보/버전 | 위치 | 양성 성공/전체 | 음성 보존/전체 | 심각한 변형 | p50/p95 | peak memory | 판정 |
|---|---|---|---|---|---|---|---|---|

실행하지 않은 값은 미측정이다. 가중치 확보, 코드/가중치 라이선스, Android 배포 크기도 기록한다.

MI-GAN은 수동/자동 mask 결과를 별도 행으로 기록하고 검출기 버전, 평가 도구 commit, checkpoint 해시, ONNX raw model/wrapper 구분, 패치 크기/context/병합 규칙도 남긴다. SR1-B의 검출 평가는 권한 있는 수동 주석과 비교하며 픽셀 또는 결함 단위 중 사용한 정의와 matching 기준을 기록한다.

## 3. 품질

50 강도의 얼굴 crop을 100% 확대해 사진별 pass/fail과 사유를 기록한다.

- 잡티·여드름: 대상 감소, 모공/정상 피부/점·주근깨 유지.
- 유분광: 반사 감소, 회색 얼룩/평면화/얼굴 전체 어두워짐 없음.
- 다크서클: 색조 완화, 눈/속눈썹/메이크업/구조/정상 음영 유지.
- 면도자국: 짧은 자국/색조 감소, 기른 수염/입술/턱선 유지.
- 공통: 정체성/기하/피부톤 변경, wax-like blur, halo, 격자/경계 흔적, 조합 과보정 없음.

초기 출시 gate: 기능별 양성 80% 이상(6개면 5개 이상), 음성 전부 보존, 정체성/눈·입/타인 등 심각한 변형 0건. 100 강도/조합에서도 심각한 변형은 허용하지 않는다. 작은 세트로 전체 사용자 품질을 보장하지 않으며 피부톤/조명별 실패도 보고한다.

0은 정확한 no-op이며 보호 영역/타인/배경은 보정 op 전후 동일 픽셀이다. 손실 인코딩 전 canonical working 크기에서 검사하고 다른 조정/JPEG 차이는 제외한다. 기하/threshold로 점·여드름·수염 구별에 실패하면 후보 미통과다. 지원 제한 때문에 요청 기능이 사실상 동작하지 않아도 완료할 수 없다.

## 4. 자동화

| 계층 | 필수 검증 |
|---|---|
| Analyzer | 0/1/다중 얼굴, 동률, 작은 얼굴, missing contour, 회전/ROI, 모델 미준비와 얼굴 없음 구분 |
| Provider | 종류 분리, 입력 불변, 출력 크기/mask, NoChange/미지원/오류, 취소/자원 정리 |
| MI-GAN adapter | 255=보존/0=복원 변환, empty mask 추론 0회, RGB/NCHW dtype, crop/resize 역변환, 분산 결함/겹친 패치, 원본 tensor 불변, wrapper 후처리의 보호 영역 차단 |
| 서버(선택 시) | localhost, contract/ID mismatch, auth/과부하/timeout, decode/크기 오류, 취소, 이미지 로그 제외 |
| 합성 | 네 값 0, 단일 항목, feather 완료 후보+binary support, 중첩/clamp, 보호/alpha, 생성 횟수, 이미 혼합된 후보의 feather 이중 적용 방지 |
| 렌더 | prefix, adjust 한 번, crop/90도/straighten, outpaint/생성 op, preview/full, CPU/GPU |
| 저장 | JSON/강도, missing ref, 부분 저장 실패, 취소 orphan, offline load/export, duplicate 파일 독립성 |
| ViewModel | Apply/Undo/Redo 한 번, zero/no-change, Cancel/Back, 새 문서/얼굴/설정, stale 차단 |
| 경합 | 취소 무시 fake 역순 완료, write 중 취소, old finally/new busy, 연속 실행/이중 Apply |
| Lifecycle | 회전, process 복구, draft autosave 제외, 자동 재추론 없음 |
| UI | profile 메뉴 중복 없음, 얼굴/slider/상태/scroll, 작은 화면/font scale 1.5, 접근성, draft export 차단 |

PortraitDetectionTest/PortraitToolMenuTest/GenerativeFillToolTest/GenerativeFillTest/OperationOrderTest/EditDocumentJsonTest 및 저장소 테스트를 참고한다. observable pixels/state/call count/history/file existence로 검증한다.

## 5. 성능/오프라인

아래는 실측이 아닌 초기 목표이며 변경에는 근거를 기록한다.

- 준비된 slider-to-preview는 기존 <100ms p50 목표.
- 로컬 한 얼굴 활성 네 항목 cold prepare p95 5초 목표. 초기 모델 다운로드는 별도다.
- 서버 네 항목 전체 왕복 p95 15초 목표. upload/inference/download를 분리하고 timeout을 유한하게 둔다.
- 12MP import 후 working 4096에서 기존 editor peak 250MB 예산 검사. full-canvas 후보 4장 동시 보유를 피한다.
- CPU/가속/발열/반복 횟수/기기/서버를 기록한다. 실측 없이 통과를 주장하지 않는다.
- MI-GAN은 모델 로드/검출/패치 전처리/순수 추론/복원·합성/preview render를 분리하고 전체 시간을 함께 측정한다. 순수 모델 논문 속도로 Android 전체 처리 시간을 대신하지 않는다. patch 개수 증가에 따른 시간/메모리와 모델 설치 후 완전 offline도 검사한다.
- 로컬 이미지 전송 0건, 준비된 모델 offline 추론, 저장 결과 load/undo/redo/export의 추론 0건.

## 6. 인계

work/RESULT.md에 SR별 범위, 실행 검사, 품질 표/명령 링크, 미실행/환경 제한, 서버 저장소/버전을 기록한다. scripts/check.sh는 실제 모델 품질이나 기기 성능을 대신하지 않는다.
