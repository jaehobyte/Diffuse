# Task

## Goal

앱의 MonetGPT 자동 보정 기능(‘자동’ 도구), 서버 설정과 연동 코드를 제거하고 Gemini 보정 추천·직접 지시 및 기존 편집 문서의 호환성을 유지한다.

## Background

2026-10-13 사용자 요청: “자동 보정 서버 제거(monetGPT)를 위한 tasks.md”. 범위 확인에 사용자가 동의했다: **앱의 MonetGPT 기능·연동 제거**, Gemini 보정 추천 유지. 외부 서버 저장소·모델·운영 자원 철거는 이번 계약에 포함하지 않는다.

현재 코드에서 확인한 연결:
- `core/ai/monet/*`가 `/health`와 `/v1/chat/completions`를 호출한다. `AiModule`이 `MonetSettings`, `MonetClient`, `MonetAutoEnhanceProvider`를 연결한다.
- `AutoEnhanceProvider` / `AutoStyle` → `EditorAi` → `AutoController` / `AutoState` → AI 그룹의 `Tool.Auto`와 `AutoSheet`로 이어진다.
- `EditorViewModel`과 `EditorRoute`에 자동 보정의 probe·상태 수집·busy/message·미리보기·적용·취소·문서 변경 처리가 분산되어 있다. 문서 변경 collector에는 멀티샷 처리도 함께 있어 통째로 삭제하면 안 된다.
- `SelectionController` / `SelectionState` / `Sam3SettingsSheet`가 SAM3·Gemini·Monet 설정을 함께 저장한다. 현재 저장 활성화는 SAM3 또는 Monet 주소에 의존하므로 Monet 필드만 삭제하면 Gemini 키만 사용하는 설정 흐름을 막을 수 있다.
- `core/ai/build.gradle.kts`는 `MONET_BASE_URL`, `MONET_TOKEN`을 생성하고 `MONET_AUTH_TOKEN`, `monet.baseUrl`, `monet.token` 입력을 읽는다. `scripts/install.sh`에도 Monet 주소 처리가 있다.
- 자동 보정 결과는 `AutoState.appliedTo`에서 일반 `withAdjust(..., maskId = null)` 연산으로 적용된다. 서버 전용 문서 포맷을 제거하는 작업이 아니다.
- 기존 RESULT에는 MonetGPT와 SAM3의 GPU 메모리 충돌 후 MonetGPT 추론을 중지한 기록이 있다. 이는 과거 운영 기록이며 현재 서비스 상태를 새로 확인한 것은 아니다.

이번 요청은 `specs/auto_enhance.md`의 자동 도구 제공 계약과 `specs/tool_groups.md`의 메뉴 계약을 명시적으로 변경한다. D081의 연결 복구 및 D082의 외부 테스트 접근은 제거된 앱 기능의 과거 결정으로 남기고, 서버 자원 철거 완료로 해석하지 않는다.

## Scope

### Modify

- 제거: `core/ai/src/main/kotlin/com/diffuse/core/ai/monet/`, `AutoEnhanceProvider.kt` 및 전용 테스트·`testShared/FakeAutoEnhanceProvider.kt`.
- 제거: `feature/editor/.../tools/auto/` 및 전용 테스트·골든. 공용 fixture나 다른 기능의 근거는 보존한다.
- 정리: `AiModule.kt`, `EditorAi.kt`, `EditorViewModel.kt`, `EditorRoute.kt`, 실제 `EditorUiState` 정의, `Tool.kt`, `ToolSheetHost.kt` 및 관련 도구 동작 분기.
- 정리: `tools/select/{SelectionController,SelectionState,Sam3SettingsSheet}.kt`, 전용 문자열·test tag 및 설정 테스트.
- 정리: `core/ai/build.gradle.kts`, `scripts/install.sh`, 추적 중인 설정 예제·현재 기능 안내. 실제 비밀값을 열람·출력할 필요는 없다.
- 갱신: `EditorAi`를 생성하는 feature/editor와 app 테스트 fixture, 메뉴·설정·Back·회귀 테스트 및 영향받은 UI 골든.
- 갱신: `specs/auto_enhance.md`, `specs/tool_groups.md`, `architecture/projetoverview.md` 및 현재 기능을 잘못 설명하는 직접 관련 문서/주석. `work/decisions.md`에 제거 결정, `work/RESULT.md`에 구현·검증 인계.

### Do not modify

- Gemini 추천·직접 지시의 프롬프트, 함수 스키마, 추천 규칙, 스타일 프리셋 및 참조 스타일 매칭 동작. 자동 도구를 Gemini 호출로 대체하지 않는다.
- 기존 미커밋 Gemini 3.8 Flash 변경과 `prompt.txt`. 관련 파일을 만질 때 해당 변경을 유지한다.
- SAM3 선택/멀티샷, 피부 서버, 생성 지우기·채우기·확대의 기능·주소·인증·운영 상태.
- `AdjustKind`(Blacks, Whites, Fade, SCurve, Clarity 및 HSL 포함), 렌더 수학, JSON/DB 스키마, 기존 프로젝트·사용자 사진.
- 외부 `/home/jaeho/monetGPT` 저장소, 모델 가중치·캐시, systemd 서비스·Caddy·포트·방화벽·GPU 프로세스. 중단/삭제/재배포 명령을 수행하지 않는다.
- 이전 REVIEW 수정, 자동 commit/push, 별도 task/result 사본, 관련 없는 리팩터링·의존성 교체.

## Requirements

1. **앱 기능 및 실행 경로 제거**
   - AI 도구 목록에서 ‘자동’을 제거한다. 비활성 메뉴, 준비 중 메시지, 빈 시트 또는 대체 서버 경로를 남기지 않는다. 나머지 메뉴의 상대 순서를 유지한다.
   - `AutoController` 생성, DI binding, 상태/Flow 수집, probe 및 재시도, 설정 저장 구독, 실행·취소·적용·미리보기·busy/message 연결을 함께 제거한다.
   - 앱 진입·사진 열기·서버 설정 저장·다른 AI 도구 사용으로 Monet health 또는 추론 요청이 발생할 경로가 없어야 한다.
   - 공용 취소/Back/문서 변경 collector에서 자동 보정 부분만 제거한다. 멀티샷의 취소·문서 전환 무효화, 나머지 도구의 preview/busy/message 처리를 보존한다.

2. **설정과 빌드 구성 정리**
   - 공용 서버 설정에서 Monet 주소·토큰·안내·검증·callback 인자·상태를 제거한다. 남은 필드는 SAM3 주소/토큰과 Gemini API 키이며 기존 DESIGN 컴포넌트를 사용한다.
   - SAM3 주소 없이 Gemini 키만 입력·수정·저장할 수 있어야 한다. 반대로 Gemini 키 없이 SAM3 설정만 저장할 수 있어야 한다. 기존 값을 비우고 저장하는 동작도 막지 않는다. 제거된 Monet 설정은 저장 가능 여부에 영향을 주지 않는다.
   - 남은 설정을 다른 서비스에 복사하거나 삭제하지 않는다. 취소 시 입력 변경을 저장하지 않고 Gemini 키 마스킹을 유지한다.
   - Monet BuildConfig 필드와 환경변수/local.properties 소비, 설치 스크립트의 Monet 처리와 현행 설정 예제를 제거한다. 로컬 credential 빌드 옵션과 다른 서버 설정 로딩은 유지한다.
   - 업그레이드 후 남는 `monet_settings` 전용 SharedPreferences는 읽거나 사용하지 않고 그대로 무시한다. 이번 제거만을 위한 마이그레이션/앱 데이터 초기화는 추가하지 않는다. 이 잔여 저장 데이터 정책을 RESULT에 명시한다.

3. **편집 데이터와 공용 코드 보존**
   - 이전 자동 보정으로 저장한 일반 Adjust 연산을 그대로 역직렬화·렌더·재편집·재저장·내보내기할 수 있어야 한다. 제거를 이유로 보정값을 초기화하거나 프로젝트를 마이그레이션하지 않는다.
   - Monet 연동에 쓰였다는 이유로 공용 tone/HSL 연산, 스타일 프리셋, 이미지 코덱·HTTP/availability 유틸을 지우지 않는다. 실제 참조를 확인해 Monet 전용 구현만 제거한다.
   - 피부 서버의 주석 등에 있는 과거 Monet 구현 참조는 필요하면 현재 동작 설명으로 고친다. 기능 구현을 재설계하지 않는다.

4. **테스트와 문서**
   - Monet 전용 테스트는 기능과 함께 제거하고 공용 테스트 fixture에서 제거된 생성자 인자를 정리한다. 나머지 행동 assertion을 약화하거나 테스트 전체를 삭제해 컴파일만 맞추지 않는다.
   - AI 메뉴의 정확한 남은 항목·순서와 ‘자동’ 부재를 확인한다. `Tool.entries`와 동등하다는 자기참조 검사만으로 끝내지 않는다.
   - 설정 테스트에 Gemini 단독 저장, SAM3 단독 저장, 기존 값 비우기·취소 및 남은 설정 보존을 포함한다.
   - 기존 일반 Adjust 저장/복원 검사를 확인하고 Blacks/Whites와 HSL을 포함한 이전 자동 보정 결과의 호환성에 빈틈이 있으면 최소 회귀 사례를 보강한다. 기존 의미 있는 검사가 충분하면 중복 테스트를 만들지 않는다.
   - 실제 변경된 메뉴·설정 골든만 육안 확인 후 갱신한다. Monet 전용 골든/등록 항목을 함께 제거하며 전체 골든을 재기록하거나 허용 오차를 넓히지 않는다.
   - `specs/auto_enhance.md`는 삭제 대신 상단에 기능 제거 상태와 보존된 Adjust 데이터 계약을 명시하고, 아래 종전 계약이 역사적 참고임을 분명히 한다. 현재 메뉴·제품 안내에서 MonetGPT를 사용 가능한 기능으로 안내하지 않는다.
   - D081/D082 등 과거 결정과 과거 릴리스 기록은 역사로 보존한다. 새 결정으로 앱 연동 폐기 범위를 명시하며 외부 서버까지 철거했다고 쓰지 않는다.

## Acceptance Criteria

- [ ] 앱에서 자동 메뉴·시트·Monet 설정이 사라지고 Monet provider/DI/네트워크 실행 경로가 제거됐다.
- [ ] SAM3와 Gemini를 독립적으로 저장·사용할 수 있고 기존 설정 및 취소/Back 동작이 보존된다.
- [ ] Monet BuildConfig·환경변수 소비·설치 스크립트 연결과 전용 테스트/골든이 정리됐다.
- [ ] Gemini 추천·직접 지시·스타일 매칭, SAM3·멀티샷 등 다른 기능에 회귀가 없다.
- [ ] 기존 자동 보정 결과의 Adjust 데이터·tone/HSL 렌더·저장 호환성이 유지된다.
- [ ] 관련 테스트·APK 빌드·저장소 전체 검사가 통과하거나 미실행/환경 차단/기존 실패가 구분되어 보고됐다.
- [ ] 명세·제품 안내·결정 로그가 제거 범위와 일치하고 외부 서버/사용자 데이터는 변경하지 않았다.

## Validation

```bash
# 변경 모듈과 소비자의 컴파일 및 행동 회귀
./gradlew --offline --quiet :core:ai:testDebugUnitTest :feature:editor:testDebugUnitTest :app:testDebugUnitTest
./gradlew --offline --quiet :core:data:testDebugUnitTest :core:imaging:testDebugUnitTest
./gradlew --offline --quiet :app:assembleDebug
scripts/check.sh
git diff --check

# 삭제된 연결의 잔존 확인 (일치 항목은 현행 코드/역사적 기록을 구분해 설명)
rg -n 'Monet|MONET_|monet\.|AutoEnhanceProvider|AutoStyle|Tool\.Auto|tools\.auto' core feature app scripts --glob '!**/build/**'
```

개발 중에는 `ToolStripLevelTest`, `Sam3SettingsSheetTest`, `SelectionToolTest`, `EditorBackTest`, 직접 지시·스타일·멀티샷 테스트 등 관련 필터부터 실행할 수 있다. 최종 검증은 수정된 공용 fixture의 모든 소비자를 포함한다. 관련 없는 서버 검사를 새로 요구하지 않는다.

기기가 가능하면 기존 앱 데이터 유지 업데이트 후 AI 메뉴·서버 설정·Gemini 단독 설정 저장, 보정 추천 진입, SAM3 선택/멀티샷 진입, 기존 보정 프로젝트 재열기·내보내기를 확인한다. 실제 API를 호출하지 못하면 fake/MockWebServer 통과와 분리해 기록한다. 외부 서버가 이미 중지됐다는 이유만으로 앱 요청 경로 제거 검증을 대신하지 않는다.

## Notes

- 이번 요청은 **작업서 작성**이며 이 파일 작성만으로 구현 완료를 뜻하지 않는다. Claude Code가 구현·검증 후 canonical `work/RESULT.md`를 갱신한다. Codex 리뷰는 별도다.
- 현재 작업 트리에 Gemini 3.8 Flash 전환(두 client의 경로/주석, endpoint 테스트, `prompt.txt`, 관련 명세, D096)이 미커밋 상태다. 이 변경은 보존하고 제거 작업의 diff와 구분해 보고한다. D096 다음 결정 번호는 작성 시 중복을 확인한다.
- 이전 보정 추천 REVIEW는 CHANGES_REQUESTED이며 R1(활성 선택과 goal 불일치), R2(배경 보존 중 CutOut 허용), R3(관련 회귀 검사)가 미해결이다. 이 제거 작업에서 수정하거나 해소됐다고 보고하지 않는다. 12종 fixture·각 3회·6축 품질 평가도 미완료다.
- 이전 멀티샷 작업은 추가 5장/추출 대상 구현 및 자동 검사 완료 보고가 있으나 3장 제한 원인·picker/5장 전체·실제 dog/car 검증이 미완료다. 이후 설치·SAM3 truck 단일 추출 성공을 전체 시나리오 완료로 확대하지 않는다.
- 기존 디자인/Back의 독립 실기기·IME/음성/TalkBack, 서버 멀티샷 ASGI 취소/큰 정수 metadata, 사진 품질/offline/12MP·PSS, Jev, 피부 D084 지원 0개와 서버 평가의 남은 사항은 유지한다. 상세는 기존 RESULT/REVIEW와 `work/retouch_evaluation.md`를 참조한다. 새 RESULT 작성 시 이 미해결 정보와 유효한 근거 링크를 이어 적고 별도 사본을 만들지 않는다.
