# Task

## Goal

Tinny-Robot/acne를 ONNX로 변환하고 Android에서 실행 가능한 여드름 검출기로 포팅하여, 얼굴 ROI의 검출 box와 복원용 후보 defect mask를 검증한다. 이번 범위는 검출기 포팅과 SR1-B 평가 경로이며 피부 보정 제품 기능 전체가 아니다.

## Background

사용자 요청: https://huggingface.co/Tinny-Robot/acne 의 pimple detector를 붙이기 위한 구현 작업서.

2026-09-09 저장소 및 공개 자료 확인:

- 모델은 Ultralytics YOLOv8 object detection 체크포인트다. 공개 파일 목록에는 약 52 MB의 `acne.pt`가 있고 ONNX/TFLite는 없다. 피부 segmentation 또는 복원 모델로 취급하지 않는다.
- 모델 카드에는 어두운 피부톤 중심 학습 설명과 Apache-2.0 표기가 있다. 실제 클래스 이름/수, 학습 입력 크기, tensor shape, 체크포인트 호환 버전과 정확도는 아직 검증하지 않았다. HF의 자동 Transformers 예제와 카드의 `detect_acne` 예제를 실행 계약으로 복사하지 않는다.
- `core:ai`에 `SkinRetouchProvider`, `FaceRegionAnalyzer`, 실제 `MlKitFaceRegionAnalyzer`가 있다. SkinRetouchProvider production binding, allowed-skin mask builder, 자동 잡티 검출기는 없다. `SkinRetouchSheet`는 준비 중 안내와 비활성 항목을 표시한다.
- `scripts/retouch/{evaluate,restore,patches,engines}.py`는 수동 defect mask로 MI-GAN/OpenCV 복원을 비교한다. 기존 평가는 자동 검출 품질의 증거가 아니다.
- Android ONNX 런타임은 아직 없고 architecture §8은 release arm64 APK <1000 MB/no-bundled-model을 요구한다. D080은 사진 업로드 없는 로컬 우선이다.

포팅 경로는 **PyTorch → 고정 입력 FP32 ONNX → Android ONNX Runtime CPU → ROI box → 허용 영역으로 제한한 후보 mask**로 한다. MI-GAN 평가도 ONNX를 사용하므로 먼저 이 경로로 정확도와 모바일 비용을 확인한다. production 런타임 채택은 실측 전 확정하지 않는다.

## Scope

### Modify

- `scripts/retouch/`: 체크포인트 inspect/export, Python ONNX 검출 기준 구현, 자동 mask 평가 CLI, weight-free tests, README.
- `core/ai/src/main/kotlin/com/diffuse/core/ai/`: 최소 BlemishDetector 계약과 모델 비종속 전후처리. 세부 구현은 retouch 패키지를 둘 수 있다.
- `core/ai/src/debug/`: ONNX adapter와 개발용 로컬 모델 로딩. debug 전용 의존성으로 평가한다.
- `core/ai/src/test/`, 필요 시 `src/androidTest/`: 전후처리/취소/자원 테스트 및 실제 기기 smoke test.
- `core/ai/build.gradle.kts`, `gradle/libs.versions.toml`: 버전 고정한 `com.microsoft.onnxruntime:onnxruntime-android` debug 의존성, 필요한 테스트 설정.
- `work/retouch_evaluation.md`, `work/RESULT.md`: 검출기 결과를 별도 절로 기록하고 기존 SR1-A 결과와 다른 작업 인계 내용을 보존한다.

### Do not modify

- PortraitDetector/menuProfile, 도구 메뉴, 피부 보정 시트/슬라이더와 Apply 활성화.
- Operation/EditDocument/저장/history/render/export 및 기존 SkinRetouchProvider 계약.
- 실제 Android MI-GAN adapter, production 얼굴 허용 mask builder, 다른 피부 보정 세 기능.
- 재학습, 다른 모델 교체, 양자화, GPU/NNAPI 최적화, 서버 API/자동 업로드.
- production 모델 다운로드 UI/서비스, 모델 APK 번들링, release ONNX 의존성 추가.
- 무관한 리팩터링, Ralph 설정, 기존 `work/REVIEW.md` 및 미커밋 파일 덮어쓰기.

## Requirements

1. **실제 모델 계약부터 확보한다.** HF revision 전체 hash, 원본 SHA-256/크기, `model.names`, task/architecture, checkpoint metadata, 실행 가능한 Python/PyTorch/Ultralytics 버전을 기록한다. 별도 export 환경의 버전을 고정하고 모델 파일은 git 밖에 둔다. 오래된 Ultralytics pickle 경로로 로딩이 실패하면 호환 버전을 조사하며 다른 가중치로 대체하지 않는다. 카드의 라이선스 표기와 확보한 라이선스 파일/상위 코드 사용 조건을 구분해 출처와 미확인 사항을 남긴다.

2. **재현 가능한 ONNX export를 만든다.** 최초 후보는 batch=1, 정적 640×640, FP32, embedded NMS 없음, opset 17이다. 평가 시작 설정이며 학습 설정으로 주장하지 않는다. 해당 checkpoint/exporter/runtime에서 검증한 값으로 고정하고 변경 이유를 기록한다. `YOLO(local_checkpoint).export(...)`를 쓰되 설치 버전의 지원 인자를 확인한다. ONNX checker와 CPU session load를 실행하고 입출력 이름/shape/dtype, 좌표 의미, 클래스 순서, 전후처리, export 옵션, ONNX SHA-256을 manifest로 저장한다. 모델/manifest 불일치는 오류다.

3. **Python 기준 출력을 확보한다.** 같은 ROI와 전처리 tensor로 PyTorch와 ONNX를 비교하고 box/score/class 및 중간 fixture를 기록한다. 얼굴 사진은 사용 권한과 외부 저장 경로를 지키고 저장소 fixture는 합성 또는 재배포 가능한 자료만 사용한다. raw tensor 수치 허용 오차와 NMS 이후 matching 허용 오차를 별도로 고정한다. 초기 NMS 이후 비교 기준은 동일 class/검출 수, 대응 box 오차 ≤1 model-input px, score 차이 ≤0.001이다. threshold 부근 차이는 raw output과 원인을 제시하며 임의로 오차만 늘리지 않는다.

4. **검출과 복원을 분리한다.** 권장 계약은 `suspend detect(faceRoi): Result<BlemishDetections>`이며 입력은 방향 정규화된 immutable ARGB_8888 한 얼굴 ROI다. 출력은 ROI pixel 좌표의 유효한 xyxy box, confidence, 원본 class ID, detectorVersion이다. 검출 0개는 정상 성공이고 모델 없음/손상/호환 불가/추론 실패와 구분한다. Bitmap/Rect 가변 객체와 입력 buffer의 소유권을 명시한다. detector에서 Operation/renderer/editor 상태를 참조하지 않는다.

5. **Python/Android 전처리를 일치시킨다.** RGB, FP32/255, NCHW, aspect 보존 letterbox, interpolation, padding 값/위치/홀수 반올림을 manifest와 fixture로 고정한다. OpenCV BGR 또는 ARGB를 그대로 넣지 않는다. 스케일/실제 padding/원래 ROI 크기를 보관한다. 전체 사진을 640으로 축소하는 대신 선택 얼굴 ROI를 받으며 얼굴 검출/EXIF/crop/display 변환은 호출자 책임이다. 투명 픽셀의 전처리 배경도 고정하고 원본 alpha는 변경하지 않는다.

6. **실제 출력에 맞는 decoder/NMS를 구현한다.** 일반적인 `[1,4+nc,N]` shape나 단일 acne class를 확인 없이 가정하지 않는다. 출력에 없는 objectness를 곱하거나 sigmoid/NMS를 중복 적용하지 않는다. 실제 여드름 class ID를 확인해 allowlist로 고정하고 미확인 class를 모두 여드름으로 간주하지 않는다. confidence/NMS IoU/max detections는 명시적 설정이며 평가 시작값은 0.25/0.45/100이다. class-aware NMS와 동점 정렬을 결정적으로 구현한다. letterbox 역변환 후 ROI clip, padding-only/퇴화 box 제거, NaN/Inf/잘못된 shape 처리를 검증한다. 검출 상한에 걸리면 평가 기록에 표시한다.

7. **box→mask는 별도 순수 변환으로 둔다.** 입력은 검출 box와 같은 ROI 크기의 binary allowedMask, 출력은 ALPHA_8 또는 Python uint8의 0/255 후보 defect mask이며 255=변경이다. 초기 기준은 box 내부 rasterization, 추가 dilation 없음이다. 경계 rasterization 규칙을 고정하고 최종 결과를 `allowedMask ∩ 원본 alpha>0`로 제한한다. 허용 mask 누락을 얼굴 전체 허용으로 대체하지 않는다. bbox는 정밀 segmentation이 아니므로 정상 피부 포함 면적을 평가한다. 타원/축소/확장은 비교 근거 없이 넣지 않는다. empty mask는 정상이며 복원 추론 0회다. MI-GAN 255=보존/0=복원 반전은 기존 restore.py 경계에서 한 번만 수행한다.

8. **실제 Android adapter를 평가용으로 격리한다.** 앱 private files에 명시적으로 설치한 ONNX+manifest를 읽으며 개발용 설치/실행 명령을 README에 제공한다. 모델 없이도 빌드/일반 테스트/기존 앱 실행이 가능해야 한다. 모델 주입은 자동 다운로드나 assets 복사에 의존하지 않는다. main DI의 SkinRetouchProvider를 바인딩하지 않는다. 실제 adapter를 호출하는 instrumentation test 또는 개발용 harness를 제공하여 UI 작업 없이 기기에서 ROI→box/mask를 확인할 수 있게 한다. production 배포는 후속 작업이다.

9. **취소·동시 실행·native 자원 수명을 명시한다.** 파일 처리는 injected io, 픽셀/추론은 injected default에서 수행한다. session을 재사용하고 종료와 inference 경합을 막는다. 입력 tensor/result/session options/소유 session을 성공·실패 시 정리하며 공유 OrtEnvironment를 요청별로 닫지 않는다. CancellationException을 전파한다. native run이 즉시 중단되지 않아도 완료 후 취소를 재확인하여 결과를 반환하지 않는다. 입력 buffer 재사용 경합과 실행 중 session close를 방지한다. 모델 준비 실패는 AppError로 반환하며 사진/원시 tensor를 로그에 남기지 않는다.

10. **기존 평가에 자동 mask 경로를 연결한다.** 수동 defect mask는 정답으로 유지하며 자동 모드의 검출 입력으로 쓰지 않는다. evaluate.py를 최소 확장하거나 인접 CLI를 두어 detector→mask→기존 RestoreRun을 실행한다. 같은 ROI/allowedMask/복원 엔진/patch 설정에서 수동·자동 결과를 별도 행으로 남긴다. MI-GAN 가중치 없이 검출만 평가하는 모드도 제공한다. 평가용 allowedMask를 쓰는 실험을 production 보호 영역 완성으로 보고하지 않는다.

11. **검출 품질과 비용을 분리 보고한다.** validation spec의 권한 있는 24장 세트에서 여드름 양성/음성, 점·주근깨/메이크업/수염/피부톤/작은 병변별 결과를 기록한다. 결함 단위 precision/recall은 1:1 class matching 및 IoU 0.5 기준이며 후보 mask의 정상 피부 포함 면적/보호 영역 침범도 별도 기록한다. 임계값 조정 자료와 최종 평가 자료를 구분한다. Android 모델 로드/전처리/추론/NMS·mask/전체 p50·p95, peak memory, 기기/ABI/반복 횟수, 모델 크기와 runtime APK 증분을 측정한다. desktop 속도를 Android 수치로 쓰지 않는다. 세트/기기가 없으면 미측정으로 남기고 구현 완료와 SR1-B 품질 통과를 구분한다.

12. **후속 연결 조건을 명시한다.** detector만으로 Blemish 지원 완료 처리하지 않는다. 자동 보정 활성화에는 protected allowed-skin mask builder, real 복원 provider, SR1-A/B 품질 gate, 저장/render/editor session이 필요하다. release 배포는 <1000 MB/250 MB 예산 실측과 모델 전달·무결성·실패 상태 설계 후 결정한다. 이번 작업에서 예산을 조용히 완화하거나 서버로 전환하지 않는다.

## Acceptance Criteria

- [ ] 원본 revision/hash, 실제 class/tensor 계약, 버전 고정 export 명령과 ONNX manifest가 있다.
- [ ] 실제 체크포인트→ONNX 추론 및 PyTorch와 수치/box parity를 확인했다.
- [ ] Android adapter와 호출 가능한 기기 평가 경로가 있고 release에 모델/runtime을 추가하지 않았다.
- [ ] RGB/layout/letterbox 홀수 padding·비정방 ROI·역변환·clip·NMS·class filtering의 행동 테스트가 있다.
- [ ] 빈 검출/빈 허용 mask/투명 영역/잘못된 출력/모델 손상/취소/반복·동시 실행과 자원 처리를 검증했다.
- [ ] 후보 mask는 allowedMask와 alpha>0 영역의 부분집합이며 입력 bitmap/mask를 변경하지 않는다.
- [ ] 수동·자동 평가를 구분하며 기존 복원 harness의 회귀 테스트가 통과한다.
- [ ] Android offline 검출 및 Python 기준과 parity/성능을 검증했거나 기기 부재를 명시해 모바일 검증 완료를 주장하지 않는다.
- [ ] 실제 사진 품질/복원/production 채택의 미완료 항목과 근거를 RESULT에 구분했다.
- [ ] 메뉴/피부 보정 준비 상태/문서/history의 무관한 동작 변경이 없다.

## Validation

개발 중 모델 없이 실행하는 검사:

```bash
python3 -m pytest scripts/retouch
./gradlew :core:ai:testDebugUnitTest
```

실제 모델 검사는 별도 opt-in 명령으로 분리한다. export, PyTorch↔ONNX 비교, 검출 전용 평가, 자동 mask→복원 평가, Android 모델 설치와 instrumentation 실행의 **구현한 실제 명령**을 README/RESULT에 제공한다. 기본 pytest/Gradle 테스트에서 모델을 다운로드하거나 인터넷에 접근하지 않는다.

최종 검사:

```bash
./gradlew :app:assembleDebug :app:assembleRelease
scripts/check.sh
git diff --check
```

의존성 변경 시 dependencyGuard의 정확한 영향만 반영하고 검사를 약화하지 않는다. release APK 모델/runtime 미포함과 크기를 비교한다. 실패는 이번 변경/기존 실패/환경 제한으로 구분한다. weight-free 테스트 통과를 실제 모델 실행·품질 통과로 기록하지 않는다.

## Notes

- 이전 피부 보정 메뉴 교체 작업을 대체하는 다음 검출기 작업이다. 기존 메뉴 구현과 RESULT/REVIEW 내용은 보존한다.
- 기준: `specs/architecture.md` §4/§8, `specs/skin_retouch_pipeline.md` §1–§3, `specs/skin_retouch_validation.md`, `work/decisions.md` D080, `DESIGN.md`. debug 평가 의존성은 production 런타임 채택을 의미하지 않는다.
- [모델 카드](https://huggingface.co/Tinny-Robot/acne), [공개 파일](https://huggingface.co/Tinny-Robot/acne/tree/main), [config](https://huggingface.co/Tinny-Robot/acne/blob/main/config.json): 공개 모델 정보 출처. 작업서 작성 단계에서 가중치를 다운로드하거나 실행하지 않았다.
- [Ultralytics ONNX export](https://docs.ultralytics.com/integrations/onnx/): export 경로 참고. 최신 문서의 YOLO26 기본값을 오래된 YOLOv8에 그대로 적용하지 않는다.
- [ONNX Runtime Android](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html), [Java API](https://onnxruntime.ai/docs/get-started/with-java.html): 배포 및 session/tensor 수명 참고. runtime 버전과 모델 호환성은 포팅 결과로 고정한다.
