# DESIGN.md — AI Image Editor (Android)

> `~/autographeragent`의 실제 Compose UI를 참고한 디자인 기준.
> 쿨 화이트, 블루 액션, 반투명 유리 시트, 완전히 둥근 입력창을 이미지 편집기에 적용한다.
> 이 문서는 **목표 디자인**이다. 이번 변경은 문서에만 적용되며 앱 구현이 완료되었다는 뜻이 아니다.

## 1. Visual Theme & Atmosphere

- 사진이 중심이고 UI는 가볍고 차분한 사진 어시스턴트처럼 보인다.
- Browse와 편집 컨트롤 모두 푸른 기가 아주 조금 있는 흰색, 검정 본문, 옅은 회색 블록을 사용한다.
- 편집 캔버스 주변만 중성 다크로 유지한다. 밝은 툴바·시트와 어두운 사진 작업 영역을 구분한다.
  이는 카메라·대화 중심인 참고 앱을 편집기에 맞춘 선택이며 참고 앱의 다크 테마를 복제한 것이 아니다.
- 파란색은 액션, 링크, 선택 상태에 사용한다. 장식용 강조나 모든 아이콘의 일괄 착색은 하지 않는다.
- 하나의 surface(상단바, 도구 스트립, 전면 시트)에는 채워진 주 액션을 최대 하나만 둔다.
  선택 표시와 텍스트 링크는 함께 있을 수 있다. 기존의 “화면 전체 강조색 한 곳” 규칙은 사용하지 않는다.
- 카드·입력창은 둥글고 여백은 편안하게 둔다. 설명은 짧고 담백한 한국어로 쓴다.
- 참고 앱의 로고, 앱 이름, 촬영 기능, 채팅 내비게이션을 가져오는 작업은 포함하지 않는다.

### 참고 근거와 적용 범위

2026-10-13에 확인한 `~/autographeragent`의 다음 소스를 기준으로 한다.
경로는 해당 저장소 기준이며 별도 Figma 이미지나 실행 화면을 확인한 것으로 간주하지 않는다.

| 소스 | 가져오는 디자인 |
|---|---|
| `app/src/main/java/com/autographer/photo_feedback_agent/ui/theme/Color.kt` | PageWhite, BlockGray, AccentBlue, 유리 시트, 선택 카드 색 |
| `app/src/main/java/com/autographer/photo_feedback_agent/ui/theme/Theme.kt` | 밝은 Material 팔레트, 8/12/16/20dp 및 50% 라운드 |
| `app/src/main/java/com/autographer/photo_feedback_agent/ui/theme/Type.kt` | 시스템 폰트, 14sp 본문, 17sp 카드 제목 |
| `app/src/main/java/com/autographer/photo_feedback_agent/ui/sheet/AssistantSheet.kt` | 28dp 유리 시트, 36×4dp 핸들, 56dp 입력 pill |
| `app/src/main/java/com/autographer/photo_feedback_agent/ui/sheet/EditCard.kt` | 16dp 카드, 연한 파란색 선택·계획 블록 |

동작은 프로젝트의 architecture/specs 및 `work/decisions.md`를 따른다. 편집 도구 순서,
사진 가져오기, 적용·취소, 히스토리, 내보내기, 음성 입력 계약은 스타일 변경으로 바꾸지 않는다.
기존 문서·코드와의 시각적 차이와 이행 기준은 §9에 명시한다.

## 2. Color Palette & Roles

아래 이름은 구현 시 사용할 의미 기반 토큰이다. 기존 이름은 가능한 한 유지하며,
새 토큰은 `core/ui/src/main/kotlin/com/diffuse/core/ui/theme/Tokens.kt`에 모은다.

### Brand & light surfaces

| Token | Value | Role / 참고 앱 대응 |
|---|---|---|
| `accent` | `#387AFF` | 액션·선택 블루 / AccentBlue |
| `accentPressed` | `#2864D9` | 눌림 상태; 편집기용 보완 색 |
| `primaryFill` | `#2864D9` | 작은 흰 글자의 대비를 확보하는 주 버튼 배경; 편집기용 보완 색 |
| `onAccent` | `#FFFFFF` | 주 버튼 위 텍스트·아이콘 |
| `canvasLight` | `#FCFCFF` | 툴바·페이지·입력창 / PageWhite, PillWhite |
| `surfaceSoft` | `#FCFCFF` | Browse 배경 / PageWhite |
| `surfaceCard` | `#F5F4F6` | 카드 내부·썸네일 로딩 배경 / BlockGray |
| `surfaceSecondary` | `#EBEFF5` | 보조 버튼·옵션 트레이 / CarouselTray |
| `hairline` | `#E6E6E9` | 구분선 / DividerGray |
| `ink` | `#000000` | 본문 / TextPrimary |
| `inkSecondary` | `#4D4D52` | 설명·placeholder / TextSecondary |
| `inkMuted` | `#8E8E92` | 비활성·보조 장식 / TextMuted |
| `selectionFill` | `#D9E2F6` | 선택 카드 / SelectedCardFill |
| `selectionBorder` | `#8FB2F5` | 선택 카드 테두리 / SelectedCardBorder |
| `promptTint` | `#D7E4FF` | 기존 요청·추천 문장을 구분할 때의 연한 블루 / UserBubbleBlue |

### Editor & glass

| Token | Value | Role |
|---|---|---|
| `editBackground` | `#1C1C1E` | 사진 외부의 중성 다크 작업 영역; 편집기용 |
| `editSurface` | `#FCFCFF` | 밝은 편집 툴바·도구 스트립 |
| `editSurfaceRaised` | `#F5F4F6` | 편집 컨트롤의 내부 블록 |
| `editHairline` | `#E6E6E9` | 밝은 편집 컨트롤 구분선 |
| `editInk` / `editInkSecondary` | `#000000` / `#4D4D52` | 밝은 편집 컨트롤 텍스트 |
| `canvasOverlayInk` | `#FCFCFF` | 다크 캔버스·진행 scrim 위 텍스트; 컨트롤용 editInk와 분리 |
| `canvasCheckerA` / `canvasCheckerB` | `#2C2C2E` / `#242426` | 투명도 체크보드, 8dp 셀; 편집기용 |
| `sheetGlassTop` | ARGB `#F5F7F8FB` | 시트 상단: 약 96% 불투명 |
| `sheetGlassBottom` | ARGB `#F0E6E9F1` | 시트 하단: 약 94% 불투명 |
| `sheetEdge` | ARGB `#CCFFFFFF` | 유리 시트의 1dp 밝은 테두리 |
| `sheetHandle` | `#B4B6BC` | 시트 핸들 |
| `pillStatus` | `#A8A8AC` | 비활성 상태 아이콘 / PillStatusGray |

- 유리 효과는 위→아래의 약한 수직 그라데이션과 테두리로 표현한다. 배경 blur는 필수가 아니다.
- 사진·썸네일 자체에는 유리 효과, 색 틴트, UI용 그라데이션을 씌우지 않는다.
- `success #1E7A46`, `warning #B8741A`는 기존 의미 색을 유지한다.
  `error #FF3B30`은 참고 앱의 HighlightRed를 오류 역할에 적용한다. 브랜드 블루와 구분한다.
- 작은 링크·오류 문구는 실제 배경에서 4.5:1 대비를 확보한다. 필요하면 더 어두운 의미별
  텍스트 토큰을 사용한다. `inkMuted`, `pillStatus`를 필수 안내·활성 버튼 라벨에 쓰지 않는다.

## 3. Typography

- 참고 앱처럼 Android 시스템 sans-serif를 기본으로 한다. 별도 폰트 다운로드를 요구하지 않는다.
- 기존 Pretendard 중심 스타일에서 시스템 폰트로 전환하는 목표이며, 이번 문서 수정으로 폰트 리소스를 삭제하지 않는다.
- 일반 본문은 14sp, 핵심 제목은 17sp로 정돈한다. 큰 제목은 빈 화면·온보딩에만 사용한다.

| Token | Size / Weight / Line-height | Use |
|---|---|---|
| `headingXl` | 27sp / 700 / 32sp | 빈 화면·온보딩 |
| `headingLg` | 17sp / 700 / 24sp | 시트·카드 제목 |
| `headingMd` | 13sp / 600 / 19sp | 구역 소제목 |
| `bodyMd` | 14sp / 400 / 21sp | 본문·입력 |
| `bodyStrong` | 14sp / 600 / 21sp | 버튼·주요 문구; 편집기용 역할 |
| `bodySm` | 13sp / 400 / 20sp | 설명·메타데이터 |
| `label` | 12sp / 400 / 17sp | 도구 이름·보조 라벨 |
| `labelSmall` | 11sp / 600 / 15sp | 짧은 배지·캡션 |
| `link` | 13sp / 600 / 18sp | 텍스트 액션 |
| `mono` | 13sp / 500 / 18sp | 좌표·HEX·수치; 기존 JetBrains Mono 유지 |

글자 간격은 기본값을 사용한다. 시스템 글꼴 배율을 존중하고, 커진 글자를 고정 높이 때문에 자르지 않는다.

## 4. Component Stylings

### Buttons

- 주 액션과 보조 액션은 완전한 pill(`RoundedCornerShape(percent = 50)`). 기본 높이 48dp.
- `primary`: `primaryFill` + `onAccent`. `secondary`: `surfaceSecondary` + `ink`.
- `tertiary`: 투명 배경, 일반 동작은 `inkSecondary`, 링크 액션은 대비를 확보한 블루.
- `iconCircle`: 사진 위 40dp 원형 시각 영역, 흰색 88% 또는 검정 60% 배경.
  터치 영역은 최소 48×48dp이고 배경에 맞춰 검정/흰색 아이콘을 쓴다.
- 비활성은 기존 38% alpha를 유지하고 색상만으로 활성 상태를 구분하지 않는다.

### Tool strip (bottom of Edit mode)

- 밝은 `editSurface`, 높이 72dp, 가로 스크롤. 항목은 폭 64dp, 아이콘 24dp와 `label`.
- 선택 도구는 블루 아이콘·라벨과 2dp 하단 표시로 구분한다. 기존 선택·스크롤 동작을 유지한다.
- AI 도구의 기존 6dp 점은 블루로 표시한다. 새 배지나 촬영 도구를 추가하지 않는다.
- 목록과 순서는 `specs/tool_groups.md` 및 D092를 따른다.

### Prompt bar (Edit mode, inside a sheet)

- 참고 앱의 흰색 입력 pill: 기본 높이 56dp, 50% 라운드, `canvasLight` 배경.
- 가로 패딩 12dp, 아이콘과 텍스트 사이 8dp. 아이콘 24dp, 터치 영역 48dp.
- 입력은 `bodyMd`/`ink`, placeholder는 `inkSecondary`. 입력창 밑줄은 표시하지 않는다.
- 기존 leading mic / trailing send 배치와 IME Done을 유지한다. 참고 앱의 로고·첨부 메뉴는 가져오지 않는다.
- 기본 아이콘은 무채색, 듣는 중인 mic만 블루. 듣는 동안 leading mic 자리에 stop을 표시하고 trailing send는 유지한다.
- 빈 입력·비활성 상태, 음성 인식기 부재 시 mic 숨김, 음성 최종 결과 제출 동작은
  `specs/prompt_input.md`를 따른다. 광채·맥박 애니메이션은 사용하지 않는다.
- 입력창은 시트의 취소·적용 행과 함께 존재한다. 입력 전송과 편집 적용을 혼동시키지 않는다.

### Bottom sheet (detail adjustments)

- 위 모서리 28dp, `sheetGlassTop`→`sheetGlassBottom` 배경, 1dp `sheetEdge` 테두리.
  참고 앱은 전체 28dp 카드지만, 이 앱의 하단 부착 시트에는 위 모서리만 적용한다.
- 핸들 36×4dp, `sheetHandle`. 내부 가로 패딩 12dp, 구역 사이 16dp.
- 기본 최대 높이 45%. 캔버스를 최소 절반 볼 수 있게 유지한다. 글자 확대·키보드에는
  내부 스크롤과 inset 처리로 입력창·취소·적용에 도달할 수 있게 한다.
- 제목 → 컨트롤 → 고정된 [취소 | 적용]. 시트가 덮은 도구 스트립으로 터치가 통과하면 안 된다.
- 참고 앱의 전체 화면 채팅 확장은 도입하지 않는다. 적용·취소·시스템 Back의 기존 계약을 유지한다.

### Slider

- 트랙 4dp, 흰색 원형 thumb 20dp와 1dp `hairline` 테두리. 활성 구간은 블루.
- 수치는 오른쪽 고정 `mono`. 0 중심 조절에는 중앙 2dp 눈금. 두 번 탭하면 기본값 복원.
- 밝은 시트에서도 트랙·thumb이 구분되어야 하며 터치 높이는 최소 48dp.

### Image tile & option card

- 사진·카드 모서리 16dp. 로딩은 평평한 `surfaceCard`; shimmer 없음.
- 사진 위에 설명을 겹치지 않는다. 메타데이터는 아래에 `bodySm`/`inkSecondary`.
- 옵션 트레이는 `surfaceSecondary`, 선택 카드는 `selectionFill` + 1dp `selectionBorder`.
  선택은 체크·라벨·semantics도 함께 제공한다.
- Browse 길게 누르기의 복제·삭제 아이콘과 삭제 확인은 기존대로 유지한다.
- 참고 앱의 노랑·하늘·연두 장면 태그를 일반 편집 도구에 장식으로 추가하지 않는다.

### Top bar

- Browse: `canvasLight`, 56dp. 기존 `Editor` 표시와 사진 가져오기 진입 구조를 유지한다.
  참고 앱의 대화 목록·새 대화 버튼을 추가하지 않는다.
- Edit: `editSurface`, 56dp. 기존 뒤로·히스토리·원본 비교·내보내기 액션을 유지한다.
  일반 아이콘은 검정, 내보내기는 주 액션 pill.

### State display

- AI 실행: 캔버스 위 검정 40% scrim, 블루 진행 표시, `canvasOverlayInk` 한 줄 설명과 취소.
- 지시 시트의 `보정 추천 받기`는 기존 예외 유지: 추천 영역 안의 `bodySm` 진행 문구와
  tertiary 취소만 표시한다. spinner·추가 accent 없이 입력창을 계속 사용할 수 있다
  (`specs/vibe_edit.md` §14, D091·D094). 전송한 지시의 계획과 적용 실행은 기존 캔버스 overlay를 유지한다.
  검토 결과는 시트 안 스크롤 영역에 `관찰 근거`·`보정 목표`·`보존할 내용`·`실행 단계`·`확인 사항`을
  `bodyStrong` 제목과 `bodyMd`/`bodySm` 줄로 구분하고, 검토 중에는 예시 문장을 숨긴다. 적용은 ready일 때만 활성.
  `실행 단계`의 각 보정은 행 전체가 하나의 스위치(최소 48dp, 상태 설명 `적용함`/`적용 안 함`을 글로도 표시)이며
  보정명·대상·예상/주의·유료/생성 여부·막힌 이유를 `bodyMd`/`bodySm`으로 보여준다. 선택 개수 줄을 두고,
  모두 끄면 `적용할 보정을 선택하세요`와 함께 주 액션을 비활성화한다. 실행·선택 확인 중에는 스위치를 잠근다
  (`specs/vibe_edit.md` §19).
  주 액션은 `선택한 보정 실행` 하나로 로컬 보정·자르기·Topaz까지 끝까지 진행한다(중간 시트·실행·적용 버튼 없음).
  진행은 같은 캔버스 overlay에 `보정을 적용하는 중 (n/m)` 또는 Topaz 단계명(업로드·처리·결과 받는 중, API가 준
  퍼센트만)으로 표시하고 취소를 둔다. 결과는 새 편집본 없이 현재 캔버스에 `후` 라벨로 보이고 기존 `HoldPill`
  전후 비교를 쓴다. Topaz 결과의 보관은 결과 영역의 secondary `고해상도 저장`과 주 액션 `새 편집본으로 열기`,
  다시 처리해야 하면 주 액션이 `선택한 보정 다시 실행`이 된다. 완성되지 않은 결과(로컬만 된 로컬+Topaz)는
  `후`로 표시하지 않는다.
- 오류는 기존 snackbar로 표시한다. 어두운 snackbar에는 밝은 텍스트를 사용한다. toast는 사용하지 않는다.
- 성공·실패를 색상만으로 전달하지 않는다. 진행·오류 문구와 재시도 가능 여부를 명확히 한다.
- 확인 대화상자는 삭제·저장하지 않고 나가기 등 파괴적 동작에 한정한다.

## 5. Layout Principles

- 일반 여백은 4 / 8 / 12 / 16 / 24 / 32dp. 컴포넌트 크기·radius·테두리는 별도 규격을 따른다.
- Browse는 2열 masonry, 열 간격 8dp, 좌우 16dp. 600dp 이상에서는 3열.
- Edit는 상단바 56dp / 남은 캔버스 / 하단 도구 스트립 72dp 구조를 유지한다.
- 사진 주위에는 최소 16dp의 중성 다크 작업 영역을 둔다.
- 터치 영역은 최소 48×48dp. 색·폰트·크기 변경이 기존 접근성 라벨을 제거해서는 안 된다.

## 6. Depth & Elevation

- 유리 시트에만 참고 앱 기준 10dp shadow elevation과 1dp 밝은 테두리를 적용한다.
- 일반 카드·툴바는 평평하게 두고 배경색·간격·hairline으로 구분한다.
- 사진 위 iconCircle은 필요한 경우 약한 2dp elevation만 사용한다.
- 강한 다중 그림자, neon, glow, 포화된 장식 그라데이션은 사용하지 않는다.
- 유리 시트 내부에 불필요한 반투명 카드를 중첩하지 않는다. 본문 가독성이 떨어지면
  내부 컨트롤은 불투명 `surfaceCard` 또는 `canvasLight`로 받친다.

## 7. Do’s and Don’ts

Do
- 쿨 화이트 컨트롤, 검정 본문, 블루 액션을 일관되게 사용한다.
- 유리 시트, 완전 라운드 입력창, 16dp 카드로 참고 앱의 인상을 재현한다.
- AI 작업에는 진행 표시와 취소 수단을 제공한다.
- 원본 비교는 기존 누르고 있는 동안 표시하는 동작을 유지한다.

Don’t
- 기존 Pinterest 레드·따뜻한 크림·올리브색 다크 표면을 새 UI에 혼용하지 않는다.
- 스타일을 맞추기 위해 채팅 기록·카메라·음성 제출 방식 등 제품 동작을 바꾸지 않는다.
- 사진에 블루 틴트나 blur를 적용하지 않는다.
- 새 아이콘 패키지를 혼용하지 않는다. 기존 Material Symbols Rounded를 유지한다.
- “✨ AI Magic” 같은 장식 문구를 쓰지 않는다. 기능 이름은 “배경 제거”, “채우기”, “확대”처럼 쓴다.

## 8. Responsive & Gesture

- 현재 v1은 세로 모드 고정(`specs/editor_shell.md`). 스타일 변경으로 가로 모드를 활성화하지 않는다.
- 600dp+에서 Browse는 3열. 편집 오른쪽 320dp 패널은 향후 반응형 구현 시의 디자인 기준이며,
  이미 구현된 기능으로 간주하지 않는다.
- 캔버스 zoom·pan·fit, 도구별 한 손가락 소유권은 `specs/canvas.md` 및 도구별 스펙을 따른다.
- 상태바는 밝은 툴바에 맞춰 어두운 아이콘을 사용한다. 투명 내비게이션 바의 아이콘은 실제
  아래 표면과 대비를 확보한다. edge-to-edge 및 IME/system inset을 처리한다.

## 9. Agent Prompt Guide & Migration

```text
accent #387AFF · primary fill #2864D9 · page #FCFCFF · block #F5F4F6
canvas surround #1C1C1E · selected card #D9E2F6 / border #8FB2F5
pill 50% · input 56dp · card 16dp · sheet top 28dp · touch 48dp
system sans-serif · body 14/21sp · title 17/24sp
```

### 기존 기준과의 차이

이 요청은 기존 Pinterest 시각 기준을 교체한다. 다음 충돌을 코드 우선으로 되돌리지 않는다.

| 기존 문서·구현 | 새 목표 |
|---|---|
| Tokens.kt의 레드·크림, Theme.kt의 warm dark Edit scheme | §2의 블루·쿨 화이트 컨트롤, 다크 캔버스만 분리 |
| 기존 Pretendard 본문 16sp, 시트 제목 22sp | §3의 시스템 폰트 본문 14sp, 제목 17sp |
| specs/prompt_input.md의 높이 48dp / radius 16dp / 어두운 fill | §4의 높이 56dp / 50% pill / 흰 fill; 제출·음성 계약은 유지 |
| 기존 시트 24dp, 단색, 그라데이션 금지 | §4·§6의 28dp, 절제된 유리 그라데이션과 edge |
| 기존 단일 그림자 수준 | 유리 시트 10dp, 사진 위 iconCircle은 필요 시 2dp |

이번 문서 수정은 위 **시각 규칙**에 대한 변경 요청을 기록한다. 다른 제품·아키텍처 계약은
상위 스펙을 유지한다. 이후 구현할 때는 해당 스펙의 스타일 표·주석과 screenshot golden도
같은 변경에서 동기화해 모순을 남기지 않는다.

### 구현 시 준수 사항

- 기존 `core:ui`의 Tokens, Typography, AppTheme, 공용 컴포넌트를 수정한다.
  Autographer의 별도 테마 계층을 복사하거나 화면별 색상을 하드코딩하지 않는다.
- `ThemeMode.Edit`를 전체 다크 테마와 동일시하지 않는다. 밝은 컨트롤과 다크 캔버스의
  텍스트·표면 역할을 분리하고, 캔버스 overlay·snackbar의 대비를 별도로 확인한다.
- 한국어 사용자 문구는 기존 `strings.xml`을 사용한다.
- 참고 앱에서 그대로 가져온 값과 편집기에 맞춘 보완 값은 §2·§3의 구분을 따른다.
- 실제 구현 검증에서는 Browse, 편집 기본 화면, 열린 시트, 입력/음성 상태, 로딩·오류,
  선택 카드와 큰 글꼴을 확인하고 관련 golden 및 `scripts/check.sh`를 실행한다.
- 문서만 수정한 현재 단계에서는 앱 테마 적용이나 UI 검증 완료를 주장하지 않는다.
