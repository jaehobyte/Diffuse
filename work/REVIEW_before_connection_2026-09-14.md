# Review

## Status

CHANGES_REQUESTED

2026-09-10. 현재 `work/tasks.md`의 MonetGPT 서버 구축 작업을 기준으로 `/home/jaeho/monetGPT`의 staged 구현과 해당 서버의 `work/RESULT.md`를 리뷰했다. 아래 구현 경로는 서버 저장소 기준이다. Diffuse의 기존 피부 검출기 변경은 이번 대상이 아니며 이전 리뷰는 하단에 보존한다.

## Blocking

### R1 [P1] 전체 요청 deadline을 강제하지 않음

Location:
`/home/jaeho/monetGPT/app/main.py:143`
`/home/jaeho/monetGPT/app/main.py:272`
`/home/jaeho/monetGPT/app/engine.py:269`

Problem:
GPU lock과 shield된 추론을 시간 제한 없이 기다린다. Deadline 객체를 전달하지만 HTTP 계층이 만료를 감시하지 않는다. 실제 adapter도 각 turn의 전처리 이후 상대 budget으로 새 만료 시각을 만들며 stopper.tripped 및 마지막 stage 종료 후 전체 deadline을 검사하지 않는다. 따라서 대기 중 만료돼도 즉시 504를 보내거나 슬롯을 반환하지 않고, 마지막 stage가 전체 시간을 초과해도 앞선 유효 JSON으로 200을 반환할 수 있다. 기존 timeout 테스트는 fake가 직접 DeadlineExceeded를 던져 이 경로를 검증하지 않는다.

Impact:
요구사항 8·9의 queue+전처리+추론 전체 deadline을 보장하지 못한다. 앱의 120초 read timeout 이후에도 요청과 슬롯이 남을 수 있다.

Required fix:
하나의 절대 deadline으로 대기·전처리·모든 turn과 응답 완료를 제한한다. 대기 중 만료는 즉시 제거하고 504로 응답한다. 실행 중 timeout 응답 이후에도 실제 native 작업이 끝날 때까지 GPU 슬롯은 유지한다. stage별 생성 중단과 전체 deadline 초과 실패를 구분하고 성공 반환 전에 전체 만료 여부를 확인한다.

### R2 [P1] HTTP 연결 종료가 대기 취소로 연결되지 않음

Location:
`/home/jaeho/monetGPT/app/main.py:101`
`/home/jaeho/monetGPT/app/main.py:120`
`/home/jaeho/monetGPT/app/main.py:272`

Problem:
BodyLimit는 본문을 읽은 뒤 실제 ASGI receive를 버리고 이후 호출마다 가상의 http.disconnect를 반환하는 _replay로 바꾼다. endpoint에도 실제 연결 종료를 감시하여 run_on_gpu 대기를 취소하는 경로가 없다. 설치된 Uvicorn h11_impl.py는 connection_lost에서 disconnected 상태를 표시하고 receive로 알리며, 이 경로에서 요청 task를 자동 취소하지 않는다. 기존 테스트의 waiting.cancel()은 HTTP 연결 종료와 다른 조건이다.

Impact:
사용자가 대기 중 취소하거나 스타일을 바꿔 연결을 끊어도 이전 요청이 슬롯을 점유하고 이후 GPU 추론까지 실행될 수 있다. 요구사항 8의 대기 취소 제거 및 RESULT의 해당 보장을 만족하지 못한다.

Required fix:
본문 재생 이후 실제 disconnect 신호를 보존하고 HTTP 연결 종료를 요청 취소에 연결한다. 대기 중에는 즉시 슬롯을 반환하고 실행 중에는 결과를 전달하지 않되 실제 작업 종료 전 슬롯 반환은 금지한다. 실제 disconnect를 전달하는 ASGI 통합 테스트 또는 socket 테스트로 검증한다.

### R3 [P1] 설치 스크립트가 주석과 두 revision을 하나의 checkout 인자로 전달

Location:
`/home/jaeho/monetGPT/scripts/install.sh:30`
`/home/jaeho/monetGPT/vendor/REVISIONS:1`

Problem:
`cut -d' ' -f1 vendor/REVISIONS`는 두 주석 행의 #, GitHub revision, Hugging Face revision을 모두 출력한다. 이를 따옴표로 감싼 command substitution으로 checkout에 전달하므로 줄바꿈을 포함한 하나의 유효하지 않은 ref가 된다. set -e 때문에 설치가 이 지점에서 실패한다.

Impact:
README의 신규 설치·업데이트 절차가 완료되지 않고 upstream revision 고정도 수행되지 않는다. 기존 환경의 모델 실행 성공으로 설치 절차의 정상 동작을 증명할 수 없다.

Required fix:
GitHub 소스 항목의 revision 하나를 명확히 선택하고 형식과 항목 개수를 검증한다. 다운로드나 패키지 설치 없이 실제 REVISIONS 파일에서 올바른 checkout 인자가 선택되는 회귀 검증을 추가한다.

### R4 [P2] 모델 설명의 중괄호가 앱 JSON 파서를 깨뜨림

Location:
`/home/jaeho/monetGPT/app/plan.py:117`

Problem:
_first_sentence는 길이와 마침표만 확인하고 중괄호를 그대로 반환한다. 예를 들어 설명 `Increase {Exposure} to brighten the photograph.`와 유효한 values `{"Exposure":10}`은 두 쌍의 중괄호를 포함한 content가 된다. MonetClient는 첫 여는 중괄호부터 마지막 닫는 중괄호까지 파싱하므로 이 응답을 읽을 수 없다. 기존 테스트는 중괄호 없는 설명만 사용한다.

Impact:
서버가 200을 반환해도 앱에서 보정 계획을 사용할 수 없다. 요구사항 4와 서버 API의 단 하나의 JSON 객체 계약 위반이다.

Required fix:
설명이 JSON 경계를 교란하지 않도록 보장한다. 해당 문장을 건너뛰고 다른 모델 문장이나 기존 숫자 기반 설명을 사용하는 등 근거 있는 fallback을 적용하며 최종 content를 앱 파싱 규칙으로 검증한다.

### R5 [P2] smoke 검사가 범위 위반을 clamp한 뒤 정상 판정

Location:
`/home/jaeho/monetGPT/scripts/smoke_client.py:103`
`/home/jaeho/monetGPT/scripts/smoke_client.py:149`

Problem:
parse에서 숫자를 먼저 -1..1로 clamp하고 check에서 그 결과의 범위를 검사한다. 원본 Exposure: 1000도 1.0이 되어 통과한다. 알 수 없는 이름과 비숫자 항목도 유효 항목 하나가 있으면 조용히 제외된다. 앱의 관대한 소비 동작과 서버의 엄격한 출력 계약을 구분하지 않는다.

Impact:
작업서 Validation이 요구한 원본 응답의 이름·유한 숫자·-100..100 범위를 검증하지 못한다. smoke 성공을 서버 출력 계약 준수의 증거로 사용할 수 없다.

Required fix:
원본 JSON의 모든 항목을 변환 전에 검사해 허용되지 않은 이름, 비숫자/bool, NaN/Inf, 범위 초과가 하나라도 있으면 실패시킨다. 앱 파서 호환성 검사는 별도로 유지하고 정상/비정상 항목이 섞인 응답의 실패 테스트를 추가한다.

## Tests Missing

- R1: 실제 시간 경과에 따른 대기 timeout, 마지막 stage 초과, timeout 응답 후 native 작업 종료 전 슬롯 유지 및 종료 후 복구.
- R2: task.cancel() 직접 호출이 아닌 HTTP disconnect에서 대기 제거·실행 중 결과 폐기 검증.
- R3–R5: revision 선택, 중괄호 포함 설명, 원본 범위/타입/이름 위반 smoke 응답의 회귀 검증.

## Non-blocking

### N1 설치 경로에서도 의존성 lock 사용

requirements.lock.txt는 존재하지만 install.sh는 requirements.txt만 설치하므로 전이 의존성은 새 설치 시 달라질 수 있다. CUDA wheel 공급 경로를 유지하면서 lock을 설치에 적용하면 기록된 환경을 재현하기 쉽다.

## Validation Notes

- 현재 작업서, 양 저장소 RESULT, 기존 REVIEW, 서버 API/README, 앱 auto_enhance 스펙·MonetClient, architecture/DESIGN/decisions의 관련 내용을 확인했다. Diffuse RESULT는 피부 검출기 작업이므로 이번 서버 구현의 인계 근거로 사용하지 않았다.
- 양 저장소 git status 및 서버 staged diff를 확인했다. 서버는 40개 신규 staged 파일이다. 주요 API·engine·입력/응답 처리·설치/배포·smoke와 관련 테스트를 검토했다.
- 직접 실행: 서버 scripts/check.sh — **exit 0**, ruff check/format 통과, **107 passed, 7 deselected**. PYTHONDONTWRITEBYTECODE=1, RUFF_CACHE_DIR=/tmp/monet-review-ruff, PYTEST_ADDOPTS='-p no:cacheprovider'로 캐시 쓰기를 제한했다. 최초 샌드박스 초기화 실패 후 승인된 실행으로 완료했다.
- 서버 git diff --check 및 git diff --cached --check — **PASS**.
- 위 findings는 코드·계약 대조 결과다. 별도의 신규 재현 테스트, 실제 socket 취소 실험, 설치 재실행은 하지 않았다.
- 실제 GPU 추론·스타일별 smoke·SAM3 공존 실험은 재실행하지 않았다. RESULT의 GPU 7개 통과 및 지연/메모리 수치는 인계 기록이며 이번 독립 검증 결과가 아니다.
- Android 기기 검증, systemd 설치와 Caddy 외부 배포는 미실행이다. 기기 부재와 SAM3 공존 실패를 명시한 PARTIAL 상태 자체를 코드 차단 사유로 삼지 않았다.
- 서버 및 Android 구현은 수정하지 않았다. 실제 기기 연동 완료나 운영 배포 승인을 의미하지 않는다.

---

# 이전 리뷰 기록 — 피부 검출기 및 메뉴

# Review

## Status

CHANGES_REQUESTED

2026-09-10. 현재 작업서의 Tinny-Robot/acne ONNX 포팅 및 SR1-B 평가 경로를 리뷰했다. 이전 메뉴 작업 리뷰는 하단에 보존했다. 기기·권한 있는 사진 세트 부재는 작업서가 허용한 미측정 항목이며 그 자체를 차단 사유로 삼지 않는다.

## Blocking

### R1 [P1] ALPHA_8 rowBytes를 무시해 정상 mask 입력에서 예외 발생

Location:
`core/ai/src/main/kotlin/com/diffuse/core/ai/retouch/CandidateMask.kt:87`

Problem:
readAlpha()가 width×height만 할당하고 native bitmap을 그대로 복사한다. native graphics Robolectric에서 `Bitmap.createBitmap(301, 173, ARGB_8888).extractAlpha()`는 rowBytes=304, byteCount=52592였고, `candidateMask(emptyList(), allowed)`가 88행에서 `Buffer not large enough for pixels`로 실패했다. 직접 ALPHA_8로 생성한 같은 크기의 bitmap은 rowBytes=301로 통과하므로 현재 테스트의 생성 방식만으로는 발견되지 않는다. [Android Bitmap rowBytes 계약](https://developer.android.com/reference/android/graphics/Bitmap#getRowBytes()).

Impact:
정상적인 binary allowedMask를 전달해도 후보 mask를 생성할 수 없다. 검출 0개는 정상 empty mask여야 한다는 요구사항 7도 위반한다.

Required fix:
입출력 bitmap의 실제 byteCount/rowBytes에 맞춰 복사하고 논리 픽셀 배열과 native 행 padding을 구분한다. 버퍼 크기뿐 아니라 행별 인덱스도 맞춘다. extractAlpha()로 만든 홀수 폭 mask에서 빈 검출·부분 허용 영역·alpha 제외·입력 불변을 검증한다.

### R2 [P2] 반투명 픽셀의 Python·Kotlin 전처리 불일치

Location:
`scripts/retouch/detector.py:209`
`core/ai/src/main/kotlin/com/diffuse/core/ai/retouch/DetectorPreprocess.kt:170`

Problem:
Python은 alpha 합성 직후 uint8로 반올림하고 Kotlin은 float 값을 유지한 채 보간한다. 1×1 검정 픽셀, alpha=128, model size=1이면 Python은 57/255 = 0.22352941, Kotlin은 0.22265281이다. native Robolectric 재현에서 공유 fixture의 허용 오차 1e-5를 초과해 실패했다. 공유 fixture는 alpha 0/255만 생성하므로 이 차이를 놓친다.

Impact:
동일 ROI라도 모델 입력 tensor가 달라져 요구사항 5와 RESULT의 동일 전처리 주장을 만족하지 못한다. threshold 부근 검출 결과 비교에도 영향을 줄 수 있다.

Required fix:
alpha 합성의 정밀도·반올림 시점을 한 규칙으로 고정해 양쪽 구현과 manifest를 맞춘다. 중간 alpha 값과 보간이 함께 있는 공유 fixture를 추가한다. Android의 premultiplied bitmap 저장에 따른 색상 표현도 고려해 비교 기준을 명시하며 오차만 임의로 늘리지 않는다.

### R3 [P2] manifest의 실행 의미를 검증하지 않아 불일치를 수용

Location:
`scripts/retouch/detector.py:104`
`core/ai/src/debug/kotlin/com/diffuse/core/ai/retouch/DetectorModel.kt:105`
`core/ai/src/debug/kotlin/com/diffuse/core/ai/retouch/OnnxBlemishDetector.kt:188`

Problem:
Python from_manifest()는 I/O shape와 num_classes만 읽고 manifest_version, objectness, embedded_nms, 전처리·좌표 의미를 검사하지 않는다. Android는 version/objectness/NMS를 검사하지만 preprocess 전체와 box_format/box_units를 무시한다. 양쪽 모두 dtype을 계약 비교에서 제외하며 Android graphContract는 실제 input batch/channel 및 output batch도 비교에서 빠진다. 동일 ONNX와 SHA를 두고 manifest의 RGB를 BGR로, cxcywh를 xyxy로 변경해도 기존 방식으로 실행된다. Python은 embedded_nms=true도 거부하지 않는다.

Impact:
manifest와 adapter의 계약 불일치가 명시적 오류가 되지 않는다. 다른 export의 의미를 같은 shape로 오해해 잘못된 box/mask를 정상 성공으로 반환할 수 있어 요구사항 2·5·6을 위반한다.

Required fix:
지원하는 manifest 버전, dtype와 전체 I/O shape, 전처리, 좌표 표현·단위, score activation/NMS, class allowlist를 검증한다. 지원하지 않는 계약은 추론 전에 거부하고 허용된 CLI 평가 설정 override는 별도로 기록한다. 동일 model digest에서 계약 필드만 변경하는 거부 테스트를 양쪽에 추가한다.

### R4 [P2] 자동 평가 CLI가 원본 alpha를 버려 후보 mask 보호 조건 누락

Location:
`scripts/retouch/detect_eval.py:118`
`scripts/retouch/detect_eval.py:183`

Problem:
신규 CLI가 기존 load_rgb()의 IMREAD_COLOR 경로로 PNG alpha를 제거하고, detect_case()도 candidate_mask에 원본 alpha를 전달하지 않는다. RGBA 배열을 직접 detect_case에 넘겨도 mask에는 alpha가 적용되지 않는다. 투명 픽셀을 덮는 검출 box와 all-255 allowedMask에서는 투명 영역이 후보 mask에 포함된다.

Impact:
요구사항 7의 allowedMask ∩ 원본 alpha>0 계약이 실제 평가 경로에서 지켜지지 않는다. 후보 면적·정상 피부 포함 면적과 자동 복원 입력도 잘못 측정되며 detector의 투명 배경 전처리도 CLI에서 우회된다.

Required fix:
SR1-B 로딩부터 alpha를 보존하고 검출 전처리와 후보 mask 제한에 각각 전달한다. 기존 SR1-A 및 RestoreRun의 RGB 계약은 보존하면서 자동/수동 평가의 허용 영역에 같은 alpha 제한을 적용한다. 투명 PNG와 deterministic fake detector로 weight-free 평가 통합 테스트를 추가한다.

### R5 [P2] Android harness의 peak memory 및 단계별 비용 측정 누락

Location:
`core/ai/src/androidTest/kotlin/com/diffuse/core/ai/retouch/AcneDetectorDeviceTest.kt:255`

Problem:
비용 테스트는 cold detect 전체와 warm detect 전체 시간만 측정한다. mask 생성은 포함되지 않고 모델 로드/전처리/추론/NMS·mask별 p50·p95도 없다. 특히 coldDetector.close() 이후 한 번 조회한 현재 totalPss를 peak_total_pss_kb로 기록한다. detect가 Failure를 반환해도 비용 테스트는 성공한다.

Impact:
기기를 연결해 실행해도 요구사항 11의 측정 자료가 생성되지 않는다. session 해제 후 메모리를 peak로, 실패 비용을 추론 비용으로 오인할 수 있다. 기기 부재와 별개의 평가 도구 구현 문제다.

Required fix:
성공한 실행만 집계하고 모델 로드·전처리·추론·NMS 및 mask·전체 비용을 분리 수집한다. 실행 중 메모리를 관찰해 측정 방식과 한계를 기록하며 현재 PSS와 관측 peak를 구분한다. 실패 시에도 임시 detector를 정리한다. 실제 Android 수치는 기기 확보 전까지 계속 미측정으로 둔다.

## Tests Missing

R1–R4의 재현 조건을 정식 회귀 테스트로 추가해야 한다. 현재 adapter JVM 테스트는 session 생성 이전의 모델 없음/손상/close 상태만 다룬다. 기기 테스트의 즉시 취소 및 delay(1) 후 close는 native run 진입을 보장하지 않으므로, 실행 진입을 동기화해 취소 후 결과 미전달·close 대기·재사용을 검증해야 한다. 기기 미실행 상태를 유지해서 보고하며 해당 보장을 검증 완료로 표현하지 않는다.

## Non-blocking

### N1 평가 문서의 현재 예산·제품 상태 참조 갱신

work/retouch_evaluation.md §5에는 여전히 15 MB 예산 및 “tool stays out of the menus”가 남아 있다. 현재 작업서/architecture는 <1000 MB와 모델 미번들링이며 피부 보정 준비 시트는 메뉴에 존재한다. 과거 SR1-A 측정 이력은 보존하되 현재 후속 조건 설명을 맞추면 좋다.

## Validation Notes

- 작업서, RESULT, 피부 보정 pipeline/validation 스펙, architecture, DESIGN, D080, tracked diff와 신규 소스·테스트를 확인했다. 기존 메뉴/시트, production DI, renderer/history의 구현 변경은 없다. 작업서와 architecture의 선행 미커밋 변경은 검출기 구현의 범위 위반으로 판단하지 않았다.
- 직접 실행: `python3 -m pytest scripts/retouch` — **58 passed**.
- 직접 실행: `scripts/check.sh` — **exit 0**. lint, detekt, testDebugUnitTest, verifyRoborazziDebug, dependencyGuard. Gradle 증분 실행을 허용한 결과다.
- 직접 실행: `./gradlew :app:assembleDebug :app:assembleRelease :core:ai:assembleDebugAndroidTest --offline --quiet` — **exit 0**. instrumentation APK 빌드는 기기 실행 검증이 아니다.
- 추가 재현: 임시 ReviewProbeTest를 native graphics Robolectric으로 실행해 R1의 rowBytes=304 mask 예외와 R2의 0.22352941 대 0.22265281 차이를 확인했다. 두 재현 테스트는 실패했으며 기존 green 검사와 구분한다. 리뷰용 임시 테스트 파일은 제거했고 제품 구현은 수정하지 않았다.
- `git diff --check` — PASS. 실제 모델 export/PyTorch parity, Android 실행, 24장 품질 평가, APK 전후 크기 비교는 이번 리뷰에서 재실행하지 않았다. 해당 수치는 RESULT의 인계 기록이며 독립 검증 완료로 간주하지 않는다.
- runtime은 debugImplementation에만 추가되고 main DI에 detector/provider binding이 없다. native tensor/result는 use로 정리되고 run/close는 mutex로 직렬화된다. 단위 테스트 통과는 Android 수치·품질 통과를 의미하지 않는다.
- 아래 이전 리뷰의 APPROVE는 이번 검출기 변경의 승인이 아니다.

---

# 이전 Review — 인물 메뉴 및 피부 보정 준비 시트

## Status

APPROVE

현재 `work/tasks.md`의 인물 메뉴 교체 및 피부 보정 준비 상태 시트 연결을 리뷰했다. 작업 트리에 함께 있는 이전 SR1 산출물은 이번 승인 범위가 아니며, 기존 SR1 리뷰 지적의 재검증이나 실제 피부 보정 엔진의 완료를 의미하지 않는다.

## Blocking

None.

## Tests Missing

차단할 테스트 누락 없음. 시스템 Back은 기존 `BackActionTest`의 열린 도구 취소 분기, 앱의 `cancelSheet()` 연결, 신규 ViewModel 취소 테스트를 함께 확인했다. 실제 Back 이벤트부터 피부 보정 시트 종료까지의 통합 테스트와 기기 수동 검증은 실행하지 않았다.

## Non-blocking

### N1 Back 처리 경로와 검증 범위의 인계 설명 보완

`work/RESULT.md`의 Review Notes는 `EditorRoute`의 AI 레벨 BackHandler만 설명한다. 실제 Root 시트 취소는 `app/src/main/kotlin/com/diffuse/navigation/DiffuseNavHost.kt:145`의 BackHandler가 `BackAction.CancelTool`을 통해 수행한다. 이 경로를 명시하면 후속 구현자가 시트 종료 처리를 누락으로 오해하지 않는다. Known Issues의 “세 항목은 Robolectric 테스트로만 확인했다”도 피부 보정의 실제 Back 이벤트를 자동화로 검증한 것처럼 읽히므로, 취소·재탭 테스트와 Back 분기/연결 확인을 구분하면 좋다.

## Validation Notes

- `work/tasks.md`, `work/RESULT.md`, architecture/tool_groups 및 피부 보정 제품·검증 스펙, DESIGN.md, D080/T79를 확인했다. 메뉴 배치는 작업서의 명시적 계약 변경을 기준으로 판단했다.
- `git status --short`, tracked diff, 신규 피부 보정 시트와 테스트, 주변 ViewModel/Route/공통 EditSheet 및 앱 내비게이션을 확인했다. 기존 SR1 파일이 함께 존재하므로 전체 작업 트리를 이번 메뉴 변경으로 간주하지 않았다.
- Portrait Root는 자동 → 피부 보정 → 라이트 → 색상 → 혼합 → 자르기 → AI이며 General의 디테일과 AI 목록은 유지된다. profile별 순서·중복·조건부 제외·합집합 테스트가 있다.
- 전용 시트는 EditSheet를 재사용하고 준비 중 안내, 네 항목의 미지원 표시, 비활성 적용을 제공한다. 슬라이더/미리보기 실행 UI는 없으며 분석·보정 provider 호출도 추가하지 않았다. fake를 실제 앱에 연결하지 않았다.
- 신규 테스트는 탭 → 전용 시트 → 취소/재탭 종료, 기존 디테일 슬라이더 진입, 늦은 인물 감지 중 열린 디테일과 조정 값 유지, 피부 보정 진입/취소 시 문서·history 보존을 검증한다.
- 직접 실행: `./gradlew :feature:editor:testDebugUnitTest --tests '*PortraitToolMenuTest' --tests '*PortraitDetectionTest' --tests '*SkinRetouch*' --tests '*ToolStripLevelTest' --tests '*ToolSheetHostTest'` — PASS. 테스트 task가 실제 실행됐으며 BUILD SUCCESSFUL을 확인했다.
- 직접 실행: `scripts/check.sh` — PASS, exit 0. lint, detekt, 전 모듈 unit test, verifyRoborazziDebug, dependencyGuard를 포함한다. Gradle 증분 실행을 허용한 검사이며 모든 task를 강제로 재실행한 것은 아니다. 최초 샌드박스 초기화 오류 후 승인된 실행으로 완료했다.
- `git diff --check` — PASS.
- 기기 실행, 회전/화면 이탈의 수동 검증, 실제 보정 품질·적용·Undo·저장·내보내기는 미검증이다. 실제 provider/자동 결함 mask → SR2 저장/render → SR3 미리보기/적용이 후속 구현으로 남아 있다는 RESULT의 구분은 적절하다.
- 구현 코드는 수정하지 않았다.
