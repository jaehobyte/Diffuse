# Task

## Goal

인물로 감지된 사진에서 디테일 자리를 피부 보정으로 교체하고 앱에서 피부 보정 시트 진입과 준비 상태를 기능 테스트할 수 있게 한다.

## Background

사용자 요청: 인물로 디텍되었을 때 디테일 칸을 피부 보정 칸으로 바꿔 기능 테스트한다.

2026-09-09 코드 확인 결과, PortraitDetector와 profile별 메뉴는 구현돼 있다. 현재 Portrait Root는 자동, 디테일, 라이트, 색상, 혼합, 자르기, AI 순서다.

다만 피부 보정은 FaceRegionAnalyzer/SkinRetouchProvider 계약과 testShared fake까지만 있다. 실제 provider/DI binding, Tool.SkinRetouch, 시트/controller, Operation.SkinRetouch, 저장/render 연결은 없다. work/RESULT.md도 SR1 부분 완료, SR2–SR4 미착수를 명시한다. DetailSheet의 실제 기능은 선명도/비네트다.

이번 작업은 메뉴 교체와 전용 시트의 준비 상태 연결이라는 한 변경으로 한정한다. 실제 픽셀 보정 테스트는 엔진 및 저장/render/preview 연결이 선행돼야 한다. 메뉴 테스트 통과를 피부 보정 완성으로 보고하지 않는다. 이전 전체 구현 계획은 [skin_retouch_plan.md](skin_retouch_plan.md)에 보존한다.

명시적 계약 변경: specs/skin_retouch.md §2의 ‘인물 Root 맨 앞에 피부 보정을 추가하고 디테일도 유지’ 대신 이번 사용자 요청대로 기존 디테일 위치를 교체한다. 일반 메뉴의 피부 보정 추가는 이번 범위에서 제외한다. 이전 계획의 SR3 이전 메뉴 비노출 제한 대신, 준비 상태를 확인할 수 있는 진입점을 먼저 제공한다.

## Scope

### Modify

- feature/editor의 Tool.kt: 독립 SkinRetouch 식별자 및 profile별 노출 목록.
- EditorViewModel.kt, EditorRoute.kt, 필요 시 EditorAi.kt: 시트 선택/닫기 및 준비 상태 연결.
- feature/editor의 tools/retouch 패키지: 기존 공통 시트를 활용한 피부 보정 시트.
- editor 한국어 strings.xml, 관련 메뉴/시트 테스트와 영향받은 goldens.
- 필요 시 core/ai의 명시적 Unavailable provider와 DI 및 테스트.
- work/RESULT.md: 이번 결과와 실제 픽셀 보정 테스트의 미완료 의존성.

### Do not modify

- PortraitDetector 감지 기준/source 재사용/Unknown fallback.
- DetailSheet, Sharpen/Vignette 연산, 기존 조정 값과 저장 문서.
- 일반 메뉴 배치, 다른 도구 동작, 기존 AI 도구 순서.
- 실제 엔진 선정/모델 다운로드/서버 구현/자동 결함 검출/피부 mask 생성.
- SkinRetouch operation/저장/render 전체 구현. 이전 SR2–SR4를 이 diff에 합치지 않는다.
- 새 의존성/모듈, 무관한 리팩터링, Ralph 설정, 기존 SR1 변경 및 평가 자료.
- specs/와 DESIGN.md. 메뉴 계약 충돌은 이 작업서의 명시적 변경을 따른다.

## Requirements

1. 아래 목록을 정확히 따른다. Root 항목 수를 늘리지 않고, Portrait의 디테일을 AI 하위로 옮기지 않는다. Tool.Detail은 유지한다.

   | Profile | Root | AI |
   |---|---|---|
   | General | 라이트, 색상, 혼합, 자르기, 디테일, AI | 뒤로, 선택, 지우기, 채우기, 확대, 스타일, 자동, 지시 |
   | Portrait | 자동, 피부 보정, 라이트, 색상, 혼합, 자르기, AI | 뒤로, 선택, 지우기, 채우기, 확대, 스타일, 지시 |

2. 기존 PortraitResult → menuProfileFor → stripItems 경로를 재사용한다. Portrait만 교체하며 NotPortrait/Unknown/감지 대기/실패는 일반 메뉴다. 메뉴 때문에 감지나 이미지 decode를 추가 실행하지 않는다.

3. Tool.SkinRetouch를 독립 도구로 추가한다. 라벨과 시트 제목은 ‘피부 보정’이고 기존 Material Rounded 아이콘/토큰을 쓴다. 디테일 문자열만 바꾸거나 선명도/비네트/자동 보정에 대신 연결하지 않는다.

4. 피부 보정 탭은 전용 시트를 연다. 현재처럼 실제 엔진이 없더라도 탭으로 진입해 ‘피부 보정 기능을 준비 중이에요’와 같은 정확한 사용 불가 이유를 볼 수 있어야 한다. 빈 시트나 무반응 탭으로 남기지 않는다. 잡티·여드름 제거, 유분광 제거, 다크서클 완화, 면도자국 완화 네 항목을 미지원/비활성으로 표시한다. slider를 표시하면 기존 AdjustSlider를 재사용하며 0..100, 기본 0이다. 미리보기/적용은 비활성, 취소/Back/dismiss는 가능하다. 엔진 부재를 얼굴 없음/분석 실패로 잘못 설명하지 않는다. 시트 진입 시 얼굴 분석/보정/네트워크 요청은 발생하지 않는다.

5. 기존 EditSheet의 제목/고정 취소·적용 행, 최대 높이 45%, 내부 스크롤, 접근성과 disabled 스타일을 따른다. 탭 높이 72dp/폭 64dp/아이콘 24dp, AI 부모 표시, 2단계 구조를 유지한다.

6. 뒤늦은 Portrait 감지 완료는 메뉴 목록만 바꾼다. 열린 디테일 시트를 피부 보정으로 전환하거나 닫지 않으며 selectedTool, draft 값, toolLevel을 유지한다. 사용자가 닫고 피부 보정을 탭할 때 새 시트로 진입한다.

7. 피부 보정 시트 진입/닫기로 document/history/activeMaskId/기존 조정 값이 바뀌지 않는다. 취소/Back/dismiss는 기존 패턴대로 Root로 돌아온다. 회전/화면 이탈 시 가짜 commit이나 자동 재요청이 없어야 한다.

8. main/debug 앱에 testShared fake를 주입해 실제 보정처럼 표시하지 않는다. provider 연결이 필요하면 기존 Availability/Result에 맞는 정직한 Unavailable 구현을 사용한다. Gemini 대체, 숨은 서버 호출, 로컬 실패 시 업로드는 없다.

9. PortraitToolMenuTest의 ‘모든 enum이 모든 profile에 정확히 한 번 등장’ 검사는 조건부 교체와 충돌한다. 검사를 삭제하는 대신 profile별 정확한 순서/중복 없음/의도된 제외(General의 SkinRetouch, Portrait의 Detail)/전체 profile 합집합의 도구 누락 없음을 검증한다. 실제 탭→전용 시트→닫기, 일반 디테일 진입, 늦은 감지 중 열린 디테일 유지도 테스트한다.

10. RESULT에 메뉴/시트 진입 검증과 실제 픽셀 보정 검증을 구분한다. 이번 범위는 메뉴 연결 완료로 보고할 수 있지만 실제 보정 테스트는 미완료다. 후속 의존성은 이전 계획의 실제 provider·자동 결함 mask 확보 → SR2 저장/render → SR3 미리보기/적용 연결이다. fake/golden 통과를 실제 품질 증거로 사용하지 않는다.

## Acceptance Criteria

- [ ] Portrait의 디테일 위치가 피부 보정으로 교체되고 목록/순서/항목 수가 표와 일치한다.
- [ ] General/Unknown 메뉴 및 일반 사진의 디테일 동작은 유지된다.
- [ ] 피부 보정 탭으로 전용 시트를 열고 준비 중 안내와 네 항목의 미지원 상태를 확인할 수 있다.
- [ ] 미리보기/적용 비활성, 취소/Back/dismiss 정상, 문서/history/선택 변경 없음.
- [ ] 늦은 감지 결과가 열린 디테일 시트와 조정 값을 변경하지 않는다.
- [ ] 메뉴 목록/중복/조건부 제외, 시트 진입/종료, 일반 디테일 회귀 검증이 있다.
- [ ] 관련 tests/goldens 및 저장소 검증 통과 또는 실행 제한을 명시했다.
- [ ] 실제 피부 보정 기능 테스트의 미완료 사유와 선행 구현이 RESULT에 있다.
- [ ] 무관한 동작 변경이 없다.

## Validation

개발 중 좁은 검사:

```bash
./gradlew :feature:editor:testDebugUnitTest --tests '*PortraitToolMenuTest' --tests '*PortraitDetectionTest' --tests '*SkinRetouch*' --tests '*ToolStripLevelTest'
```

최종 검사:

```bash
./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug
scripts/check.sh
git diff --check
```

core/ai 변경 시 `./gradlew :core:ai:testDebugUnitTest`도 실행한다.

수동 검증:
1. 얼굴 사진 로드 후 자동 다음에 피부 보정이 나오는지 확인한다.
2. 피부 보정 시트의 준비 중 안내/비활성 항목/비활성 적용을 확인하고 취소와 Back으로 닫는다.
3. 일반 사진에서 디테일의 선명도/비네트가 기존처럼 동작하는지 확인한다.
4. 기기/이미지가 없어 실행하지 못한 항목은 미실행으로 보고한다. 실제 픽셀 개선·적용·Undo·저장·내보내기 검증은 선행 구현 부재로 미완료임을 별도 기록한다.

## Notes

- 기준: specs/architecture.md, specs/tool_groups.md, DESIGN.md, work/decisions.md의 T79/D080.
- 후속 보정 계약: specs/skin_retouch.md, specs/skin_retouch_pipeline.md, specs/skin_retouch_validation.md, work/skin_retouch_plan.md. 메뉴 배치/현재 노출 범위는 이번 작업서가 우선한다.
- 현재 작업 트리의 SR1 미커밋 변경을 보존한다. 이전 RESULT의 통과 기록을 이번 변경의 검증 결과로 재사용하지 않는다.
