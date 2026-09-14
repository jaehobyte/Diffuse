# Task

## Goal

다운로드한 앱의 피부 보정을 실제 사용 가능하게 구현하고, AI → 자동의 서버 연결 실패를 해결하여 수정 APK에서 보정·미리보기·적용·저장까지 검증한다.

## Background

사용자 보고(2026-09-14): 피부 보정에 들어가도 기능을 사용할 수 없고, AI → 자동은 “자동 보정 서버에 연결하지 못했어요”를 표시한다. 문구 변경이나 메뉴 숨김만으로 해결한 것으로 판정하지 않는다.

저장소 확인 결과:

- `SkinRetouchSheet.kt`는 준비 중 안내와 네 항목 미지원, `applyEnabled = false`인 placeholder다. `AiModule.kt`에 production `SkinRetouchProvider` binding이 없다. 클릭 이벤트만 수정해서 해결할 수 없다.
- 얼굴 분석 계약과 미커밋 잡티 검출기 평가 구현은 있지만 검출기는 복원 엔진이 아니다. 기존 `work/RESULT.md`는 PARTIAL이며 보호 피부 mask, 실제 복원 provider, SR1 품질 gate, 편집·저장 연결이 남았다고 명시한다. ONNX 실행은 debug 평가 경로이고 일반 배포 모델 준비 경로가 없다.
- `MonetAutoEnhanceProvider`는 settings Flow 방출 시에만 health를 확인한다. 최초 실패 후 같은 설정에서 서버가 복구돼도 재확인 경로가 없다. `health()`는 HTTP 실패를 Boolean으로 축약하고 `AutoController.onToolTapped()`는 실패 메시지만 표시하며 진입을 거부한다. controller 초기값도 연결 실패여서 확인 중 tap을 실패로 안내할 수 있다.
- `MonetSettings`의 저장 override가 빌드 기본값보다 우선한다. `scripts/install.sh`는 `-Pdiffuse.localCreds`로 개인 설정을 사용하는 반면 일반 다운로드 APK가 동일 설정을 가진다고 보장할 수 없다.
- `/home/jaeho/monetGPT/work/RESULT.md`에는 실제 모델 추론과 2026-09-14 smoke 성공이 기록돼 있지만 휴대폰 연동은 미검증이다. 같은 T4에서 SAM3와 동시 상주가 실패했다고 기록돼 있다. 이는 현재 기기 연결 실패 원인의 확정 증거가 아니다.
- 실제 설치 APK 버전, 현재 서버 접근성은 아직 확인하지 않았다. 작업서 작성 중 기기 재현·서버 호출·빌드·테스트는 실행하지 않았다.

이전 서버 작업서는 `work/tasks_monet_server_2026-09-10.md`, 검출기 작업서는 `work/tasks_acne_detector_2026-09-09.md`에 보존한다. 작업 시작 시 기존 미커밋 변경과 RESULT/REVIEW를 확인하고 보존한다. 이전 결과 문서는 새 결과로 덮어쓰기 전에 별도 보관한다.

## Scope

### Modify

- 자동 연결: `core/ai/.../monet/`, `AutoEnhanceProvider.kt`, `feature/editor/.../tools/auto/`, 기존 서버 설정 시트/controller와 관련 Editor 연결·문자열·테스트.
- 피부: `core/ai/` 얼굴 분석·보호 mask·provider·모델 준비/수명 관리, `feature/editor/.../tools/retouch/` 및 Editor 세션 통합.
- 피부 결과 저장에 필요한 `core/imaging` operation/codec/renderer, `core/data` 저장·참조·복제·삭제 경로와 테스트.
- 실제 배포에 필요한 Gradle/모델 설치 설정, `scripts/install.sh`, APK 설치/설정 안내.
- `/home/jaeho/monetGPT`: 자체 지침·git 상태·구현을 먼저 확인하고 재현된 서버/배포 설정 결함만 최소 수정한다. 결과는 해당 저장소에도 기록한다.
- 관련 specs, `work/decisions.md`, `work/retouch_evaluation.md`, `work/RESULT.md`.

### Do not modify

- SAM3 코드/토큰/기존 서비스를 임의로 변경·중단하지 않는다. GPU 부족을 SAM3 종료로 숨기지 않는다.
- MonetGPT를 fake/고정값/다른 모델로 대체하거나 피부 기능을 전체 blur·미백·단순 얼굴 검출로 대체하지 않는다.
- 피부 보정에 범용 외부 생성 API를 사용하거나 로컬 실패 시 자동 업로드하지 않는다. 공개 APK에 개인 주소/토큰을 하드코딩하지 않는다.
- 무관한 도구·디자인 개편, 기존 사용자 데이터 삭제, 자동 commit·공개 배포는 범위 밖이다.

## Requirements

### T1 — 자동 보정 연결 진단·복구

1. **설치 경로를 재현한다.** 앱 versionName/versionCode, APK 출처·variant, clean install과 기존 앱 업데이트의 설정 차이를 확인한다. 프로젝트/설정을 지워서 증상을 가리지 않는다. Monet 설정 출처(저장 override/빌드 기본값/빈 값), base URL, 인증, DNS/TLS/포트/프록시/Android network security, health 응답을 확인한다. 비밀값은 출력하지 않는다. 휴대폰 localhost, 에뮬레이터 `10.0.2.2`, 서버 loopback을 혼동하지 않는다.
2. **실제 요청 경로를 검증한다.** 기기가 쓰는 경로에서 인증된 `GET /health`, `POST /v1/chat/completions`를 검증한다. `/v1` 없는 base URL, `model: "test"`, PNG, 세 스타일, 문자열 content의 보정 계획 계약을 유지한다. 잘못된 URL은 저장/요청 시 검증하여 crash를 방지한다. 기존 잘못된 override도 앱 내 수정으로 복구할 수 있어야 한다.
3. **실패 후 재시도를 제공한다.** 미설정은 서버 설정으로 안내한다. 상태 확인 중을 연결 실패로 확정 표시하지 않는다. 실패 시 명시적 재시도와 설정 수정 경로를 제공한다. 서버 복구 후 같은 URL/토큰 그대로 앱 재시작 없이 재확인하고 자동 보정을 실행할 수 있어야 한다. 같은 설정 재저장도 복구 경로가 된다. 중복 요청을 제어하고 무한 polling·사진 자동 재업로드는 하지 않는다.
4. **오류 원인과 timeout을 구분한다.** 기존 AppError/availability 패턴으로 인증 실패, 서버 준비 중/503, 연결·시간 초과, 잘못된 주소를 가능한 범위에서 구분하고 행동 가능한 한국어 안내를 제공한다. health에는 유한하고 짧은 전용 timeout을 두며 추론 connect 10초/read 120초 계약은 유지한다. 모든 HTTP 실패를 Boolean 네트워크 실패로 버리지 않는다.
5. **경합을 막는다.** 설정 A 확인 중 B로 변경하면 A의 늦은 완료가 B 상태를 덮지 못한다. 취소를 HTTP에 전파한다. 문서/설정 변경·취소·스타일 재실행 후 늦은 추론 결과가 새 미리보기/history를 쓰지 못하고 old finally가 new busy를 해제하지 못한다. 관련 실제 경합을 회귀 테스트로 고정한다.
6. **서버 원인도 해결한다.** 모델 준비 상태, 인증, 프록시 및 서비스 재시작 후 접근성을 확인한다. USB reverse는 개발 검증용으로 명시하고 일반 다운로드 앱의 연결 완료와 구분한다. GPU 공존 불가가 계속되면 가용 호스트/장치 등 운영 해법과 필요한 외부 조치를 기록한다. 기존 서비스를 임의로 중단하지 않는다.
7. `specs/auto_enhance.md` §6의 기존 “probe 실패 → 동작 없음”을 이번 요청의 재시도/설정 수정 동작으로 갱신한다. 정상 자동→결과 시트, 강도 로컬 조절, 스타일별 요청, 한 Apply/Undo, 취소 시 무변경 계약은 유지한다.

### T2 — 실제 피부 엔진과 배포 준비

8. **기존 평가부터 이어간다.** 미커밋 검출기·평가·REVIEW를 확인하고 재사용 가능한 부분을 구분한다. `skin_retouch_validation.md` SR1-A/B를 충족하는 실제 잡티 검출+국소 복원+보호 mask부터 확보한다. 검출 box나 수동 정답 mask 결과만으로 자동 잡티 제거 완료를 선언하지 않는다. MI-GAN은 첫 평가 후보이며 production 채택 완료 모델이 아니다.
9. **종류별 지원을 검증한다.** 잡티가 통과하면 T3에 연결하고 유분광/다크서클/면도자국은 SR1-C로 각각 평가·구현한다. 하나의 전체 얼굴 후보를 네 slider에 공유하지 않는다. 미완료 종류는 사유와 함께 비활성으로 남기되 일부 구현을 피부 전체 완료로 보고하지 않는다. 네 기능 전체 완료에는 각각의 품질 gate가 필요하다.
10. **일반 배포에서 준비 가능하게 한다.** 모델/런타임의 사용·배포 조건, 버전/해시/크기, 실제 기기 비용을 검증한다. debug 전용 코드나 adb push만이 모델 설치 경로가 되지 않게 한다. 미설치/다운로드/실패·재시도/준비 완료를 구분하고 설치된 로컬 모델은 offline에서 동작해야 한다. 현행 architecture의 APK <1000MB, editor peak <250MB와 피부 성능 목표를 검증한다. 예전 15MB 기준을 재도입하거나 gate를 편의상 완화하지 않는다.
11. **D080을 지킨다.** 로컬 우선·사진 업로드 0건을 유지한다. 실측상 별도 보정 서버가 필요하면 채택 근거와 pipeline §8의 wire/설정/인증 계약을 먼저 구체화하고 명시적 서버 설정 후 실행한다. 위치/모델 제공 방식은 decisions에 기록한다. 모델·평가 자료·운영 자원 미확보는 해당 단계의 BLOCKED 사유로 기록하며 정상 지원으로 표시하지 않는다.

### T3 — 피부 편집·저장 통합

12. `SkinRetouchProvider`, `FaceRegionAnalyzer` 계약과 Hilt production binding을 연결하고 실제 모델 자원 소유자를 정한다. 메뉴용 감지를 보정 가능성으로 쓰지 않는다. 현재 문서의 삽입 prefix를 canonical 좌표에서 분석하여 crop/회전/Adjust를 중복 적용하지 않는다. 얼굴 0/1/다중, 작은 얼굴, 가림·누락 landmark를 구분하며 눈·입·머리카락·점/주근깨·다른 사람 보호를 검증한다.
13. placeholder를 기존 EditSheet/AdjustSlider 기반 기능 시트로 교체한다. 얼굴 선택, 처리 위치/준비 상태, 네 종류별 지원과 0..100 강도, 명시적 미리보기, 취소/적용을 연결한다. 기본 0과 NoChange는 history를 만들지 않는다. 후보 없는 활성 항목은 미리보기 갱신 전 Apply를 막는다. 준비된 후보의 강도 변경은 로컬 합성만 한다. 미준비/실패와 얼굴 없음을 구분하고 재시도를 제공한다. DESIGN.md의 시트 높이·고정 버튼·접근성 패턴을 유지한다.
14. `skin_retouch_pipeline.md` §4–7의 동일 base, 종류별 candidate, binary support, feather 한 번, alpha 보존 계약을 구현한다. 얼굴/문서/설정/세션 변경과 취소 후 늦은 분석·추론·저장을 무효화한다. `Operation.SkinRetouch`, codec/참조 검사, CPU/GPU 렌더, 원자적 result+mask 저장, 복제/삭제/outpaint guard를 포함한다. Apply는 history 한 번, Undo/Redo/load/export는 재추론 없이 같은 결과를 보존한다. 부분 저장 실패는 문서를 유지하고 이번 요청의 미참조 파일만 정리한다.
15. 회전 시 draft 유지, process 재생성 시 committed 문서만 복구, draft autosave 제외/export 차단, Cancel/Back/dismiss 시 무변경을 검증한다. 기능이 켜진 실제 배포 variant에서도 동작해야 한다.

### T4 — 수정 APK와 인계

16. T1은 피부 모델 평가와 독립적으로 완료·검증한다. T2→T3는 검증된 종류부터 연결하고 T4에서 통합한다. 한 거대 diff로 섞지 말고 단계별 변경/검증을 기록한다. 자동만 해결하거나 피부 버튼만 활성화해서 두 문제 모두 해결됐다고 선언하지 않는다.
17. 실제 기기에서 권한 있는 사진으로 자동 세 스타일→강도→적용/취소와 피부 각 지원 기능→강도→미리보기→적용/Undo/Redo→재열기/export를 확인한다. clean install은 별도 테스트 환경에서 수행하여 기존 데이터를 보존한다. 모델 미설치, offline, 서버 중단 후 복구, 잘못된 토큰, 기존 override가 남은 업데이트도 확인한다.
18. `work/RESULT.md`에 두 문제 각각의 실제 원인, 수정 파일, 단계별 상태, 실행 검사/결과, 품질·성능 근거, 기기/네트워크 조건, APK 경로·SHA-256·버전 및 설치/런타임 설정 절차를 기록한다. 공개 산출물에 개인 주소/토큰/사진을 포함하지 않는다. 실제 기기/서버/평가 자료가 없으면 미실행/제약과 남은 조치를 명시한다.

## Acceptance Criteria

- [ ] 설치 APK의 버전·설정 경로와 자동 연결 실패 원인을 재현 근거로 구분했다.
- [ ] 미설정/잘못된 설정/인증 실패/확인 중/서버 미준비를 안내하며 설정 수정과 같은 설정 재시도로 복구된다.
- [ ] 실제 기기에서 MonetGPT 세 스타일 결과, 미리보기/강도/적용/취소/Undo가 동작한다.
- [ ] 피부가 production provider로 실제 보정 결과를 만든다. 네 종류별 평가가 명시되며 전체 완료에는 네 기능 모두 통과했다.
- [ ] 일반 배포 APK에서 모델 준비와 오류 복구가 가능하고 debug 수동 설치에 의존하지 않는다.
- [ ] 피부 보호 영역/0 강도/종류 독립성/NoChange 및 품질·성능 gate를 통과했다.
- [ ] 피부 Apply/Undo/Redo/저장 후 재열기/export가 재추론 없이 동일 결과를 보존한다.
- [ ] 취소·역순 완료·설정/얼굴/문서 변경·저장 실패에서 상태/history/파일이 안전하다.
- [ ] 관련 검사와 저장소 검증을 통과했으며 미실행과 실제 기기 검증을 구분했다.
- [ ] 수정 APK와 설치·설정 안내가 있고 비밀값 노출·기존 데이터 손실·무관한 회귀가 없다.

## Validation

좁은 개발 검사 후 최종 전체 검증을 수행한다. 기존 placeholder의 “항상 미지원” 및 auto의 “실패 시 동작 없음” 테스트는 새 행동 계약으로 교체하되 실패 coverage를 삭제하지 않는다.

```bash
git status --short
./gradlew :core:ai:testDebugUnitTest --tests 'com.diffuse.core.ai.monet.*'
./gradlew :feature:editor:testDebugUnitTest --tests 'com.diffuse.feature.editor.tools.auto.*'
./gradlew :core:ai:testDebugUnitTest :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest
scripts/check.sh
./gradlew :app:assembleDebug :app:assembleRelease
git diff --check
```

필수 회귀 coverage:

- health 실패→같은 설정 재시도→Ready, 같은 설정 재저장, 최초 확인 중 tap, 401/503/timeout/잘못된 URL, 설정 A/B 역순 응답, clean/기존 override 경로.
- 실제 HTTP 취소, 늦은 auto 결과 차단, 정상 스타일/강도/history.
- 피부 입력 불변/보호 mask/NoChange/종류 분리, 모델 준비 실패·재시도, 합성/좌표/원자 저장/offline 재열기/export.
- 취소 무시 fake 역순 완료, 얼굴/문서 교체, 저장 중 취소, old finally/new busy, 이중 Apply, 회전/draft 복구.

실제 모델/기기 검증은 별도다. 기존 androidTest harness와 SR1 평가 절차를 따르고 전체 editor 메모리/지연을 별도 측정한다. 기기가 있으면 `./gradlew :core:ai:connectedDebugAndroidTest`를 실행하되 검출기 harness 통과를 피부 기능 완료로 해석하지 않는다.

서버 수정 시 `/home/jaeho/monetGPT`의 `scripts/check.sh`와 기존 실제 모델 smoke를 수행한다. GPU 점유·기존 서비스 영향을 확인하고 서버 README의 명령을 따른다. 토큰은 환경 설정에서 읽으며 로그에 노출하지 않는다. host smoke 성공과 휴대폰 end-to-end 성공을 따로 기록한다.

## Notes

- 명세: `specs/architecture.md`, `specs/auto_enhance.md`, `specs/skin_retouch.md`, `specs/skin_retouch_pipeline.md`, `specs/skin_retouch_validation.md`, `DESIGN.md`, `work/decisions.md` D080.
- 참고 테스트: `MonetClientTest`, `AutoToolTest`, `SkinRetouchSheetTest`, `SkinRetouchToolTest`, 얼굴 분석/검출기 테스트, `OperationOrderTest`, `EditDocumentJsonTest`와 저장소 테스트. Fake는 계약 테스트용이며 실제 품질 증거가 아니다.
- 이번 요청에 따라 자동 연결 복구 동작을 명세에 반영한다. 주소 없는 공개 APK 정책과 런타임 설정은 유지하며 운영 주체를 임의로 바꾸지 않는다.
- 피부 gate 미통과 시 T1 완료와 T2/T3 잔여를 분리 보고한다. 전체 완료 기준을 낮추거나 미완성 UI를 해결책으로 삼지 않는다.
