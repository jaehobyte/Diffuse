# DESIGN.md — AI Image Editor (Android)

> Galaxy(One UI 8.5) 포토 에디터를 기준으로 한 디자인 기준.
> 2026-10-14에 SM-S948N 실기기의 `com.sec.android.mimage.photoretouching`을 adb로 직접 열어
> 조절·필터·색조·데코·도구 탭, 비율 하위 메뉴, 값이 움직인 슬라이더, 선택된 필터, ⋮ 메뉴를 캡처하고
> 색을 샘플링했다. 근거와 매핑은 `docs/superpowers/specs/2026-10-14-galaxy-photo-editor-restyle-design.md`.

## 1. Visual Theme & Atmosphere

- 화면 전체가 검정이다. 캔버스 주변, 상단바, 하단 탭, 시트 패널 모두 같은 `#000000` 위에 놓인다.
  별도의 시트 표면·유리·그림자는 없다. 컨트롤은 검정 위에 떠 있는 어두운 회색(`#202023`) 원·칩·카드다.
- 글자와 아이콘은 흰색이다. 보조 글자는 `#A6A6A7`.
- **노랑**(`#F9DA4B`)은 선택 상태에만 쓴다: 선택된 탭 아이콘, 슬라이더의 현재 눈금, 선택 원의 값 wedge.
- 선택된 칩·원은 **흰 배경에 검정 글자**로 반전된다. 선택된 썸네일은 2dp 흰 테두리다.
- 채워진 버튼은 드물다. 포토 에디터의 편집 화면에는 하나도 없고(`저장`도 글자 버튼), 대화상자의 주 버튼만
  One UI 블루다. 이 앱에서도 편집 화면의 적용·취소는 글자 버튼이고, 채워진 블루는 Browse CTA와 시트 밖의
  주 액션에만 쓴다.
- Browse와 편집은 같은 팔레트를 쓴다. 두 모드의 차이는 없다. Browse는 Galaxy 갤러리의 사진 탭처럼
  날짜 헤더 아래 정사각 썸네일을 1dp 간격으로 빽빽하게 배열한다.
- 포토 어시스트처럼 AI 진입점만 블루→보라 그라데이션 원이다. 그 외 장식 그라데이션은 없다.
- 설명은 짧고 담백한 한국어로 쓴다. 포토 에디터의 로고·Galaxy AI 문구·스티커·그리기 기능은 가져오지 않는다.

동작은 프로젝트의 architecture/specs 및 `work/decisions.md`를 따른다. 편집 도구 순서, 두 단계 도구 스트립,
시트의 취소·적용 수명주기, 누르고 있는 동안 원본 비교, 히스토리, 내보내기, 음성 입력 계약은 스타일 변경으로
바꾸지 않는다. 기존 문서와의 차이는 §9에 명시한다.

## 2. Color Palette & Roles

토큰은 `core/ui/src/main/kotlin/com/diffuse/core/ui/theme/Tokens.kt`에 모은다. 값은 포토 에디터
스크린샷에서 샘플링한 것이며, `TokensTest`가 아래 표의 모든 행을 그대로 검사한다.

### Brand & selection

| Token | Value | Role / 포토 에디터 대응 |
|---|---|---|
| `accent` | `#F9DA4B` | 선택 상태: 선택된 탭 아이콘·라벨, 슬라이더 현재 눈금, 듣는 중 mic / 선택 탭 노랑 |
| `accentSoft` | `#F9E16E` | 선택 원 안의 값 wedge / 색조 원의 노랑 부채꼴 |
| `accentPressed` | `#E3C53A` | 노랑 요소의 눌림 상태 |
| `primaryFill` | `#2F6BEB` | 채워진 주 버튼; One UI 블루를 흰 글자 4.5:1에 맞춰 조금 어둡게 |
| `onAccent` | `#FFFFFF` | 주 버튼 위 텍스트·아이콘 |
| `selectionFill` | `#FAFAFA` | 선택된 칩·원의 배경, 선택된 썸네일의 테두리 / 선택 원 흰색 |
| `selectionInk` | `#000000` | 선택된 칩·원 위의 글자·아이콘 |
| `promptTint` | `#2A3346` | 이전 요청·추천 문장 pill의 배경 |

### Surfaces & ink (Browse·Edit 공통)

| Token | Value | Role / 포토 에디터 대응 |
|---|---|---|
| `background` | `#000000` | 화면 배경 / 전체 검정 |
| `surface` | `#000000` | 상단바·툴스트립·시트 패널; 배경과 구분하지 않는다 |
| `surfaceRaised` | `#202023` | 원형 버튼, 칩, 카드, 입력창, 썸네일 로딩 / 조절 pill·색조 원·도구 카드 |
| `surfaceSecondary` | `#2C2C30` | 보조 버튼, hold pill, 회전 버튼 |
| `surfaceMenu` | `#272727` | 메뉴·대화상자·snackbar / ⋮ 메뉴 카드 |
| `hairline` | `#3A3A3D` | 구분선 |
| `ink` | `#FAFAFA` | 본문·아이콘 / 흰 글자 |
| `inkSecondary` | `#A6A6A7` | 설명·placeholder·선택되지 않은 라벨 / 비율 칩의 회색 글자 |
| `inkMuted` | `#6E6E70` | 비활성 장식 |

### Editor

| Token | Value | Role |
|---|---|---|
| `editBackground` | `#000000` | 캔버스 주변 |
| `editSurface` | `#000000` | 편집 상단바·툴스트립·시트 패널 |
| `editSurfaceRaised` | `#202023` | 편집 컨트롤의 원·칩·카드 |
| `editHairline` | `#3A3A3D` | 편집 컨트롤 구분선 |
| `editInk` / `editInkSecondary` | `#FAFAFA` / `#A6A6A7` | 편집 컨트롤 텍스트 |
| `canvasOverlayInk` | `#FAFAFA` | 캔버스·진행 scrim 위 텍스트 |
| `canvasCheckerA` / `canvasCheckerB` | `#2C2C2E` / `#242426` | 투명도 체크보드, 8dp 셀 |
| `sliderTickMajor` | `#8D8D8E` | 눈금자 슬라이더의 5번째마다의 눈금 |
| `sliderTickMinor` | `#4D4D4F` | 눈금자 슬라이더의 나머지 눈금 |
| `aiGradientStart` | `#5C8AE6` | AI 진입 원의 그라데이션 시작(위-왼쪽) / 포토 어시스트 원 |
| `aiGradientEnd` | `#9D7BD8` | AI 진입 원의 그라데이션 끝(아래-오른쪽) |

### Semantic

| Token | Value | Role |
|---|---|---|
| `success` | `#3DBE6A` | 성공 |
| `warning` | `#F2A33C` | 경고 |
| `error` | `#FF3B30` | 오류 표시(아이콘·테두리) |
| `linkText` | `#6FA3FF` | 글자 링크; 검정 위 4.5:1 |
| `errorText` | `#FF6B5E` | 오류 문구; 검정 위 4.5:1 |

- 사진·썸네일 자체에는 색 틴트, blur, UI용 그라데이션을 씌우지 않는다.
- 작은 링크·오류·보조 문구는 `#000000`과 `#202023` 위에서 4.5:1을 확보한다. `inkMuted`는 필수 안내·활성
  버튼 라벨에 쓰지 않는다.
- 비활성은 38% alpha이며 색상만으로 활성 상태를 구분하지 않는다.

## 3. Typography

- Android 시스템 sans-serif를 기본으로 한다. 포토 에디터는 SamsungOne을 쓰지만 별도 폰트를 요구하지 않는다.
- 크기 체계는 유지한다. 포토 에디터의 상단 `저장`은 `headingLg` 굵기의 글자 버튼, 색조의 `밝기` 같은
  현재 항목 이름은 `label`, 칩은 `bodyMd`/`bodyStrong`이다.

| Token | Size / Weight / Line-height | Use |
|---|---|---|
| `headingXl` | 27sp / 700 / 32sp | 빈 화면·온보딩 |
| `headingLg` | 17sp / 700 / 24sp | Browse 제목, 상단 `저장`, 대화상자 제목 |
| `headingMd` | 13sp / 600 / 19sp | 구역 소제목 |
| `bodyMd` | 14sp / 400 / 21sp | 본문·입력·칩 |
| `bodyStrong` | 14sp / 600 / 21sp | 버튼·선택된 칩·선택 원의 값 |
| `bodySm` | 13sp / 400 / 20sp | 설명·메타데이터 |
| `label` | 12sp / 400 / 17sp | 시트 제목(현재 항목 이름)·보조 라벨 |
| `labelSmall` | 11sp / 600 / 15sp | 툴스트립 라벨·짧은 배지 |
| `link` | 13sp / 600 / 18sp | 텍스트 액션 |
| `mono` | 13sp / 500 / 18sp | 좌표·HEX·수치; JetBrains Mono 유지 |

글자 간격은 기본값. 시스템 글꼴 배율을 존중하고, 커진 글자를 고정 높이 때문에 자르지 않는다.

## 4. Component Stylings

### Buttons

- 모든 버튼은 완전한 pill(`RoundedCornerShape(percent = 50)`), 기본 높이 48dp(최소값).
- `primary`: `primaryFill` + `onAccent`. 한 surface에 최대 하나. 편집 시트 안에서는 쓰지 않는다.
- `secondary`: `surfaceSecondary` + `ink`.
- `tertiary`: 투명 배경 + `ink`. 시트의 적용은 `accent` 글자, 링크는 `linkText`.
- `iconCircle`: 사진 위 40dp 원, 검정 60% 배경, 흰 아이콘. 터치 영역 48dp.
- 포토 에디터의 조절 pill처럼 아이콘 버튼 묶음은 `surfaceRaised` pill 안에 둔다.

### Top bar (Edit)

- 검정, 최소 56dp. 왼쪽 ↶ ↷ ◐(누르고 있는 동안 원본), 오른쪽 `원본 복원` 글자 버튼과 굵은 `저장` 글자 버튼.
  뒤로 화살표는 두지 않는다(시스템 back). 아이콘은 흰색, 비활성 38%.
- 포토 에디터의 ⋮ 메뉴는 대응 기능(다른 파일로 저장·크기 변경·편집 복사)이 없으므로 두지 않는다.

### Tool strip (bottom of Edit mode)

- 검정, 높이 72dp, 가로 스크롤. 항목 폭 64dp, 아이콘 26dp, 라벨 `labelSmall`.
- 선택 도구는 아이콘·라벨이 `accent` 노랑. 밑줄 표시는 없다. 비선택은 흰 아이콘에 `inkSecondary` 라벨.
- AI 부모 항목은 `aiGradientStart`→`aiGradientEnd` 대각 그라데이션 40dp 원에 흰 아이콘이다. 점 배지는 없다.
- 목록과 순서는 `specs/tool_groups.md` 및 D092를 따른다. 포토 에디터는 아이콘만 두지만, 이 앱의 AI 단계는
  추상적인 도구가 여덟 개라 라벨을 유지한다.

### Sheet (tool panel)

- 검정 패널. 모서리·그림자·테두리·핸들 없음. 위아래 패딩 12dp, 가로 12dp, 구역 간격 16dp.
- 제목은 포토 에디터의 `밝기`처럼 가운데 정렬 `label`/`ink` 한 줄.
- 내용 → 고정된 [취소 | 적용]. 취소는 `ink` 글자, 적용은 `accent` 글자(`bodyStrong`). 다른 라벨(`저장`, `닫기`,
  `선택한 보정 실행`)도 같은 자리를 쓴다.
- 기본 최대 높이 45%. 키보드·큰 글자에는 내부 스크롤과 inset으로 대응한다. 시트가 덮은 스트립으로 터치가
  통과하면 안 된다. 적용·취소·시스템 Back의 기존 계약을 유지한다.

### Adjust sheet (라이트·색상·디테일·혼합)

포토 에디터 색조 탭의 배치다.

- 가운데 `label`로 현재 항목 이름.
- 56dp 원 한 줄(가로 스크롤, 가운데 정렬, 간격 12dp). 종류마다 원 하나.
  - 비선택: `surfaceRaised` 배경, 흰 24dp 아이콘. 값이 0이 아니면 아이콘 대신 흰 `bodyStrong` 수치.
  - 선택: `selectionFill` 배경, 값이 0이면 `selectionInk` 아이콘, 아니면 `accentSoft` 부채꼴
    (12시부터 `|값|/최대` × 360°, 양수는 시계방향·음수는 반시계) 위에 `selectionInk` 수치.
- 아래에 눈금자 슬라이더 하나. 선택된 원의 종류를 조절한다.
- 혼합의 밴드 칩, 선택 마스크 토글, AI 바로가기는 원 줄 위에 그대로 둔다.

### Slider (눈금자)

- 트랙·thumb 없음. 눈금 41개: 5번째마다 `sliderTickMajor` 12dp, 나머지 `sliderTickMinor` 8dp, 폭 2dp.
- 현재 값 위치에 `accent` 2dp × 20dp 눈금. 0 중심 조절은 가운데 위 4dp `sliderTickMajor` 점.
- 수치는 오른쪽 `mono`(`showValue`); Adjust sheet는 원 안에 보이므로 숨긴다.
- 터치 높이 48dp. 두 번 탭하면 기본값 복원. 절대 위치 드래그.

### Chips & option cards

- `OptionChip`: 비선택 `surfaceRaised` + `ink` `bodyMd`; 선택 `selectionFill` + `selectionInk` `bodyStrong`.
  테두리 없음. 터치 48dp.
- 편집 시트 안의 카드·썸네일 모서리 16dp, 로딩은 평평한 `surfaceRaised`. 선택된 썸네일은 2dp `selectionFill` 테두리.

### Browse (갤러리 사진 탭)

- 상단바 56dp, 검정, 왼쪽 `headingLg` 앱 마크.
- 그리드는 `LazyVerticalGrid` 4열(600dp 이상 6열), 정사각 타일, 간격 1dp, 좌우 8dp. 타일은 모서리 없이
  `surfaceRaised` 위에 가운데 크롭 썸네일. 타일 아래 글자 없음.
- 마지막 편집일 기준 날짜 섹션: `오늘`, `어제`, 같은 해는 `M월 d일`, 다른 해는 `yyyy년 M월 d일`.
  헤더는 `headingLg`/`ink`, 위 20dp·아래 8dp, 최신 날짜가 위.
- 하단 가운데 플로팅 pill: `surfaceRaised` 92%, 최소 56dp, `+` 아이콘과 `새 프로젝트` `bodyStrong`/`ink`.
  내비게이션 바 위 20dp. 빈 화면도 같은 pill을 쓴다.
- 길게 누르기의 복제·삭제 iconCircle과 삭제 확인은 기존대로.

### Prompt bar

- `surfaceRaised` pill, 최소 56dp, 테두리 없음. 입력 `bodyMd`/`ink`, placeholder `inkSecondary`.
- 아이콘 흰색, 듣는 중 mic만 `accent`. 배치·제출·음성 계약은 `specs/prompt_input.md`를 따른다.

### State display

- AI 실행: 캔버스 위 검정 40% scrim, `accent` 진행 표시, `canvasOverlayInk` 한 줄과 취소.
- 지시 시트의 추천·검토·실행 표시는 `specs/vibe_edit.md`의 기존 구조를 유지하고 색만 이 팔레트를 쓴다.
- 오류는 snackbar(`surfaceMenu` 배경, `ink` 글자). toast는 사용하지 않는다.
- 대화상자는 `surfaceMenu` 카드, 주 버튼은 `primaryFill`. 확인 대화상자는 파괴적 동작에 한정한다.

## 5. Layout Principles

- 여백은 4 / 8 / 12 / 16 / 24 / 32dp.
- Browse는 4열 정사각 그리드, 간격 1dp, 좌우 8dp. 600dp 이상에서는 6열.
- Edit는 상단바 56dp / 캔버스 / 하단 툴스트립 72dp. 사진 주위에 최소 16dp의 검정 여백.
- 터치 영역은 최소 48×48dp. 접근성 라벨을 제거하지 않는다.

## 6. Depth & Elevation

- 그림자는 쓰지 않는다. 깊이는 검정 위의 `surfaceRaised` 회색과 간격으로만 표현한다.
- 사진 위 iconCircle은 필요 시 2dp elevation만.
- neon, glow, 포화된 장식 그라데이션은 사용하지 않는다. AI 진입 원의 그라데이션이 유일한 예외다.

## 7. Do’s and Don’ts

Do
- 검정 바탕, 흰 글자, `#202023` 컨트롤, 선택에만 노랑.
- 선택된 항목은 흰 배경·검정 글자로 반전한다.
- 조절은 원 한 줄과 눈금자 슬라이더로 보여준다.
- AI 작업에는 진행 표시와 취소 수단을 제공한다. 원본 비교는 누르고 있는 동안만.

Don’t
- 밝은 표면, 유리 시트, 파란 선택 표시, 밑줄 인디케이터를 되살리지 않는다.
- 스타일을 맞추기 위해 취소·적용 계약, 히스토리, 음성 제출 방식 등 제품 동작을 바꾸지 않는다.
- 사진에 틴트나 blur를 적용하지 않는다. 새 아이콘 패키지를 혼용하지 않는다(Material Symbols Rounded 유지).
- “✨ AI Magic” 같은 장식 문구를 쓰지 않는다.

## 8. Responsive & Gesture

- v1은 세로 모드 고정(`specs/editor_shell.md`).
- 600dp+에서 Browse는 6열.
- 캔버스 zoom·pan·fit, 도구별 한 손가락 소유권은 `specs/canvas.md` 및 도구별 스펙을 따른다.
- 상태바·내비게이션 바는 투명하고 아이콘은 밝은색이다. edge-to-edge 및 IME/system inset을 처리한다.

## 9. Agent Prompt Guide & Migration

```text
black #000000 · raised #202023 · secondary #2C2C30 · menu #272727
ink #FAFAFA · ink2 #A6A6A7 · accent(선택) #F9DA4B · primary(블루) #2F6BEB
selected = white fill / black ink · pill 50% · card 16dp · touch 48dp
system sans-serif · body 14/21sp · title 17/24sp · ruler slider, no track
```

### 기존 기준과의 차이

| 기존 문서·구현 | 새 목표 |
|---|---|
| 쿨 화이트 컨트롤, 블루 액션, 다크 캔버스만 분리 | 전체 검정, 노랑 선택, 블루는 채워진 버튼에만 |
| 28dp 유리 시트, 핸들, 10dp 그림자, 취소·적용 pill | 검정 패널, 글자 버튼 취소(흰)·적용(노랑) |
| 세로로 쌓인 슬라이더 + 트랙·thumb | 원 한 줄 + 눈금자 슬라이더 하나 |
| 상단바 ←·내보내기 pill | ↶ ↷ ◐ / `원본 복원` · `저장` 글자 버튼 |
| 툴스트립 블루 밑줄·점 배지 | 노랑 아이콘, 그라데이션 AI 원 |
| Browse 2열 masonry, 16dp 라운드 타일, 타일 아래 상대 시간, 블루 CTA pill | 날짜 헤더 + 4열 정사각 1dp 그리드, 하단 플로팅 pill |

`specs/editor_shell.md`, `specs/prompt_input.md`, `specs/tool_groups.md`, `specs/browse.md`의 스타일
서술(밝은 상단바, 블루 인디케이터, 흰 입력창, 2열 masonry와 상대 시간)은 이 문서가 우선한다. 동작 계약은 그대로다.

### 구현 시 준수 사항

- `core:ui`의 Tokens, Typography, AppTheme, 공용 컴포넌트를 수정한다. 화면별 색상을 하드코딩하지 않는다.
- Material 3는 primitives에만 쓰고 `darkColorScheme`에 이 팔레트를 넣는다.
- 한국어 사용자 문구는 `strings.xml`을 사용한다.
- 검증은 Browse, 편집 기본 화면, 열린 시트, 조절 시트의 원 줄과 슬라이더, 입력/음성 상태, 로딩·오류, 큰
  글꼴을 확인하고 관련 golden과 `scripts/check.sh`를 실행한다.
