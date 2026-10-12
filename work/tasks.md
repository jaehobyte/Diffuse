# Task

## Goal

편집기에서 피부 보정 기능과 얼굴 감지에 따른 메뉴 분기를 제거하고, 모든 사진에 동일한 일반/AI 2단계 도구 메뉴를 제공한다.

## Background

2026-10-12 사용자 요청: “피부 보정, face detect에 따른 메뉴 분기 제거”. 이번 작업은 앱에서 피부 보정을 새로 실행하는 기능을 제거하는 범위이며, 이미 저장된 편집 결과를 지우는 작업이 아니다.

확인한 기준은 main `14828a5`. 계획 작성 시 작업 트리는 깨끗했다.

- `Tool.kt`는 General/Portrait 프로필을 나눈다. 현재 Portrait Root는 자동·피부 보정·라이트·색상·혼합·자르기이고 디테일은 빠진다. General은 피부 보정을 숨긴다.
- `EditorViewModel.renderSource()`가 원본 preview를 렌더한 뒤 `PortraitDetector.detect()`를 실행하고, `EditorRoute`가 결과를 메뉴 프로필로 변환한다.
- 피부 보정은 `tools/retouch` controller/sheet/state, EditorAi의 provider/analyzer/settings, VM의 draft·busy·메시지·취소·적용, Route의 export 차단과 연결되어 있다. 서버 설정 시트에도 전용 주소/토큰이 있다.
- `Operation.SkinRetouch`는 JSON과 결과 PNG/support mask로 저장된다. 기존 프로젝트의 렌더·저장·내보내기에는 이 호환 경로가 계속 필요하다.
- 기존 T79와 `specs/skin_retouch.md`의 메뉴/신규 보정 요구는 이번 사용자 요청으로 대체한다. 해당 명세의 메뉴 표는 현재 구현과도 다르므로 그대로 새 메뉴의 근거로 사용하지 않는다.

## Scope

### Modify

- `feature/editor/.../Tool.kt`, `EditorToolStrip.kt`, `EditorScreen.kt`, `EditorRoute.kt`, `EditorViewModel.kt`, `EditorAi.kt` 및 직접 연결된 tool dispatch/host 코드.
- `feature/editor/.../tools/retouch/`: 신규 피부 보정 UI·controller·상태 제거.
- `tools/select/SelectionController.kt`, `SelectionState.kt`, `Sam3SettingsSheet.kt`: 피부 보정 전용 설정 표시·수집·저장 연결 제거.
- 관련 문자열, editor 테스트/fake 생성자, 변경된 메뉴·설정 골든.
- `core/ai`: 메뉴용 PortraitDetector/ML Kit 구현/fake와 DI 연결 제거. 피부 보정 관련 바인딩은 사용처를 확인해 앱 실행 경로에서 분리한다.
- `app/src/main/AndroidManifest.xml`: 다른 소비자가 없다면 얼굴 모델 사전 다운로드 metadata 제거.
- `specs/tool_groups.md`, `specs/editor_shell.md`, `specs/skin_retouch*.md`, `specs/ai_provider.md`, `specs/multishot.md`, `DESIGN.md`: 이번 동작과 충돌하는 현재 제품 설명만 정합하게 수정.
- `work/decisions.md`: 고정 메뉴와 피부 보정 신규 실행 제거, 저장 호환 유지 결정을 기록. `work/RESULT.md`: 구현·검증·미해결 인계 갱신.

### Do not modify

- 기존 `Operation.SkinRetouch`의 저장 형식, decoder/encoder, renderer, 결과/mask 파일 참조·보존·정리 계약. 기존 프로젝트에서 보정 연산을 삭제하거나 원본으로 치환하지 않는다.
- `server/retouch`, 서버 배포·포트·모델·평가 자료, `work/retouch_evaluation.md` 등 실질적인 평가 근거. 서버 중단/삭제는 하지 않는다.
- 이번 실행 경로 제거에 불필요한 core/ai 피부 보정 엔진·클라이언트·기하 라이브러리 전면 삭제. 호출되지 않는 하위 구현은 유지 가능하며 잔존 이유를 RESULT에 적는다.
- 자동 보정(MonetGPT), 멀티샷, 선택/지우기/채우기/확대/스타일/지시 및 사진 맞춤 문장 추천의 제품 동작.
- 전체 도구 막대 숨기기, 1단계 메뉴로 개편, 새로운 메뉴 정렬/설정/기능 플래그.
- 무관한 변경, `work/REVIEW.md` 자동 덮어쓰기, 자동 commit/push, task/result/review 사본 생성.

## Requirements

1. 모든 사진에서 아래 메뉴와 순서를 사용한다. 인물 수, 얼굴 유무, 모델 준비 상태, 사진 로딩 시간에 따라 메뉴가 바뀌지 않는다.

   | 단계 | 순서 |
   | --- | --- |
   | Root | 라이트, 색상, 혼합, 자르기, 디테일, AI |
   | AI | 뒤로, 선택, 지우기, 채우기, 확대, 스타일, 자동, 멀티샷, 지시 |

2. 피부 보정 항목은 모든 메뉴에서 제거한다. `Tool.SkinRetouch`와 시트/이벤트/컨트롤러 진입 경로도 제거하여 숨겨진 기능으로 남기지 않는다. 자동 보정은 AI 단계에서 계속 사용 가능하고 디테일은 모든 사진의 Root에 표시한다.
3. `ToolMenuProfile`, `menuProfileFor`, Portrait 전용 목록/필터/인자와 `EditorUiState.portrait`, detector 주입·job·detect 호출을 제거한다. 단순히 General로 고정한 채 백그라운드 감지를 계속하지 않는다. `renderSource()`의 원본 렌더는 비교 보기 등 기존 용도를 위해 유지한다.
4. 사진 열기·재진입·문서 변경 시 메뉴/피부 보정을 위한 얼굴 분석, 모델 다운로드 요청, retouch health/capability/보정 요청이 발생하지 않는다. 사용되지 않는 provider가 EditorAi를 통해 불필요하게 구성되지 않도록 연결을 정리한다.
5. 피부 보정 draft·busy·progress·snackbar·설정·취소·Apply·문서 변경·export 차단 처리를 함께 제거한다. 남은 도구의 busy/취소/오류 표시와 draft 처리는 유지하고, 제거된 상태 때문에 내보내기나 다른 도구가 잠기지 않게 한다.
6. 서버 설정에서 피부 보정 전용 주소/토큰과 검증 조건을 제거한다. SAM3·MonetGPT·Gemini 설정과 저장 가능 조건은 기존 기능에 맞게 유지한다. 기존 피부 보정 설정 저장값을 삭제하는 데이터 마이그레이션은 추가하지 않는다.
7. 기존 피부 보정 연산을 가진 프로젝트는 그대로 열리고 같은 보정 픽셀을 렌더/내보내기한다. 다른 편집 후 저장·재열기에도 해당 연산과 참조 파일이 유지되어야 한다. 기존 history Undo/Redo·Reset의 의미를 바꾸지 않으며 신규 보정 실행 없이 저장 결과만 재현한다.
8. 메뉴의 AI/뒤로, system Back, 도구 적용·취소 후 Root 복귀, disabled-but-tappable 안내, 선택 표시, 가로 스크롤, 접근성 및 기존 치수/토큰을 유지한다. 얼굴 분석 대기/실패 UI는 새로 만들지 않는다.
9. 삭제된 기능만 검증하던 테스트는 제거/대체할 수 있으나 남은 메뉴 동작 및 저장 호환 테스트를 약화하지 않는다. 전체 참조를 검색해 main/test/testShared의 생성자와 DI를 맞춘다. ML Kit dependency는 남겨둔 FaceRegionAnalyzer 구현이 여전히 사용하는지 확인한 뒤 판단하고, 컴파일을 깨뜨리면서 제거하지 않는다. 의존성 정리가 필요하지 않다면 Gradle/catalog/baseline 변경을 만들지 않는다.
10. 명세와 결정 로그에는 피부 보정 신규 실행 및 T79의 메뉴 정책이 대체되었음을 명시한다. 과거 서버 처리/평가 계약은 보존하고 현재 제공 기능으로 오해되지 않도록 상태를 표시한다. 저장 호환성 때문에 남기는 코드와 현재 앱에서 실행하는 코드를 구별해 기록한다.

## Acceptance Criteria

- [ ] 인물/비인물 사진 모두 위 표와 같은 메뉴·순서이고 피부 보정은 어느 단계에도 없다.
- [ ] 디테일은 항상 Root, 자동은 항상 AI 단계에서 사용할 수 있다.
- [ ] 편집기 진입에 메뉴용 얼굴 감지나 피부 보정 분석/네트워크 요청이 없다.
- [ ] 피부 보정 시트·전용 설정·draft/상태 연결을 제거했고 다른 도구와 내보내기가 정상 동작한다.
- [ ] 기존 SkinRetouch 프로젝트의 렌더·저장·재열기·내보내기와 파일 참조 호환성이 유지된다.
- [ ] 관련 행동 테스트, 필요한 골든 검증, APK 빌드, canonical 검사가 통과한다. 미실행/환경 제한은 구별해 기록한다.
- [ ] 현재 제품 문서와 결정 로그를 맞추고 이전 미해결 인계를 보존한다.

## Validation

개발 중 좁은 검사:

```bash
./gradlew --offline --quiet :feature:editor:testDebugUnitTest
./gradlew --offline --quiet :core:imaging:testDebugUnitTest --tests '*SkinRetouch*' --tests '*EditDocumentJsonTest*' --tests '*OperationOrderTest*'
./gradlew --offline --quiet :core:data:testDebugUnitTest --tests '*SkinRetouchPersistenceTest*'
./gradlew --offline --quiet :app:assembleDebug
```

완료 검사:

```bash
scripts/check.sh
git diff --check
```

행동 검증:

- `ToolStripLevelTest` 및 기존 Portrait 메뉴 테스트를 고정 메뉴 계약으로 정리한다. 각 남은 Tool이 정확히 한 단계에 한 번 나타나는지, 디테일/자동/멀티샷/지시 순서와 navigation을 확인한다.
- 기존 `PortraitDetectionTest`의 감지 수행 기대는 폐기하되, 원본/편집 preview와 화면 진입 회귀 검증은 유지/옮긴다. 얼굴 유무에 독립적인 메뉴와 감지 실행 경로 제거를 코드·DI 검토로 확인한다. 제거된 API를 테스트 목적으로 다시 도입하지 않는다.
- 서버 설정 테스트에서 retouch 필드가 없고, 나머지 설정의 입력·검증·저장이 유지되는지 확인한다.
- 기존 SkinRetouch 모델/렌더/영속성 테스트를 유지한다. 부족하다면 저장된 보정 결과가 있는 프로젝트에 다른 편집을 추가한 뒤 저장·재열기해 연산/파일/픽셀이 보존되는 의미 있는 회귀 테스트를 추가한다.
- 메뉴/설정 변경으로 실제로 달라진 골든만 갱신하고 육안 확인한다. 무관한 골든 재생성이나 허용 오차 확대는 금지한다.
- 연결 가능한 실기기에서 인물 1장/비인물 1장의 동일 메뉴, 디테일·자동 진입, 피부 보정 설정 부재, 기존 보정 프로젝트 열기/내보내기를 확인한다. 기기/fixture가 없으면 자동 테스트와 구별해 미실행으로 기록한다. 사용자 사진 업로드나 외부 API 실행은 메뉴 검증의 필수 조건이 아니다.

## Notes

- 이 파일은 Claude Code를 위한 구현 계약이다. 이번 작성만으로 구현/검증 완료를 의미하지 않는다.
- 기존 RESULT는 지시 추천 R1·R2의 코드 수정과 자동 검사 통과를 보고하지만 REVIEW는 CHANGES_REQUESTED이며 수정 후 독립 실기기 재확인이 남아 있다. 2026-10-12 APK 설치 성공은 해당 검증을 완료했다는 뜻이 아니다. 사진 6종·8문장, TalkBack·음성·오류 경로 등 기존 미실행을 완료로 바꾸지 않는다.
- 이전 서버 멀티샷 R1(ASGI 취소 후 후속 추출)/R2(큰 정수 metadata 500), Jev 미구현, 전체 사진 품질·offline·12MP/PSS 평가는 별도 미완료다. 기존 RESULT/REVIEW의 근거와 함께 후속 RESULT에 인계한다.
- 피부 보정 D084 지원 종류 0개·품질/lifecycle 평가 기록은 보존한다. 앱 신규 실행 제거로 현재 앱 출시 범위에서는 제외되지만 서버 품질 문제가 해결된 것으로 기록하지 않는다. 제거되는 피부 보정 draft 실기기 검증은 “기능 제거로 비적용”으로 구분한다.
- 결정 로그는 `work/decisions.md` 하나만 사용하며 T79와 D080/D083/D084를 지우거나 과거 사실을 다시 쓰지 말고 새 결정의 적용 범위를 연결한다.
