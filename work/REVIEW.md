# Review

## Status

CHANGES_REQUESTED

2026-10-07 Codex 독립 확인. **지시 도구 사진 맞춤 추천 문장 넛지**의 R1·R2 수정이 필요하다.
사용자 연결 복구 후 `127.0.0.1:15039`의 SM-S948U에 최신 APK 설치 및 실기기 smoke를 수행했다.
실제 Gemini 추천·계획·노출 적용/Undo 정상 경로는 확인했지만, 추천 취소 표시 소실과 키보드 가림을 재현하여 승인하지 않는다.
이전 연결 장애는 해소됐다. 사진 6종·카탈로그 8문장 전체 검증 완료를 의미하지 않는다.

## Blocking

### R1 분석 중 예시 선택 시 진행 표시와 추천 취소 버튼이 사라짐

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/direct/DirectSuggestionArea.kt:99`

Problem:
`사진에 맞는 문장 보기`로 Loading에 진입한 뒤 여전히 활성인 일반 예시 pill을 누르면
`requestSource == Suggestion`이 된다. `StatusLine`은 Loading보다 추천 초안 안내를 먼저 검사하므로
진행 문구와 추천 취소 버튼을 `문장을 바꿔도 좋아요`로 대체한다.
`pickSuggestion()`은 추천 요청을 취소하지 않아 네트워크 요청은 계속된다.

Impact:
사용자는 진행 중인 추천 요청을 확인하거나 추천 영역에서 취소할 수 없다.
입력·사진·history를 유지하는 추천 취소 대신 지시 시트 전체를 닫아야 하는 상태가 되며,
`work/tasks.md` 요구 14와 `specs/vibe_edit.md` §14의 인라인 진행+취소 계약을 위반한다.

Required fix:
분석 중 예시를 골라도 요청이 계속되는 동안 진행 상태와 추천 취소 동작을 유지한다.
추천 취소 후 선택한 문장과 문서/history를 보존하는 회귀 테스트를 추가한다.

실기기 재현:
맞춤 요청 → 0.3초 후 일반 예시 선택 → `문장을 바꿔도 좋아요`만 표시되고 추천 취소 없음.
그 뒤 15:04:07 KST에 Gemini 추천 결과가 도착해 요청이 계속됐음을 확인했다.
증거: `/tmp/direct-device-review/loading-picked.{png,xml}`, `loading-picked-end.xml`, `final-log.txt`.

### R2 키보드가 지시 시트의 고정 취소·적용 버튼을 가림

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/direct/DirectSheet.kt:45`
`core/ui/src/main/kotlin/com/diffuse/core/ui/components/EditSheet.kt:65`

Problem:
기본 설정(1080×2340, density 450, font_scale 1.0)에서 추천 문장을 선택하고 입력창을 누르면
Samsung 키보드가 추천 영역과 하단 취소·적용 버튼을 덮는다. 화면은 입력창까지 위로 이동하지만
고정 액션 행은 키보드 뒤에 남는다. 실제 screenshot에서 확인했으며, UI hierarchy의 존재 여부만으로는 검출되지 않는다.
현재 공통 시트는 navigation bar 여백을 처리하지만 이 진입 경로에서 IME 표시 시 액션 행 노출을 보장하지 않는다.

Impact:
문장을 수정하는 사용자가 키보드를 별도로 닫아야 시트 취소·적용을 확인하거나 조작할 수 있다.
`work/tasks.md` 요구 22의 “키보드 표시에서도 입력과 고정 취소·적용이 가려지지 않게” 조건을 충족하지 않는다.
공통 시트의 기존 동작일 수 있으며 이번 diff가 처음 만든 회귀라고 단정하지 않는다.

Required fix:
지시 시트에서 IME가 열린 동안 입력과 고정 액션 행을 보이게 하고,
큰 글자·작은 화면에서도 스크롤과 캔버스/시트 제약을 검증한다.
공통 컴포넌트 변경이 필요하면 이번 요구를 충족하는 최소 범위로 조정하고 다른 시트의 회귀를 확인한다.
증거: `/tmp/direct-device-review/keyboard.png`, `keyboard.xml`, `typed.xml`.

## Tests Missing

- R1의 **Loading + RequestSource.Suggestion + 비어 있지 않은 입력** 조합.
  기존 `a suggestion in progress says so and can be cancelled`는 빈 입력만 검사한다.
- 실사진 6종 및 카탈로그 8문장 전체 검증은 미완료. 이번에는 공개 앵무새 사진 1장의 원본/노출 보정 상태와
  Brighten·VividColor 두 문장의 실제 계획을 확인했다. 다른 사진 유형이나 나머지 문장까지 통과로 확대하지 않는다.
- 실제 TalkBack 읽기 순서, 음성 Final, 네트워크 실패·빈 추천, 피부 보정 draft 취소 후 프레임 상태는 미실행.
- 키보드 가림 R2의 실기기/IME 회귀 검증이 필요하다.

## Non-blocking

- 피부 보정 취소 후 `renderedDocument != document` 잔류 가능성은 기존 확인 필요 항목으로 유지한다.
  이번 기기에서 재현하거나 해소했다고 판단하지 않는다.

## Validation Notes

### 연결 복구 후 실기기 검증 (2026-10-07 14:56~15:05 KST)

- 같은 15039 포트, `R3CYA0AVYEL` / SM-S948U / Android 16. 기존에 빌드한 동일 SHA-256 APK의
  `adb install -r` **Success / exit 0**. 전송 포함 약 3분. 로그 `/tmp/direct-device-install-retry.log`.
- 기존 저장소 로컬 Gemini 키를 신규 앱 설정에 저장하고 실제 앱에서 호출했다. 키 값은 로그/문서에 노출하지 않았다.
  서버 주소·서비스·모델·공개 포트는 변경하지 않았다.
- 저장소 기존 샘플 `test/kodim23.png`(앵무새 2마리)를 `/sdcard/Pictures/DirectReview/parrots.png`로 복사해
  시스템 Photo Picker로 새 프로젝트 `2aabf5ed-82e9-423b-b9c6-02beeee44c41`를 생성했다. 개인 사진은 사용하지 않았다.
- PASS: 지시 진입 시 일반 예시 3개, 입력 비어 있음, 적용 disabled. pill 선택 후 완성 문장이 채워지고 키보드는 열리지 않음.
  `before.json == picked.json` assertion 통과, Undo disabled 유지. 선택만으로 편집하지 않았다.
- PASS: `사진 전체를 조금 더 밝게 해줘` → 전송 → 실제 계획 `Exposure +0.2`(UI `노출 20`) → 적용.
  저장 operations는 Adjust 하나, Undo enabled. Undo 후 operations=`[]`; 이후 Redo 화면에서도 노출 변화와 Undo 활성 확인.
- PASS: 원본 preview 명시적 분석 → `vivid_color, film_warm` 2개 추천. 시트 재진입에서 같은 결과를 즉시 표시했고
  해당 구간 성공 응답 로그가 1개로 유지됐다. 로그는 완료 이벤트이며 별도 패킷 계측으로 요청 수를 측정한 것은 아니다.
- PASS: 맞춤 `색감을 생생하게` 선택 → `사진 전체의 채도를 조금 높여줘` → 전송 → 실제 계획 `Saturation +0.2`.
  선택/삭제/생성 단계 없음. 이 채도 계획은 적용하지 않고 입력 수정 검증에 사용했다.
- PASS: 입력창에서 문장에 `x`를 삽입하자 추천 목록이 숨겨지고 적용 disabled로 전환됨. 이전 계획 적용 차단.
  해당 수정 문장은 전송하지 않고 시트를 취소했다.
- PASS: Redo로 현재 문서가 바뀐 뒤 지시 재진입 시 일반 예시로 돌아가며 자동 분석하지 않았다.
  다시 명시적으로 요청한 보정 preview의 추천도 `vivid_color, film_warm`이었다.
- FAIL: 분석 중 예시 선택 시 진행/취소 소실(R1). 이후 응답은 추천 목록만 갱신하고 선택한 초안은 유지했다.
- FAIL: 키보드가 하단 액션을 가림(R2). UI hierarchy에 액션 노드가 남아 있어도 실제로는 키보드 뒤였다.
- 큰 글자/좁은 화면: font_scale=1.5, density=480(가로 360dp)에서 고정 취소·적용은 키보드가 없을 때 보였다.
  긴 pill은 가로 스크롤, 내용은 세로 스크롤로 노출됨. 입력 placeholder의 두 번째 줄 일부가 잘리는 것도 관찰했다.
  전체 접근성 통과 판정은 하지 않는다. 확인 후 **font_scale=1.0, density=450** 원복을 조회로 확인했다.

| 입력/문장 | 실제 추천 또는 계획 | 결과 |
| --- | --- | --- |
| 강한 색감의 앵무새 원본 | vivid_color → film_warm | 맞춤 제목과 2개 pill 표시 |
| 같은 사진 Exposure +0.2 preview | vivid_color → film_warm | 문서 변경 후 새 명시적 분석, 초안 유지 |
| Brighten 완성 문장 | Exposure +0.2, masked=false | 계획 확인·적용·저장·Undo 통과 |
| VividColor 완성 문장 | Saturation +0.2, masked=false | 계획 확인·입력 수정 후 적용 차단 통과 |

- 추천 성공 로그 KST 15:00:54.559 / 15:04:07.599, 계획 성공 로그 14:59:21.280 / 15:02:10.861.
  탭→응답 지연을 별도로 정밀 계측하지 않았으므로 latency 수치를 보고하지 않는다.
- 증거 `/tmp/direct-device-review/`: `before/picked/applied/undo/final.json`, `tailored.png`,
  `loading-picked.png`, `keyboard.png`, `large-direct.png`, 각 UI XML 및 `final-log.txt`.
  `before == picked`, applied=Exposure 0.2 하나, Undo/final operations=`[]`를 로컬 assertion으로 검증했다.
- 최종 상태: 원본으로 Undo한 새 검증 프로젝트의 일반 편집 화면. 샘플 사진과 프로젝트, 앱/설정은 기기에 남겼다.
  기존 사용자 사진·프로젝트를 수정하지 않았다. 제품 코드/테스트 수정 및 commit/push 없음.
  동일 소스/APK의 실기기 검증만 수행했으므로 이전 통과한 canonical 검사를 불필요하게 재실행하지 않았다.

### 복구 전 빌드·자동 검증·연결 시도 (기존 기록)

- `work/tasks.md`, `work/RESULT.md`, 기존 REVIEW, 관련 구현·테스트·diff, DESIGN 및 D091을 확인했다.
- `scripts/check.sh`: 첫 실행 **exit 0**. lint/detekt/unit/Roborazzi/dependencyGuard를 포함하며 캐시·증분 결과가 섞인다.
  확인한 관련 XML은 Suggestion 16, GeminiPlan 66, Direct 67, VoicePrompt 7 — failures/errors 0.
  기존 테스트의 전체 강제 재실행을 주장하지 않는다.
- R1 검증을 위해 `DirectSheetTest`에 임시 테스트 1개를 추가해 실행:
  `./gradlew --offline --quiet :feature:editor:testDebugUnitTest --tests '*DirectSheetTest*review probe*'`
  → **1 test / 1 failed**, `DirectSuggestionCancel` 노드를 찾지 못하는 assertion으로 재현.
  임시 테스트는 제거했으며 제품 구현은 수정하지 않았다.
  제거 후 `scripts/check.sh` 재실행도 **exit 0**, `git diff --check` 통과.
  증거: `/tmp/direct-review-probe.log`, `/tmp/direct-device-review/probe-result.xml`.
- `./gradlew --offline :app:assembleDebug -Pdiffuse.localCreds`: **BUILD SUCCESSFUL, 1분 32초**.
  APK 133,276,286 bytes, SHA-256 `826465a3816bbe9d17cc1de601ca1ab386e756c0a4efc3debf3a7d186eb07a59`.
  로컬 자격 정보 포함 빌드이며 공개 배포하지 않았다.
- 15039 최초 조회: serial `R3CYA0AVYEL`, model **SM-S948U**, Android **16**, 1080×2340, density 450.
  이전 리뷰의 S25 `R3CY601Q6GZ`와 다른 기기다. 최초 패키지 목록에는 `com.diffuse`가 없었다.
- 이후 ADB shell/기기 목록이 timeout. 호스트 권한 재시도도 20초 timeout(exit 124).
  TCP 연결은 열리지만 ADB `host:version`, `host:devices-l` 각각 4초 read timeout.
  `timeout 180 adb -H 127.0.0.1 -P 15039 -s R3CYA0AVYEL install -r ...`도 **exit 124**,
  설치 성공 응답 없음. 설치 완료 여부·앱 화면·Gemini 요청·사진 편집을 확인하지 못했다.
- 빌드/표준 검사/설치 로그: `/tmp/direct-device-{build,check,install}.log`.
  `/tmp` 증거의 영구 보존은 보장하지 않는다. 사진·키를 새로 전송하지 않았고 자동 commit/push하지 않았다.
- 재개 조건: PC의 USB/ADB 및 15039 SSH 전달 복구 후 기기 응답·설치 상태부터 다시 확인.
  기존 서버 멀티샷 R1/R2, 피부 보정·Jev 미해결은 이번 검증으로 해소되지 않았다.

## 이전 구현자 자체 리뷰 (2026-10-07)

## Status

COMMENT — 구현자(Claude) 자체 리뷰, 승인 아님

2026-10-07. 현재 `work/tasks.md`의 **지시 도구 사진 맞춤 추천 문장 넛지** 구현을 사용자 요청으로 구현자가 직접 검토했다.
독립 리뷰어 판정이 아니며, 실사진 smoke와 실제 Gemini 계획 해석이 미실행이므로 APPROVE를 주지 않는다.
코드·테스트·명세 대조 범위에서 차단 결함은 발견하지 못했다. 아래 확인 필요 항목을 독립 리뷰/실기기에서 확인해야 한다.

## Blocking

None (자동 검증 범위).

## Needs Verification

1. **실사진 smoke 미실행** — Acceptance의 "현재 preview를 실제 분석해 서로 다른 방향 추천", 6종 사진 표, 카탈로그 8문장 →
   실제 계획(선택/삭제/생성 단계 미발생) 확인이 비어 있다. `127.0.0.1:15039` ADB가 15초 timeout으로 응답하지 않았다.
2. **피부 보정 취소 직후의 프레임 상태** — `SkinRetouchController.close()`는 `onDraftChanged()`를 부르지 않는다. 취소 후 preview가
   draft 렌더로 남으면 `renderedDocument != document`라 지시 시트의 맞춤 요청이 다음 문서 변경까지 `사진을 준비하는 중`으로
   비활성일 수 있다. 잘못된 프레임 전송은 막히지만 UX 정지 가능성이 있다(기존 preview 갱신 경로 확인 필요).
3. **큰 글자/작은 화면/키보드** — 구조(시트 45%, 고정 취소·적용, 내용 세로 스크롤, pill 가로 스크롤)는 유지했으나 기기에서 미확인.
   Robolectric 기본 화면에서는 pill이 시트 스크롤 영역 밖이라 터치 테스트를 semantics OnClick으로 대체했다.

## Tests Missing

- 시트 열림 상태에서 프로젝트 전환 시나리오는 별도 테스트가 없다(문서 id가 동등성 키에 포함되어 같은 경로로 무효화된다).
- 실제 TalkBack 읽기 순서·반복 알림 부재는 자동 검증하지 않았다(contentDescription/liveRegion 미사용만 코드로 확인).

## Non-blocking

- 추천 실패 snackbar는 401/403만 `direct_needs_key`, 나머지(차단 포함)는 단일 문구다. 세분화는 요구되지 않았다.
- 분석 중 영역의 `취소`와 시트의 `취소`가 같은 글자다. 추천 쪽은 contentDescription `문장 추천 찾기 취소`로 구분된다.
- `EditorUiState.renderedDocument`는 렌더 성공마다 `shown`(도구별 표시 문서)을 기록하므로 자동/스타일/자르기 표시 중에는
  의도적으로 현재 문서와 다르다. 지시 시트는 해당 도구가 닫힌 뒤 재렌더되는 경로에 의존한다.

## Validation Notes

- 대조: tasks Requirements 1–23, `specs/vibe_edit.md`/`prompt_input.md`/`ai_provider.md`, `DESIGN.md` §4, D091, 신규·변경 코드 전체 diff.
- `scripts/check.sh` exit 0 (lint/detekt/unit/Roborazzi verify/dependencyGuard), `git diff --check` 통과.
- 신규/관련 테스트: GeminiSuggestionClient 15, GeminiSuggestionProvider 1, GeminiPlan 66, DirectToolTest 41(+20), DirectSheetTest 22(+11),
  DirectGolden 4(+2), VoicePromptBar 7 — failures/errors 0.
- 골든: `direct_sheet_open` 갱신, `direct_suggest_loading`/`direct_suggest_tailored` 추가, 육안 확인(적용만 accent, 순위 배지 없음).
  `direct_plan_preview` 불변.
- 보존: 이전 리뷰(2026-10-02 멀티샷 승인 및 미커밋 추가분)는 아래에 그대로 두었다. 서버 멀티샷 R1/R2, 피부 보정 D084·Jev 등
  이전 미해결은 이번 작업으로 해소되지 않았다.

## 이전 리뷰 (2026-10-02 Android 멀티샷 일괄 추출·자동 배치)

### Status

APPROVE

2026-10-02. 현재 `work/tasks.md`의 **Android 다중 선택 → 일괄 추출 → 즉시 자동 균등 배치**를 승인한다.
코드·관련 테스트를 대조하고 최신 APK를 로컬 Galaxy S25에 설치해 **새 프로젝트의 3장/6장 정상 경로**를 직접 검증했다.
사진별 추출 확정이나 별도 배치 버튼 없이 적용 가능한 미리보기에 도달했다. 검토 범위에서 차단 회귀는 발견하지 못했다.

이 승인은 현재 Android 작업에 한정한다. **서버 멀티샷 API의 기존 R1/R2는 미해결**이며, 피부 보정·Jev·전체 사진 품질/성능 평가는 승인하지 않는다.
`RESULT` 상단의 “기기가 없어 실기기 미실행”은 구현 당시 기록이다. 아래는 그 이후 리뷰어가 직접 수행한 검증이다.

### Blocking

None.

### Tests Missing

현재 변경에 새 차단 수준의 자동 테스트 누락은 발견하지 못했다. 다음은 실기기 미검증 범위이며 통과로 해석하지 않는다.

- 실제 서버 unavailable/인증/timeout/rate limit/저장 실패 후 재시도, 업로드·저장 중 취소 및 설정 변경 경합.
  해당 제어 흐름은 자동 테스트·코드로 검토했다. 이번 기기의 취소/재개는 **다중 후보 선택 대기 중**이었다.
- Picker 한도 초과 fallback, 남은 한 자리, 늦은 결과, 읽을 수 없는 파일, 구성 변경의 실기기 전체 조합.
- 실제 네트워크 차단 상태의 재열기/export, 12MP·PSS·slider 지연, 반복 실행 잔류, 골프채·손·머리카락 경계 및 연속 동작 품질.
- 이번 실행에서 JPEG export와 프로젝트 복제를 새로 수행하지 않았다.

### Non-blocking

- 기존 Android 접근성 N1은 해소: 균등 배치에서 “원본 발밑 높이의 기준선 … N개의 목표 위치”가 실제 UI hierarchy에 표시됐다.
- NASA 초상은 기능 smoke용이다. 6장에는 서로 다른 인물이 포함되고 피사체가 넓어 겹침·잘림이 크다.
  현재 D088의 고정 크기/원본 중앙 슬롯 계약과 잘림 안내는 지켰지만, 최종 사진 품질 합격 예시로 사용하면 안 된다.

### Validation Notes

#### 저장소·빌드

- `git status --short`, `git diff --check`, 관련 diff와 미추적 멀티샷 파일을 확인했다.
  전체 작업 트리에는 이전 피부/서버/문서 변경이 함께 있으므로 이번 Android 변경과 구분했다.
- tasks/RESULT/기존 REVIEW, `architecture/architecture.md`, DESIGN, `specs/multishot.md`, D088~D090을 대조했다.
  Picker 연결, controller/state/sheet/overlay, ViewModel history·autosave 연결과 관련 테스트를 검토했다.
- `scripts/check.sh`: **exit 0**. lint/detekt/단위 테스트/Roborazzi/dependencyGuard의 canonical 실행이며 캐시·증분 결과를 포함한다.
  멀티샷 XML 결과는 controller 63 + sheet 18 + tool 7 + golden 4 = **92 tests, failures/errors 0**.
  테스트 XML의 기존 timestamp를 확인했으며 92개를 모두 강제 재실행했다고 주장하지 않는다.
- `./gradlew --offline :app:assembleDebug -Pdiffuse.localCreds`: **BUILD SUCCESSFUL, 18초**.
- `adb -H 127.0.0.1 -P 15039 -s R3CY601Q6GZ install -r app/build/outputs/apk/debug/app-debug.apk`: **Success**.
  APK 133,259,494 bytes, SHA-256 `9d517f6d4188577696458c552eceb27b956939ffb1e3cb32c2ad3aa90414a591`.
- 서버 API/retouch 검사는 이번에 재실행하지 않았다. 제품 코드·테스트·tasks/RESULT는 수정하지 않았고 자동 commit도 하지 않았다.

#### 연결과 입력

- EC2에서 로컬 PC의 SSH 전달 ADB **127.0.0.1:15039** 사용. serial **R3CY601Q6GZ**, Samsung **SM-S931N**, Android **16**.
  EC2 기본 ADB의 빈 목록을 기기 부재로 판단하지 않았다.
- 처음에는 샌드박스 연결이 차단되어 승인된 호스트 명령으로 접근했다. APK 전송 중 일부 UI 명령이 60초 timeout을 냈으나
  약 133MB 전송과 설치가 최종 성공했다. 이후 최신 APK에서 아래 시나리오를 수행했다.
- 기존 앱의 SAM 설정을 사용했다. 서버 서비스/모델/키/rate limit/공개 포트를 변경하지 않았다.
- 기존 평가용 NASA 공개 사진 `jsc2011e017045/046/047/048.jpg`, `jsc2010e013041.jpg`, `jsc2016e000683.jpg`를
  `/sdcard/Pictures/MultishotBatchReview/`에 추가했다. 예외 경로는 기존 `MultishotCheck`의 두 인물 동작 샘플을 사용했다.
  개인 사진을 SAM에 새로 전송하지 않았다.

#### 새 3장 정상 흐름 — PASS

- 새 프로젝트 **`4c83ffc3-3d82-4762-bcaf-f3ceef0e9b93`** 생성. 원본 + 추가 2장을 **하나의 다중 Picker**에서 선택.
- 시간 순서 배치 → 사진 추가 → 2장 선택/완료 → 시트 스크롤 → **모두 추출하고 자동 배치 1회** → 자동 미리보기 → 적용.
  주인공 타일 선택, 사진별 추출 완료, 순서 확인, 별도 배치 버튼, 수동 드래그는 정상 완료까지 사용하지 않았다.
- 실제 SAM 로그(KST **10:47:13~10:47:23**)에서 upload 201 **3회**, text 200 **3회**, DELETE 204 **3회**.
  각 세션 종료 뒤 다음 업로드가 시작됐다. 후보 대기 없이 완료했고 배치 위치 패널·적용 enabled를 확인했다.
- 저장 JSON: hero `(0.47974536, 0.9537037)`, Even/spacing=1, 추가 anchor x **0.146412 / 0.813079**,
  공통 y **0.953704**, opacity **0.25 / 0.70**, scale=1, rotation=0. `[A, 원본, B]` 수식과 일치.
- 적용 직후 Undo enabled. 실제 Undo 후 저장 operations=`[]`; Redo 후 원래 operations·피사체 refs·좌표가 정확히 복원됨을 JSON assertion으로 확인했다.

#### 새 6장 정상 흐름·수동 보정·재시작·PNG — PASS

- 별도 새 프로젝트 **`4d4b2708-280a-4f27-b010-244b18c68940`** 생성. 원본 + 추가 5장을 한 번에 선택하고
  **모두 추출하고 자동 배치 1회**로 완료했다. 1~5단계·주인공과 적용 enabled, 자동 배치 가이드를 확인했다.
- 실제 SAM 로그(KST **10:49:45~10:50:03**)에서 upload/text/DELETE 각각 **6회**, 모두 성공.
  후보 선택이나 사진별 확정 없이 순차 처리됐으며 자동 배치 전후 별도의 배치 버튼을 누르지 않았다.
- 저장된 자동 배치 수치: hero는 위와 동일, Even/spacing=1.

| 추가 사진 시간 순서 | 목표 x | 목표 y | opacity |
| --- | --- | --- | --- |
| 1 | 0.146412 | 0.953704 | 0.25 |
| 2 | 0.313079 | 0.953704 | 0.3625 |
| 3 | 0.646412 | 0.953704 | 0.475 |
| 4 | 0.813079 | 0.953704 | 0.5875 |
| 5 | 0.979745 | 0.953704 | 0.70 |

- scale=1/rotation=0 유지. `[A, B, 원본, C, D, E]`와 `spacing/N` 수식에 일치한다.
- 재열기 → 첫 피사체 드래그 → 배치 위치/사진별 편집 왕복 → 적용 후 첫 anchor가 **(0.273170, 1.017082)**로 저장됐다.
  나머지 좌표·opacity·scale·rotation은 그대로였다. 패널 전환이 수동 보정을 자동 배치로 덮어쓰지 않았다.
- Undo로 자동 배치 수치가 복원됨을 별도 JSON으로 확인했다. 그 직후 Redo 후 약 1초 내 force-stop한 시도는
  기존 **2초 autosave debounce**보다 빨라 재시작 시 직전 저장 상태(자동 배치)가 복원됐다.
  이 시도를 “수동 보정 Redo의 저장/재시작 통과”로 계산하지 않는다. 3장 Redo의 저장 복원은 별도로 확인했다.
- 앱 force-stop → 실행 → 해당 프로젝트 열기에서 **저장된 6장 자동 배치**가 정상 표시됐고,
  재시작 후 operations가 저장된 자동 배치 JSON과 같다는 assertion을 통과했다. 이 과정에서 새 SAM 추출은 없었다.
- 원본 크기 PNG export 실파일: **`/sdcard/Pictures/Diffuse/IMG_20261002_105137.png`**,
  **4,181,685 bytes, 1536×1920 RGBA**. 실제 파일을 가져와 decode하고 육안 확인했다. 가이드/번호는 출력에 없었다.
  픽셀 단위 원본 보호 품질이나 골프 합성 품질의 완전한 검증으로 확대하지 않는다.

#### 다중 후보·진행 취소/재개·시트 취소 — PASS

- 새 예외 검증 프로젝트 **`3ff4c6b4-ab75-4101-8df6-822dfd428dd3`**: 두 인물 원본 + 단일 인물 추가 1장.
- 일괄 실행 시 **주인공에서 1/2 · 대상 선택 필요**, 후보 2개, 적용/타일/패널 전환 비활성.
  무한 spinner나 임의 자동 후보 채택은 없었다. 서버 로그에 첫 사진 upload/text **1회만** 있고 추가 사진 요청은 없었다.
- **진행 취소 → 재실행**: 새로운 업로드 없이 같은 후보 대기를 이어갔다. 후보 1을 명시 선택해 윤곽을 육안 확인한 뒤
  **추출 완료 1회**로 남은 사진의 추출·저장·자동 배치까지 이어졌고 적용 가능 상태로 전환됐다.
- KST **10:53:42~10:55:03**, 이 시나리오의 upload/text/DELETE는 각각 **2회**.
  원본 세션을 다시 업로드하지 않았고, 확정 전에는 추가 사진을 보내지 않았다.
- 최종 **시트 취소** 후 Undo 비활성, 저장 operations=`[]`, 프로젝트 파일은 `document.json/source.jpg/thumb.png`만 남았다.
  이번 draft의 hero/subject 파일이 회수된 것을 확인했다.

#### 증거·남겨둔 상태

- 이번 임시 증거: `/tmp/multishot-batch-review/`의 `three-auto.png`, `three-{applied,undo,redo}.json`,
  `six-auto.png`, `six-{auto-saved,corrected,reopened}.json`, `six-reopened.png`, `six-export.png`,
  `candidates.png`, `exception-cancelled.json`, `sam-requests.log`.
- 서버 로그의 이번 세 시나리오 총 upload/text/DELETE는 **각 11회**다. 타임스탬프 구간으로 이전 요청과 구분했다.
- NASA 입력 6장, 새 3장/6장 검증 프로젝트, 취소한 예외 검증의 원본 프로젝트와 PNG export는 기기에 남겼다.
  최종 화면은 저장된 6장 프로젝트이며, 재열기 화면과 캔버스 RGB가 같다는 비교로 확인했다. `/tmp` 증거의 영구 보존은 보장하지 않는다.

#### 후속 요청 — HTTP API 자동 추출·배치·합성 확인 (2026-10-02)

사용자 요청에 따라 `POST /v1/multishot` 한 번으로 완성 PNG에 배치가 반영되는지 직접 재검증했다.
**정상 경로 자동 적용은 이미 구현되어 있고 통과했다. `work/tasks.md`는 변경하지 않는다.**
이 결과는 서버 API 전체 승인이나 Android 편집 문서 자동 저장 연동을 뜻하지 않는다. 기존 서버 R1/R2는 미해결이다.

- 브랜치 `dev/combie`, 기반 커밋 `70da3dc`. `server/multishot/scripts/check.sh`: **131 passed, 2 warnings, 2.95초, exit 0**.
- 실제 `python -m app`를 임시 `127.0.0.1:18086`에 실행하고 기존 실제 SAM `127.0.0.1:18091`에 연결했다.
  검증용 임시 API 토큰과 기존 SAM 자격 정보를 사용했으며 값은 기록하지 않았다. 공개 배포 endpoint를 검증한 것은 아니다.
- 기존 NASA 공개 초상으로 3장/6장 각각 **POST 한 번**, `metadata` 생략(기본 spacing=1, 자동 person 추출).
  별도 후보 확정/배치/적용 HTTP 호출은 없었다. 6장에는 다른 인물 2명이 포함되어 동일인 동작 품질 검증은 아니다.

| 입력 | HTTP | 요청 시간 | 반환 PNG | 원본 hero와 다른 픽셀 수 |
| --- | --- | --- | --- | --- |
| 3장 | 200 | 11.80초 | 1536×1920 RGBA, 3,671,754 bytes | 958,782 |
| 6장 | 200 | 21.55초 | 1536×1920 RGBA, 3,995,857 bytes | 1,454,623 |

- 입력 순 슬롯은 3장 `[0,2,1]`, 6장 `[0,1,3,4,5,2]`. 마지막 입력이 hero,
  hero anchor `(0.485532,0.954630)`, 모든 y가 같고 x는 `hx+(slot-k)/N`에 일치했다.
  opacity는 3장 `[.25,.7,1]`, 6장 `[.25,.3625,.475,.5875,.7,1]`로 assertion을 통과했다.
- 응답 multipart 파싱, PNG decode/크기, 좌표/불투명도, 원본과 픽셀 차이를 검사했고 두 PNG를 직접 열어 합성을 확인했다.
  픽셀 차이만으로 사진 품질이나 보호 영역의 완전한 정확성을 증명하지 않는다. 겹침·잘림 및 `subject_clipped` 경고가 있었다.
- 인증된 health 200, 무인증 401, 이미지 2장 422 `invalid_image_count`도 확인했다.
  두 성공 요청 뒤 spool이 비었고 임시 서버는 종료했다. 기존 SAM rate limit을 변경하지 않고 요청 사이 65초 간격을 뒀다.
- 임시 증거: `/tmp/multishot-api-auto-check/{three,six}.png`, `{three,six}.json`, `summary.json`, `server.log`.
  request_id: 3장 `a20e3606263545f0a9f81ed2c27f9992`, 6장 `78081583c0774df3ae943b0a86120e33`.
- 여러 후보/미검출은 명세상 422이며 자동 임의 선택하지 않는다. 이 경우에는 `subject_points`로 대상을 지정해 재요청해야 한다.
  현재 API는 완성 PNG와 진단 metadata를 반환하며 앱의 피사체별 편집 문서에 자동 저장하는 API는 아니다(D089).

---

# 이전 서버 API·Android 리뷰 기록 — 현재 Android 승인과 별개

아래는 이번 일괄 실행 작업 이전의 기록이다. 서버 API R1/R2와 별도 품질 평가의 미완료 사항을 보존한다.
아래 “최신 tasks”는 **해당 리뷰 당시의 작업서**를 뜻한다. 이전 접근성 N1은 위 최신 검증에서 해소됐다.


### Status

CHANGES_REQUESTED

2026-10-02. 최신 `work/tasks.md`의 **3–6장 멀티샷 HTTP API**(`server/multishot/`)를 리뷰했다.
기존 서버 테스트 131개는 통과했지만, 아래 두 계약 위반을 별도 재현했다.
로컬 Galaxy S25의 기존 Android 멀티샷도 ADB로 조작했다. **앱은 신규 API를 호출하지 않으므로 기기 조작을 API 종단 검증으로 계산하지 않는다.**
Android 균등 배치의 이전 기능 승인과 남은 평가 사항은 아래 이전 리뷰에 보존한다. 이번 판정은 신규 서버 API에 대한 것이다.

### Blocking

#### R1 [P2] 요청 태스크 취소가 worker 중단 신호로 전달되지 않음

Location:
`server/multishot/app/main.py:108`

Problem:
`Runtime.run()`이 `asyncio.wait()` 중 `CancelledError`를 받으면 finally에서 `gone`만 취소하고 이탈한다.
아래의 `job.ctx.stop.set()` 및 `work.cancel()`은 실행되지 않는다. HTTP disconnect 메시지나 자체 deadline 경로와 달리,
ASGI 요청 태스크 자체가 취소되는 경로(예: 서버 종료 시 진행 중 요청 취소)는 추출을 계속한다.

Impact:
첫 추출을 fake extractor에서 막고 ASGI 호출 태스크를 `cancel()`한 뒤 해제하는 재현에서 **추출 호출이 총 3회** 발생했다.
취소된 요청이 다음 이미지까지 처리하며 SAM 세션·GPU·admission을 불필요하게 점유한다. 작업서 요구 18의
“요청 취소 후 다음 이미지 추출을 시작하지 않는다”를 위반한다. 이 재현에서 slot은 worker 종료까지 유지됐고,
최종 spool도 정리됐으므로 조기 slot 반환이나 파일 누수까지 주장하지 않는다.

Required fix:
외부 요청 태스크 취소에도 stop 신호와 자식 작업 정리를 보장하고 취소를 다시 전파한다.
대기 중 작업은 시작하지 않고, 실행 중 thread는 실제 종료할 때까지 slot/spool 소유권을 유지해야 한다.
첫 추출 중 취소 후 호출 수가 1회에 머물며 종료 후 자원이 해제되는 회귀 테스트를 추가한다.

#### R2 [P2] 큰 JSON 정수 좌표·간격이 400 대신 500으로 처리됨

Location:
`server/multishot/app/options.py:40`

Problem:
`_unit()`의 `float(value)`가 큰 Python 정수에서 `OverflowError`를 내며 API 오류로 변환되지 않는다.
JSON 숫자로 `9`를 400개 연결한 `spacing`은 64KiB metadata 제한보다 훨씬 작고 JSON 파싱에도 성공한다.
동일 검증기를 쓰는 `subject_points` 좌표에도 해당한다.

Impact:
유효 이미지 3장과 해당 metadata를 endpoint에 전달해 **500 `internal_error`**를 직접 확인했다.
범위를 벗어난 사용자 입력은 작업서 요구 3 및 API 명세 §2.2에 따라 **400 `invalid_metadata`**로 거절해야 한다.
내부 정보 유출은 관찰하지 않았다.

Required fix:
float 변환 오버플로를 포함해 범위 밖 숫자를 일관되게 400 `invalid_metadata`로 처리한다.
spacing 및 point 좌표의 큰 양수·음수 정수에 대한 endpoint 회귀 테스트를 추가하고 SAM 미호출을 확인한다.

### Tests Missing

- R1: 기존 `test_lifecycle.py`는 disconnect/deadline을 검사하지만 ASGI 호출 태스크 자체의 취소를 검사하지 않는다.
- R2: NaN/Infinity/`1e999` 검사에 더해 JSON 정수의 float 변환 오버플로가 필요하다.
- 실제 SAM 3장/6장 smoke는 `work/RESULT.md`의 구현자 실행 기록을 검토했다. 초기 리뷰 후 아래 재접속 검증에서 실제 SAM 3장/6장 요청도 직접 통과했다.
- 재접속 후 기존 앱의 저장·Undo/Redo·재시작·PNG export를 검증했다. 새 API의 앱 연동, offline·12MP 성능 및 골프 연속 동작 품질은 미검증이다.

### Non-blocking

기존 Android 접근성 문구 N1과 품질·성능 평가의 미완료 상태를 아래 이전 리뷰에 보존한다.
신규 API의 Android 미연동은 현재 작업서에 명시된 범위이며 결함으로 분류하지 않는다.

### Validation Notes

#### 직접 실행한 서버 검사

- `git status --short`, `git diff --check`, 관련 diff/신규 파일을 확인했다. 기존 미커밋 Android·피부 변경이 섞여 있으므로
  전체 작업 트리를 이번 API 구현분이라고 간주하지 않았다.
- tasks/RESULT/이전 REVIEW, `architecture/architecture.md`, DESIGN, API 명세, D088/D089, 서버 구현과
  API/합성/SAM adapter/lifecycle 테스트를 대조했다.
- `server/multishot/scripts/check.sh`: **exit 0, 131 passed, 2 deprecation warnings, 3.00s**.
  샌드박스 실행은 출력 없이 진행되지 않아 호스트 권한으로 재실행한 결과다.
- 임시 재현 스크립트 `/tmp/multishot_review_probe.py`: **exit 0**. 제품 코드를 수정하지 않고 fake extractor와
  ASGI 요청으로 R1/R2를 확인했다. 출력: `large_integer_endpoint: 500 internal_error`,
  `cancel: worker_live=True slot=1`, `cancel: extraction_calls=3 spool=[]`.
- canonical `scripts/check.sh`, Android 빌드/단위 테스트는 이번에 실행하지 않았다.
  신규 서버 검사와 기존 설치 앱의 UI smoke를 구분한다. APK 재빌드·재설치는 하지 않았다.
- 서비스 재기동, GPU 모델 교체, 포트 공개, 자동 commit은 하지 않았다.

#### 초기 로컬 실기기 확인과 연결 제한 — 아래 재접속 결과로 보완

- 기존 SSH reverse ADB `127.0.0.1:15039`에서 `R3CY601Q6GZ`, Galaxy S25 `SM_S931N`을 `device`로 확인했다.
- 화면을 켜고 실행 중인 `com.diffuse`에서 도구 목록을 스크롤 → AI → 멀티샷을 실제 탭했다.
- UI Automator에서 시간 순서 배치 선택 상태, **1–5단계와 마지막·주인공 타일**, 취소/적용 enabled를 확인했다.
  적용을 누르거나 새로운 사진을 전송하지 않았다.
- 이후 시트 스크롤과 이전 검증 프로젝트 `3a62e870-e9fb-4248-810c-3a0ac8b70d6f`의 JSON 읽기가 각각
  **60초 ADB timeout**으로 실패했다. 현재 열린 문서의 ID·좌표를 새로 검증하지 못했다.
- 시트 취소 명령도 전송을 시도했지만 응답을 확인하지 못했다. 최종 UI 복귀 여부는 미확인이다.
  이전 screenshot/JSON을 이번 실행의 새 증거로 사용하지 않았다.

#### 2026-10-02 재접속 — 실제 신규 API 검증

사용자의 재접속 요청 후 직접 실행했다. 코드 수정은 없으므로 **R1/R2와 CHANGES_REQUESTED는 유지**한다.

- 실제 serve 진입점 `python -m app`를 임시 `127.0.0.1:18086`에서 실행하고, 기존 SAM 3 `127.0.0.1:18091`에 연결했다.
  fake/mock을 사용하지 않았다. API 토큰은 검증 전용 임시값, SAM 토큰은 기존 설정을 사용하고 노출하지 않았다.
- 공개 포트 개방이나 GPU 모델/기존 서비스 변경 없이 검증했다. 검증 API 프로세스는 완료 후 정상 종료했다.
- 입력은 기존 검증 자료인 NASA 공개 초상이다. 3장은 같은 인물의 다른 컷, 6장은 다른 인물 2장을 포함한다.
  골프 등의 연속 동작 사진은 아니며 동일인 추적 또는 동작 품질의 검증으로 간주하지 않는다.

| 요청 | 직접 확인 결과 |
| --- | --- |
| 인증된 `/health` | 200, `contract_version:1`, `status:ready` |
| 인증 없는 `/health` | 401 |
| 이미지 2장 | 422 `invalid_image_count`, spool 정리 |
| 3장, 기본 spacing=1 | **200, 7.34초, 1536×1920 RGBA PNG, 3,671,754 bytes** |
| 6장, spacing=0.8 | **200, 13.10초, 1536×1920 RGBA PNG, 4,147,861 bytes** |

- 3장 입력: `jsc2011e017045.jpg`, `jsc2011e017046.jpg`, `jsc2011e017047.jpg`.
  입력 순 shots 슬롯 `[0,2,1]`, opacity `[0.25,0.7,1]`, anchor x `[0.152199,0.818866,0.485532]`.
- 6장 입력: `jsc2010e013041.jpg`, `jsc2016e000683.jpg`, `jsc2011e017045.jpg`, `jsc2011e017046.jpg`,
  `jsc2011e017048.jpg`, `jsc2011e017047.jpg`.
  입력 순 슬롯 `[0,1,3,4,5,2]`, opacity `[0.25,0.3625,0.475,0.5875,0.7,1]`.
- 양 요청 모두 마지막 입력이 hero, hero anchor x=0.485532, 모든 anchor y=0.954630.
  metadata 순서/slot/배치 수식과 PNG decode·RGBA·크기를 클라이언트 assertion으로 확인했다.
- 3장의 추가 입력 0/1, 6장의 추가 입력 0–4에 `subject_clipped`가 반환됐다. 화면 밖 잘림은 현재 계약에 맞는다.
  실제 PNG를 열어 반투명 인물 합성을 확인했으나, 넓은 초상 간 겹침과 일부 경계/보호 영역의 시각적 부자연스러움이 있다.
  API 성공을 최종 사진 품질 승인으로 확대하지 않는다.
- 두 성공 요청 종료 후 spool이 비어 있었다. 3장 이후 65초 간격을 두어 기존 SAM 업로드 rate limit을 피했다.
  위 시간은 각각 단일 요청 측정이며 지연 백분위나 부하 성능 측정이 아니다.
- 직접 생성한 임시 증거: `/tmp/multishot-live-review/{three,six}.png`, `{three,six}.json`, `summary.json`, `server.log`.
  요청 ID: 3장 `5bdb5c9dd1e74767a140b7eb407c0756`, 6장 `592e7d5c09cc449b8311b99315e1a568`.

#### 2026-10-02 재접속 — 로컬 Galaxy S25 실기기 검증

- ADB `127.0.0.1:15039`, serial `R3CY601Q6GZ`, Galaxy S25 SM-S931N, Android 16에 재접속했다.
  기존 설치 debug 앱을 사용했으며 APK 재빌드/설치는 하지 않았다.
- 대상은 이전에 생성한 검증 프로젝트 `3a62e870-e9fb-4248-810c-3a0ac8b70d6f`다.
  이번에는 실제 JSON을 읽어 추가 5장, Even, spacing=0.76470315, hero=(0.5291667,1.0)을 확인했다.
- PASS: 멀티샷 → 배치 위치 → 간격 76%에서 약 67%로 드래그 → 적용.
  저장 spacing=**0.6658487**, 추가 anchor x=`[0.307217,0.418192,0.640142,0.751116,0.862091]`, y 모두 1.
  opacity 0.25/0.3625/0.475/0.5875/0.7, scale=1, rotation=0 유지.
- PASS: UI Undo → 저장 spacing=0.76470315 및 기존 위치 복원. UI Redo → 0.6658487 및 새 위치 복원.
  버튼 enabled 상태만이 아니라 각 단계의 저장 JSON을 확인했다.
- PASS: 앱 force-stop → 재실행 → 최근 검증 프로젝트 열기. 재시작 전/후 screenshot의 캔버스
  `(0,259)-(1080,1980)` RGB 픽셀 비교에서 차이가 없었다. 프로세스 재시작 후 히스토리 버튼 초기화는 저장 합성 복원과 구분한다.
- PASS: 원본 크기 PNG 저장 → “저장했어요” UI와 새 파일
  `/sdcard/Pictures/Diffuse/IMG_20261002_080227.png` **1,159,262 bytes** 확인. 실제 파일을 가져와 **1280×720 RGBA PNG** decode와 화면을 확인했다.
- 검증 프로젝트는 변경된 spacing=0.6658487로 남겼으며 PNG 출력도 기기에 남아 있다. 마지막 UI는 편집 화면이다.
- 임시 증거: `/tmp/multishot-device/oct02-{before,applied,undo,redo}.json`,
  `oct02-before-restart.png`, `oct02-reopened.png`, `oct02-export.png`.
- 신규 API는 Android에 미연동이므로 위 앱 합성과 서버 API 합성은 별개 검증이다.
  이번에 JPEG export, 실제 네트워크 차단, 복제, 12MP/PSS, 골프 품질을 새로 검사하지 않았다.

---

# 이전 리뷰 보존 — 2026-10-01 Android 균등 배치

아래 APPROVE와 명령·실기기 수치는 **이전 Android 작업의 결과**다. 위 최신 서버 API 판정이나 이번 실행 결과와 구분한다.

### Status

APPROVE

2026-10-01. 최신 `work/tasks.md`의 **원본 중심·장수별 균등 배치(총 2–6장)**와 `work/RESULT.md`를 구현·테스트에 대조하고, 최신 APK를 로컬 Galaxy S25에 설치해 검증했다. 검토 범위에서 차단 결함은 발견하지 못했다. **기능 구현 승인**이며, 작업서의 전체 실사진 품질·오프라인·12MP 성능 검증 완료를 뜻하지 않는다. 해당 항목은 PARTIAL로 남긴다.

피부 보정·Jev 등 기존 미커밋 변경은 승인 범위에서 제외한다.

### Blocking

None.

이전 리뷰의 차단 항목은 다음 근거로 해소됐다.

- **이전 R1 — 투명 보호 경계 색상 오류:** `MultiShotOp.protect`가 alpha를 고려한 premultiplied 보간을 수행하고 mask 0/255 경계를 보존한다. 투명/부분 투명 입력의 색·alpha와 보호 경계 회귀 테스트를 확인했고 관련 렌더 테스트를 직접 재실행해 통과했다. 이번 불투명 실기기 사진만으로 투명 PNG 정확성을 판정한 것이 아니다.
- **이전 R2 — 자유 배치 편집 시 주인공 정보 손실:** 순서 확인 상태와 timeline/hero 보존을 분리한 `orderConfirmed`, `draftTimeline()` 및 재열기 경로를 확인했다. 사진 변경·자유 전환·재열기 및 참조 보존 회귀 테스트가 추가됐고 controller/persistence 테스트가 통과했다. 이 전이의 모든 조합을 실기기에서 재현한 것은 아니다.

### Tests Missing

#### T1 실기기 품질·오프라인·성능 검증은 PARTIAL

이번 기기 검증은 1280×720 연속 동작 샘플의 3장/6장 기능 smoke다. 아래를 통과로 보고하지 않는다.

- 실제 네트워크 차단 상태에서 재열기·편집·export. 이번 재시작/export는 네트워크 연결 상태였다.
- 12MP + 추가 5장, slider p50 <100ms, PSS <250MB, 10회 반복 잔류, 적용/export peak 및 추출 지연 측정.
- 골프채·손·팔·머리카락 등의 다양한 실사진 품질과 정돈된 시간 흐름 가독성. 기존 첫 번째 샘플 마스크의 누락과 큰 피사체 간 겹침이 있어 이번 산출물을 최종 품질 합격 예시로 쓰면 안 된다.
- 크게 치우친 원본 앵커, 정확한 화면 중앙 앵커, 장수 2/4/5, 간격 0 및 빠른 연속 조절/취소의 실기기 전체 조합. 해당 수학·상태 전이는 자동 테스트 검토 범위이며 실기기 통과와 구별한다.
- 복제는 기존 3장 프로젝트를 실제 UI에서 복제해 이후 검증에 사용했다. 완성된 6장 문서를 다시 복제하거나 복제 원본을 삭제하는 실기기 검증까지 수행하지 않았다.

새로운 차단 수준의 자동 테스트 누락은 발견하지 못했다. 위 항목은 남은 평가 범위이며 `RESULT`의 PARTIAL 상태를 유지해야 한다.

### Non-blocking

#### N1 균등 배치의 접근성 설명에 기존 경로 문구가 남음

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/multishot/MultiShotOverlay.kt:66`
관련: `feature/editor/src/main/res/values/strings.xml:381`.

균등 배치에서도 캔버스 설명이 “시작에서 주인공까지의 경로와 5개의 목표 위치”로 노출된다. 실기기 UI hierarchy에서도 확인했다. 원본이 중앙 슬롯이고 양옆에 배치되는 현재 동작을 스크린리더 이용자에게 잘못 설명한다. arrangement에 맞춰 균등 배치는 원본 기준 수평 슬롯, 기존 Path는 경로로 안내하면 된다. 시각적 배치·저장 계산에는 영향을 주지 않아 비차단으로 분류한다.

### Validation Notes

#### 직접 실행한 저장소 검사

- `git status --short`, 관련 diff/신규 파일, tasks/RESULT/이전 REVIEW, DESIGN, 관련 명세 및 D088, 모델/JSON/배치 수학/렌더/controller/state/sheet/overlay와 주변 테스트를 검토했다.
- `scripts/check.sh` → **exit 0**. 캐시·증분 실행을 포함하며 모든 검사를 새 환경에서 재실행했다는 의미는 아니다.
- `./gradlew --offline :app:assembleDebug -Pdiffuse.localCreds` → **exit 0**. 생성 APK를 `adb install -r`로 설치했고 **Success**를 확인했다.
- `./gradlew --offline --quiet :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest --tests '*MultiShot*' --tests '*HistoryStack*'` → **exit 0**.
- `git diff --check` → **exit 0**.
- 기존 문서의 arrangement 누락은 Path로 읽고 신규 기본은 Even인 점, 총 추가 5장 한도, 원본 슬롯 `floor((N-1)/2)`, `spacing/N` 간격, 실제 placement 저장 및 재열기 시 자동 재배치하지 않는 경로를 확인했다.

#### 이번 빌드의 로컬 실기기 검증

- 환경: SSH reverse ADB `127.0.0.1:15039`, Galaxy S25 `SM-S931N`, serial `R3CY601Q6GZ`, Android 16. 기존 SAM 3 서버를 사용했으며 서버/키 설정을 변경하지 않았다.
- 기존 테스트 프로젝트 `4a206fff-6565-42cf-89e2-97c98db35daf`를 UI에서 복제했다. 이번 편집 대상은 복제본 **`3a62e870-e9fb-4248-810c-3a0ac8b70d6f`**다. 개인 사진 프로젝트를 검증용으로 편집하지 않았다.
- SAM 샘플 연속 프레임 0/25/50/75/90 + 원본 100(1280×720)을 사용했다. 추가 25/75/90의 실제 SAM 요청·후보 선택·추출 완료, 25의 교체/재추출, 타임라인 순서 이동을 확인했다. 후보 번호는 프레임마다 달라 육안으로 전경 인물을 확인해 선택했다.
- 기존 문서는 Path로 열렸다. Even으로 명시 전환한 3장 배치는 `[A, 원본, B]`였다. 기존 개별 확대·회전을 유지했고, 두 번째 사진의 위치 초기화는 현재 슬롯으로 이동하면서 scale=1/rotation=0, opacity=0.70을 유지했다.
- 6장에서는 추가 5개와 주인공 타일이 표시되고 사진 추가 버튼이 사라졌다. 사진 변경 후 순서 미확정 상태에서는 적용이 비활성이고, **‘이 순서로 6장 균등 배치’** 실행 후 적용 가능해졌다.
- 6장 배치는 `[A, B, 원본, C, D, E]`, 간격 변경 전후 모두 같은 y 앵커였다. 원본 위치를 바꾸거나 자동 축소하지 않았다. 기본 간격의 마지막 앵커가 화면 밖에 놓이는 것은 현재 계약에 명시된 결과이며 잘림 안내도 표시됐다.

실제 저장 JSON에서 계산한 canonical 정규화 좌표:

| 구성 | 간격 | 추가 피사체 x 좌표(시간 순서) | 공통 y |
| --- | --- | --- | --- |
| 3장 | 1.0 | 0.195833, 0.862500 | 1.0 |
| 6장 | 1.0 | 0.195833, 0.362500, 0.695833, 0.862500, 1.029167 | 1.0 |
| 6장 | 0.76470315 | 0.274266, 0.401716, 0.656617, 0.784068, 0.911518 | 1.0 |

원본 앵커는 `(0.5291667, 1.0)`으로 유지됐다. 간격 축소 전후 opacity는 **0.25/0.3625/0.475/0.5875/0.70**, scale은 1, rotation은 0으로 같았다. 원본 샘플이 아래에서 잘린 구도라 y=1은 마스크 하단 앵커이며 실제 발 위치 품질을 증명하는 값은 아니다.

- 간격을 직접 드래그해 UI 약 76%로 변경 → 적용 → Undo → Redo를 실제 터치로 수행했다. 저장 JSON에서도 `spacing=1.0` 복구 후 `0.76470315` 및 해당 좌표 복원을 확인했다.
- `am force-stop` 후 앱 재실행 → 복제 프로젝트 재열기 → PNG/JPEG 내보내기 성공. PNG 실파일을 가져와 열어 주인공 보호·합성 결과와 가이드가 출력에 포함되지 않음을 육안 확인했다. 양 형식의 엄밀한 픽셀 동등성 검사는 수행하지 않았다.
- 실제 생성 파일: `/sdcard/Pictures/Diffuse/IMG_20261001_203320.png` **1,177,343 bytes**, `IMG_20261001_203359.jpg` **166,434 bytes**. 이전 17시대 산출물은 이번 빌드의 근거로 사용하지 않았다.
- 임시 증거: `/tmp/multishot-device/even-three.json`, `even-six-default.json`, `even-six-narrow.json`, `even-six-undo.json`, `even-six-redo.json`, `even-six-default.png`, `even-six-narrow.png`, `even-six-export.png`. 임시 경로이므로 영구 배포 산출물로 간주하지 않는다. 검증 프로젝트와 export 파일은 기기에 남겨 두었다.

#### 이전 미완료 인계 보존

- 피부 작업의 이전 R1(품질 gate 없는 지원 노출), R2(body/decode 전 admission), R3(취소된 draft transient 누수), R4(설정 변경 중 얼굴 분석 고착)는 구현자 수정 보고만 있으며 별도 재리뷰 완료로 판정하지 않는다. gate/health, 수신·디코딩·후처리 동시 제한, 합성 취소 회수, 분석 중 설정 변경 복구와 실제 Corrected 적용·Undo/Redo·재열기·export/offline·lifecycle 경합을 별도 검증한다.
- D084상 피부 지원 종류는 0개. 권한 있는 최소 24장·종류별 양성/음성 각 6장 이상 평가, 일반 메뉴 진입 불일치, 12MP/working 4096 PSS <250MB·slider p50 <100ms·네 종류 왕복 p95 15초는 미완료다. 기존 debug PSS 353,171kB와 배포/운영 기록은 `work/retouch_evaluation.md` §7 및 `server/retouch/deploy/README.md`를 참조한다. 과거 서버 검사 81 passed / 4 deselected는 현재 재승인 근거가 아니다.
- Jev: 구현·검증 완료 증거가 없으며 재개 시 계약을 다시 정리한다.
- 이번 리뷰에서 저장소 파일은 `work/REVIEW.md`만 수정했다. 제품 구현·테스트·tasks/RESULT는 수정하지 않았고 자동 commit은 하지 않았다.
