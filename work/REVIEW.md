# Review

## Status

CHANGES_REQUESTED

2026-10-13 Codex 독립 리뷰 — 최신 `work/tasks.md` / `work/RESULT.md`의 **보정 추천 워크플로우**.
15039 ADB 서버의 최신 기기 **SM-S948N / R5KL10GAH3B / Android 16**에 현재 소스로 빌드한 APK를 설치하고 실제 Gemini와 UI를 검증했다.
기본 추천·직접 지시·no_change·취소·부분 완료 Undo/Redo를 확인했다. **15:00 SAM 복구 후 얼굴 선택·국소 보정·Undo/Redo·재열기도 실기기 통과**했다. 구조화된 계획 검증의 아래 두 차단 결함은 별개로 남아 있다.
자동 검사 통과를 추천 품질 또는 모든 실기기 시나리오의 승인으로 확대하지 않는다.

## Blocking

### R1 [P1] 이전 goal의 Select가 존재하기만 하면 다른 객체의 활성 마스크를 사용함

Location:
`core/ai/src/main/kotlin/com/diffuse/core/ai/gemini/GeminiProposal.kt:269`
관련: `feature/editor/src/main/kotlin/com/diffuse/feature/editor/tools/direct/PlanRunner.kt:175`.

Problem:
`ownSelection`은 앞선 모든 단계에서 같은 goal의 Select가 있었는지만 검사한다.
`Select(face,g1) → Adjust(g1) → Select(flowers,g2) → Adjust(g2) → Adjust(g1)`을 정상 계획으로 수용한다.
그러나 runner의 활성 마스크는 마지막 Select의 꽃이며, 마지막 얼굴 조정도 꽃에 적용된다.
각 Select 뒤에 소비 단계가 있으므로 “사용되지 않는 Select” 검사도 이 경우를 막지 못한다.
임시 단위 테스트에서 거부를 기대한 assertion이 실제 null 반환으로 실패했다.

Impact:
검토한 목표와 실제 편집 대상이 달라진다. 얼굴·꽃 등 복수 객체 요청에서 잘못된 객체를 보정하거나
지우기/채우기를 수행할 수 있으며, 요구 5의 선택 대상과 후속 masked 조정 일치 계약을 위반한다.

Required fix:
선택을 소비하는 매 단계에서 **현재 활성 Select**가 해당 goal의 선택인지 검증하고, 불일치 계획은
전체 거부한다. 과거 goal로 돌아가려면 해당 객체를 다시 선택해야 한다. runner에 별도 실행 언어를 추가할 필요는 없다.

### R2 [P1] 배경 보존 조건에서 CutOut을 국소 작업으로 오판함

Location:
`core/ai/src/main/kotlin/com/diffuse/core/ai/gemini/GeminiProposal.kt:277`
관련: 같은 파일 `isLocal` / `consumesSelection`, `PlanRunner.kt`의 `cutOut`.

Problem:
`isLocal`이 `consumesSelection`을 그대로 허용하므로 CutOut도 국소 변경으로 분류된다.
`keep_background` 제약과 user/subject goal을 가진 `Select(face) → CutOut`이 검증을 통과한다.
CutOut은 선택 영역 밖을 삭제하므로 선언한 배경 보존과 기계적으로 확인 가능한 모순이다.
임시 단위 테스트에서 해당 계획의 거부 assertion도 실패했다.

Impact:
앱이 “배경은 그대로”를 보존 조건으로 표시하면서 실제 적용은 배경을 제거할 수 있다.
요구 4의 hard constraint 및 모순되는 요청 처리, 요구 5의 원자적 거부 계약을 충족하지 않는다.

Required fix:
선택을 필요로 하는지와 실제로 변경하는 영역을 구분한다. 최소한 `keep_background`에서 CutOut을
전체 거부하고, `subject_only`도 동일한 실제 영향 범위 기준으로 검토한다. 모순 요청은 확인 필요로
처리하거나 안전하게 거부하며 보존 조건을 조용히 무시하지 않는다.

## Tests Missing

### R3 활성 선택 교체 및 CutOut 보존 조건의 회귀 검사

- g1 선택/조정 → g2 선택/조정 → g1 조정의 전체 거부, g1을 다시 선택하면 허용.
- Adjust뿐 아니라 Erase/Fill/CutOut 등 선택 소비 단계의 활성 goal 일치.
- keep_background + CutOut 거부와, 보존 제약 없는 명시 CutOut 요청 허용.

기존 `GeminiProposalRefusalTest`의 “own goal selection” 검사는 g2가 아예 Select되지 않은 경우만
다룬다. `PlanRunnerProposalTest`는 순차적인 올바른 선택은 확인하지만 위 교차 goal 응답을 검출하지 못한다.

## Non-blocking

### N1 실제 이미지 품질 평가는 여전히 PARTIAL

이번 모델 응답을 실기기에서 확인한 것은 아래 1개 사진의 세 요청이다. 요구 7의 12종 fixture·각 3회·
6축 0/1/2 평가는 수행하지 않았다. 첫 추천은 겹쳐진 투구 장면을 인식했지만 빨간 색 대상에 흰 공을
포함했고, 직접 지시의 관찰 필드에는 사용자 요청도 섞였다. 구조 검증 통과와 시각적/의미적 품질을
구분해야 한다. 전체 품질 합격 또는 안정적인 거부율을 주장할 근거는 아직 없다.

## Validation Notes

### 2026-10-13 15:00–15:06 KST — SAM 정상 기동 후 실기기 재검증 완료

**이 항목이 아래 14시대 환경 차단 기록을 대체하는 최신 서버/기기 상태다.** 서버 기동과 정상 국소 보정 검증은
완료했으며, R1/R2 코드 결함은 수정되지 않았으므로 리뷰 전체는 CHANGES_REQUESTED를 유지한다.

#### 서버

- 14:59:48 GPU 사용량 839MiB로 메모리 확보를 확인하고 기존 `sam3-validation.service`를 재시작했다.
  이전 NLUT 종료 명령은 권한 오류였으므로, 이번 GPU 확보를 Codex가 해당 작업을 종료한 결과라고 주장하지 않는다.
- 실제 SAM3 모델이 **15:00:18 startup complete**. PID 3890569, 내부 18091, 기존 Caddy 8090 유지.
- `SM-S948N / R5KL10GAH3B`의 curl로 `http://44.233.156.159:8090/healthz`의
  `{"status":"ok"}` / **HTTP 200**을 기동 직후와 검증 종료 시 확인했다.
- 최종 두 unit 모두 active. GPU 전체 4,663MiB / 15,360MiB, SAM 프로세스 3,824MiB.
  모델과 프록시는 **켜둔다**. transient unit이며 부팅 자동 시작 설정을 추가한 것은 아니다.

#### 야구 샘플 — 선택 실패 처리 PASS, 선택 품질은 실패

- 기존 프로젝트 `9d263833-a7dc-4c59-9f87-1a456b66617e`의 검토된 계획
  `baseball pitcher 선택 → 선택 영역 Exposure=0.30`을 실제 적용했다.
- 연결 거부가 아니라 정상 SAM 호출 뒤 `baseball pitcher을(를) 찾지 못했어요`로 중단했다.
  `sam-live-before.json`과 `sam-live-applied.json`은 **전체 동일**하다. 후속 전역 대체/국소 조정 없음,
  기존 Vibrance/Contrast/Exposure 각 0.15만 유지되고 Undo는 비활성이다.
- 겹쳐진 작은 사진의 이 문구로 피사체를 찾는 품질은 통과하지 못했다. 안전한 실패 처리 통과와 구분한다.

#### 단일 인물 — 실제 선택·국소 보정·Undo/Redo·복원 PASS

- 기기의 기존 테스트용 NASA 인물 사진을 Photo Picker로 열어 새 프로젝트
  **`44040b3f-bd3e-4863-9eed-bacac48f13f3`**를 생성했다. 원본 **3200×4000**,
  SHA-256 `f026060fbbc4c9f4bda516f8c28905ed5ba648ff4fc519c08a29b7614b2e7c88`.
- 실제 요청: `Brighten only the face. Keep the background unchanged.`.
  Gemini가 사용자 목표 `얼굴을 밝게`, 보존 조건 `배경은 그대로 유지`,
  `face 선택 → 선택 영역 노출 15`를 제안했다. 적용 전 저장 operations는 빈 배열이었다.
- 15:03:26 업로드 **201**, 15:03:27 text segmentation **200**, 15:03:28 DELETE **204**.
  세션 ID `a991c3dd-a1d4-4c7f-bcc6-230884d2afae`를 해당 적용 시간의 서버 journal에서 확인했다.
- 적용 후 저장 문서: mask `036029de-95df-47af-8202-0245bf7308bd`와 이를 참조하는
  **Exposure=0.15** 연산. 전역 Adjust는 없다. 실제 저장 마스크를 가져와 **864×1080 RGBA** decode와
  얼굴 범위 포함을 육안 확인했다.
- UI Undo 한 번 → mask는 유지, Exposure 제거. UI Redo → 같은 maskId의 Exposure=0.15 복원.
  각 단계의 저장 JSON을 대조했다. 선택과 조정을 단계별 history로 유지하는 기존 계약과 일치한다.
- 적용/Redo 캔버스 `(0,263)-(1080,2095)` RGB 픽셀 차이는 **없음**.
  Undo/Redo의 변화 bounding box는 캔버스 상대 `(514,459)-(775,776)`으로 얼굴 영역에 국한됐다.
  그 밖의 배경/의상/손 영역의 스크린샷 픽셀은 동일했다. 원본 해상도 export 픽셀 검증은 아니다.
- force-stop → 재실행 → 새 프로젝트 재열기. 재열기/Redo 캔버스 RGB 픽셀 차이 **없음**.
  재시작 후 Undo 초기화는 저장 편집 결과 복원과 구분한다.
- 얼굴은 밝아졌으나 턱/목 마스크 경계의 밝기 단차가 보인다. 기능 흐름 통과를 자연스러운 경계 품질이나
  전체 사진 평가 합격으로 확대하지 않는다. 기존 마스크/렌더 구현을 이번 리뷰에서 수정하지 않았다.

증거: `/tmp/recommend-device-review/face-{before,applied,undo,redo}.json`,
`face-{applied,undo,redo,reopened}.png`, `face-mask.png`, `face-source.jpg`, `sam-face-steps.xml`,
`sam-live-{before,applied}.json`, `sam-live-planner.log`. 임시 경로이며 영구 보관을 보장하지 않는다.
최종 기기 화면은 새 인물 프로젝트의 편집 화면이고 얼굴 보정은 적용된 상태다.
기존 설치 APK를 사용했으며 제품 코드·테스트는 수정하지 않았다. 서버 기동/기기 검증만 수행했으므로
자동 검사를 반복 실행하지 않았고, 문서 변경 후 `git diff --check`는 통과했다.
실제 export·다양한 사진/문구 반복·정량 마스크 품질·PSS/성능 측정은 이번 범위에서 미실행이다.

### 2026-10-13 14:09–14:14 KST — SAM 서버 재기동 요청 후 확인

사용자 요청에 따라 실제 SAM3와 기존 8090 프록시를 기동했으나, **다른 계정의 GPU 작업과 메모리 충돌로 재검증은 아직 환경 차단**이다.

- 처음 확인한 GPU 사용량은 839MiB / 15,360MiB였고, 기존 SAM 임시 unit은 없었다.
- 기존 deploy 환경 파일을 사용해 `sam3-validation.service` (실제 모델, `127.0.0.1:18091`, max sessions 1)와
  `sam3-validation-public.service` (기존 Caddy 8090→18091)를 생성했다. 모델/제품 코드는 수정하지 않았다.
- 첫 SAM 프로세스는 14:11:46, 재시작 프로세스는 14:13:22에 CUDA OutOfMemory로 종료됐다.
  초기화 시 다른 작업이 약 12.94GiB를 사용했고 잔여 메모리는 4.75MiB였다.
- 충돌 작업은 `ubuntu` 계정의 `/mnt/efs/beomjin.ahn/pstbench-work/envs/l4/bin/python inference_finetuning_image.py`.
  content/style 순차 벤치마크로 PID가 바뀌며 재실행되고 있었다. 다른 사용자의 작업은 중단하지 않았다.
- 기기 `R5KL10GAH3B`에서 기존 테스트 프로젝트에 `Brighten only the pitcher. Keep the background unchanged.`로
  실제 Gemini 추천을 요청했다. `baseball pitcher 선택 → 선택 영역 노출 30`, 배경 보존 조건을 표시했다.
  `sam-before.json`과 `sam-reviewed.json`은 전체 동일해 적용 전 무변경을 확인했다.
- 서버 준비 전에는 적용하지 않았다. 기기에서 기존 주소 `/healthz`는 HTTP 502(모델 미기동)를 반환했다.
  정상 선택/국소 보정/Undo/Redo 성공으로 보고하지 않는다. 기기는 미적용 검토 화면에 있다.
- 현 상태: SAM unit failed, Caddy proxy active. GPU 자원이 확보되면 SAM unit restart 후 실제 적용·마스크·
  Undo/Redo·세션 해제를 검증해야 한다. 다른 계정 작업 중단 여부를 사용자에게 확인 중이다.
- 증거: `/tmp/recommend-device-review/sam-{before,reviewed}.json`, `sam-plan.{xml,png}`.
  기존 R1/R2 판정은 변경하지 않는다.

### 2026-10-13 14:57 KST — NLUT 중단 승인 후 OS 권한 차단

사용자는 NLUT 벤치마크 중단과 SAM 기동을 명시 승인했다. 그러나 현재 실행 계정 `jaeho`에서
`sudo -n kill -TERM`은 `a password is required`, 직접 `kill -TERM`은 `Operation not permitted`로 실패했다.
대상은 `ubuntu` 소유의 루프 PID 2576986과 그 시점의 inference 자식이며, 작업은 종료되지 않았다.
GPU 사용량 14,091MiB / 15,360MiB (NLUT 13,252MiB)를 확인했다.
재시도한 SAM 초기화는 메모리 충돌을 피하도록 중지했다. 프록시 설정은 유지했고 실제 기기 적용은 수행하지 않았다.
권한 있는 계정에서 해당 루프와 자식을 종료한 뒤 SAM을 재시작해야 한다. 추가 사용자 동의가 아닌 OS 권한 문제다.

### 저장소 검사와 재현

- tasks/RESULT/기존 REVIEW, DESIGN, D093/D094, `vibe_edit`/`ai_provider`/`prompt_input`/`editor_shell`,
  provider·decoder·validator·controller·ViewModel 연결·runner·UI와 관련 테스트/변경 diff를 대조했다.
  기존 미커밋 전체 디자인 변경은 최신 추천 기능의 신규 변경과 구분했다.
- `scripts/check.sh` → **exit 0** (lint/detekt/unit/golden/dependencyGuard, 증분·캐시 포함).
  최초 sandbox 실행은 Gradle 캐시 lock 쓰기 차단으로 실패했고 승인된 호스트 실행은 통과했다.
  로그 `/tmp/recommend-review-check.log` (quiet 성공이라 내용 없음).
- `./gradlew --offline --quiet :app:assembleDebug -Pdiffuse.localCreds` → **exit 0**.
  APK SHA-256 `503dd74e032eefab820e69dc3732bdff4b6d0aafb2e4d1dc9ef4335d45f65b2f`.
- 임시 `ReviewSelectionReproTest` → **2 tests / 2 assertion failures**. 위 R1/R2의 거부 누락을 각각 재현했다.
  제품 코드는 변경하지 않았고 임시 테스트는 제거했다. 재현 소스는 `/tmp/recommend-device-review/ReviewSelectionReproTest.kt`,
  로그는 `/tmp/recommend-review-repro.log`에 있다. 이 추가 재현의 실패와 기존 canonical 통과를 구분한다.
- 임시 테스트 제거 후 `./gradlew --offline --quiet :core:ai:testDebugUnitTest` → **exit 0**.
  로그 `/tmp/recommend-review-core-final.log`.
- `git diff --check` → 통과. 저장소의 최종 수정 대상은 `work/REVIEW.md`뿐이며 commit하지 않았다.

### 실제 기기·샘플·모델

- 연결: `adb -H 127.0.0.1 -P 15039 -s R5KL10GAH3B` (ADB 서버 포트이며 `adb connect` 대상 adbd 포트가 아님).
  모델 `SM-S948N`, Android 16, 화면 1080×2340. offline emulator는 사용하지 않았다.
- 기기에 앱이 없어서 새로 설치했다. 최초 90초 제한 설치는 전송 timeout, 재시도는 `Success`.
  신규 앱의 Gemini 설정에는 기존 저장소 로컬 키를 사용했다. 키 값은 보고서에 포함하지 않는다.
- 입력: 저장소 `test.jpg`, 400×426 야구 투구 합성 이미지.
  SHA-256 `2ca07806cdde0b83d0c1cb9c6cc2f82c6a2869baee7486553452811b687acd8a`.
  `/sdcard/Download/recommend-review-baseball.jpg`로 전송하고 Photo Picker로 열었다.
  개인 사진 대신 신규 프로젝트 **`9d263833-a7dc-4c59-9f87-1a456b66617e`**를 사용했다.
- 실제 모델 `gemini-2.5-flash`, 프롬프트는 현재 `PLAN_SYSTEM_INSTRUCTION`.
  소스 파일 `GeminiPlanCatalog.kt` SHA-256 `c344b844c4b035ca8adffa64c2a5f3539f1256f76e3b11b442d339bdb66a4d52`.
- 정상 응답 3건 + 응답 전 취소 요청 1건. 각 요청을 3회 반복한 품질 평가가 아니다.

### 실기기 결과

| 시나리오 | 직접 확인한 결과 |
| --- | --- |
| 빈 입력 `보정 추천 받기` | inline 진행 후 Ready. 관찰·기본 목표·색 역할·4단계와 예상 효과 표시, 내부 스크롤 가능 |
| 적용 전 무변경 | 저장 JSON의 operations는 빈 배열 유지, Undo 비활성. 최초 파일과 updatedAt은 재열기 때문에 달라 전체 파일 동일성으로 보고하지 않음 |
| 추천 적용·선택 실패 | 전역 Vibrance=0.15, Contrast=0.15 완료 후 SAM3 `/sessions` 연결 거부. `적용하지 못했어요` 표시, 후속 masked Contrast는 실행되지 않음 |
| 부분 완료 Undo/Redo | 실제 UI Undo로 Contrast 제거(Vibrance 유지), Redo로 두 연산 복원. 각 저장 JSON 확인 |
| 일반 예시 선택 | `조금 더 밝게`는 `사진 전체를 조금 더 밝게 해줘`만 입력, 자동 계획/적용 없음 |
| 직접 전송·적용 | overlay 후 사용자 목표/노출 15 검토. 적용으로 Exposure=0.15 추가, 앞선 두 연산 유지. planner 로그에는 추가 재계획 없음 |
| 입력 중 추천·취소 | `Keep the photo unchanged` 입력 상태에서 추천 가능. inline 취소 후 입력 유지, 취소 안내, 적용 비활성 |
| 재요청 no_change | 동일 입력을 명시 재요청해 NoChange(0), 이유 표시, 적용 비활성 확인 |
| IME | 입력창 터치 시 시트가 키보드 위로 이동하고 취소/적용 접근 가능. Back으로 IME만 숨겨 입력·시트 유지 |
| 글자 확대 | 시스템 font_scale 1.0→2.0에서 고정 취소/적용과 추천 진입 확인. 이후 1.0으로 복원. 큰 글자 전체 내용 스크롤은 미검증 |
| 시트 터치 차단 | 지시 시트 하단 `(540,2190)`, `(12,2190)`, `(1068,2190)` 터치 후 지시 유지, 뒤 도구 전환 없음 |
| 시스템 Back | 시트 닫기, 별도 AI 메뉴→Root, Root→Browse 확인. 이전 Back 리뷰의 독립 실기기 미확인 항목 해소 |
| 재시작·재열기 | force-stop/재실행 후 프로젝트의 앞선 Vibrance/Contrast 보정 복원, 미적용 제안 자동 복원/재실행 없음 |

- SAM3 설정 서버의 TCP 연결이 `ECONNREFUSED`여서 정상 segmentation 완료·국소 보정 성공·마스크 품질은
  **환경 차단**이다. Gemini 자체와 전역 조정은 성공했다. 서버 설정/서비스를 임의 변경하지 않았다.
- 중간에 입력창 진입을 확인하지 않은 좌표/ADB text 주입 뒤 흰 화면이 한 번 관찰됐다.
  crash buffer는 비어 있었고 force-stop 후 복구됐다. 이후 입력창 focus를 확인한 정상 IME 흐름에서는
  재현되지 않아 현재 변경의 원인으로 단정하지 않는다. 임시 `ime.png`/`app.log`에 관찰을 남겼다.
- 증거: `/tmp/recommend-device-review/`의 UI XML/스크린샷, `before.json`, `reviewing.json`,
  `applied-partial.json`, `undo.json`, `redo.json`, `direct-applied.json`, `planner.log`.
  임시 경로이며 영구 평가 자료로 간주하지 않는다.
- 검증 프로젝트/입력 샘플/설치 앱은 기기에 남아 있다. 최종 화면은 Browse, 글자 배율은 원래 1.0.
- 이번 미실행: 실제 export, TalkBack·음성, 네트워크 차단 전체 흐름, 12MP/PSS, 다양한 사진의 마스크/품질,
  needs_clarification/unsupported 실제 모델 응답, 높은 기존 Exposure에서 추가 밝게 요청의 실기기 반복.

### 이전 리뷰 상태와 미해결 인계

- 디자인 리뷰 R1/R2의 시트 터치 누출은 수정 코드·좌표 회귀 테스트·이번 실제 세 지점 터치로 해소 확인.
  이전 Back 수정도 이번 실기기에서 확인했다. 과거 15309 연결 거부는 이번에 요청한 15039 검증을 막지 않는다.
- 교체된 구형 추천의 6종/8문장 평가는 새 워크플로우의 완료 근거로 사용하지 않는다.
- 멀티샷 서버 ASGI 취소/큰 정수 metadata, 전체 사진 품질·offline·12MP/PSS,
  D084 피부 지원 0개 및 서버 품질/lifecycle, Jev 미구현, 음성/TalkBack 미완료는 유지한다.
- 아래 과거 리뷰의 상세 증거는 보존하지만 과거 Status는 이번 추천 기능의 판정이 아니다.

---

# 이전 리뷰 기록 — 현재 판정과 구분

# Review

## Status

CHANGES_REQUESTED

2026-10-12 Codex 독립 리뷰 — 현재 `work/tasks.md`의 **피부 보정·얼굴 감지 메뉴 분기 제거**.
최신 APK를 AWS `127.0.0.1:15039`의 실제 SM-S948U에 설치해 검증했다.
삭제 범위·고정 메뉴·저장 보정 호환성에서는 차단 결함을 발견하지 못했다.
다만 요구 8의 시스템 Back → Root 복귀가 실제 앱에서 동작하지 않아 R1 수정을 요청한다.
**R1의 충돌 코드는 HEAD에도 존재한다. 이번 삭제 diff가 만든 회귀라는 판정은 아니며,
현재 작업의 명시적 메뉴 탐색 검증 조건을 충족하지 못한 것으로 구분한다.**

## Blocking

### R1 시스템 Back이 AI 메뉴를 닫지 않고 편집기를 나감

Location:
`app/src/main/kotlin/com/diffuse/navigation/DiffuseNavHost.kt:145`
관련: `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorRoute.kt:78`

Problem:
시트 없이 AI 메뉴가 열린 상태에서 시스템 Back(`adb shell input keyevent 4`)을 한 번 누르면
Root 메뉴 대신 프로젝트 목록으로 이동한다. 비인물 및 저장 보정 fixture에서 두 번 재현했다.
`EditorRoute`에는 메뉴 복귀 BackHandler가 있지만, 뒤에 구성되는 `EditorWithExport`의
항상 활성인 BackHandler가 메뉴 레벨을 모르는 `backAction`으로 `LeaveEditor`를 실행한다.
AI 행의 명시적 ‘뒤로’ 버튼은 Root로 정상 복귀한다.

Impact:
사용자가 AI 메뉴만 닫으려다 편집 화면을 이탈한다.
`work/tasks.md` 요구 8 및 `specs/tool_groups.md` §4의 시스템 Back 계약 불충족.
저장 데이터 손실은 이번 검증에서 발견하지 않았다.

Required fix:
실제 앱의 Back 우선순위를 조정해 export/도구 시트의 기존 취소·확인 동작을 보존하면서,
시트가 없고 AI 메뉴가 열린 경우 첫 Back은 Root, 다음 Back은 편집기 이탈이 되게 한다.
별도 메뉴 구현을 만들지 말고 현재 상태 소유권과 navigation 연결에서 해결한다.

재현 증거: `/tmp/removal-device-review/back-repro-ai.xml` → `back-repro-after.xml`.
명시적 버튼 성공 대조: `explicit-back-root.xml`.

## Tests Missing

### R2 실제 navigation wrapper를 포함한 Back 통합 회귀 검사

도구 목록/단독 Compose 테스트만으로는 위 상위 BackHandler 충돌을 잡지 못한다.
`EditorWithExport`와 `EditorRoute`를 함께 사용하는 조건에서 AI → 시스템 Back → Root 유지,
다음 Back → Browse, 열린 도구/내보내기 시트의 취소 우선순위를 검증한다.
이번 변경의 다른 자동 검사에서는 실패를 발견하지 못했다.

## Non-blocking

None. 이전 작업의 미완료 범위는 아래에 별도로 보존한다.

## Validation Notes

### 직접 실행한 검사 및 코드 검토

- tasks/RESULT/기존 REVIEW, DESIGN, tool_groups·피부 보정 관련 명세와 D092,
  실제 diff, EditorRoute/ViewModel/Tool/EditorAi, DI/manifest/설정, 메뉴·진입·영속성 테스트를 대조했다.
- `./gradlew --offline --quiet :app:assembleDebug` → **exit 0**.
- `scripts/check.sh` → **exit 0**. 증분·캐시 실행 포함. SDK XML 버전 경고는 있었으나 검사 실패는 없었다.
- 최초 sandbox 실행은 `~/.gradle` lock 쓰기 제한으로 실패했다. 권한을 확보한 재실행이 위 성공 결과다.
- `git diff --check` → **exit 0**.
- 모델/JSON/renderer 및 core/data production 저장 경로에는 diff가 없다.
  새 `EditorEntryTest`와 `SkinRetouchPersistenceTest`의 보정 연산·파일·픽셀 보존 assertion을 검토했다.
- PortraitDetector/메뉴 프로필/controller/EditorAi·DI 연결과 manifest 얼굴 모델 metadata 제거를 확인했다.
  얼굴 분석 및 retouch 네트워크 부재는 실행 경로 검토에 근거하며, 패킷 캡처로 0건을 측정한 것은 아니다.

### 기기와 fixture

- ADB `127.0.0.1:15039`, serial **R3CYA0AVYAP**, **SM-S948U**, Android **16**, 1440×3120.
  이전 REVIEW의 다른 serial/해상도 결과와 구별한다.
- 최신 debug APK `install -r` **Success**.
  SHA-256: `23755ad9d802a0e73e0d5c048d9d9ef693afd62ec00759738bff3eb2ffefd4c8`.
- 공개 NASA 초상 `jsc2011e017045.jpg`를 614×768로 축소한 인물 fixture,
  저장소 `test/kodim23.png` 앵무새 fixture를 사용했다. 개인 사진은 편집/업로드하지 않았다.
- 검증용 프로젝트: `review-removal-portrait`, `review-removal-parrots`, `review-removal-legacy`.
  앱을 정지하고 debug run-as로 파일과 Room 목록을 준비했다. 기존 DB/WAL/SHM을 먼저
  `/tmp/removal-device-review/index-original.db*`에 백업하고 기존 행은 유지했다.
  따라서 Photo Picker 신규 가져오기 경로를 검증한 것으로 보고하지 않는다.
- legacy는 저장 JSON 계약으로 만든 **합성 호환성 fixture**다. 인물 사진 위 101×101 녹색 표시를
  baked result에 넣고 전체 support mask와 SkinRetouch 연산을 저장했다.
  과거 서버가 만든 실제 보정 프로젝트나 피부 품질의 검증으로 간주하지 않는다.

### 실기기 확인 결과

| 항목 | 결과 |
| --- | --- |
| 인물/비인물 Root | 둘 다 라이트·색상·혼합·자르기·디테일·AI, 피부 보정 없음 |
| 인물/비인물 AI | 가로 스크롤로 뒤로·선택·지우기·채우기·확대·스타일·자동·멀티샷·지시 확인 |
| 디테일 | 인물에서 시트 진입/취소, legacy에서 선명도 변경·적용 후 Root 복귀 |
| 자동 | AI에서 탭 → 서버 미설정 안내 및 설정 시트. 서버 호출/보정 결과 생성은 미실행 |
| 서버 설정 | 상단부터 끝까지 SAM 주소/토큰, Gemini 키, 자동 보정 주소/토큰 확인. 피부 보정 필드 없음. 빈 설정 저장 disabled |
| disabled-but-tappable | 선택 없는 지우기 탭 시 ‘먼저 영역을 선택해주세요’ 안내 |
| AI 행 ‘뒤로’ | Root 복귀 성공 |
| 시스템 Back | **실패 — R1** |
| 저장 SkinRetouch 열기/최초 PNG | 성공. 실제 PNG 614×768 RGB를 기대 baked result와 비교해 모든 픽셀 일치 |
| 추가 편집·Undo/Redo | 선명도 0.73212177 적용 후 0.3714447로 변경. 안정화 후 Undo=0.73212177, Redo=0.3714447 저장 JSON 확인 |
| 프로세스 재시작 | force-stop/재실행/재열기 후 최종 operations가 Redo 저장 상태와 정확히 일치 |
| 참조 파일 | result/support 존재 및 SkinRetouch 설정·참조 보존 확인 |

- 실제 출력: `/sdcard/Pictures/Diffuse/IMG_20261012_194440.png` (635,855 bytes),
  추가 선명도 0.73212177 적용 후 재시작·재열기에서 `IMG_20261012_194616.png`도 저장/회수/디코드했다.
  두 번째 출력의 조정 후 모든 픽셀을 독립 renderer와 비교한 것은 아니다.
- 최초 빠른 Undo/Redo 직후 JSON 읽기는 비동기 저장보다 빨라 이전 상태를 관찰했다.
  해당 `legacy-undo.json`/`legacy-redo.json`은 통과 근거에서 제외하고 UI dump 후 저장 안정화된
  `legacy-{undo,redo}-settled.json`과 최종 `legacy-final-reopen.json`으로 검증했다.
- 증거: `/tmp/removal-device-review/`의 portrait/parrots 메뉴 XML, `portrait-root.png`,
  `parrots-confirmed.png`, `legacy-open.png`, settings XML, `export-0.png`, `export-1.png`,
  저장 JSON 및 `verification.txt`. 임시 파일이며 영구 평가 자료로 간주하지 않는다.
- 검증용 프로젝트 3개와 PNG 2개는 기기에 남겼다. 마지막 화면은 legacy 편집기 Root다.
  서버/키/네트워크 설정은 변경하지 않았다. 저장소는 **work/REVIEW.md만 작성**했으며 구현 수정·commit은 없다.

### 미실행 및 이전 미해결 인계

- TalkBack 실제 읽기, 큰 글자/회전, 강제 네트워크 차단, 12MP/PSS, 실제 과거 서버 보정 사진,
  자동 보정 API 실행은 이번 검증에 포함하지 않는다.
- 지시 추천 이전 R1(Loading 취소 표시)/R2(IME 버튼 가림)는 RESULT의 수정 보고와 별개로
  수정 후 독립 실기기 재확인 미완료. 사진 6종·8문장·음성·오류 경로 미완료도 유지한다.
- 서버 멀티샷 ASGI 취소 후 후속 추출/큰 정수 metadata 500, Jev, 전체 품질·offline·성능 검증은 별도 미완료다.
- 피부 보정 D084 지원 종류 0개 및 서버 품질/lifecycle 평가 미완료는 그대로다.
  제거된 앱 피부 보정 draft 검증은 기능 제거로 비적용이며 서버 문제 해소를 뜻하지 않는다.
- 아래 이전 리뷰는 과거 증거/미해결 근거 보존용이다. 그 Status를 이번 판정으로 읽지 않는다.

---

# 이전 리뷰 기록 — 지시 추천 및 이전 작업

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
