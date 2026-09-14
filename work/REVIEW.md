# Review

## Status

CHANGES_REQUESTED

2026-09-14. 현재 `work/tasks.md`와 `work/RESULT.md` 기준 리뷰. T1 코드에 아래 수정이 필요하다. 전체 작업도 승인하지 않는다: T2/T3 피부 보정은 BLOCKED이고 T1/T4 휴대폰 검증은 미완료다. RESULT의 PARTIAL 구분은 적절하다.

이전 서버/검출기 리뷰는 `work/REVIEW_before_connection_2026-09-14.md`에 보존했다. 기존 검출기 변경을 이번 피부 기능 구현으로 간주하거나 이전 서버 지적의 해결 여부를 재판정하지 않았다.

## Blocking

### R1 [P2] 연결 성공 이력이 있으면 주소 수정 경로가 사라짐

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/auto/AutoController.kt:141`
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/auto/AutoController.kt:106`

Problem:
Ready를 한 번 관측하면 everReady는 계속 true다. 이후 서버 주소 변경·네트워크 이동·USB reverse 해제 등으로 Io가 발생하면 자동 탭은 같은 주소의 재확인만 반복하고 서버 설정을 열지 않는다. 503도 설정 진입을 제공하지 않는다. EditorRoute에는 별도 서버 설정 진입 버튼이 없으며 다른 AI 도구의 설정 오류를 유발하는 것은 자동 도구의 복구 경로가 아니다. 기존 테스트 `a server that answered before re-checks without throwing the sheet`는 설정을 열지 않는 동작만 고정한다.

Impact:
T1 요구사항 3의 “실패 시 명시적 재시도와 설정 수정 경로”를 충족하지 못한다. 이전 성공 여부로 현재 주소도 맞다고 판단하여 앱 내에서 정상 주소로 복구할 수 없는 상태가 남는다.

Required fix:
재시도와 함께 사용자가 명시적으로 서버 설정을 열 수 있게 한다. 매번 시트를 자동으로 띄울 필요는 없지만 과거 성공 여부나 다른 provider 상태에 의존하지 않는 진입 동작이 필요하다. auto_enhance 명세와 D081도 작업서의 복구 계약에 맞춘다.

### R2 [P2] 문서 변경으로 중단한 자동 세션이 이전 사진을 재사용함

Location:
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/auto/AutoController.kt:241`
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/auto/AutoController.kt:220`
`feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorViewModel.kt:286`

Problem:
기존 결과 시트에서 스타일 재요청 중 Undo로 문서가 바뀌면 새 onDocumentChanged는 진행 중 job만 무효화한다. 이전 input, plan, 열린 Auto 시트는 남는다. 이어 다른 스타일을 누르면 setStyle이 이전 문서의 bitmap으로 새 runId를 발급해 요청한다. 이 요청 중 추가 문서 변경이 없으면 완료 결과가 정상 처리되고 현재 문서에 적용 가능해진다. 이전 plan도 busy가 해제되면 즉시 Apply 가능하다. 현재 문서 변경 테스트는 결과 시트가 아직 없는 최초 요청만 검사한다.

Impact:
runId 검사만으로 입력 문서와 결과의 대응이 보장되지 않는다. Undo한 현재 사진에 이전 사진을 분석한 보정이 미리보기·history로 들어갈 수 있어 T1 요구사항 5의 문서 변경 후 결과 격리 목적을 충족하지 못한다.

Required fix:
문서 변경 시 자동 세션의 입력·후보·Apply 유효성을 함께 처리한다. 세션을 종료하거나 현재 문서에 맞는 입력을 다시 준비한 뒤에만 새 요청/적용을 허용한다. 정상적인 스타일 요청 실패에서 같은 문서의 이전 결과를 유지하는 계약은 보존한다. 시트를 종료한다면 Editor의 selectedTool과 sheetBaseline도 일관되게 정리한다.

## Tests Missing

- R1: Ready → Io/503 → 사용자의 설정 열기 → 주소 수정·저장 → Ready → 자동 실행. 다른 provider가 정상 설정된 조건에서도 설정에 접근 가능해야 한다.
- R2: 수정 이력이 있는 문서에서 자동 결과 시트 열기 → 스타일 요청 지연 → Undo → 다른 스타일 선택/Apply → 역순 완료. 이전 bitmap으로 새 요청이 실행되거나 오래된 후보가 현재 문서에 commit되지 않아야 한다.
- 실제 기기: 요구사항 17의 세 스타일·강도·적용/취소/Undo, clean install/기존 override 업데이트, 잘못된 토큰·서버 복구는 RESULT에서도 미실행이다. host smoke와 fake 테스트로 대체할 수 없다.

## Non-blocking

None.

## Validation Notes

- 작업서, 현재/이전 RESULT·REVIEW, 실제 자동 코드·테스트·설정 시트·Editor 연결, 관련 git diff, architecture/auto/skin pipeline/validation 명세, DESIGN, D080/D081과 피부 평가 기록을 확인했다.
- 리뷰에서 실행: `./gradlew :core:ai:testDebugUnitTest --tests 'com.diffuse.core.ai.monet.*' :feature:editor:testDebugUnitTest --tests 'com.diffuse.feature.editor.tools.auto.*' --tests 'com.diffuse.feature.editor.tools.select.*'` → BUILD SUCCESSFUL. core:ai 테스트는 FROM-CACHE, feature:editor 테스트 task는 실행됐다.
- 리뷰에서 실행: `scripts/check.sh` → exit 0. lint·detekt·단위 테스트·Roborazzi·dependencyGuard의 Gradle 증분/캐시 결과를 포함한다. 강제 전체 재실행은 하지 않았다.
- 리뷰에서 실행: `git diff --check` → exit 0. R1/R2는 코드 경로와 기존 테스트 범위를 대조한 발견이며 별도 재현 테스트를 추가하거나 기기에서 재현하지는 않았다.
- 서버 smoke, 공개 라우팅, APK 재빌드·해시 검증, 기기 설치·instrumentation·품질/성능 측정은 이번 리뷰에서 실행하지 않았다. RESULT의 해당 성공 기록은 작성자의 보고이며 독립 재검증 결과가 아니다.
- 전체 완료 제한: SkinRetouchSheet는 applyEnabled=false이고 AiModule에 production SkinRetouchProvider binding이 없다. 평가 기록상 SR1 품질 세트/가중치 사용 조건/기기 비용·배포 경로가 미해결이다. gate 없이 활성화하지 않은 판단은 타당하지만 T2/T3 완료를 의미하지 않는다. 자원 확보 → 종류별 SR1 평가 → production 편집·저장 통합 → 기기 검증이 남는다.
- T1 운영 제한: RESULT가 보고한 휴대폰 접근 경로 부재와 GPU 공존 문제는 앱 재시도 코드만으로 해결되지 않는다. 접근 가능한 서비스 경로 확보 및 실제 휴대폰 검증 전까지 자동 연결 문제 해결로 승인할 수 없다.
- 구현·테스트·작업서는 수정하지 않았다. 이번 변경은 REVIEW 작성과 이전 리뷰 보관뿐이다.
