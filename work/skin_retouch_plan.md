# Task

## Goal

선택한 얼굴의 잡티·여드름, 유분광, 다크서클, 면도자국을 독립적으로 완화하고 미리보기·적용·취소·실행 취소·저장·내보내기가 가능한 피부 보정 도구를 구현한다.

## Background

Evoto는 사용 경험의 참고이며 내부 알고리즘이나 동등 품질이 확인됐다는 뜻은 아니다. 중복 요청된 잡티/여드름은 하나의 항목으로 묶는다.

사용자 지시: **사진 업로드 없는 기기 내 처리 우선. 모델을 서버에서 실행하는 편이 나으면 별도 보정 서버도 가능.** Gemini 등 외부 범용 생성형 편집 API로 구현하거나 로컬 실패 시 자동 업로드하지 않는다.

T79의 PortraitDetector와 인물 메뉴는 구현돼 있지만 메뉴용 PortraitResult만 반환하고 피부 마스크는 제공하지 않는다. 기존 work/RESULT.md와 work/REVIEW.md는 이전 작업 자료이며 새 기능의 완료 증거가 아니다.

후보 우선순위: **MI-GAN으로 잡티·여드름의 국소 복원을 먼저 검증**한다. MI-GAN은 결함 검출기가 아니므로 자동 검출은 별도로 검증한다. StyleRetoucher는 피부 보정에 특화된 비교/설계 참고 후보지만 공식 코드·가중치·사용 조건을 확보하기 전에는 구현 의존성이 아니다. 유분광/다크서클은 질감 보존형 국소 색조 보정, 면도자국은 별도 검출/보정 경로를 평가한다. 어느 후보도 아직 production 채택 또는 품질 통과 상태가 아니다.

계약: [기능/UI](../specs/skin_retouch.md), [엔진/좌표/저장](../specs/skin_retouch_pipeline.md), [품질/검증](../specs/skin_retouch_validation.md). DESIGN.md와 architecture.md, work/decisions.md의 T70/T79/D080도 따른다.

## Scope

### Modify

- core/ai: 얼굴 분석, 보정 provider와 구현, DI, fake 및 테스트.
- core/imaging의 model/render: SkinRetouch, JSON/참조 검증, 합성, CPU/GPU 일치, outpaint guard.
- core/data: 결과 파일 저장, load/duplicate/delete, 취소 파일 정리.
- feature/editor: Tool.kt, EditorAi.kt, EditorViewModel.kt, route/screen, tools/retouch, 문자열과 관련 테스트/goldens.
- scripts/retouch의 재현 가능한 평가 도구, work/retouch_evaluation.md의 엔진 평가 기록.
- SR1로 정당화된 런타임 의존성만 추가한다. 서버 선택 시 확인된 실제 서버 저장소에 별도 구현 작업을 인계한다.

### Do not modify

- 기존 SAM 3 선택, Gemini 지우기/채우기/확대, 자동/지시 도구의 의미.
- 얼굴형/미백/주름/치아/눈/몸 피부, 일괄 처리, 수동 복구 브러시.
- PortraitDetector 메뉴 계약, 원본 사진 파일, 기존 조정 값.
- 새 core 모듈, 무관한 리팩터링/빌드 스택 변경, Ralph/저장소 운영 지침.
- 평가 없는 대형 모델 APK 번들링. 기존 15MB/no-bundled-model 원칙과 충돌하면 측정과 별도 결정 기록이 필요하다.

## Requirements

SR1부터 순서대로 진행한다. 각 SR은 하나의 구현·검증·리뷰 단위이며 전체를 한 diff에 합치지 않는다. SR1 완료는 전체 기능 완료가 아니다.

### SR1: 얼굴 분석과 엔진 선정

1. 기존 ML Kit를 재사용하는 별도 FaceRegionAnalyzer와 테스트를 추가한다. 얼굴 bounds, 선택 얼굴의 부위별 지원 여부를 제공한다. 메뉴 enum을 피부 마스크로 쓰지 않는다.
2. SkinRetouchProvider와 fake를 정의한다. 얼굴 ROI/허용 영역/한 기능에서 기준 강도 후보와 변경 support를 반환한다. 허용 피부 영역과 실제 결함 mask는 다르며 MI-GAN에는 별도 검출된 결함 mask를 전달한다. 후보의 feather/합성은 provider에서 한 번만 수행하고 renderer가 다시 곱하지 않는다.
3. SR1-A에서 수동 정답 결함 mask로 MI-GAN 복원 자체를 평가하고, SR1-B에서 자동 잡티 검출을 연결해 전체 경로를 평가한다. FFHQ 256과 Places2 512를 같은 결함/원본 패치 범위로 비교한다. 얼굴 전체 축소보다 결함 주변 패치를 우선 검증하고 mask 극성/empty mask/좌표 복원/입력 불변을 테스트한다. SR1-C에서 유분광/다크서클/면도자국의 별도 경로와 네 기능 조합을 평가한다. 상세 단계는 validation §1.1을 따른다.
4. 동일 평가 이미지로 품질/지연/메모리를 측정한다. 알고리즘/모델 버전, 가중치 출처·해시·라이선스, 런타임, 기기/서버 사양, 재현 명령을 work/retouch_evaluation.md에 남긴다.
5. 로컬이 기준을 통과하면 채택한다. 실패하면 해당 실패와 서버 후보의 동일 평가를 근거로 별도 서버를 선택할 수 있다. 같은 checkpoint를 서버에서 실행하는 것만으로 품질이 좋아진다고 가정하지 않는다. 일부만 통과하면 미지원 기능과 미완료 상태를 명시한다. MI-GAN ONNX의 Android 실행 호환성/전후처리 포함 지연/런타임 크기를 측정한다. StyleRetoucher의 공개 artifact 미확보는 MI-GAN 실험을 막지 않으며 재현/재학습은 별도 작업으로 제안한다.
6. 서버가 필요하면 실제 저장소/API를 확인하고 wire schema/제한/인증/취소를 확정한다. 스펙의 논리 계약을 배포 endpoint로 가정하지 않는다. 모델/가중치/기기/서버가 없으면 필요한 의존성과 미검증 항목을 구체적으로 기록한다.
7. 산출물은 analyzer/provider 테스트, 실행 가능한 평가 도구, 평가 기록과 선정 결과다. SR1-A의 수동 mask는 평가 전용이며 제품 브러시를 추가하지 않는다. SR1-B 잡티 경로가 통과하면 SR2/SR3의 잡티 수직 구현은 진행할 수 있다. 나머지 기능은 구현/검증이 끝난 항목만 활성화하고 전체 완료는 SR1-C와 SR4 통과 후다. 메뉴 노출은 SR3까지 하지 않는다.

### SR2: 비파괴 저장과 렌더링

8. Operation.SkinRetouch를 추가한다. 시트 적용 한 번은 history entry 하나이고 activeMaskId는 유지한다.
9. 삽입 위치의 정확한 prefix를 crop 없이 렌더링해 입력으로 쓴다. T70의 중복 조정 방지와 canonical 좌표를 만족한다. 현재 generativeInput()은 Crop을 유지하므로 그대로 호출하지 않는다.
10. 결과/mask를 working canvas 좌표에 저장하고 입력 alpha를 보존한다. preview/full/GPU fallback 합성이 일치해야 한다.
11. JSON/참조 검증, 저장 실패, 복제/삭제, undo/redo 파일 수명을 처리한다. 새 보정 파일이 있으면 기존 mask-bearing op처럼 outpaint를 거부한다.

### SR3: 시트와 생명주기

12. 일반 메뉴 AI 하위와 인물 메뉴 루트 첫 위치에 피부 보정을 한 번씩 노출한다.
13. 한 얼굴 선택, 네 0..100 slider(기본 0), 명시적 미리보기, 취소/적용을 제공한다.
14. slider 변경은 준비된 후보를 로컬 합성한다. 시트 진입/drag는 서버 요청을 만들지 않는다. 필요한 새 후보만 미리보기에서 준비한다.
15. 분석/얼굴 없음/모델 미준비/처리/결과/실패/저장 실패를 구분한다. busy 중 취소 가능, 오류 시 값 유지가 필수다.
16. 문서 revision/session/얼굴/provider 설정/request generation으로 stale 결과를 차단한다. 취소 무시 provider와 저장 중 취소도 commit할 수 없어야 한다.
17. draft는 autosave 제외, 회전은 ViewModel draft 유지, process death는 마지막 저장 문서 복구와 자동 재추론 금지다.
18. 서버 사용은 설정/시트에서 식별 가능해야 한다. 로컬 이미지 전송 0건, 서버 선택 얼굴 ROI만 전송, 사진/credential 로그 제외를 검증한다.

### SR4: 통합 검증

19. 네 기능/조합, 다중 얼굴, crop/회전/outpaint/조정/기존 생성 결과, 취소 경합과 저장 복구를 검증한다.
20. work/RESULT.md에 실제 실행 검사와 품질 평가 링크/제한을 기록한다. 이미지 원본을 무단 커밋하지 않는다.
21. 모델 품질을 fake/golden 성공으로 대체하지 않는다. 필수 기능 기준 미달이면 해당 부분은 미완료다.

## Acceptance Criteria

- [ ] MI-GAN 수동 mask 복원 평가와 자동 잡티 검출 포함 평가가 분리돼 있고, 네 기능별 실행 위치/엔진 선정 근거가 있다.
- [ ] MI-GAN mask 극성, 작은 결함/분산 결함 패치, 입력 불변, 보호 영역, 합성 중복 방지를 검증했다.
- [ ] StyleRetoucher를 네 기능 제어 모델로 가정하지 않으며 artifact 미확보 상태를 명시한다.
- [ ] 네 기능 독립 조절, 대상 없는 얼굴의 불필요한 변화 없음.
- [ ] 정체성, 피부톤/질감, 눈/입/눈썹, 타인과 배경 보존.
- [ ] 전체 0은 픽셀/history no-op, Apply 한 번은 Undo 한 번으로 복구.
- [ ] crop/회전/export 좌표와 기존 조정 순서가 맞다.
- [ ] 실패/취소/늦은 응답/저장 실패가 문서와 선택을 변경하지 않는다.
- [ ] 저장/재실행/복제/export는 모델 재실행 없이 결과를 재사용한다.
- [ ] 로컬 업로드 0건, 서버로 자동 전환 없음.
- [ ] 관련 behavioral tests/goldens/저장소 검증 통과 또는 환경 제한을 구체적으로 기록한다.
- [ ] 네 기능 실제 품질 기준 통과, 무관한 동작 변경 없음.

## Validation

개발 중 해당 SR의 모듈만 선택하고 최종 저장소 검증을 실행한다.

```bash
./gradlew :core:ai:testDebugUnitTest
./gradlew :core:imaging:testDebugUnitTest :core:data:testDebugUnitTest
./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug
scripts/check.sh
```

실제 모델 평가/기기 성능은 check.sh에 포함되지 않는다. SR1 기록의 실제 명령으로 별도 실행한다. 서버 사용 시 실제 서버 저장소의 테스트 결과도 인계한다.

## Notes

현재 요청은 구현 계약과 스펙 작성이다. 이 단계에서 앱 구현, 모델 다운로드, 사진 업로드, 서버 배포를 수행하지 않는다. 이후 Claude Code가 SR 단위로 구현하고 GPT/Codex가 리뷰한다.
