# 피부 보정 처리 계약

Status: Planned. 신규 구현 계약이며 현재 제공되는 API가 아니다.
Related: [product](skin_retouch.md), [validation](skin_retouch_validation.md)

## 1. 경계와 엔진 선정

- core:ai: 분석/보정 알고리즘·모델, 서버 adapter, Availability/Result, fake.
- core:imaging: operation, 합성/render. 모델/네트워크를 호출하지 않는다.
- core:data: 파일/프로젝트 수명. UI draft를 저장하지 않는다.
- feature:editor: session/얼굴/취소, provider-document mapping, history commit.

ML Kit Play-services는 얼굴 기하에 재사용한다. 피부 문제 검출/보정 모델로 간주하지 않으며 PortraitDetector 메뉴 경로와 별도 analyzer를 둔다.

첫 후보는 **MI-GAN을 사용하는 잡티·여드름 국소 복원**이다. 자동 결함 검출은 별도이며 얼굴/피부 rectangle을 MI-GAN의 복원 mask로 넘기면 안 된다. OpenCV inpaint는 같은 mask로 비교하는 기준 구현이고 LaMa는 필요 시 후순위 서버 비교 후보다. 유분광/다크서클은 부위 제한 색조 보정, 면도자국은 별도 검출/보정 경로를 평가한다. 어느 후보도 아직 품질 통과/production 채택 상태가 아니다.

StyleRetoucher는 자동 피부 보정과 특징 혼합 강도 제어를 연구한 비교/설계 참고 후보다. 확인한 논문/저자 페이지에서 공식 코드·가중치 배포를 찾지 못했다. 추후 확보되면 checkpoint/라이선스/재현 경로를 검증하고 같은 사진으로 비교한다. 확보 전에는 필수 의존성으로 두거나 재현/재학습을 이 작업에 포함하지 않는다. 내부 attention은 네 기능별 정답 mask가 아니며 논문의 강도 제어도 네 기능 독립 제어가 아니다. 서버에서 실행할 수 있어도 이 제어 문제는 별도로 해결해야 한다.

평가 기록에 알고리즘, checkpoint/버전/해시, 코드·가중치 라이선스, 런타임, 입출력/전처리, 지원 기능, 품질, 기기 지연/메모리를 남긴다. 증거가 없으면 선정 미완료다. 서버가 유리하면 같은 경계로 구현하고 명시적으로 설정한다. APK 15MB/no-bundled-model 원칙과 충돌하는 asset/runtime은 크기 측정과 별도 결정 기록이 필요하다.

### 1.1 MI-GAN adapter

- 수동 정답 결함 mask로 복원 품질부터 확인하고 자동 검출 경로를 연결한다. MI-GAN 자체는 종류별 결함을 찾아주지 않는다. 기능별 engine 선택은 provider 내부의 정적 구성으로 충분하며 plugin/동적 routing 계층을 만들지 않는다.
- 공개 FFHQ 256 얼굴 모델과 Places2 512 모델을 같은 원본 패치 범위로 비교한다. 얼굴 데이터 학습 또는 높은 해상도만으로 어느 쪽이 우수하다고 결정하지 않는다.
- 공식 ONNX pipeline의 입력은 uint8 RGB `[1,3,H,W]`, binary mask `[1,1,H,W]`이며 **255=보존, 0=복원**이다. 앱의 defect mask(255=변경)를 adapter에서 반전한다. empty defect mask는 모델을 호출하지 않고 NoChange다.
- 공식 wrapper는 masked bbox 주변을 crop하고 고정 모델 크기로 resize한다. 서로 멀리 떨어진 잡티를 한 bbox에 묶으면 작은 결함이 축소될 수 있으므로 가까운 결함끼리 묶은 context patch를 비교한다. patch 크기/context/병합 규칙은 평가 후 고정한다. 같은 base에서 처리하고 overlap 순서를 결정적으로 정하며 보호 영역을 침범하지 않는다.
- ONNX wrapper는 resize와 blending까지 수행한다. 아래 provider 후보 계약에 맞춰 이미 혼합된 결과에 feather를 또 곱하지 않는다. wrapper 후처리로 결함 주변이 바뀔 수 있으므로 adapter가 최종 support 밖과 보호 영역을 원본으로 되돌린다. 원본 bitmap/tensor를 공유해 in-place로 변경하지 않는다.
- Android에는 현재 ONNX runtime 의존성이 없다. 공식 ONNX를 출발점으로 실제 tensor/연산 호환성, CPU/가능한 가속 경로, 모델 로드 및 전후처리 포함 지연, 메모리/배포 크기를 측정한 뒤 런타임을 채택한다. ONNX 공개가 Android 가속 호환성을 보장하지는 않는다.
- 논문 속도는 순수 모델 기준이며 공식 ONNX wrapper 전후처리 시간이 제외돼 있다. 서버로 옮기면 속도/메모리 제약을 바꿀 수 있지만 동일 모델/입력의 복원 품질 향상을 보장하지 않는다.

## 2. Provider 논리 계약

이름은 권장안이며 Kotlin 패턴에 맞출 수 있지만 의미는 유지한다.

```text
FaceRegionAnalyzer.analyze(canonicalImage) -> Result<FaceAnalysis>
FaceAnalysis = faces(bounds, ephemeralId, perKindEligibility, geometry)

SkinRetouchProvider
  availability: StateFlow<Availability>
  executionLocation: Local | RetouchServer
  supportedKinds: Set<Blemish | Shine | DarkCircles | ShavingShadow>
  prepare(faceRoi, allowedMask, kind)
    -> Result<PreparedCorrection(candidateRoi, changeSupport, outcome, engineVersion)>
outcome = Corrected | NoChange
```

core:ai는 provider 전용 타입을 두고 Operation/EditDocument/renderer를 참조하지 않는다. editor에서 타입을 변환하며 ai_provider.md의 core:imaging 접근 제한을 넓히지 않는다.

입력은 방향 정규화된 ARGB_8888 ROI와 같은 크기 허용 영역 mask다. allowedMask는 최대 변경 가능 범위이며 실제 결함 mask가 아니다. 잡티 adapter 내부의 검출기가 defect mask를 만들고 허용 영역과 교집합을 취한다. SR1-A만 평가용 정답 mask를 검출기 대신 사용한다.

출력 candidateRoi는 기준 강도 100에서 영역 제한/feather를 한 번 적용한 최종 후보이고 changeSupport는 실제 변경 가능 영역의 binary mask(0/255)다. support 밖 후보는 원본과 동일해야 한다. RGB/mask는 입력과 같은 크기/좌표로 복원한다. 외부 입출력의 aspect 변경/crop/face warp/잘못된 크기는 실패이며 adapter 내부 patch resize는 역변환을 검증한다. 입력 bitmap은 불변이다. NoChange는 정상 결과이고 오류/미지원과 구분한다.

한 요청은 한 얼굴의 한 종류만 처리하며 다른 항목을 함께 보정하지 않는다. 여러 후보는 같은 immutable base ROI에서 생성한다. 클라이언트도 support를 허용 영역의 support와 교집합하고 원본 투명 영역을 제외한다. soft feather를 재적용하지 않는다. 종류별 engine/version을 결과에 기록하며 한 모델의 전체 보정 결과를 네 종류에 복제하지 않는다.

pixel/model 처리는 injected default, 파일/HTTP는 io dispatcher다. CancellationException은 전파한다. bitmap/model/native resource 소유자와 정리 시점을 명확히 한다. 기존 AppError를 쓰고 raw detail은 사용자에게 노출하지 않는다. 테스트는 fake/localhost를 사용한다.

## 3. 얼굴 기하와 영역

ML Kit contour는 가장 두드러진 얼굴에만 제공되므로 하나의 contour를 모든 얼굴에 복사하면 안 된다. 전체 bounds 감지 후 선택 ROI에서 contour/landmark를 구하고 역변환하는 경로를 우선 검토한다. 재감지 얼굴과 선택 bounds가 불일치하면 처리하지 않는다. 확대는 작은 얼굴의 디테일을 만들지 않는다.

- ROI는 bounds에 각 축 길이 20% context를 추가하고 canvas로 clip한다. 가장자리/다른 얼굴 겹침을 처리하며 타인 보호 영역을 뺀다.
- 초기 eligibility는 canonical working 얼굴 200x200px 이상, 필요한 contour/landmark 존재, yaw/roll 절댓값 30도 이하다. 제품 초기값이며 SR1 근거로 조정·기록할 수 있다. 누락 좌표를 0으로 대체하지 않는다.
- 허용 영역은 피부이며 눈/눈썹/입술/콧구멍/머리카락/타인을 제외한다. 기하만으로 안경/머리카락/수염 가림을 판별할 수 없으면 추가 mask를 검증하거나 미지원 처리한다.
- 다크서클은 눈 아래, 면도자국은 입술 제외 인중/턱/턱선, 유분광은 피부의 검출된 반사, 잡티는 실제 결함 부위만 변경한다.
- Feather는 ROI scale 비례이며 마지막 hard protection mask로 보호 영역 침범을 막는다. 수치는 평가 후 고정/테스트한다.
- 검출 ID/geometry는 세션용이며 영구 사람 ID/얼굴 임베딩을 만들거나 저장하지 않는다.

## 4. 입력과 좌표

canonical canvas는 EXIF 정규화 후, Outpaint가 있으면 확장된 canvas이며 Crop/회전 전이다. mask/ROI/result는 이 공간을 사용한다. working long edge는 기존 4096 이내이고 화면 1080 preview를 무조건 보정 원본으로 쓰지 않는다.

T70처럼 새 결과는 첫 Adjust 앞, Adjust가 없으면 마지막에 삽입한다.

1. 진입 document와 삽입 index를 고정한다.
2. index 이전 pixel operations만 포함하고 Crop을 제거해 base를 렌더링한다. prefix가 참조하는 Mask는 lookup용으로 보존한다.
3. 이 base로 분석/보정한다. generativeInput()은 Crop 유지와 전체 비조정 op 렌더이므로 그대로 사용하지 않는다.
4. 해당 위치에 삽입하고 나머지 op 순서는 유지한다. 뒤쪽 op는 보정 위에 실행되고 Crop은 마지막이다.
5. draft도 임시 document를 실제 renderer로 그린다. 조정 이중 적용/뒤쪽 op의 선행 bake가 없어야 한다.

드문 op 순서에서는 뒤쪽 생성 결과가 새 보정을 덮을 수 있다. list-order를 유지하고 최종 draft 모습을 보여준다. 이를 피하려고 기존 op를 재정렬하지 않는다. 썸네일/가시성은 canonical-to-display crop/rotation을 쓰고 ROI local/canonical/display round-trip 테스트를 둔다.

## 5. 강도 합성

동일 base B, 종류 k의 영역 제한/feather 완료 후보 Ck, binary support Pk(0 또는 1), 강도 sk를 사용한다. RGB는 동일한 정규화 sRGB 값에서 합성하고 alpha는 B를 보존한다. 색 공간 변경은 품질 비교와 수식 갱신을 필요로 한다.

```text
R = clamp(B + sum_k(sk * Pk * (Ck - B)), 0, 1)
```

clamp는 합계 후 한 번이다. SR1에서 overlap/clipping 조합 품질도 검증한다. 다른 합성법이 필요하면 수식과 zero/독립성 계약을 함께 갱신한다. 같은 입력 순서는 결정적이며 한 기능 0은 해당 delta를 제거한다.

원본과 후보의 선형 합성은 앱의 강도 정의다. StyleRetoucher 내부 channel mask를 바꾸는 강도 조절과 동일하지 않으며 그 논문 결과를 이 합성의 검증으로 사용하지 않는다. 100 후보를 보간해도 중간 강도 질감이 부자연스러우면 평가 미통과다.

캐시 키는 session/base revision/face ROI/engine version/kind다. 한 얼굴, ROI 단위로 처리하고 full-canvas 후보 4장을 동시에 보유하지 않는다. slider 합성/render는 conflated latest-wins다. 메모리 해제 후 후보 재준비는 명시적 미리보기에서만 한다.

## 6. Operation과 파일

Operation.SkinRetouch(id, maskId, resultRef, settings)를 추가한다. settings는 네 유한한 0..1 강도와 활성 종류별 engine/checkpoint version 및 합성 알고리즘 version이다. 검출기 버전도 해당 종류 출처에 포함한다. geometry는 저장하지 않는다. JSON root v=1, type=skinRetouch다.

강도가 bake된 canonical working-size R과 강도>0 후보 변경 영역 합집합의 binary support Mask를 저장한다. R에 feather/강도가 이미 반영돼 있으므로 renderer에서 soft alpha를 다시 곱하지 않는다. preview 축소 경계 보간은 허용하되 working-size 합성은 동일해야 한다.

list order에서 support 안 RGB를 결과로 교체하고 입력 alpha를 유지한다. 밖은 그대로 둔다. GPU는 기존 bitmap composite/CPU fallback을 따른다. full export는 저장 결과를 재사용하고 추론하지 않는다. 모델 해상도 이상의 질감이 복원됐다고 주장하지 않는다.

Mask+SkinRetouch를 history 한 번으로 넣고 activeMaskId/source는 유지한다. 재진입은 새 세션이다. retouch_<id>.png와 mask_<id>.png를 원자 저장하고 모두 성공한 뒤 commit한다. 취소/실패/세대 변경 시 이번 요청 미참조 파일만 정리한다. history/redo/디스크 문서 참조 파일은 보존한다. process death의 임시 파일은 다음 load에서 보수적으로 정리한다. 기존 GC가 구현돼 있다고 가정하지 않는다.

codec/참조 검증/복제 경로 재작성/삭제에 새 op를 포함한다. missing mask/result와 잘못된 강도를 정상 load하지 않는다. unknown type drop은 기존 정책이며 구버전이 새 보정을 보존한다고 보장하지 않는다. outpaint guard에 새 mask-bearing op를 포함한다.

이 절은 edit_model.md/render.md/persistence.md/gpu_render.md/outpaint.md의 추가 계약이다. 기존 operation 수식/순서는 변경하지 않는다.

## 7. 세션

ViewModel은 history/session을 소유하고 controller는 viewModelScope에서 실행한다. 유효성 키는 project/source, document revision, session generation, face, provider config revision, request generation이다. provider 완료 후와 저장 후 commit 직전에 모두 검사한다.

취소를 무시한 늦은 provider도 상태/history를 쓰지 못해야 한다. old finally가 new busy를 해제해서도 안 된다. 회전은 draft 유지, process death는 저장 문서만 복구한다. autosave에는 committed document만 포함하고 provider/UI bitmap 소유권을 구분해 해제한다.

## 8. 서버 선택 시

segmentation의 suspend/availability/error 패턴을 따르되 SAM 3 session ID/endpoint를 재해석하지 않는다. 별도 서비스/프로세스가 기본이며 같은 호스트 사용은 배포 선택이다.

SR1에서 실제 서버와 wire 계약을 확정한다. 최소 request ID/contract version/kind/engine version/ROI dimensions/image/allowed mask가 필요하다. response는 §2와 같은 feather 완료 candidate/binary support, outcome/종류별 engine version/대응 ID다. 실제 결함 검출이 기기/서버 중 어디서 실행되는지도 명시한다. body/pixel limit, timeout, auth, cancellation 또는 유한 job TTL, 과부하/재시도를 문서화하고 MockWebServer/실제 서버를 모두 테스트한다.

주소/credential은 런타임 설정이며 SAM 3 credential을 자동 복사하지 않는다. 사진/mask를 로그·영구 파일·학습 데이터로 보관하지 않는다. 임시 데이터는 응답/취소/TTL 후 정리한다. 숨은 재업로드/로컬→서버 fallback은 없다. 서버가 없으면 Unavailable이고 fake 성공으로 대체하지 않는다.

## 9. 참고 자료

ML Kit/OpenCV/LaMa는 2026-09-08, MI-GAN/StyleRetoucher는 2026-09-09 대화에서 확인했다. API/알고리즘 범위의 근거이며 이 앱의 실측 품질 증거가 아니다.

- [MI-GAN 공식 저장소](https://github.com/Picsart-AI-Research/MI-GAN): 공개 checkpoint, ONNX 경로, mask 극성, 속도 측정 범위.
- [MI-GAN ONNX wrapper](https://github.com/Picsart-AI-Research/MI-GAN/blob/main/scripts/create_onnx_pipeline.py): tensor shape, crop/resize/blending. 구현 시 실제 commit을 고정한다.
- [StyleRetoucher 논문](https://arxiv.org/html/2312.14389v1): 자동 피부 보정, 특징 혼합, §4.4 강도 제어와 §5 한계.
- [StyleRetoucher 저자 페이지](https://ansire.github.io/styleRetoucher.html): 결과/논문 공개. 확인 당시 코드·가중치 링크를 찾지 못함.

- [ML Kit 얼굴 감지](https://developers.google.com/ml-kit/vision/face-detection/android): contour 얼굴 제한, 입력 크기, 모델 준비 상태.
- [OpenCV inpainting](https://docs.opencv.org/4.x/df/d3d/tutorial_py_inpainting.html): mask 기반 국소 복원 비교 구현.
- [LaMa 공식 구현](https://github.com/advimman/lama): 서버 복원 후보, 피부 전용 모델로 채택된 상태가 아니다.
