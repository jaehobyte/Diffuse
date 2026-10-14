# Review

## Status

CHANGES_REQUESTED

2026-10-14 Codex — 현재 `work/tasks.md`의 **단일 실행 워크플로우 + 현재 화면 최종 미리보기**를 리뷰했다.
15039 ADB 서버의 실기기에 현재 작업 트리 APK를 설치하고 실제 Gemini/Topaz로 디노이즈 단독 실행,
현재 화면 전후 비교, 갤러리 저장, 복사본 열기를 확인했다. 기본 성공 경로는 동작하지만
복사본 생성 취소→재시도 경합 R1이 재현된다. 정규 검사도 기존 인계에 보고된 문구 테스트 1건이 실패한다.
이 판정은 다른 미커밋 기능 전체 또는 모든 사진/Topaz 목적의 품질 승인을 뜻하지 않는다.

## Blocking

### R1 [P2] 복사본 생성 취소 후 재시도하면 취소한 작업이 새 작업으로 인정된다

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/direct/DirectController.kt:843`
관련: `openCopy()` :848, `cancelWork()` :994–996, `endSession()`의 reader 대기.

Problem:
복사본 생성은 `NonCancellable`에서 계속되며 `runSeq`와 현재 `Opening` 상태로 완료를 판정한다.
그러나 생성 취소는 phase만 null로 바꾸고 세대를 증가시키지 않는다. 다음 `openCopy()`도 같은
`runSeq`를 캡처한다. A 생성 → overlay 취소 → B 생성 → A 완료 순서이면 A는 B의 `Opening`을
자신의 것으로 인정하여 `openedProjectId`를 내보내고 B의 진행 상태를 지운다.
`reader`도 B로 덮어써져 여러 미완료 reader의 수명이 표현되지 않는다.

결정적 ViewModel 재현 테스트: 각 create를 서로 다른 `CompletableDeferred`에 대기시킨 뒤
A 취소/B 시작/A 해제. 기대 navigation id=null, 실제 **copy-1**. 실기기의 정상 복사본 성공과
구분한 fake repository 경합 재현이다. 증거: `work/evidence/workflow_review/WorkflowReviewProbeTest.kt`,
`copy-cancel-probe.xml` (1 test / 1 failure).

Impact:
사용자가 취소한 복사본으로 뒤늦게 이동하고 새 시도의 진행 상태를 잃는다. A가 늦게 실패하는
경우에도 B를 실패/idle로 덮어쓸 수 있다. 요구 8의 취소 후 늦은 완료·실행 식별자 계약 위반이다.

Required fix:
취소·재시도마다 보관 작업을 구분하여 A 완료가 B의 상태/탐색에 영향을 주지 않게 한다.
취소한 create의 결과는 폐기하고 실제 현재 요청만 채택한다. 모든 진행 중 reader가 끝날 때까지
결과 파일을 보존하되 중복 create를 허용하지 않는 방식도 가능하다. Topaz 재호출 없이 재시도해야 한다.

## Tests Missing

### R2 복사본 overlay 취소→즉시 재시도의 완료 순서 회귀 검사

기존 `DirectWorkflowTest`는 시트 전체 닫기 중 create 완료와 동시 중복 클릭은 다루지만,
`cancelWork()` 후 같은 결과에서 다시 만드는 경우는 없다. A/B 각각의 성공·실패 및 완료 순서,
취소 결과 삭제, 현재 시도만 탐색, 닫을 때 마지막 reader까지 파일 보존을 고정한다.
리뷰용 테스트는 증거 폴더로 옮겼고 정규 테스트 디렉터리에는 남기지 않았다.

## Non-blocking

### N1 기존 인계에 있던 정규 검사 실패는 여전히 미해결

`core/ai/src/test/kotlin/com/diffuse/core/ai/gemini/GeminiPlanSelectionTest.kt:121`의
`Propose erasing a specific distraction you can see` 문자열 검사가 현재 catalog와 불일치한다.
RESULT가 이번 구현 전 변경이라고 보고했던 동일 실패를 확인했다. 이번 단일 실행 변경이
새로 만든 실패라고 단정하지 않는다. 정책 의도에 맞춰 프롬프트와 테스트를 정합화해야 하며,
현재 작업 트리의 canonical 검사는 green이 아니다.

### N2 이전 리뷰·평가 범위 보존

- 이전 TopazHandOff R1/R2의 코드는 제거됐고 실행 세대·취소 검사로 대체됐다.
  준비 중 취소/키 변경/도구 이동/취소 무시 provider의 회귀 테스트가 통과한다.
  이전 인계 결함을 그대로 남은 결함으로 계산하지 않으며, 이번 R1은 별도 보관 단계 경합이다.
- 이전 crop 수정 및 로컬 토글/Undo/Redo 실기기 통과 기록은 유지한다
  (`work/evidence/toggle_review/`, `work/RESULT.md`). 이번에는 그 전체를 재실행하지 않았다.
- 실모델 12종×3회×6축, Topaz 전체 목적의 실사진 품질, TalkBack 음성/큰 글자,
  대용량 메모리·지연 측정은 완료되지 않았다. T4 BLOCKED와 이전 키 교체 권고도 해결 확인되지 않았다.

## Validation Notes

### 환경 및 자동 검사

- ADB 서버 `127.0.0.1:15039`, serial `R5KL10GAH3B`, SM-S948N / Android 16.
  `adb -P 15039 -s R5KL10GAH3B` 사용. 기본 5037의 offline 항목과 구분했다.
- `./gradlew --offline --quiet :app:assembleDebug`: exit 0; `adb install -r`: Success, 데이터 유지.
  APK SHA-256 `e21bcb2991636b498a69d3bd4a9f4d8020b97bf3c8085445418fa6a80e8363d9`.
- `scripts/check.sh`: exit 1, N1. 같은 task들을 `--continue`로 실행해도 동일 1건만 실패.
  lint/detekt/Roborazzi/dependencyGuard 및 다른 단위 검사는 통과.
  전체 unit XML 집계 **1,546 tests / 1 failure / 0 errors / 2 skipped**.
- 관련 `:feature:editor:testDebugUnitTest --tests '*tools.direct*' --tests '*tools.enhance*' --tests '*CropApply*'`:
  **285 tests / 0 failures / 0 errors / 0 skipped**, exit 0 (리뷰 재현 테스트 제거 후).
- R1 임시 재현 검사: exit 1, expected null / actual copy-1. 환경 실패가 아니다.
- 최초 sandbox Gradle 실행은 캐시 read-only로 막혔고 승인된 재실행에서 빌드/검사를 수행했다.
- 요구사항/RESULT, 관련 명세·D101·DESIGN, 변경 diff, controller/processor/runner/Route/ViewModel와 테스트를 검토했다.
  `git diff --check` 통과. 제품 코드·tasks/RESULT/decisions 수정 및 자동 커밋 없음.
- 자동 검사 로그와 재현 XML: `work/evidence/workflow_review/{build,check,targeted,continue,probe}.log`,
  `catalog-test.xml`, `copy-cancel-probe.xml`.

### 실기기 — 디노이즈 단독

입력: `testsets/specialized_v1/images/35_denoise.png` (481×321, 노이즈가 있는 새 사진).
기기 `/sdcard/Pictures/WorkflowReview35.png`로 가져와 별도 검증 프로젝트
`2db9a27e-42e3-4465-9e3d-d1ad7b8bd7e5`를 만들었다. 사용자 사진 프로젝트는 편집하지 않았다.

- 첫 요청 `Only denoise the whole photo with Topaz. Keep colors and framing.`은
  `SocketTimeoutException`; 명시적 재시도는 `topaz under a preservation constraint`로 거부됐다.
  이 두 시도는 워크플로우 성공으로 계산하지 않는다. 키/설정 변경 없이 계속했다.
- `Denoise the whole photo using Topaz.` → 실제 Gemini의 노이즈 제거 1배/얼굴 복원 끔,
  선택 1/1 계획. 유료·원격·크레딧 안내를 스크롤하여 확인한 뒤 주 버튼 **1회** 실행.
- PASS: 별도 화질 개선 시트·중간 실행·확인 클릭 없이 Topaz 처리 진행 → 같은 지시 화면에
  `후`, 결과 481×321, `새 편집본으로 열기` 표시. 새 프로젝트는 아직 생성되지 않음.
- PASS: `전후 비교`에 실제 DOWN/UP 입력. 누르는 동안 `전`과 노이즈 입력, 놓으면 `후`와
  노이즈가 줄어든 출력으로 복귀. 사진 영역 RGB 평균 절대차 약 19.19/18.36/17.04로 프레임 차이 확인.
  품질의 정량 합격 기준이나 모든 사진에 대한 품질 보장은 아니다.
- PASS: 실행 전·처리 중·결과 검토 후 원본 JSON `operations=[]` 유지. 후보 autosave/선확정 없음.
- PASS: `고해상도 저장` → 갤러리 저장 안내 및 `다시 저장`. 기기 출력
  `Pictures/Diffuse/IMG_20261014_181410.jpg`는 Topaz 출력과 SHA-256 동일:
  `736b4647f533009ff9c9caa8b551d4dee6ff5e0862f7144461cb67bc8256d4bf`.
- PASS: 결과 확인 후 `새 편집본으로 열기` → 새 편집 화면. 복사본
  `cb3e7627-86c9-48fe-ab07-f28273920bbf`, operations=[], 별도 source. 원본 불변.
  보관 완료 후 `files/topaz_work`가 비어 있음을 확인.
- 실제 HTTP submit 횟수/크레딧 차감 수는 계측하지 않았다. 실기기에서 한 번 누르고 중간 버튼 없이
  처리된 관찰과 fake에서 검증한 submit=1을 구분한다.

증거: `work/evidence/workflow_review/`의 단계별 XML, `denoise-before.png`, `denoise-released.png`,
입력/처리 중/결과/복사본 JSON, 출력·갤러리 JPEG 및 프로젝트 목록.
검증 프로젝트·복사본·갤러리 출력은 기기에 남겨 두었다.

### 실기기 — 노출 + crop + 디노이즈

같은 원본 검증 프로젝트에서 실제 요청:
`Set exposure to 0.2, crop to 4:5, then denoise with Topaz.`

- PASS: 실제 Gemini 계획에 노출 20 / 4:5 / 노이즈 제거, 선택 3/3. 실행 1회로
  업로드→결과 257×321까지 완료. 자르기 시트·선택 확인·Topaz 별도 실행 클릭 없음.
- PASS: 세션 `input.jpg` 자체를 읽어 **257×321** 확인. 원본 481×321의 대응 중앙 crop 평균 RGB
  101.14/92.66/57.72, 업로드 132.10/121.41/76.65로 밝기 변화 확인.
  정확한 노출 수식의 수치 일치는 이 실기기 측정 범위 밖이며 full 후보 전달은 자동 테스트도 검사한다.
- PASS: 새 복사본 없이 현재 캔버스에 crop과 노이즈 제거가 반영된 `후` 표시.
  검토 시 원본 JSON operations=[] 유지.
- PASS: Topaz off → 로컬 후보 표시 및 주 버튼 `적용`; on → `새 편집본으로 열기`와 기존 결과 복귀.
  on 복귀 캔버스 영역 `(0,263)-(1080,1245)`은 최초 결과와 픽셀 동일.
  off/on 이미지 차이 RGB MAE는 15.83/14.94/14.53. 세션에는 `output_1.jpg` 한 개 유지. 새 원격 요청 횟수의 직접 계측은 아님.

- PASS: Topaz를 다시 off하고 로컬 `적용` → Exposure 0.2 + crop 두 연산 저장.
  Undo 한 번 → operations=[]; Redo 한 번 → 동일 id/값의 두 연산 복원.
  마지막에는 다시 Undo하여 원본 상태로 두고 편집기를 나왔다.

추가 증거: `local-{applied,undone,redone}.{json,xml}`, `combo-*.xml`, `combo-upload.jpg`, `combo-after.png`, `combo-topaz-{off,on}.png`,
`combo-original.json`, `combo-files-reused.txt`.

### 이번 실기기 검증의 한계

- 선택+지우기, 처리 중 Back/취소, 설정 변경 중 늦은 완료, configuration change,
  process death, 큰 Topaz 결과, 키 없음/투명 입력/저장 실패/생성 실패는 실기기에서 재현하지 않았다.
  관련 fake/Compose 테스트의 통과와 실기기 검증을 구분한다.
- 이번 실기기 성공은 텍스트 지시 진입이다. `보정 추천 받기` 진입은 같은 실행 경로의 코드/테스트를
  검토했으며 이번 기기에서 다시 실행하지 않았다.
- 복합 결과의 서로 다른 비율 hold 전후 비교는 별도 실기기 캡처하지 않았다.
  단독 결과의 hold는 위에서 확인했다.
