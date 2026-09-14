# Task

## Goal

`/home/jaeho/monetGPT`에 SAM3와 같은 독립 Python HTTP 서비스 방식으로 MonetGPT 서버를 구축하고 기존 Diffuse 앱의 자동 보정 기능과 실제 모델 연동을 검증한다.

## Background

사용자는 `~/monetGPT` 폴더를 생성했다. 구현 작업 디렉터리는 **`/home/jaeho/monetGPT`**이다. 먼저 해당 폴더의 숨김 파일·지침·git 상태를 확인하고 기존 내용을 보존한다.

2026-09-10 확인한 기준:

- SAM3는 독립 `.venv`, FastAPI/Uvicorn, 환경변수, real/fake engine, GPU 없는 기본 테스트, 별도 GPU 테스트, `scripts/check.sh`, systemd unit, Caddy 설정을 사용한다. 이 운영 구조를 따른다.
- SAM3 README는 Tesla T4(sm_75), 약 14.6 GB VRAM을 기록한다. 현재 장비/사용량은 다시 확인한다. SAM3 환경을 공유하거나 변경하지 않는다.
- Diffuse의 `specs/auto_enhance.md`와 `core/ai/.../monet/`에 클라이언트가 이미 있다. 서버는 **연산 이름과 보정 수치**를 반환하며 앱이 원본 해상도에서 렌더링한다.
- 공식 `llm/test.sh`는 LLaMA-Factory API를 실행한다. `llm/configs/test.yaml`은 `qwen2_vl`, Hugging Face backend, `pure_bf16: true`를 사용한다. upstream 설치/실행 설정을 T4에 그대로 적용하지 않는다.
- 기존 피부 검출기 작업서는 `work/tasks_acne_detector_2026-09-09.md`에 보존했다. 기존 미커밋 구현은 이번 범위가 아니다.

## Scope

### Modify

`~/monetGPT` 안에 다음 역할의 파일을 만든다. 구체적인 모듈 구성은 작은 서버에 맞게 조정한다.

- `app/`: 설정, API/인증/입력 검증, 실제 MonetGPT adapter, fake engine, 요청 제한.
- `pyproject.toml`, 의존성 고정 파일, `.gitignore`, 독립 `.venv` 설치 절차.
- `vendor/` 또는 동등한 upstream 소스 관리 경로와 revision 기록.
- `tests/`: GPU 없는 테스트 및 명시적으로 실행하는 GPU 통합 테스트.
- `scripts/check.sh`, 설치/실행/벤치마크 및 앱 호환 smoke script.
- `deploy/monetgpt-server.env.example`, `deploy/monetgpt-server.service`, Caddy 예시.
- `README.md`, `specs/api.md`, `work/RESULT.md`: 계약·운영·검증 결과.

### Do not modify

- `~/sam3-server` 코드/가상환경/토큰/서비스/기존 Caddy 설정.
- Diffuse Android 코드, UI, 렌더러, 저장/history, 피부 검출기 및 기존 RESULT/REVIEW.
- 재학습, 다른 모델로 교체, GIMP 렌더링 pipeline, 범용 채팅, SAM3 API 병합.
- 자동 commit, 기존 서비스 중단, DNS/방화벽 변경. 외부 공개는 배포 설정과 로컬 검증을 마친 후 실제 주소/권한에 맞춰 진행한다.

## Requirements

1. **환경과 실제 모델부터 확보한다.** 공식 `niladridutt/monetGPT` 코드와 체크포인트 revision, 다운로드 경로, 모델 구조/크기, Python/PyTorch/Transformers/LLaMA-Factory 및 CUDA 관련 버전을 기록한다. 버전을 고정하고 라이선스를 보존한다. 가중치·토큰·사진·캐시는 git에서 제외한다. upstream 경로의 `monetGPT`/`monetgpt` 대소문자 차이를 확인한다. 설치 스크립트를 무검토 실행하지 않는다.

2. **먼저 한 장 추론을 검증한다.** 현재 GPU/VRAM/프로세스를 읽기 전용으로 조사한다. T4이면 FP16과 지원되는 attention 경로를 우선 검증하고 BF16/Flash Attention 지원을 가정하지 않는다. 단독 모델 로딩 가능 여부부터 확인한다. 메모리 부족을 fake 성공으로 대체하지 않는다. 양자화/offload가 필요하면 품질·지연 영향을 측정하고 선택한 설정과 한계를 기록한다.

3. **기존 앱 요청을 그대로 수용한다.** `GET /health`, `POST /v1/chat/completions`를 제공한다. POST는 `Authorization: Bearer <MonetGPT 전용 토큰>`과 JSON을 사용한다. 앱이 보내는 실제 형태는 다음과 같다.

   ```json
   {
     "model": "test",
     "temperature": 0.3,
     "messages": [{
       "role": "user",
       "content": [
         {"type": "image_url", "image_url": {"url": "data:image/png;base64,<PNG>"}},
         {"type": "text", "text": "<MonetInstructions.kt에서 생성한 실제 지시문>"}
       ]
     }]
   }
   ```

   앱은 PNG 긴 변을 최대 1280px로 줄이고 stream/max_tokens/style 필드는 보내지 않는다. `test`를 실제 모델 alias로 지원한다. 균형/선명/레트로는 지시문에 포함된다. 별도 업로드 단계나 서버 전용 필수 필드를 추가하지 않는다.

4. **응답은 앱 파서와 호환되게 한다.** `choices[0].message.content`는 문자열이며 짧은 영어 설명 뒤에 단 하나의 평면 JSON 객체를 둔다. 문자열 예시는 `Lift exposure to brighten the image.` 다음 줄의 `{"Exposure":10,"Shadows":15}`이다. 연산 이름은 `MonetOperations.kt`의 33개(기본 9개와 HSL 24개)를 따른다. 값은 유한한 숫자 **-100..100**이며 서버에서 -1..1로 나누지 않는다. 중첩 객체/배열/null/NaN/Inf/비숫자 및 인식 가능한 연산이 없는 결과를 정상 성공으로 내보내지 않는다. 정상화 정책과 실패를 테스트한다. 설명은 실제 모델 결과에 근거하고 이미지/마스크/파일 경로를 반환하지 않는다.

5. **upstream 모델을 최소한으로 감싼다.** real/fake adapter를 분리한다. 내부 upstream API 프로세스가 필요하면 loopback 전용으로 두고 외부 인증/검증 우회를 막는다. 앱의 단일 요청으로 직접 추론할지 설명→JSON 두 단계가 필요한지는 실제 결과로 판단한다. 두 단계여도 외부 요청은 하나이며 전체 deadline을 공유한다. GIMP 실행이나 inference CLI의 디스크 출력에 의존하지 않는다.

6. **health는 모델 준비 상태를 반영한다.** 앱은 `/health`의 HTTP 성공 여부만 본다. 정상 준비 완료 200, 모델 로딩/실패/backend 단절 503을 반환한다. 추론 중에도 health가 GPU 작업 때문에 막히면 안 된다. SAM3의 `/healthz`만 복제하지 않는다. 운영 모드에는 health와 추론 모두 인증을 적용한다. 앱의 빈 토큰은 헤더 생략이므로 무인증 개발 모드는 명시적 설정으로만 허용한다.

7. **입력·인증·오류를 제한한다.** 운영 토큰은 필수이며 SAM3 토큰을 복사하지 않는다. 인증 실패 401, 잘못된 입력 400 또는 422, 크기 초과 413, 요청 과다/queue 초과 429, 준비 불가 503, 추론 실패/timeout은 적절한 5xx로 구분한다. 오류에 요청/토큰/traceback을 노출하지 않는다. PNG base64 data URL만 받고 외부 URL은 fetch하지 않는다. 디코딩 PNG 상한 20 MiB와 별도로 **base64 확장과 JSON을 수용하는 body 상한**(예: 30 MiB)을 둔다. 픽셀 수/크기도 제한한다. Caddy와 서버 body 상한을 일치시킨다.

8. **GPU 작업과 취소를 관리한다.** 모델은 한 번 로드하고 초기 설정은 단일 worker, 동시 추론 1개, 제한된 대기열이다. decode/GPU 작업이 async event loop를 막지 않게 한다. 대기 취소는 제거하고 실행 중 취소/timeout 후에도 실제 추론이 끝나기 전에 GPU 슬롯을 반환하지 않는다. 종료/실패 시 자원을 정리하고 후속 요청이 복구되어야 한다. 사진/base64/전체 지시문/모델 응답을 로그·영구 파일에 보관하지 않는다.

9. **앱 시간 제한을 실측한다.** connect 10초, read 120초에 맞춰 queue+전처리+추론 전체 deadline과 생성 token 상한을 설정한다. upstream의 6000-token 설정을 그대로 운영값으로 채택하지 않는다. cold start와 warm inference, 스타일별 성공률·지연·peak VRAM을 구분한다. 120초 내 완료하지 못하면 앱 연동 완료로 판정하지 않는다.

10. **SAM3 방식으로 운영한다.** 기본 bind는 `127.0.0.1`, 후보 포트는 `8082`로 하고 충돌을 확인한다. systemd는 별도 WorkingDirectory/EnvironmentFile, foreground 실행, `Restart=on-failure`를 사용한다. Caddy는 별도 호스트명 환경변수, 같은 upstream 포트, 추론 deadline에 맞는 timeout을 쓴다. SAM3 예시는 Uvicorn 8080/Caddy upstream 8091이므로 그대로 복사하지 않는다. README에 설치·시작·상태·로그·재시작·중지·업데이트/복구와 HTTPS 또는 USB `adb reverse tcp:8082 tcp:8082` 연결을 적는다. 앱에는 `/v1` 없는 base URL과 전용 토큰을 입력한다.

11. **SAM3 공존을 확인한다.** 같은 GPU에서 두 모델의 동시 상주·추론을 가정하지 않는다. 단독 측정 후 기존 서비스 운영을 방해하지 않는 조건에서 공존을 검증한다. 기존 서비스를 임의로 종료/변경하지 않는다. 불가능하면 별도 GPU/호스트 또는 운영 전환이 필요한 이유와 측정치를 기록한다. 단독 성공과 공존 미검증을 구분한다.

12. **인계를 기록한다.** `~/monetGPT/work/RESULT.md`에 변경 파일, 정확한 설치/실행 명령, revision/버전, API 예시, 테스트/실측 결과, 설정과 남은 제약을 기록한다. 비밀값은 제외한다. 실제 기기에서 자동→미리보기→적용/취소 및 스타일 전환을 확인하며 기기가 없으면 미실행으로 표시한다.

## Acceptance Criteria

- [ ] 독립 환경에서 실제 MonetGPT가 로드되고 PNG 입력에 보정 계획을 반환한다.
- [ ] `model: "test"`와 세 스타일의 실제 앱 지시문으로 호환성이 확인된다.
- [ ] health/인증/입력 제한/오류 응답이 검증된다.
- [ ] queue 초과, 취소, timeout/실패 후 복구 테스트가 있다.
- [ ] GPU 없는 `scripts/check.sh`가 통과하고 GPU smoke는 별도로 기록된다.
- [ ] warm 요청이 120초 내 완료되고 peak VRAM과 SAM3 공존 여부가 기록된다.
- [ ] systemd/Caddy/env 예시와 앱 연결 절차의 주소·포트가 일치한다.
- [ ] 기기 연동의 실행 여부와 결과가 명시되며 미실행을 통과로 표현하지 않는다.
- [ ] SAM3/Android의 기존 작업을 변경하지 않고 비밀값·사진·모델이 git에 포함되지 않는다.

## Validation

아래 서버 명령이 동작하도록 구현한다. 실제 모델 테스트는 설치 이후 별도 실행한다.

```bash
cd /home/jaeho/monetGPT
scripts/check.sh
.venv/bin/python -m pytest -m gpu tests/test_gpu_integration.py -v
.venv/bin/python scripts/smoke_client.py --base-url http://127.0.0.1:8082 --image /path/to/authorized-photo.png --all-styles
git diff --check
```

smoke script는 토큰을 환경변수로 읽고 앱과 같은 요청으로 응답 타입·이름·숫자 범위·지연을 검증한다. 기본 테스트는 GPU/모델 다운로드 없이 fake engine으로 실행하며 production은 자동 fake fallback을 금지한다.

Android 회귀 확인이 필요한 경우에만 해당 저장소에서 다음을 실행하고 기존 실패와 구분한다. Android 빌드 성공을 실제 서버 연동 증거로 사용하지 않는다.

```bash
cd /home/jaeho/vibe_editor_260905
./gradlew :core:ai:testDebugUnitTest --tests 'com.diffuse.core.ai.monet.*'
scripts/check.sh
```

## Notes

- 로컬 기준: `~/sam3-server/README.md`, `deploy/`, `scripts/check.sh`; Diffuse `specs/auto_enhance.md`, `core/ai/src/main/kotlin/com/diffuse/core/ai/monet/`와 대응 `MonetClientTest.kt`.
- 공식 출처: [MonetGPT](https://github.com/niladridutt/monetGPT), [모델](https://huggingface.co/niladridutt/monetGPT), [실행 스크립트](https://github.com/niladridutt/monetGPT/blob/main/llm/test.sh), [추론 설정](https://github.com/niladridutt/monetGPT/blob/main/llm/configs/test.yaml). 구현 시 검증한 revision을 고정한다.
- 이 파일은 서버 구현 인계다. 작성 시점에 모델 다운로드, GPU 실행, 서비스 설치, 외부 공개를 수행한 것은 아니다.
