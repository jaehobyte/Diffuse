# Result

## Review Fix Pass (2026-10-08, work/REVIEW.md R1·R2)

### Status

PARTIAL — R1·R2 코드 수정과 자동 검증은 완료. 실기기 재확인은 ADB 15039 연결 거부로 미실행.

### Changed

- **R1** `DirectSuggestionArea.StatusLine`: Loading 검사를 추천 초안 안내보다 먼저 둔다. 분석 중 예시를 골라도
  진행 문구와 추천 취소가 유지되고, 요청이 끝난 뒤에야 `문장을 바꿔도 좋아요`가 보인다. 취소 동작 자체는 변경 없음.
- **R2** `EditorScreen.SheetOverlay`에 `imePadding()`(크기 측정 뒤) 추가 + `MainActivity`에
  `windowSoftInputMode="adjustResize"`. edge-to-edge에서 창이 pan되지 않고 IME 인셋만큼 시트가 키보드 위로 올라가며,
  측정 높이에 키보드가 포함되어 캔버스가 남은 공간으로 다시 맞춰진다. 시트 내부 `navigationBarsPadding`은 IME가 소비한
  인셋과 겹쳐 0이 된다. 앱의 텍스트 입력은 모두 편집기 시트 안(지시/채우기/확장/SAM3 설정/멀티샷)이라 모두 같은 처리를 받는다.

### Files

- `feature/editor/.../tools/direct/DirectSuggestionArea.kt`, `feature/editor/.../EditorScreen.kt`, `app/src/main/AndroidManifest.xml`
- tests: `DirectSheetTest`(분석 중 + Suggestion 출처 + 비어 있지 않은 입력 → 진행·취소 노출),
  `DirectToolTest`(선택 후 추천 취소 → 문장·출처·도구·문서·history 보존), `EditorShellTest`(IME 인셋 900px 주입 →
  시트 하단이 키보드 위, 캔버스가 시트 위로 재배치)

### Validation

| 명령 | 결과 |
| --- | --- |
| `./gradlew --offline --quiet :feature:editor:testDebugUnitTest --tests '*EditorShellTest*' --tests '*DirectSheetTest*' --tests '*DirectToolTest*'` | 통과 |
| 같은 IME 테스트를 `imePadding()` 제거 상태로 실행 | 실패(EditorShellTest.kt:317, 키보드가 시트를 덮음) → 원복 |
| `scripts/check.sh` | **exit 0** |
| `git diff --check` | 통과 |
| `adb -H 127.0.0.1 -P 15039 devices -l` | Connection refused → 실기기 미실행 |

### Review Notes

- 키보드가 열리면 시트(최대 화면 45%)+키보드 때문에 캔버스가 매우 작아진다(“캔버스 절반 노출”은 키보드 표시 중 보장 불가).
  큰 글자/좁은 화면에서는 시트 본문이 기존 내부 세로 스크롤로 줄어들며, 액션 행은 고정된다. 상단 바를 덮지는 않는지
  실기기 확인 필요.
- Robolectric은 인셋 주입으로 레이아웃만 검증한다. Samsung 키보드 실제 동작·IME 애니메이션은 실기기 재검증 필요.

### Known Issues

- R2 실기기(1080×2340/450, font_scale 1.5·density 480 포함) 재확인 미실행. REVIEW의 기존 미실행 항목(사진 6종·8문장,
  TalkBack, 음성 Final, 네트워크 실패, 피부 보정 draft)은 이번 패스 범위 밖으로 그대로 남는다.

---

## Status

PARTIAL

2026-10-07 지시 도구의 사진 맞춤 추천 문장 넛지를 구현했다. 코드·자동 테스트·canonical 검사는 통과했다.
**실사진 smoke(실제 Gemini 키·기기)와 카탈로그 8문장의 실제 계획 해석 확인은 미실행**이라 PARTIAL로 둔다.

## Changed

- 지시 시트 입력창 아래 추천 영역: 즉시 보이는 일반 예시 3개(`이렇게 말해보세요`), 명시적 `사진에 맞는 문장 보기`
  + 전송 안내, 분석 중(인라인 진행 문구 + 취소), 맞춤(`이 사진에는 이런 방향도 좋아요`, 최대 3개), 빈 결과, 실패(snackbar +
  `다시 찾기`), 숨기기(시트 세션 동안), 추천 초안 안내(`문장을 바꿔도 좋아요`). 음성 인식 중·직접 입력 시 숨김.
- pill 선택은 입력창 채우기만 한다(`RequestSource.Suggestion`). planner/runner/commit 0회, 키보드 강제 없음. 전송 시 기존
  `EditPlanProvider` 1회 → 검증 → 단계 목록 → 적용.
- 문장 변경 시 대기 계획·`canApply` 즉시 무효화, 진행 중 계획 취소, 이전 문장의 늦은 계획 폐기(`planSeq`). 실행 중엔 변경 불가.
- `core:ai`: `PromptSuggestionProvider`/`PromptSuggestionId`(8개, 충돌 그룹, capability), `GeminiSuggestionClient`
  (강제 function call `suggest_directions(ids)`, ID만 읽음, 미지원/중복/충돌/초과 제거, 빈 결과≠실패, 차단·잘림·잘못된 구조=실패,
  기존 상태코드 매핑, 12초 timeout, 취소 시 call 종료), `GeminiSuggestionProvider`(기존 `GeminiImageCodec` ≤1024, 임시 bitmap만
  recycle), DI 바인딩.
- `DirectSuggestions`(DirectController 소유 협력 객체): 문서 단위 키 + generation, 메모리 1항목 캐시, 연속 탭 병합, 자동 재시도 없음.
  문서 변경(Undo/Redo/보정/실행)·Gemini 설정 변경·시트 닫기/도구 전환·전송/음성 Final에서 취소·무효화.
- `EditorUiState.renderedDocument`: preview가 렌더된 문서. 현재 문서와 같을 때만 분석 가능(렌더 대기/실패 시 `사진을 준비하는 중`).
- 명세/디자인/결정: `specs/vibe_edit.md` §14 및 §2 문단, `specs/prompt_input.md`, `specs/ai_provider.md`, `DESIGN.md` §4 상태 표시
  예외, `work/decisions.md` D091.

## Files

- core/ai main: `PromptSuggestionProvider.kt`, `gemini/GeminiSuggestion{Catalog,Client,Provider}.kt`(신규),
  `gemini/GeminiDto.kt`(`Schema.items`), `AiModule.kt`
- core/ai testShared/test: `FakePromptSuggestionProvider.kt`(신규), `FakePlanProvider.kt`(hold/release),
  `GeminiSuggestion{Client,Provider}Test.kt`(신규)
- feature/editor main: `tools/direct/DirectSuggestions.kt`, `DirectSuggestionArea.kt`(신규), `DirectController.kt`,
  `DirectSheet.kt`, `EditorAi.kt`, `EditorViewModel.kt`, `EditorRoute.kt`, `res/values/strings.xml`
- feature/editor test: `DirectToolTest.kt`(+20), `DirectSheetTest.kt`(+11), `DirectGoldenTest.kt`(+2),
  EditorAi 생성자 인자 추가만 한 9개 tool 테스트
- goldens: `direct_sheet_open.png`(갱신, 추천 영역 포함), `direct_suggest_loading.png`, `direct_suggest_tailored.png`(신규).
  `direct_plan_preview.png`는 변하지 않음
- docs: `specs/vibe_edit.md`, `specs/prompt_input.md`, `specs/ai_provider.md`, `DESIGN.md`, `work/decisions.md`, `work/RESULT.md`,
  `work/REVIEW.md`(사용자 직접 요청으로 자체 리뷰 섹션 추가, 기존 내용 보존)

## Validation

| 명령 | 결과 |
| --- | --- |
| `./gradlew --offline --quiet :core:ai:testDebugUnitTest --tests '*Suggestion*' --tests '*GeminiPlan*'` | 통과 (Suggestion 16, GeminiPlan 66) |
| `./gradlew --offline --quiet :feature:editor:testDebugUnitTest --tests '*DirectToolTest*'` / `'*DirectSheetTest*'` | 통과 (41 / 22) |
| `./gradlew --offline --quiet :feature:editor:recordRoborazziDebug --tests '*DirectGoldenTest*'` | 3장 기록, 육안 확인 |
| `./gradlew --offline --quiet detekt` | 통과 (ReturnCount 2건·줄 길이 1건 수정 후) |
| `scripts/check.sh` | **exit 0**, 2분 43초 (lint/detekt/unit/Roborazzi verify/dependencyGuard). Direct 골든 4, VoicePrompt 7 포함 |
| `git diff --check` | 통과 |
| `adb -H 127.0.0.1 -P 15039 devices` | 15초 timeout(응답 없음) → 실기기 미실행 |

실사진 smoke 기록(요구 표):

| 입력 유형 | 추천 ID/순서 | 전송까지 동작 | 지연/요청 수 | 계획·문장 일치 | 환경 |
| --- | --- | --- | --- | --- | --- |
| 6종(어두운 실내/밝은 야외/음식/인물/강한 색감/야경) | 미실행 | 미실행 | 미실행 | 미실행 | ADB 전달 응답 없음, 키/권한 사진 미사용 |

자동 테스트의 추천 결과는 fake/MockWebServer 응답이며 실제 문장 해석·추천 적합성 품질을 검증하지 않는다.

## Review Notes

- 요청 식별은 `renderedDocument == document`(구조적 동등) + generation. 같은 내용의 문서로 Undo/Redo 왕복해도 이전 응답은
  generation으로 폐기되며, 완료된 캐시만 동일 문서에 재사용된다.
- 추천 영역은 `DirectSheet`의 `suggestions` slot이며 `state.showSuggestions`로만 노출. 음성 Listening 숨김은
  `DirectSuggestionArea`가 `SpeechInput.state`를 직접 구독해 처리.
- pill 터치 테스트는 Robolectric 기본 화면(시트 45% 스크롤 안 가로 Row)에서 터치가 닿지 않아 semantics OnClick 액션으로 검증.
  48dp 높이와 TalkBack 설명은 별도 테스트로 확인.
- 실패 snackbar: 401/403 → `direct_needs_key`, 그 외(차단 포함) → `추천 문장을 가져오지 못했어요`.
- 피부 보정 `close()`는 재렌더를 요청하지 않으므로, 취소 직후 preview가 draft 렌더로 남으면 다음 문서 변경 전까지 맞춤 요청이
  `사진을 준비하는 중`으로 비활성일 수 있다(잘못된 프레임 전송은 아님). 실기기 확인 필요.

## Known Issues

1. 실사진 smoke·카탈로그 8문장의 실제 Gemini 계획 결과(선택/삭제/생성 단계 미발생 여부) 미확인. 키·기기 환경에서 수행 필요.
2. 큰 글자·작은 화면·키보드 표시 실기기 확인 미실행(자동: 시트 45%·고정 취소/적용·내용 스크롤 구조 유지, 골든은 Pixel6a).
3. 사용성 개선 효과는 가설이며 사용자 검증 없음.
4. 이전 미해결 유지: 서버 멀티샷 API R1(ASGI 취소 후 후속 추출)/R2(큰 정수 metadata 500), 피부 보정 D084 지원 종류 0개·품질/lifecycle
   평가, Jev 미구현, 앱 골프/얇은 경계 품질·offline·12MP/PSS 평가 — 아래 이전 기록, `work/retouch_evaluation.md`, `work/REVIEW.md`.

## 이전 기록 (2026-10-02 멀티샷 일괄 추출·자동 배치, 이번 변경 전)
### Status

PARTIAL — Android 시간 순서 배치에서 **다중 선택 → `모두 추출하고 자동 배치` 한 번 → 자동 균등 배치 미리보기 → 적용**
흐름을 구현했다. 자동 테스트와 canonical `scripts/check.sh`는 통과했다(아래 Validation). **실기기 시나리오(요구 25)는
미실행**: 이 환경에 연결된 Android 기기/에뮬레이터가 없다(`adb devices` 빈 목록, `adb reverse --list` →
`error: no devices/emulators found`). fake 통과로 대체하지 않았다.

### Changed

- **Picker** (`EditorRoute.MultiShotToolSheet`): 시간 순서 배치의 추가는 `PickMultipleVisualMedia(남은 자리)`, 남은 자리 1장·
  자유 배치·교체는 단일 `PickVisualMedia`. 실행마다 controller의 `requestPick(replaceKey)`가 id·교체 대상·한도를 세션에
  기록하고, 결과는 `onPicked(id, uris)`로만 받는다(id는 `rememberSaveable`). 다른/이전 요청·닫힌 시트·새 세션의 결과는 버림,
  한도 초과 목록은 전체 거절+안내(`multishot_too_many`), 빈 결과는 무변경. 반환 순서 그대로 기존 뒤에 붙이고 재정렬·중복 제거 없음.
- **일괄 가져오기**: 순차 디코딩(기존 `loadPhoto`/1080 preview/thumbnail). 읽히지 않는 사진은 자리에 남겨
  `ShotStatus.Unreadable` + 사유로 표시, "n단계 사진을 불러오지 못했어요…"로 실행 차단, 교체/삭제로 해결. 읽기 취소 시 아직
  못 읽은 사진만 제거.
- **일괄 실행** (`MultiShotController.runAll`): 누르는 순간 표시 순서를 확정(`orderConfirmed`)하고 미완료 항목만 — 주인공
  먼저(입력 렌더 중이면 "준비" 단계로 대기), 이어 추가 사진을 표시 순서로 — 기존 `SegmentationProvider`로 한 장·한 세션씩
  `person` 추출. 비어 있지 않은 후보가 정확히 1개면 자동 채택·저장, 0/다수면 그 사진에서 멈춤(`RunPhase.Choosing`, 후보·점·문구
  보정), 그 사진의 `추출 완료`가 저장 후 **자동 재개**. 모든 항목 저장 직후 **한 번만** 기존 `MultiShotLayout`(positioned+faded)
  으로 배치하고 `laidOutOrder`/`keptPositions=false` 갱신, 배치 위치 패널을 표시 → 바로 적용 가능. 이미 전부 추출된 상태면
  네트워크 없이 즉시 배치(`자동 다시 배치`: 크기·회전 유지).
- **오류·취소**: 실패(서버/설정/추출/재읽기/저장)는 그 항목에서 실행 종료+사유, 이후 사진 미전송·배치 안 함, 재시도는 실패
  항목부터(완료 항목 재업로드 없음, 저장만 실패한 선택은 업로드 없이 재저장). `진행 취소`/오버레이 취소/시트 취소/문서 변경/
  설정 변경은 실행 종료(설정 변경 후 자동 재전송 없음), 늦은 업로드는 닫고 버림, 저장 중 닫힌 파일은 회수. 실행 토큰
  (`Session.run`)으로 끝난 실행의 다음 단계를 막고, 대기 중에는 코루틴을 들고 있지 않아 `startWork` 중첩 취소가 없다.
  실행 중/선택 대기 중에는 추가·선택·순서·삭제·교체·모드·패널 전환·재실행이 막힌다(`MultiShotState.busy`).
- **시트**: 타임라인 아래에 선택 타일/패널과 무관하게 주 동작(TertiaryPill; 적용만 accent) — `모두 추출하고 자동 배치` /
  `남은 사진 추출하고 자동 배치` / `이 순서로 자동 배치` / `자동 다시 배치` + `순서 뒤집기`, 실행 전 전송 안내(원본 포함·host),
  진행 "n/N · 단계", 구성 변경 안내. 별도 `이 순서가 맞아요`와 `confirmOrder()` 제거, 시간 배치의 사진별 `피사체 추출` 버튼 숨김
  (대기 중인 선택만 표시). 자유 배치는 기존 그대로.
- **Overlay**: 균등 배치의 접근성 설명을 경로 대신 "원본 발밑 높이의 기준선 … N개의 목표 위치"로.
- `specs/multishot.md`(§2 Adding, §2.1 흐름, Hero tile, 적용, §8 lifecycle, §9 tests), `work/decisions.md` **D090**(D086/D087의
  필수 수동 단계를 대체, D088 수학·D089 서버 경계 유지).
- core 제품 코드 변경 없음(배치 수학·저장 형식·렌더 미변경).

### Files

- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorRoute.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/multishot/{MultiShotController,MultiShotState,MultiShotSheet,MultiShotOverlay}.kt`
- `feature/editor/src/main/res/values/strings.xml`
- `feature/editor/src/test/kotlin/com/diffuse/feature/editor/tools/multishot/{MultiShotControllerTest,MultiShotSheetTest,MultiShotToolTest,MultiShotGoldenTest}.kt`
- `feature/editor/src/test/screenshots/multishot_sheet_timeline.png`(재기록), `multishot_sheet_run.png`(신규) — 나머지 골든 불변(md5 동일)
- `specs/multishot.md`, `work/decisions.md`, `work/RESULT.md`

### Validation

| Command | Result |
|---|---|
| `./gradlew --offline --quiet :feature:editor:testDebugUnitTest --tests '*MultiShot*'` | 변경 전 baseline 통과, 변경 후 92 tests / 0 failures |
| `./gradlew --offline --quiet :feature:editor:recordRoborazziDebug --tests '*MultiShotGoldenTest*'` | timeline 변경·run 신규만 갱신, 두 장 육안 확인 |
| `scripts/check.sh` (lint, detekt, 전체 testDebugUnitTest — core:imaging/core:data 포함, verifyRoborazziDebug, dependencyGuard) | 1차: detekt MaxLineLength 8건(이번 변경)으로 실패 → 줄 바꿈 수정 후 **exit 0** |
| `git diff --check` | exit 0 |
| 실기기(`adb devices`) | 기기 없음 — 미실행 |

- 자동 테스트 근거(정상 흐름은 `requestPick`/`onPicked` → `runAll`에서 시작, `placeByOrder()` 미호출, 저장된 draft placement를
  `MultiShotLayout.onCanvas`로 변환한 anchor 수치 확인):
  - 3장: 비중앙 주인공(x≈0.25)으로 `[A, 원본, B]` = hx∓1/3, hy 동일, opacity 25/70%, scale 1/rotation 0, 업로드 3회·세션 3개 닫힘, 적용 1회.
  - 6장: 크기/종횡비가 다른 사진(60×20, 20×40), 중복 URI 포함 Picker 순서 유지, `[A, B, 원본, C, D, E]` = hx−2/6…+3/6, 5단계 opacity.
  - 다중 후보 중단 → 재실행/선택/패널/순서 변경 무시 → 선택+추출 완료로 자동 재개·배치; 주인공 0명 → 점 보정 후 재개.
  - 중간 실패 시 이후 미전송·배치 없음, 재시도는 남은 2장만(업로드 3→5회); 진행 취소+늦은 업로드 닫힘; 저장 중 설정 변경;
    저장 중 닫기 파일 회수; 주인공 렌더 대기; 전부 Ready면 네트워크 없이 배치·수동 보정 유지 후 재배치 시 크기/회전 유지;
    배치 후 추가 시 새 사진만 추출; Picker 한도/초과/빈/이전·늦은 결과; 자유 배치 단일 Picker·실행 없음; 불러오기 실패 사진; 읽기 취소.
  - `MultiShotToolTest`: 실제 `EditorViewModel`·`ImageLoader`로 2장 선택 → 실행 → (공용 fake는 항상 2명이라) 3회 선택 대기·자동 재개
    → 슬롯 anchor 확인 → 적용 1 history step → Undo/Redo.
  - 기존 저장 Free/Path/Even 재열기·모드 전환 무네트워크/무재배치, persistence(저장·재열기·export·복제) 테스트는 변경 없이 통과.

### Review Notes

- 기존 수동 흐름 테스트에서 `confirmOrder()` 호출과 "확인만으로는 배치 아님" 단정을 제거했다(기능 삭제에 따른 변경). 자유 배치·
  저장 복원·취소 회귀 테스트는 그대로다. `placeByOrder()`는 배치 패널의 수동 동작으로 남아 있고 이제 배치 패널을 표시한다.
- "유효 후보"는 비어 있지 않은 mask로 정의했다(수동 추출 경로에도 동일 적용 — 빈 mask는 이제 "찾지 못함"으로 취급).
- 저장 실패 시 실행은 끝나지만 SAM 세션과 선택은 유지되어, 재실행이 업로드 없이 재저장한다.
- 진행 중 표시는 바쁜 오버레이(단계 문구)와 시트의 "n/N · 단계" 줄 두 곳. 오버레이 라벨은 인자 없는 문자열 계약이라 번호는 시트에만 있다.
- 실행 버튼은 accent가 아닌 TertiaryPill(DESIGN의 하나의 accent=적용 유지). 시트는 45% 높이 안에서 스크롤된다.

### Known Issues

1. **실기기 미검증(요구 25)** — 새 프로젝트에서 원본+2장, 원본+5장 정상 흐름, 다중 후보 해결, 취소/재시도, 수동 보정 유지,
   적용→Undo/Redo→재시작→PNG export, 서버 요청 수 기록이 남아 있다. 필요: ADB 기기, SAM 3 서버(`127.0.0.1:15039` reverse 재확인),
   `./gradlew --offline :app:assembleDebug -Pdiffuse.localCreds`. SAM 업로드 한도(6회/60초) 때문에 6장 시나리오 간 1분 이상 간격 필요.
2. 서버 멀티샷 API REVIEW **R1(ASGI 태스크 취소 후 후속 추출)**/**R2(큰 정수 metadata 500)**는 미해결 그대로 — 이번 Android 작업과 무관
   (`work/REVIEW.md`, 아래 이전 기록).
3. 이전 미완료 유지: 앱 골프/얇은 경계 품질·진짜 offline·12MP/PSS 평가, 실제 동작 연속 사진 품질, 피부 보정 D084 지원 종류 0개·
   품질/lifecycle 평가, Jev 미구현 — `work/retouch_evaluation.md`, `work/REVIEW.md`.
4. 미적용 draft의 process death 복구는 범위 밖(재시작 세션은 이전 Picker 결과·실행을 받지 않음).

---

## 이전 기록 (2026-10-01 멀티샷 HTTP API, 이번 변경 전)

### Status

PARTIAL — 3–6장 멀티샷 HTTP API(`server/multishot/`, `POST /v1/multishot`, `GET /health`)를 구현하고
GPU 없이 자동 검증했다: `server/multishot/scripts/check.sh` exit 0(131 passed), `git diff --check` exit 0.
실제 SAM 3(로컬 검증 인스턴스 127.0.0.1:18091)로 3장/6장/point/추출 없음 smoke를 실행해 계약대로 동작했다.
**미검증: 같은 인물의 실제 동작 연속 사진(골프 등) 품질** — 권한 있는 연속 동작 사진이 없어, 서버 처리 승인된
NASA 공개 초상(같은 인물·같은 세션 4컷 + 다른 인물 2장)으로 대신했다. Android 연동·배포는 범위 밖.

### Changed

- 신규 독립 서비스 `server/multishot/` (FastAPI, 자체 토큰·포트 8086·venv·spool, 모델 미적재, SAM 3는 HTTP).
  - 입력: 같은 필드명 `images` 3–6회 반복, multipart 등장 순서 = `input_index` = 시간 순서. 파일명/EXIF 시각/
    완료 순서로 재정렬하지 않고 같은 bytes도 각각 한 장. 선택적 `metadata`(`spacing`, `subject_points`) 엄격 검증.
  - 순서·마지막 입력=배경·주인공, D088 슬롯(3장 `[0,2,1]`, 6장 `[0,1,5,2,3,4]`), 앵커 하단 중앙→목표점,
    contain fit, opacity 25→70%, 반경 2 feather 1회, premultiplied bilinear source-over, `MultiShotOp.protect`
    정수식 주인공 보호를 NumPy로 재현(scale 1, rotation 0). 잘림은 clamp 없이 `subject_clipped` warning.
  - SAM adapter: 업로드(≤1080 px RGB PNG) → text `person`(0.5/20) 또는 point 1개(`multimask:false`), `format=png`
    밝기 mask 검증 → finally DELETE(5 s). 410만 1회 재업로드·재생, 0/다수 후보는 422 명시 실패.
  - 운영: 인증 → admission(1 실행/0 대기, 429+Retry-After) → 제한된 스트리밍 파싱(20 MiB/125 MiB/40 MP/7 part)
    → 전 입력 검증·정규화(EXIF·ICC→sRGB RGBA, ≤4096) → 순차 추출·합성(worker thread). 300 s 전체 deadline,
    upstream 10/60 s(잔여 예산 한도). worker가 살아 있는 동안 slot·spool 유지, stop 신호 후 다음 단계 미시작.
  - 200 = multipart(`metadata` JSON + `image` PNG, EXIF 없음). 오류 JSON `{"error","request_id"[,"image_index"]}`.
- 문서: `specs/multishot_api.md`(API v1 계약), `server/multishot/README.md`(환경변수·설치·check·curl 3/6장·health·
  추출 없음/모호함·point 재요청·잘림 예제), `work/decisions.md` D089.

### Files

- `server/multishot/` 신규: `app/{config,errors,upload,options,images,sam,compose,pipeline,main,serve,__init__,__main__}.py`,
  `tests/{conftest,test_compose,test_api,test_lifecycle,test_sam_adapter}.py`, `scripts/{check.sh,smoke_client.py}`,
  `README.md`, `requirements.txt`, `requirements-test.txt`, `pytest.ini`, `.gitignore`(`.venv` 제외)
- `specs/multishot_api.md` 신규, `work/decisions.md`(D089 추가), `work/RESULT.md`
- Android·retouch·SAM 3 저장소·기존 멀티샷 코드는 변경하지 않음

### Validation

| Command | Result |
|---|---|
| `server/multishot/scripts/check.sh` | exit 0 — 131 passed (compose 39, api 56, lifecycle 8, SAM adapter 28), GPU·weight 불필요 |
| `git diff --check` | exit 0 |
| `scripts/check.sh` (Android canonical) | **미실행** — 서버만 추가, Android/공통 코드 변경 없음 |
| `server/retouch/scripts/check.sh` | 미실행 — retouch 미변경 |
| 실 SAM 3 smoke (`scripts/smoke_client.py`, 서버 `python -m app`, SAM 127.0.0.1:18091) | 아래 표 |

자동 테스트가 픽셀로 확인한 것(fake SAM = 색 기반 분할, 식별 가능한 색 사각형 fixture):
- 3/4/5/6장 200, 0/1/2/7장 422(SAM 미호출). 같은 파일명·역순 파일명·역순 EXIF 시각·같은 bytes 3장에서 호출
  순서·슬롯이 multipart 순서. 입력 0/1 교환 시 해당 위치의 색·opacity(25%↔70%)·겹침 상하가 함께 바뀜.
- 실제 결과 픽셀에서 이동량(+5/+5, +5/+20 px)과 source-over 값, 배경 복귀, 주인공 내부 bit-exact, 주인공 경계
  weight 153 premultiplied lerp, 반투명 배경 alpha(100) 보존, 4096 축소(5000×50→4096×41)·SAM 1080 업로드, 결과 EXIF 없음.
- 치우친 주인공(hx 0.3) + 크기·종횡비 다른 5장(scale 0.67–2) + EXIF orientation 6 JPEG, spacing 0.8: metadata 앵커가
  D088 값, 결과 픽셀의 피사체 하단 중앙이 목표 x ±1.5 px, y는 주인공 높이(feather 허용) 일치.
- 6장 hx 0.5 spacing 1 → input 4 `subject_clipped`(x=1, 왼쪽 절반 픽셀 확인), 0.8·0 → warning 없음.
- Kotlin `MultiShotSubjectTest`/`RenderTest`/`LayoutTest` 수치(2 px ramp 204/153/102/51, 128/127, 192, 100/50, 슬롯
  1/6…1, 0.2333…0.9, opacity 0.25…0.7, premultiplied lerp 0/64/128/192/255)를 독립 참조 구현과 비교.
- 거절: 빈/손상 PNG·JPEG/텍스트 422, GIF/BMP/WebP/APNG 415(MIME·확장자 무시), 픽셀·이미지 bytes 초과 413
  (image_index), Content-Length 없는 과대 body 413, malformed multipart/중복 metadata/미지 part 400,
  metadata 20종(NaN/Infinity/1e999/bool/범위/개수/중복 키/미지 필드) 400 — 모두 SAM 미호출.
- 추출 없음/다중 인물 422 + image_index, 실패 이후 이미지 미호출, point로 대상 선택, 주인공 추출 실패도 422.
- lifecycle: 401(누락/오답/Basic/빈 Bearer) 시 body receive 0회·WWW-Authenticate, health 200/503(토큰·URL 미노출),
  동시 요청 429+Retry-After, deadline 504 후 worker 생존 동안 in_flight 1·spool 유지·새 요청 429 → 종료 후 해제·
  다음 이미지 미추출, disconnect 499·결과 폐기, 느린 업로드도 deadline 504, upstream 503/502/504 후 spool 삭제,
  요청 간 이미지 혼합 없음, 500 응답에 내부 정보 없음.
- adapter(httpx MockTransport): 업로드 multipart·text/points body·Bearer, 밝기 PNG→mask, LA/RGBA/회색값/크기 불일치/
  base64·JSON 오류 502, 401/429/5xx/OOM/연결 실패 503, read timeout 504, 410 1회 복구(같은 prompt 재생)·2회 503,
  모든 경로 DELETE, DELETE 실패는 결과를 바꾸지 않고 request_id·오류 종류만 로그, upload 후 stop 시 prompt 없이 DELETE,
  timeout이 잔여 예산으로 제한, 실제 앱+실제 adapter 종단(세션 3개 순서·삭제).

실 SAM 3 smoke (사진: `~/retouch-eval/nasa`, NASA 공개 초상 — 저장소에 넣지 않음, 결과는 세션 scratchpad):

| 입력 (순서) | point | 결과 | 시간 | 서버 RSS peak(VmHWM) |
|---|---|---|---|---|
| 3장 `jsc2011e017045, 046, 047`(같은 인물 연속 컷) | 없음 | 200, 1536×1920, 슬롯 [0,2,1], 앵커 x 0.1522/0.8189/0.4855, y 모두 0.9546, 0.25/0.7, warning input 0·1 잘림 | 11.4 s | 63 → 191 MB |
| 6장 `jsc2010e013041, jsc2016e000683, 045, 046, 048, 047`, spacing 0.8 | 없음 | 200, 슬롯 [0,1,3,4,5,2], x 0.2189/0.3522/0.6189/0.7522/0.8855/0.4855, y 동일, opacity 0.25…0.7, 잘림 input 0–4 | 22.5 s | 208 MB |
| 3장, point `[0.5,0.6]`(입력 0·2) | 혼합 | 200, 앵커 y 0.9852 | 11.6 s | 214 MB |
| 3장 `045, kodim23(앵무새), 047` | 없음 | 422 `subject_not_found`, image_index 1 | 5.4 s | — |
| curl `-F images=@` ×3 + `--form-string metadata`(spacing 0.6, point) | 혼합 | 200 multipart, spacing 0.6 반영 | 12.1 s | 215 MB(최종) |
| 2장 / 잘못된 토큰 | — | 422 `invalid_image_count` / 401 | <0.2 s | — |

결과 이미지를 육안 확인: 주인공(마지막 입력) 원래 자리·선명, 앞선 입력은 인물만 반투명으로 슬롯에, 사각형 crop 없음.
각 요청 후 spool 비어 있음, SAM 세션 active 0. 운영 SAM 서비스·GPU 모델은 건드리지 않음(로컬 서버만 일시 기동 후 종료).

### Review Notes

- **SAM 3 업로드 rate limit**: 참조 배포는 클라이언트 IP당 업로드 6회/60초. 6장 요청이 한도를 다 쓰므로 직후 요청은
  429 → 계약대로 503 `segmentation_unavailable`(smoke에서 실제 발생 후 간격을 두고 재실행). 자동 대기/재시도는
  작업서(410 외 재시도 금지)에 따라 넣지 않았고 README·D089에 기록. 운영 시 SAM `SAM3_UPLOAD_RATE` 조정 여부는 결정 필요.
- 오류 코드 중 작업서에 없던 것: 400 `invalid_request`(multipart 구조)와 `invalid_metadata`(metadata 내용) 구분,
  500 `internal_error`. 410 외 SAM 4xx(400/404/413/415)는 502, ConnectTimeout은 503, read/write timeout은 504.
- 합성은 Kotlin과 같은 수식·정수 반올림이지만 Skia와 bit-identical은 아니다(비정수 이동의 bilinear, 캔버스 경계 처리).
  같은 정규화 사진·mask → 같은 bytes(테스트로 확인).
- 주인공 보호 weight = `heroPhotoAlpha × feather(mask)`(앱 hero 파일의 alpha와 같음). 앵커·bounds·잘림은 이진 mask 기준.
- MPO(카메라 JPEG)는 JPEG 정지 영상으로 받아 frame 0 사용. 손상 ICC 프로파일은 무시(sRGB 가정).
- 인물 초상은 폭이 넓어 세로 캔버스에서 기본 간격에도 잘림 warning이 흔하다(clamp 금지 계약대로).

### Known Issues

1. **실제 동작 연속 사진(골프·투구 등) 품질 미검증** — 필요: 권한 있는 같은 인물 연속 사진 3장/6장. smoke는 정지 초상으로
   wire·배치·보호·성능만 확인했다.
2. 실 SAM의 410 복구 경로는 mock으로만 검증(세션 강제 만료 불가).
3. Android 연동, 배포/공개 포트, 비동기 job은 범위 밖(미구현). Python RSS는 Android PSS 목표와 비교하지 않았다.
4. 이전 작업 미해결 유지: 앱 멀티샷 실기기 3/6장·offline·12MP 성능·골프 품질 PARTIAL(아래 이전 기록), 피부 보정
   D084 지원 종류 0개·품질/lifecycle 평가, Jev 미구현 — `work/retouch_evaluation.md` §7, `work/REVIEW.md` 그대로.

---

## 이전 기록 (2026-10-01 장수별 균등 배치, 이번 변경 전)

### Status

PARTIAL — 장수별 균등 배치(work/tasks.md 2026-10-01 최신)와 추가 5장(총 6장) 확장을 구현하고 자동 검증했다:
`scripts/check.sh` exit 0, `git diff --check` exit 0. **실기기 3장/6장(중앙·치우친 원본, 기본·좁힌 간격),
offline/export/복제, 12MP+추가5장 성능, 골프 품질은 미실행** — 이 세션에 연결된 기기가 없다(`adb devices` 비어 있음).
이전 R1(premultiplied 보호)/R2(`orderConfirmed`, hero 보존)/D087 흐름은 유지하고 회귀를 재실행했다. REVIEW 판정은 바꾸지 않았다.

### Changed

- **배치 방식** (`TimelineArrangement { Path, Even }`, `TimelineLayout.arrangement/spacing`): 새 제안은 **장수별 균등
  배치**가 기본. 저장된 timeline에 필드가 없으면 기존 경로 배치(Path)로 읽어 픽셀·좌표 불변. JSON은 Even일 때만
  `"arrangement":"even","spacing"`을 기록. 알 수 없는 arrangement, Even인데 spacing 없음/범위 밖/비숫자 → 로드 거부.
- **수학** (`MultiShotLayout.target`→`evenTarget/heroSlot/evenSlots`): N=추가+1(원본 포함), 원본 슬롯
  `k=⌊(N−1)/2⌋`(짝수는 가운데 두 칸 중 왼쪽), 추가 사진은 확인한 시간 순서대로 k를 뺀 슬롯 0..N−1.
  `x_j = hx + (j−k)·s/N`, `y_j = hy`. 기존 `placedAt`으로 앵커를 슬롯에 맞추므로 사진 속 인물 위치·종횡비·
  scale·rotation을 반영, 무누적, clamp 없음. 원본은 이동하지 않는다.
- **추가 5장**: `MAX_SHOTS = 5`(→ `MAX_ITEMS`, 모델 검증, 렌더 캐시 `SHOT_CACHE_ENTRIES` 5개·각 preview 크기).
  6번째 추가(총 7장)는 controller·시트가 거절. 추출/디코딩은 기존대로 한 장씩.
- **UI (공통 배치 위치 단계)**: "총 N장 (원본 포함, 최대 6장)", **장수별 균등 배치 | 기존 경로 배치**, 버튼
  "이 순서로 N장 균등 배치"(정리 후 "위치 다시 배치"), 현재 위치 유지, **간격** 슬라이더(0–100%, 0은 겹침 안내),
  원본 고정·치우침 안내, 짝수 장 "가운데 두 칸 중 왼쪽" 안내. 균등 배치에서는 방향(시간 화살표)을 숨긴다.
  경로 배치는 기존 방향·거리 그대로.
- **캔버스 가이드** (`MultiShotOverlay`): 균등 배치에서는 원본 앵커를 지나는 수평 점선 기준선 + 원본 슬롯 사각형 +
  번호 슬롯(실제 위치 일치 시 채움/아니면 링). overlay에만 그리며 preview/export에 굽지 않는다.
- **controller**: `setSpacing`, `setArrangement`(확정 순서가 있을 때 위치만 갱신, 없으면 설정만). 추가/삭제/교체/순서
  변경은 재확인 필요 상태로 두고 위치를 덮어쓰지 않으며, 다음 명시적 배치가 새 N을 사용.
- **문서**: `specs/multishot.md`(§1·§2 장수, §2.1 배치 단계, §3 모델, 신규 §4.3, §7 JSON, §8 캐시, §9 테스트),
  `specs/{edit_model,render}.md` 장수/캐시, `work/decisions.md` D088.

### Files

- core:imaging: `model/{Operation,EditDocumentJson}.kt`, `render/MultiShotLayout.kt`; tests
  `model/MultiShotModelTest.kt`, `render/{MultiShotLayoutTest,MultiShotRenderTest}.kt`
- core:data: test `MultiShotPersistenceTest.kt`(5장 균등 배치 저장/재열기/export/복제)
- feature:editor: `EditorRoute.kt`, `res/values/strings.xml`, `tools/multishot/{MultiShotController,MultiShotState,
  MultiShotSheet,MultiShotOverlay}.kt`; tests `tools/multishot/{MultiShotControllerTest,MultiShotSheetTest}.kt`,
  golden `multishot_sheet_timeline.png` 재기록(총 3장·균등 배치 단계)
- 문서: `specs/{multishot,edit_model,render}.md`, `work/decisions.md`(D088), `work/RESULT.md`

### Validation

| Command | Result |
|---|---|
| `./gradlew --offline -q :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest --tests '*MultiShot*' --tests '*HistoryStack*'` | exit 0 — Model 16, Layout 22, Render 24, Subject 2, History 9, Persistence 10, Controller 48, Sheet 14, Golden 3, Tool 6 |
| `:feature:editor:recordRoborazziDebug --tests '*MultiShotGoldenTest*'` | timeline golden 재기록, 육안 확인 |
| `scripts/check.sh` | 1차 exit 1(detekt: JSON `timeline` 복잡도 16) → arrangement 파싱 분리 후 exit 0; persistence 테스트 이름 길이 수정 후 최종 **exit 0** |
| `git diff --check` | exit 0 |
| 실기기 3/6장, 중앙·치우친 원본, 기본·좁힌 간격, Undo/Redo, 재시작, offline, JPEG/PNG, 복제 | **미실행** — 기기 없음 |
| 12MP 원본 + 추가5장 성능(p50 <100ms, PSS <250MB, 잔류, 적용/export peak, 추출 지연), 골프 품질 | **미실행** — 기기·사진 없음 |

자동 검증으로 확인한 계약(모두 **변환된 앵커 좌표** 기준):
- 슬롯: 3장 `[A, 원본, B]`, 5장 `[A, B, 원본, C, D]`, 6장 `[A, B, 원본, C, D, E]`; heroSlot 2/4/5/6장.
- 수치(hx=0.5): 3장 1/6·5/6, 5장 0.1·0.3·0.7·0.9, 6장 1/6·1/3·2/3·5/6·1, s=0.8 → 0.2333·0.3667·0.6333·0.7667·0.9,
  s=0 → 모두 0.5. 3장(W/3)과 6장(W/6) 간격이 실제로 다름. 치우친 원본(hx=0.3)은 그룹도 치우치고 clamp 없음.
- 서로 다른 크기·종횡비·인물 위치·scale 1.81·rotation 48.8°의 사진에서 3장/6장 × s=1/0.8 모두 슬롯 x와 y=hy 일치,
  scale/rotation 유지, 재실행 무누적, preview/export 크기 무관, 순서 반전 시 슬롯 교환.
- controller: 잔상 선택 → 배치 위치 → 3장 배치([A,원본,B], y 동일) → 3장 추가·추출(위치 불변, 재확인 필요) →
  6장 배치([A,B,원본,C,D,E], W/6) → opacity 25/36.25/47.5/58.75/70% → 적용(5 shot, Even, valid). 삭제+반전 후 4장 재배치,
  간격 변경 시 크기/회전/opacity 유지, 7번째 거절.
- 시트(실제 클릭): 총 N장 표시, "이 순서로 3장 균등 배치"/유지/경로 전환 클릭, 간격 100%, 균등 배치에서 방향 미표시,
  짝수 장 안내, 경로 배치의 방향·거리.
- Render: 5개 잔상의 시간 순서 z-order(순서 반전 시 최상단 변경)와 주인공 보호. Model/JSON: 5개 허용·6개 거절,
  기존 4개 유지, Even/Path 왕복, 레거시 timeline = Path, invalid arrangement/spacing 거부. Persistence: 5장 균등 배치
  저장·재열기·export 픽셀 동일, 복제 후 원본 삭제, hero 보존.

### Review Notes

- **원본 중심 해석**: 원본은 배경 자체 픽셀이라 이동하지 않는다. "가운데"는 원본 슬롯이며 W/N 칸 묶음이 원본 앵커에
  맞춰 이동한다. 원본이 칸 중심에 있지 않거나 N이 짝수이면 화면 정확한 N등분과 다르며 시트가 이를 안내한다.
  원본 인물 자체를 화면 정중앙으로 옮기는 기능은 구현하지 않았다(clean plate 필요, 작업서 Notes 대로).
- 6장 기본(hx=0.5)에서 마지막 앵커는 오른쪽 경계(x=1)라 그 피사체가 잘린다. 기존 잘림 안내 + 간격 조절로 해결하며
  자동 축소/clamp는 하지 않는다.
- `DEFAULT_DIRECTION_DEG`는 그대로(0°). 균등 배치에서는 방향을 쓰지 않는다. 경로로 전환하면 기존 기본값이 쓰인다.
- 모델 기본값 `arrangement = Even`은 새 제안용이다. 저장 문서는 필드 유무로 결정되므로(없음 = Path) 의미 변화 없음.
- 실제 UI 클릭→좌표 변화는 시트 테스트(클릭→콜백)와 controller 테스트(콜백→좌표·commit)로 나뉘어 검증된다.

### Known Issues

1. **실기기·실사진·성능 미실행**(위 표). 필요: Android 기기, SAM 3 서버, 권한 있는 같은 인물 연속 사진(3장·6장, 골프 포함).
   기록할 것: 시간 순서, 슬롯 ID, 변환된 앵커 좌표, 장수, 기본/좁힌 간격 결과, 성능 수치.
2. 주인공 입력 캔버스 렌더 실패 시 재시도 버튼 없음, 프로세스 종료 시 미적용 draft 파일 잔류 — 기존과 동일.
3. 피부 보정·Jev 미완료 인계는 아래 이전 기록, `work/retouch_evaluation.md` §7, `work/REVIEW.md`에 그대로 남아 있으며
   완료 처리하지 않았다(D084 피부 지원 종류 0개).

---

## 이전 기록 (2026-10-01 위치 배치·시간 순서 배치·Review Fix Pass, 이번 변경 전)

### Status

PARTIAL — 위치 배치(work/tasks.md 2026-10-01 최신) 구현과 자동 검증 완료: `scripts/check.sh` exit 0.
**실기기 3/5순간 배치, 오른쪽→왼쪽·거리·개별 이동, 적용 직후 Undo/Redo, 재시작, offline, JPEG/PNG,
복제(요구 24)와 골프·12MP 성능(요구 25)은 미실행** — 이 세션에 연결된 기기가 없다(`adb devices` 비어
있음, 리뷰어의 `127.0.0.1:15039` 연결 실패). 이전 Review Fix Pass(R1 premultiplied 보호, R2
`orderConfirmed` 분리)는 유지·재실행했다. REVIEW 판정은 리뷰어 몫이며 바꾸지 않았다.

### Changed

- **공통 배치 단계** (`MultiShotSheet`, `MultiShotState.TimelinePanel`): 타임라인 아래 **사진별 편집 | 배치
  위치** 두 단계. 배치 위치는 어떤 타일이 선택돼 있어도 한 번에 열린다(잔상 타일에서도). 주인공 타일에서 방향·
  거리·강도·배치 버튼을 제거하고 이 단계로 옮겼다(한 곳에서만 관리). 주인공 타일은 "원래 자리에 그대로" 안내 +
  기준점 조정 + 주인공 다시 선택. 잔상 배치 행에 배치 위치 바로가기. 배치 위치를 열면 열린 SAM 선택은 닫히고
  (Picked로 복귀) 캔버스는 합성을 보여 준다.
- **이 순서로 위치 배치 / 위치 다시 배치** (`placeByOrder`): 보이는 순서를 확정하고 즉시 각 잔상 앵커를 그 순서의
  슬롯에 놓으며 시간 잔상 프로파일(1장 50%, 2–4장 25→70% × 강도)을 적용. 크기·회전·mask/ref 유지, 무누적,
  서버 호출 없음. **현재 위치 유지** (`keepPositions`): 순서만 확정하고 위치·opacity 그대로.
- **상태 분리**: 순서 확정(`orderConfirmed`), 그 순서에 대한 위치 정리(`laidOutOrder`, `keptPositions`, 메모리),
  저장된 ShotPlacement(렌더 기준)를 구분. 시간 모드 적용은 위치가 확정 순서에 대해 정리돼야 가능(`positionsCurrent`).
  순서 변경은 위치를 바꾸지 않고 "바뀐 순서가 위치에 아직 반영되지 않았어요" 안내. 저장된 시간 합성은 재열기 시
  정리된 상태로 열린다(D086 적용 조건상 이미 배치됨). 새 저장 필드 없음.
- **부족 항목 안내** (`placeBlocker`): 사진 없음 / 주인공 미선택 / "n단계 사진의 피사체를 추출해 주세요". 배치·
  확정을 막고 사진 제외나 자유 모드 전환을 하지 않는다.
- **기본 방향 왼쪽→오른쪽** (`DEFAULT_DIRECTION_DEG = 0f`): 새 제안·레거시 Free의 첫 시간 배치에만 적용. 저장된
  timeline은 `directionDeg`가 필수라 기존 문서 의미 불변. 방향은 출발→도착 이름: "왼쪽 → 오른쪽 (오래된 → 최신)",
  "오른쪽 → 왼쪽", 대각선 4종. 방향·거리는 확정된 순서가 있으면 실제 좌표를 갱신, 없으면 설정만 선택.
  거리 0 안내.
- **캔버스 경로/슬롯 표시** (`MultiShotOverlay`, `slotMarkers`): 배치 위치 단계에서만 시작→주인공 점선과 번호 슬롯.
  해당 사진 앵커가 실제로 슬롯 위에 있을 때만 채움, 손으로 옮기면 링. overlay에만 그리며 preview/export에 굽지 않음.
  canvas 정규 좌표 → `imageRect`로 변환하므로 확대/이동과 무관.
- **개별 보정 안내**: 잔상 편집에 "끌면 이 피사체가 옮겨져요. 다음 위치 다시 배치가 직접 옮긴 위치를 덮어써요",
  기준점 조정 중 "지금은 기준점만 옮겨져요".
- **문서**: `specs/multishot.md` §2.1 흐름 재작성, §3 기본값, §4.2 기본/공간 순서 계약/덮어쓰기, §9 테스트;
  `work/decisions.md` D087(D086 흐름 변경, 모델·렌더 유지).

### Files

- core:imaging: `model/Operation.kt`(기본 방향 상수); test `render/MultiShotLayoutTest.kt`
- feature:editor: `EditorRoute.kt`, `res/values/strings.xml`, `tools/multishot/{MultiShotController,MultiShotState,
  MultiShotSheet,MultiShotOverlay}.kt`; tests `tools/multishot/{MultiShotControllerTest,MultiShotSheetTest,
  MultiShotFixtures}.kt`, golden `multishot_sheet_timeline.png` 재기록(배치 위치 단계, 잔상 선택)
- 문서: `specs/multishot.md`, `work/decisions.md`(D087), `work/RESULT.md`

### Validation

| Command | Result |
|---|---|
| `./gradlew --offline -q :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest --tests '*MultiShot*' --tests '*HistoryStack*'` | 1차 1 failed(시트 실제 클릭: 아래 Review Notes) → 수정 후 통과. 최종: Model 16, Layout 16, Render 23, Subject 2, History 9, Persistence 10, Controller 45, Sheet 12, Golden 3, Tool 6 — 0 fail |
| `:feature:editor:recordRoborazziDebug --tests '*MultiShotGoldenTest*'` | timeline golden 재기록, 육안 확인(배치 위치 단계·위치 다시 배치·현재 위치 유지 비활성) |
| `scripts/check.sh` | 1차 exit 1(detekt 2건: Overlay LongMethod, TimelineContent ReturnCount) → 분리 후 **exit 0** |
| `git diff --check` | exit 0 |
| 요구 24 실기기(3/5순간 왼→오, 순서만 변경 vs 배치 후 비교, 오→왼, 거리, 개별 이동, Undo/Redo, 재시작, offline, JPEG/PNG, 복제) | **미실행** — 기기 없음 |
| 요구 25 골프 품질·12MP+4장 성능(p50 <100ms, PSS <250MB, 잔류, 적용/export peak, 추출 지연) | **미실행** — 기기·사진 없음 |

자동 검증으로 확인한 계약:
- 계약 예시: H=(0.8,0.8), S=(0.2,0.8) → 2장 x 0.2/0.5, 4장 0.2/0.35/0.5/0.65 (`MultiShotLayoutTest`).
- 서로 다른 원본 위치·종횡비·크기(1.81)·회전(48.8°)의 4장에 대해 6방향 모두 **변환된 앵커**의 경로 투영이
  오래된→최신 증가, 주인공 미만. 왼→오는 주인공 높이 유지.
- 잔상 타일 선택 → 배치 위치 → A/B 뒤집기(위치 불변, 적용 불가) → 이 순서로 위치 배치 → 실제 앵커 x: B < A < 주인공,
  y 동일, 슬롯 모두 채움, opacity 25/70%, SAM 호출 없음 → 적용 시 draft와 동일한 shot·순서 [B, A] commit.
- 부족 항목(주인공 → 2단계 추출) 안내와 차단, 현재 위치 유지, 수동 이동 후 슬롯 해제·재배치 복원, 배치 후 취소 시
  문서 불변·세션 파일만 정리, 배치 단계 진입 시 열린 선택 종료.
- 시트(실제 `performClick`): 잔상 선택 상태에서 배치 위치 진입, 이 순서로 위치 배치 / 현재 위치 유지 / 방향 클릭,
  정리된 상태의 위치 다시 배치와 적용 활성, 2단계 미추출 안내와 배치 비활성.

### Review Notes

- 순서 확정과 위치 정리 분리는 메모리 상태다. 자유 배치로 timeline을 남긴 채 적용한 문서는 다시 시간 모드로
  가면 배치/유지를 다시 묻는다(파괴적 변경 없음). 저장 필드를 늘리지 않으려는 선택이며 D087에 기록.
- 시간 모드에서 방향·거리 조절은 순서가 확정돼 있으면 "현재 위치 유지"로 둔 위치도 다시 배치한다(안내 문구에 명시).
- 시트 테스트의 실제 클릭: 이 Compose 버전의 `performScrollTo`는 **가장 가까운** 스크롤 부모만 움직인다. 가로
  스크롤 행 안의 pill은 시트가 세로로 스크롤되지 않아 화면 밖(뷰포트 y≤901, pill y=933)에 남아 클릭이 전달되지
  않았다. 행을 먼저 시트 안으로 스크롤한 뒤 실제 클릭한다. 이전 Review Fix Pass에서 semantics 호출로 대체했던
  두 클릭도 실제 클릭으로 되돌렸다. 실기기 터치는 별도 확인 필요.
- 실제 UI 클릭 → 컨트롤러 좌표 변화는 시트 테스트(클릭 → 콜백)와 컨트롤러 테스트(콜백 → 좌표·commit)로 나뉘어
  검증된다. 하나의 테스트에서 실제 시트와 실제 컨트롤러를 묶은 종단 테스트는 없다.
- 큰 scale/rotation 잘림은 기존 잘림 안내 + 배치 위치 바로가기로 연결한다. 크기·회전을 자동으로 초기화하지 않는다.

### Known Issues

1. **요구 24–25 미실행**(기기 없음). 필요: 연결된 Android 기기, SAM 3 서버, 권한 있는 연속 사진(3·5순간, 골프).
   기록할 것: 입력 프레임 순서, 방향/거리, 변환된 앵커 좌표, 순서만 변경 vs 배치 후 캔버스, 성능 수치.
2. 주인공 입력 캔버스 렌더 실패 시 재시도 버튼 없음(시트 재진입 필요) — 기존과 동일.
3. 프로세스 종료 시 미적용 draft 파일은 폴더에 남음(전역 GC 없음) — 기존과 동일.
4. 피부 보정·Jev 미완료 인계는 아래 이전 기록, `work/retouch_evaluation.md` §7, `work/REVIEW.md`에 그대로
   남아 있으며 이번 작업으로 완료 처리하지 않았다(D084 피부 지원 종류 0개).

---

### 이전 기록 (2026-10-01 시간 순서 배치 구현 및 Review Fix Pass, 이번 변경 전)

#### Status

PARTIAL — 시간 순서 배치(주인공 + 앞선 1–4순간), 주인공 보호 합성, 4개 잔상, 저장/호환/복제, REVIEW
R1/R2(+R3 테스트), 적용 직후 Undo/Redo 버튼(N1), 기본 SAM 문구 `person`(N2)을 구현하고 자동 검증했다.
**실사진(3순간/5순간·골프), 실기기, 12MP 성능(요구 36–38)은 이 세션에 기기·SAM 3·권한 있는 연속 사진이
없어 실행하지 않았다.** 아래 "이전 기록"의 2026-10-01 실기기 증거는 이번 변경 전 빌드의 결과다.
2026-10-01 리뷰(CHANGES_REQUESTED)의 R1(투명 경계 색)·R2(자유 편집 시 주인공 손실)를 수정하고 R3 회귀 테스트를
추가했다(아래 Review Fix Pass). R4 실기기·성능은 여전히 PARTIAL.

#### Review Fix Pass (work/REVIEW.md 2026-10-01)

- **R1 (투명 경계 색 오염) — 수정.** 주인공 보호를 공유 `MaskBlend`(비 premultiplied 보간) 대신 신규
  `MultiShotOp.protect`로 수행한다: alpha와 premultiplied 색을 마스크 가중치로 보간한 뒤 un-premultiply,
  mask 0/255는 합성/입력 픽셀을 그대로 복사, 마스크는 bilinear 확대. `MaskBlend`와 기존 선택·지우기·채우기
  경로는 변경하지 않았다. 리뷰 재현 사례(투명 검정 입력 + 불투명 빨강, mask 128)는 red 255 / alpha 127이 된다.
- **R2 (자유 배치 편집 시 주인공 손실) — 수정.** 순서 확인 여부를 `Timeline.orderConfirmed` 플래그로 분리했다.
  자유 배치에서 사진을 추가·삭제·교체해도 주인공 ref/anchor·배치 설정·남은 변형은 유지되고 플래그만 false가 된다.
  시간 모드는 확인된 순서를 요구하므로(`isValid`, `canArrange`) 미확정 순서가 몰래 쓰이지 않는다. JSON에는
  false일 때만 `"orderConfirmed":false`를 쓴다(비 boolean은 로드 거부). 주인공 파일은 commit 시 kept 목록에 포함.
  timeline은 확정 순서·주인공·기본값이 아닌 설정 중 하나라도 있을 때만 기록 → 레거시 자유 문서는 그대로.
- **R3 — 테스트 추가.**
  - Render: 투명/반투명(alpha 100) 입력 × 불투명/50% 잔상 × mask 0/64/128/192/255에서 premultiplied 기대값과
    RGB/alpha 수치 비교, mask 0/255 정확 보존, preview/full 모두. **이전 `MaskBlend` 경로로 바꿔 실행하면
    실패함을 확인**(mask 64: red expected 255, was 191) 후 원복.
  - Controller: 새 draft(시간 배치 → 자유 전환 → 삭제 → 적용 → 재열기 → 시간 복귀: 주인공 Ready·anchor 유지,
    순서 재확인 요구, 확인 후 적용 가능, segmentation 재호출 없음), 저장된 시간 합성(자유에서 추가+교체 → 적용:
    hero·layout·남은 placement 유지, 순서 미확정), 취소(저장 파일 미삭제, 세션 파일만 정리).
  - Model/JSON: `orderConfirmed=false` 왕복, 시간 모드+미확정은 invalid, 확정 시 키 미기록, 비 boolean 거부.
- **R4** — 실사진·5순간·12MP 성능은 여전히 미실행(아래 Known Issues 1). 리뷰어의 Galaxy S25 smoke 결과는
  REVIEW.md에 있으며 이 수정 이후 빌드로 재실행하지 않았다.

| Command | Result |
|---|---|
| `./gradlew --offline -q :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest --tests '*MultiShot*' --tests '*HistoryStack*'` | exit 0 |
| 같은 render 테스트를 `MaskBlend` 경로로 임시 변경 후 실행 | exit 1(의도한 실패) → 원복 |
| `scripts/check.sh` | 1차 exit 1(detekt `LargeClass`: 테스트 클래스) → suppress 사유 주석 후 **exit 0** |
| `git diff --check` | exit 0 |

수정 파일: `core/imaging/.../model/{Operation,EditDocumentJson}.kt`, `render/{MultiShotOp,Renderer}.kt`,
`feature/editor/.../multishot/MultiShotController.kt`, 테스트 `MultiShotRenderTest`, `MultiShotModelTest`,
`MultiShotControllerTest`, 문서 `specs/{multishot,render}.md`, `work/decisions.md`(D086 보강), 이 파일.

#### Changed

- **모델** (`Operation.kt`, `EditDocument.kt`, `EditDocumentJson.kt`): `MAX_SHOTS = 4`.
  `MultiShot(id, shots, mode = Free, timeline = null)`, `Shot.anchor: NormPoint?`, `MultiShotMode`,
  `Timeline(order, layout, hero)`, `TimelineLayout(directionDeg 135°, distance 0.5, strength 1)`,
  `HeroMask(ref, w, h, anchor)`. `isValid`: 1–4 shot, order = 정확히 shot id 집합, 범위, 시간 모드는 hero +
  모든 anchor 필수. `drawingOrder`(시간 모드는 확정 순서). `withMultiShot(…, mode, timeline)`,
  `multiShotBase()`(MultiShot 직전 op, Crop 제외, Mask 유지 = 주인공 선택 입력).
  JSON: `mode`는 시간 모드일 때만, `timeline`은 순서 확정 시에만 기록 → 이전 문서는 그대로 저장된다.
  필드 없음 = 자유 배치. 알 수 없는 mode / 객체 아닌 timeline / order 누락 / 반쪽 anchor 등은 invalid로 유지해
  load `Unsupported`.
- **배치 계산** (신규 `render/MultiShotLayout.kt`, 순수·결정적): 프로파일(1장 50%, 2장 이상 25→70% 균등 ×
  강도), 경로 슬롯 `hero − d·len·(1 − i/n)`(len = 거리 × 짧은 변, 주인공 슬롯 비움), 기존 §4 변환을 풀어
  **앵커가 슬롯에 오도록** offset 계산(크기·회전·opacity 유지, 이전 offset 미사용 → 무누적),
  `faded`(opacity만), 앵커 드래그 역변환, 화면 밖 판정, 마스크 bounds(1px 포함)·하단 중앙 앵커.
- **렌더** (`Renderer.kt`, `MultiShotOp.kt`): 시간 모드는 확정 순서로 그린 뒤 `MultiShotOp.protect(합성, 입력,
  hero mask)` 1회(premultiplied, 리뷰 R1 반영) → 마스크 내부는 입력 RGB/alpha 그대로, 경계 feather, 밖은 기존 source-over. hero 파일
  누락/크기·종횡비 불일치 → `MissingSource`. 피사체 preview 캐시 `SHOT_CACHE_ENTRIES = MAX_SHOTS`,
  캐시 항목은 preview 크기로 축소(4장 ≈ 14MB@1080). 자유 모드는 저장된 hero가 있어도 보호 안 함.
- **저장** (`DefaultProjectRepository.kt`): hero mask는 기존 `saveShotSubject`/`discardShotSubjects`
  (`shot_<id>.png`) 소유권 재사용. load 시 hero 파일 존재(`MissingSource`)·크기 일치(`Unsupported`) 확인,
  참조 경로/복제 재작성에 hero 포함. 공개 인터페이스 변경 없음.
- **History** (`HistoryStack.publish`): canUndo/canRedo를 `current`보다 먼저 발행(REVIEW N1 원인: 즉시
  dispatcher의 collector가 대입 중에 실행되어 이전 availability를 읽음).
- **편집기** (`tools/multishot/*`, `EditorViewModel`, `EditorRoute`, strings):
  - 새 합성은 **시간 순서 배치 / 자유 배치** 선택 전 사진 추가 불가. 저장된 합성은 저장 모드로 열림, 상단
    행으로 명시 전환(피사체·변형·확정 순서 보존).
  - 시간 모드: 안내, `먼저 → 나중 → 마지막·주인공` 타임라인(썸네일 아래 `n단계`), 최대 4장, `이 순서가
    맞아요`/`순서 뒤집기`/`이전으로`/`다음으로`. 추가·삭제·교체 성공 시 재확인. 순서 변경은 위치를 바꾸지
    않으며, 배치와 다른 확정 순서는 안내. 주인공 타일: 미선택이면 MultiShot 입력 캔버스(로컬 렌더)로 추출 흐름,
    선택 후 방향 6종(↙ 기본)/펼침 거리/잔상 강도/`시간 순서대로 배치`/`기준점 조정`/`주인공 다시 선택` +
    덮어쓰는 항목 안내. 사진 타일: 슬라이더, 위치 초기화(시간 모드는 경로 슬롯, opacity 유지), 기준점 조정.
    잘림 안내. 자유 모드 전용 `잔상`(3–4장은 35→65% 균등) 및 앞/뒤 버튼은 시간 모드에 표시하지 않음.
  - 적용: 시간 모드는 hero + 모든 anchor + 확정 순서 필요(미완료 시 자동 모드 전환/제외 없음).
  - 오버레이: 선택 앵커 점 표시(ink, 강조색 미사용), 기준점 조정 중 드래그는 앵커만 이동.
  - **R1**: 교체 실패·취소 시 `restoreItem`이 draft를 재구성(`publish`) → 미리보기·적용이 썸네일과 일치.
  - **R2**: `closeSegmentation`이 열려 있던 항목을 Selecting→Picked로 되돌림. 사진 추가/교체/다른 사진
    추출·선택 모두 이 경로를 거쳐 세션 없는 Selecting이 남지 않음.
  - 기본 개념 문구 `person`, 추가 문구 예시 `person, golf club`.
  - 초안 op id를 세션 단위로 고정(그렇지 않으면 미리보기 키가 매번 달라 무한 재렌더 — 이번 변경 중 발견해 수정).
    미리보기 collector 키는 `(멀티샷 열림, 초안 문서)`로 바꿔 닫을 때 Crop 포함 미리보기를 다시 그린다.
- **문서**: `specs/multishot.md`(모드, §2.1 흐름, §3 모델, §4.2 경로/앵커, §6 주인공, §7 JSON/파일,
  §8 메모리, §9 테스트), `specs/{edit_model,render,persistence}.md` 관련 줄, `work/decisions.md` D086
  (D085 유지).

#### Files

- core:imaging: `model/{Operation,EditDocument,EditDocumentJson}.kt`, `render/{Renderer,MultiShotOp}.kt`,
  신규 `render/MultiShotLayout.kt`, `history/HistoryStack.kt`; tests `model/MultiShotModelTest.kt`,
  `render/MultiShotRenderTest.kt`, 신규 `render/MultiShotLayoutTest.kt`, `history/HistoryStackTest.kt`
- core:data: `DefaultProjectRepository.kt`; test `MultiShotPersistenceTest.kt`
- feature:editor: `EditorViewModel.kt`, `EditorRoute.kt`, `res/values/strings.xml`,
  `tools/multishot/{MultiShotController,MultiShotState,MultiShotSheet,MultiShotOverlay}.kt`;
  tests `tools/multishot/{MultiShotControllerTest,MultiShotToolTest,MultiShotSheetTest,MultiShotGoldenTest}.kt`,
  신규 `MultiShotFixtures.kt`, golden `multishot_sheet_{empty,arrange}.png` 재기록, `multishot_sheet_timeline.png` 신규
- 문서: `specs/{multishot,edit_model,render,persistence}.md`, `work/decisions.md`(D086), `work/RESULT.md`
- core:ai 변경 없음(`person` literal은 editor 컨트롤러 상수).

#### Validation

| Command | Result |
|---|---|
| `./gradlew --offline :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest --tests '*MultiShot*' --tests '*HistoryStack*'` | exit 0 — Model 16, Layout 13, Render 22, Subject 2, HistoryStack 9, Persistence 10 |
| HistoryStack 신규 테스트를 수정 전 `publish`로 실행 | 1 failed(재현 확인) → 수정 후 통과 |
| `./gradlew --offline :feature:editor:testDebugUnitTest --tests '*multishot*'` | 1차 3 failed(시트 클릭 2, 닫힘 후 미리보기 키 1) → 수정 후 **exit 0** (Controller·Tool·Sheet·Golden 54 tests) |
| `:feature:editor:recordRoborazziDebug --tests '*MultiShotGoldenTest*'` | 3개 기록, 육안 확인(주인공 타일 글자 넘침 발견 → `주인공`으로 수정 후 재기록) |
| `scripts/check.sh` (lint, detekt, 전체 unit, Roborazzi verify, dependencyGuard) | 1차 exit 1(detekt 37건: 길이/복잡도) → 수정 후 2차 exit 1(1건) → **exit 0** |
| `git diff --check` | exit 0 |
| 요구 36 실사진 3/5순간·골프 품질 | **미실행** — 권한 있는 연속 사진·SAM 3·기기 없음 |
| 요구 37 실기기(서버 실패/복구, 재배치, 방향 반전, 빠른 조절·취소, 적용 직후 Undo/Redo, 재시작, offline, JPEG/PNG, 복제) | **미실행** — 연결된 기기 없음. 적용 직후 Undo/Redo는 단위(History, ViewModel)로만 검증 |
| 요구 38 12MP + 4장 성능(p50, PSS, 잔류, 적용/export peak, 추출 지연) | **미실행** — 기기 없음 |

#### Review Notes

- **주인공 보호 방식**: 주인공 RGB를 복사하지 않고 op 입력을 마스크 안에 되돌린다. 따라서 앞선 Adjust/Style은
  주인공에도 반영되고 뒤의 op는 전체에 적용된다(렌더 테스트로 고정). 비용: hero PNG decode(1080 기준) +
  확대한 마스크 + 출력 버퍼 1장. 마스크는 bilinear 확대.
- hero mask는 `shot_<id>.png`(불투명 bitmap의 alpha) 형식으로 저장된다 — 새 파일 종류/저장 API를 만들지 않기 위함.
- 레거시 문서: 열기만으로 anchor를 메모리에서 계산하지만, 사용자가 적용하면 shot에 anchor 필드가 기록된다
  (자유 모드 픽셀 불변). `mode`/`timeline`은 순서를 확정하지 않으면 기록되지 않는다.
- 시트 테스트 두 곳은 가로 스크롤 행의 pill을 `performClick` 대신 `SemanticsActions.OnClick`으로 누른다.
  Robolectric에서 좌표 클릭이 전달되지 않았다(활성 상태는 같은 테스트가 확인). 실기기 터치 확인 필요.
- 방향은 6종 프리셋(↙↘←→↖↗)만 UI에 노출한다. 모델은 −180…180° 임의 각도를 허용한다.
- 앵커 보정은 다음 `시간 순서대로 배치`/방향·거리 조절/위치 초기화부터 반영된다(보정 즉시 피사체를 옮기지 않음).
- SAM 3 기본 문구 `person`은 기존 2장 샘플 관찰에 근거한다. 같은 이미지 재검증은 하지 않았다.

#### Known Issues

1. **요구 36–38 미실행**(위 표). 필요: 권한 있는 연속 동작 사진(최소 3순간·5순간, 골프 포함), Android 기기,
   SAM 3 서버. 측정 항목: 시간 흐름 가독성, 주인공 선명도, 배경 이중상, 얇은 채·손·팔, 12MP+4장 slider→preview
   p50 <100ms / PSS <250MB, 10회 진입·조절·취소 잔류, 적용/export peak, 추출 지연.
2. 주인공 입력 캔버스 렌더 실패 시 재시도 버튼이 없다(시트를 다시 열어야 함).
3. 프로세스 종료 시 미적용 draft(hero mask 포함) 파일은 폴더에 남는다(기존 D085와 동일, 전역 GC 없음).
4. 원본 추가 사진은 보관하지 않으므로 재추출은 교체로만 가능(기존과 동일).
5. 피부 보정·Jev 미완료 인계는 아래 "이전 기록"과 `work/retouch_evaluation.md` §7, `work/REVIEW.md`에
   그대로 남아 있으며 이번 작업으로 완료 처리하지 않았다(D084 피부 지원 종류 0개).

---

#### 이전 기록 (2026-09-29 멀티샷 구현 및 2026-10-01 실기기 검증, 이번 변경 전)

##### Status

PARTIAL — 멀티샷 모델·렌더·저장·편집 UI/추출·수명 연결과 자동 검증은 완료(`scripts/check.sh` exit 0).
2026-09-29 구현 인계 당시 실제 골프 사진·실기기·12MP 성능은 미실행이었다.
2026-10-01 Galaxy S25에서 서버 실패 경로 검증 후 SAM 3를 기동해 실제 추출·합성·재열기·JPEG/PNG 내보내기를 검증했다(아래 두 실기기 검증 절).
**기본 한국어 사람 추출은 샘플 2장에서 미검출, 적용 직후 Undo 비활성**을 관찰했다. 골프 품질·12MP 성능 및 기존 R1/R2는 여전히 미검증이며 전체 승인하지 않는다.

##### Changed

- **모델** (`core:imaging`): `Operation.MultiShot(id, shots)` + `Shot`/`ShotPlacement`. 문서당 1개, shot 1–2개, 안정 id, RGBA 피사체 ref, EXIF 정규화 working 크기, 변형·opacity. `withMultiShot`(확장 함수): 새 합성은 목록 끝에 추가, 수정은 같은 위치·id로 교체, 빈 목록은 제거. `canOutpaint`에 포함. `referencesResolve`가 2개 이상/잘못된 개수/중복 id/비유한·범위 밖 값/빈 ref를 거부. JSON `multiShot` 수동 codec — 알려진 op가 깨졌으면 드롭하지 않고 invalid로 유지해 load가 `Unsupported`로 실패.
- **렌더**: `MultiShotOp` — contain 초기 배치 → 이미지 중심 기준 균일 scale·회전 → canvas 정규화 이동, 한 개의 matrix를 preview/full 공용. Skia premultiplied source-over, paint alpha = opacity(한 번만 곱함), canvas 밖은 clip. `CpuRenderer`는 shot을 한 장씩 decode해 그리며 preview 크기 decode만 2 entry 캐시, export는 비캐시. 피사체 파일이 decode되지 않으면 `MissingSource` 실패(누락된 채 성공하지 않음). `MultiShotSubject`: 원본 RGB + `원본 alpha × mask(working 2px box feather 1회)`, 행 단위 처리.
- **저장** (`core:data`): `saveShotSubject`(원자적 `shot_<fileId>.png`, 실패/취소 시 파일 없음), `discardShotSubjects`(저장된 document가 참조하는 파일은 삭제 안 함), load 시 피사체 파일 누락 → `MissingSource`. **복제 경로 수정**: `duplicate`가 원본 폴더를 가리키는 모든 ref(source 포함)를 복제 폴더로 재작성 — 기존에는 source/mask/erase/fill/outpaint가 원본을 공유해 원본 삭제 후 복제본 재열기/export가 불가능했다(기존 결함, 이 시나리오 때문에 고침).
- **core:ai 최소 수정**: `Sam3SegmentationProvider.close(session)`은 그 세션이 아직 live일 때만 닫는다(멀티샷과 선택 도구가 각자 세션을 가질 때 늦은 close가 다른 세션을 죽이지 않게).
- **편집기** (`feature:editor/tools/multishot/`): `MultiShotController`/`State`/`Sheet`/`Overlay`, `Tool.MultiShot`(AI 그룹, 지시 앞, General/Portrait 둘 다), ViewModel/Route/ToolSheetHost 연결, 문자열.
  - 시스템 Photo Picker로 한 장씩 최대 2장, `ImageLoader`로 읽기(형식/과대/읽기 오류 메시지), Picker 취소는 무변경.
  - 사진 단계에서는 canvas에 해당 사진을 표시, 서버 전송 안내·서버 상태/다시 확인/서버 설정. **피사체 추출**을 누를 때만 선택 도구 세션 해제 → SAM 3 open → "사람". 0개는 실패로 표시(대체 없음), 여러 개는 후보 선택 필수, 탭/길게 눌러 포함/제외, 추가 문구("골프채") 후보를 골라 합집합. **추출 완료** 시 working 크기로 피사체 생성·저장, 세션 close.
  - 배치 단계: 한 손가락 드래그(두 번째 손가락이 닿으면 canvas에 양보), 불투명도/크기(log, 10–400%)/회전 슬라이더(접근성 이름·값·setProgress), 위치 초기화, 잔상(1장 50%, 2장 35/65%, opacity만), 앞뒤 순서, 교체/삭제.
  - 시트가 열려 있는 동안 preview는 draft를 **Crop 없이** 렌더(자르기 T69 규칙) → 화면 드래그를 그대로 canonical 좌표로 변환.
  - 적용 = history 1단계(추가/수정/전체 제거). 미완료 사진이 있거나, 새 합성에 사진이 없거나 모두 opacity 0이면 비활성. 취소/Back/Undo·Redo·Reset/설정 변경/편집기 종료 시 세션·요청·미적용 파일 정리. 모든 비동기 단계에 세션+사진별 token, 늦은 응답(취소 무시 fake 포함) 무시, `CancellationException`은 실패로 바꾸지 않음. 업로드가 이미 나간 open은 끝까지 받은 뒤 close.
- **문서**: `specs/multishot.md`(신규), `edit_model.md`·`render.md`·`persistence.md`·`tool_groups.md`·`canvas.md` 관련 줄, `work/decisions.md` D085. 이전 피부 보정 RESULT(상태·검증·실기기·배포/운영·Known Issues)는 수정 없이 `work/retouch_evaluation.md` §7로 이동.

**in-order 한계(명세 §3, 회귀 테스트로 고정)**: 합성 전에 있던 Adjust는 in-place 갱신 규칙 때문에 나중에 바꿔도 배경에만 적용된다. 합성 뒤에 새로 추가한 Adjust는 합성 결과 전체에 적용된다.

##### Files

- core:imaging: `model/{Operation,EditDocument,EditDocumentJson}.kt`, `render/Renderer.kt`, 신규 `render/{MultiShotOp,MultiShotSubject}.kt`; tests 신규 `model/MultiShotModelTest.kt`, `render/{MultiShotRenderTest,MultiShotSubjectTest}.kt`
- core:data: `ProjectRepository.kt`, `DefaultProjectRepository.kt`, `file/ProjectFiles.kt`; test 신규 `MultiShotPersistenceTest.kt`, 수정 `SkinRetouchPersistenceTest.kt`(복제 source 기대값), `ProjectAutosaveTest.kt`(fake)
- core:ai: `sam3/Sam3SegmentationProvider.kt`, `Sam3SegmentationProviderTest.kt`(1 test 추가)
- feature:editor: `Tool.kt`, `EditorViewModel.kt`, `EditorRoute.kt`, `tools/ToolSheetHost.kt`, `res/values/strings.xml`, 신규 `tools/multishot/{MultiShotController,MultiShotState,MultiShotSheet,MultiShotOverlay}.kt`; tests 신규 `tools/multishot/{MultiShotControllerTest,MultiShotToolTest,MultiShotSheetTest,MultiShotGoldenTest}.kt`, golden `screenshots/multishot_sheet_{empty,arrange}.png`; 수정 `ToolStripLevelTest.kt`, `PortraitToolMenuTest.kt`(AI 목록에 멀티샷, 길어진 lazy strip 스크롤), 기존 ViewModel 테스트들의 `ProjectRepository` fake에 메서드 2개 추가
- feature:browse: `BrowseImportTest.kt`(fake 메서드 2개)
- 문서: `specs/multishot.md`(신규), `specs/{edit_model,render,persistence,tool_groups,canvas}.md`, `work/decisions.md`, `work/retouch_evaluation.md`(§7), `work/RESULT.md`

##### Validation

| Command | Result |
|---|---|
| 기준선(변경 전) `./gradlew --offline :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:export:testDebugUnitTest` | exit 0 |
| `scripts/check.sh` (lint, detekt, 전체 unit, Roborazzi verify, dependencyGuard) | 1차 exit 1(detekt 17건) → 수정 후 **exit 0**. unit 1,245 tests / 0 fail / 2 skip(기존 core:imaging) |
| 멀티샷 관련 테스트 | 82 passed: Model 10, Render 16, Subject 2, Persistence 7, Sam3 provider 15(신규 1), Controller 18, Tool(ViewModel) 6, Sheet 6, Golden 2 |
| `:feature:editor:recordRoborazziDebug --tests '*MultiShotGoldenTest*'` | golden 2개 신규 기록, 눈으로 확인. 기존 golden(`editor_shell_ai_open` 포함)은 재생성하지 않았고 verify 통과 |
| `git diff --check` | clean |
| 실제 골프 사진 3장 합성(요구 29) | **미실행** — 사용 권한 있는 사진 없음 |
| 실기기(요구 30: Picker 취소, 서버 실패/복구, 빠른 조절·닫기, 재진입, 재시작, offline 재열기/export, JPEG/PNG, 복제) | **미실행** — 연결된 기기 없음 |
| 성능(요구 26: 12MP×3, slider→preview p50, peak PSS, 반복 잔류, 적용/export peak, 추출 지연) | **미실행** — 기기 없음. 설계상 한도: 한 번에 working 사진 1장, preview 사진 ≤1080px, 렌더러 피사체 캐시 2 entry(preview), export는 피사체를 순차 decode |

##### Review Notes

- **공통 복제 경로 변경**(요구 21): `duplicate`가 이제 source와 모든 저장 ref를 복제 폴더로 옮긴다. 피부 작업 테스트가 고정했던 "source는 원본을 계속 가리킨다"는 기대값을 새 규칙으로 바꿨다(`SkinRetouchPersistenceTest`). 원래 결함(공유 ref)과 이번 수정은 D085에 구분해 적었다.
- `Sam3SegmentationProvider.close` 의미 변경: 인자로 받은 세션이 live일 때만 닫는다. 기존 호출자(선택 도구)는 항상 자기 live 세션을 닫으므로 동작 동일.
- `ProjectRepository`에 `saveShotSubject`/`discardShotSubjects` 추가(공개 인터페이스). 파일은 추출 완료 시 저장하고 적용 시에만 문서에 공개한다(적용 전 파일은 세션 소유 → 닫힐 때 discard). 프로세스가 죽으면 그 파일은 폴더에 남는다(전역 GC 없음, 범위 밖).
- 시트가 열린 동안 canvas는 Crop 없는 draft를 보여준다. 확대/이동 제스처는 두 손가락; 첫 손가락이 사진 위에서 먼저 움직이면 그 제스처는 드래그가 된다.
- SAM 3는 추가 사진을 1080px로 받고 mask는 working 크기로 최근접 확대 후 2px feather — 4096 working에서는 가장자리가 약 4px 계단에서 부드러워지는 수준이다. 실제 사진에서 얇은 골프채 가장자리 품질은 미검증.
- 크기 슬라이더는 log 눈금(더블탭 → 100%), 회전 −180…180, 불투명도 더블탭은 AdjustSlider 기본대로 0%.
- 관찰(무관, 미수정): `EditorViewModel`의 `canUndo`는 `HistoryStack.publish`가 `current`를 먼저 내보내 한 박자 늦게 반영될 수 있다(테스트는 문서 상태로 단계를 검증함).
- DESIGN.md는 새 규칙이 필요 없어 수정하지 않았다(EditSheet·TertiaryPill·AdjustSlider·토큰만 사용).

##### Known Issues

1. **요구 26·29·30 미실행** — 필요: 사용 권한 있는 동일/비슷한 구도의 골프 스윙 사진 3장 이상, 연결된 Android 기기, SAM 3 서버 설정. 확인 항목: 배경 이중상 없는 동작 구분, 얇은 채·손·팔 경계, 겹침 순서, 반투명, Crop 후 위치, 적용/재열기/export, SAM이 채를 놓쳤을 때 점/"골프채" 문구로 복구되는지, 12MP 기준 p50 <100ms·peak PSS <250MB(기준 사진 단독 대비), 10회 진입/조절/취소 후 잔류, 적용/export peak, 추출 지연.
2. 원본 추가 사진은 보관하지 않으므로 저장된 피사체를 다시 추출하려면 교체로 사진을 다시 골라야 한다.
3. 추출 중 다른 썸네일로 바꾸면 그 사진의 선택(세션·mask)은 버려지고 '사진' 상태로 돌아간다.
4. 미적용 draft는 process death 후 복구되지 않는다(범위 밖, 마지막 저장 문서로 복귀).

##### 이전 작업 미완료 요약 (변경 없음)

- **피부 보정**: RESULT가 보고한 R1–R4 수정은 재리뷰 전(REVIEW는 CHANGES_REQUESTED). D084에 따라 앱 지원 종류 0개. 남은 것: 권한 있는 최소 24장·종류별 양성/음성 각 6장 이상 품질 평가, Corrected 실기기 적용/Undo/Redo/재열기/export/offline·lifecycle, 일반 메뉴 진입 불일치, 12MP/working 4096 PSS <250MB·slider p50 <100ms·네 항목 왕복 p95 15초 미검증(기존 debug PSS 353,171 kB). 상세·배포/운영 정보: `work/retouch_evaluation.md` §7, `server/retouch/deploy/README.md`.
- **Jev**(선택적 전역 Adjust/Style/Crop, Gemini fallback, 기본 OFF, 취소/stale 보호, 한국어 holdout, 실기기 지연): 이번 멀티샷으로 대체되어 **구현·검증되지 않았다**. 재개 시 계약을 다시 정리해야 한다.

##### 2026-10-01 실기기 검증 — Codex

환경: Galaxy S25 SM-S931N, Android 16, SSH reverse로 연결한 로컬 ADB.
앞선 설치에서 `:app:assembleDebug -Pdiffuse.localCreds` 성공 APK 설치 및 서비스 키 저장을 확인했다.
이번에는 실제 앱을 ADB 터치/UI Automator/화면 캡처로 조작했다.

- PASS: AI 메뉴의 멀티샷 진입, 빈 상태 안내 및 비활성 적용의 시각 확인.
- PASS: 시스템 Photo Picker를 열고 Back으로 취소 → 빈 상태 유지.
- PASS: 기기의 최근 사진을 한 장씩 가져와 썸네일 및 사진 단계 표시. 추가 사진 두 장에서 사진 추가 버튼 숨김.
- PASS: 미추출 두 장 상태에서 적용을 탭해도 commit되지 않고 시트 유지.
- PASS: 관리 행 가로 스크롤 후 선택 사진 삭제 → 나머지 한 장 유지 및 사진 추가 버튼 복원.
- PASS: 전송 대상 서버 안내, 연결 실패 표시, 다시 확인 조작 후 실패 상태 유지. 피사체 추출 탭 후에도 명시적인 연결 오류를 표시하며 시트 유지.
- PASS: 시트 취소 후 멀티샷 재진입 → 사진 없는 초기 상태. 마지막에는 취소하여 기존 편집 화면으로 복귀했다. 합성을 적용하거나 내보내지 않았다.
- BLOCKED: `http://44.233.156.159:8090` SAM 3 연결 실패. 실제 피사체 추출, 후보/점/문구 수정, 배치/잔상/순서의 합성 결과, 적용/Undo/Redo, 저장/재열기/내보내기/복제는 실행하지 못했다.
- NOT RUN: 골프 스윙 품질, 12MP 성능/메모리, 서버 복구 성공 경로, 기존 REVIEW R1/R2 실기기 재현. 현재 사진은 일반 사물 사진이므로 골프 품질 증거가 아니다.

임시 화면 증거: `/tmp/multishot-device/empty.png`, `/tmp/multishot-device/unavailable.png`.
사진이 포함된 캡처는 저장소에 추가하지 않았다. 구현은 수정하지 않았고 기존 REVIEW의 CHANGES_REQUESTED를 유지한다.

##### 2026-10-01 SAM 3 기동 후 실기기 재검증 — Codex

###### 실행 환경과 최종 서버 상태

- 같은 Galaxy S25 / Android 16, 설치된 실제 debug 앱을 터치 조작. 제품 코드는 변경하지 않았다.
- GPU 여유 3,705MiB로 동시 실행이 불가능해 확인된 monetGPT PID 93428을 SIGTERM으로 중지했다.
- SAM 3 실제 모델: user transient unit `sam3-validation.service`, 내부 `127.0.0.1:18091`, `SAM3_MAX_SESSIONS=1`.
- 프록시: `sam3-validation-public.service`, `/tmp/sam3-on.Caddyfile`, 기존 기기 주소의 8090 → 18091. `/healthz` HTTP 200. 최종 두 unit 모두 active. 부팅 자동 시작을 설치한 것은 아니다.
- 최종 GPU: 15,360MiB 중 6,973MiB 사용 / 7,939MiB 여유. **SAM 3 ON, monetGPT 모델 OFF**를 유지했다.

###### 입력과 범위

- SAM 3 저장소 `assets/videos/0001/{0,50,100}.jpg` (1280×720 인물 동작 프레임).
- 기기 `/sdcard/Pictures/MultishotCheck/{01,02,03}.jpg`에 복사. 기존 사용자 프로젝트와 별개로 검증 프로젝트 `4a206fff-6565-42cf-89e2-97c98db35daf`를 생성했다.
- 배경 100 프레임, 추가 사진 50/0 프레임. 골프 사진이 아니며 골프채 품질 검증을 대체하지 않는다.

###### 확인 결과

- PASS: 인물 메뉴에서도 AI → 멀티샷 진입. 2장 가져오기와 미추출 적용 차단.
- 실제 기본 추출은 두 장 모두 `사람을 찾지 못했어요`를 표시. HTTP 업로드 201 / text 200이므로 연결 오류가 아니다.
- PASS(수동 복구): 첫 장에 `person` 문구 → 후보 3개 → 후보 1 선택 → 마스크 표시 → 추출 완료 → 투명 PNG 저장/배치 전환.
- PASS(점 선택): 두 번째 장의 인물을 탭 → points 200 → 인물 마스크 → 추출 완료. 두 세션 모두 완료 후 DELETE 204를 확인했다.
- PASS: 잔상 프리셋 35%/65%, 화면 드래그 → 두 번째 `offsetX=0.21830738`, 적용 후 `multiShot` 하나에 두 shot 저장. PNG 피사체 파일 두 개 존재.
- PASS: 재편집에서 앞으로 가져오기 → 저장 shots 순서 역전. 크기 181% / 회전 49° UI, 실제 저장 `scale=1.8103374`, `rotationDeg=48.81163`. 적용 직후 자동 저장 debounce 전 파일은 이전 값이며 후속 읽기에서 새 값을 확인했다.
- PASS: 앱 force-stop → 재실행 → 검증 프로젝트 재열기. 초기 합성의 캔버스 영역(0,259–1080,1980) 스크린샷 비교에서 차이 없음. 멀티샷 재진입에서 두 썸네일과 35% 설정 복원.
- PASS: 최초 합성을 JPEG/PNG로 저장. 기기 `Pictures/Diffuse/IMG_20261001_142542.jpg` (150,603 bytes), `IMG_20261001_142608.png` (970,958 bytes). 둘 다 decode 성공, 1280×720 RGB. PNG를 육안 확인했다. 이후 순서/크기/회전 재편집 결과는 프로젝트에만 저장했으며 이 두 export는 최초 합성 결과다.
- PASS(요청 관찰): 세션 종료 이후 재편집·재열기·export에서 새 segmentation 요청이 없었다. 재시작 후 health probe만 있었다. 네트워크 자체를 끊은 offline 검증은 별도로 수행하지 않았다.
- FAIL: 첫 적용 직후 실행 취소/다시 실행 버튼이 실기기 UI 계층에서 disabled. 버튼 탭 전후 캔버스 차이도 없음. Undo/Redo 통과로 계산하지 않는다. 기존 RESULT Review Notes의 히스토리 상태 관찰과 부합하며 이번 멀티샷 변경이 원인이라고 단정하지 않는다.
- NOT RUN: 골프채/손 경계 정량 품질, 길게 누르기 제외, Crop·복제·진짜 offline, 12MP 성능·PSS·지연 백분위, 기존 REVIEW R1/R2 재현 및 수정 검증.

화면/출력 증거는 `/tmp/multishot-device/`의 `selected.png`, `point-result.png`, `composite.png`, `undo.png`, `redo.png`, `reopened.png`, `export.png`, `export.jpg`. 임시 파일이며 영구 보존을 보장하지 않는다. 검증 프로젝트와 출력 사진은 기기에 남겨 두었다. 전체 판정은 PARTIAL / CHANGES_REQUESTED 유지.
