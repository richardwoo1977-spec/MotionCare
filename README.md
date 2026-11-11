<!-- ver 1.1.0 | 2026-11-11 20:45 -->
# 차멀미 케어 (MotionCare)

말 한 마디로 멀미하는 동승자 쪽에 바람을 보내고, 쉬어 갈 곳을 내비 경유지로 넣어 주는 Pleos Connect 차량용 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 무엇을 하나요

- 시동을 걸면 백그라운드 서비스로 켜집니다. 화면을 열지 않아도 Gleo AI 음성으로 시작합니다. 예: "차멀미 케어, 뒷자리 모두 멀미 나", "조수석이 멀미 나".
- **자리는 짐작하지 않습니다.**
  - 말한 자리가 있으면 그 자리로 대응합니다. 한 자리도 되고, "2열 전체", "뒷자리 모두", "조수석 앞뒤" 같은 묶음도 됩니다.
  - 탄 사람이 한 명이면 그 자리로 대응합니다.
  - 그 밖에는 Gleo가 "어느 자리인가요?"라고 되묻습니다(`asking`). 답이 없으면 아무것도 켜지 않습니다.
  - 이 차에 없는 자리(예: 2열 차의 3열)를 말하면 다시 묻습니다.
- 화면에서는 좌석을 눌러 고른 뒤 **멀미 나요** 버튼을 누릅니다. 자리를 고르기 전에는 버튼이 꺼져 있습니다.
- 대응할 때는 내기순환 끄기(바깥 공기), 풍량 최대, 얼굴 쪽 바람을 적용합니다.
  - 공조는 차가 알려 주는 공조 구역 단위로 제어합니다. 좌석 하나만 따로 제어하는 차가 아니면, 그 자리를 포함한 구역 또는 차 전체에 적용합니다.
  - 창문은 앱이 열지 않습니다. 비·눈·매연을 앱이 알 수 없기 때문입니다. 대신 대응을 시작하고 5초 뒤 음성으로 "밖이 괜찮으면 창문을 조금 열어 보세요"라고 권합니다.
- 앞쪽 휴게소 중 화장실이 있는 곳을 권합니다. 20 km 안에 수유실이나 키즈존이 있는 곳이 있으면 먼저 권합니다. 홈 화면의 **쉬어 갈 곳** 카드를 한 번 누르면 내비 경유지로 넣고, 내비가 앞으로 나옵니다.
- "괜찮아졌어요", "차멀미 케어 종료해 줘"라고 하거나 10분이 지나면 원래 설정으로 되돌립니다. 10분이 지나 되돌린 경우에는 좌석 표시를 남겨 둡니다.
- 멀미가 난 시각을 목적지별로 기록합니다. 다음에 같은 목적지로 갈 때는 그 시각보다 5분 앞서 쉬기를 권합니다.

## 실행 방법

### APK로 설치

```
adb -s emulator-5554 install -r apk/BeyondSpark_MotionCare_release.apk
```

설치하면 앱 목록과 하단 바에 **차멀미 케어**가 생깁니다. 앱을 처음 실행하면 타이틀과 안내 화면이 나오고, 그 뒤 홈 화면이 열립니다.

### 소스에서 빌드

1. Android Studio(JDK 17)에서 이 폴더를 엽니다.
2. Pleos Connect Emulator 2.1.0(API 34)을 실행합니다.
3. Run 'app'을 누릅니다.

Pleos SDK(Vehicle, NaviHelper, TextToSpeech)는 `settings.gradle.kts`에 적힌 Pleos 공개 Maven(nexus-playground.pleos.ai)에서 받습니다.

### 단위 시험

```
./gradlew :app:testDebugUnitTest
```

판단 규칙 132개를 시험합니다. 대상은 좌석 결정, 묶음 좌석, 되묻기, 이 차에 없는 자리, 복구, 휴게소 선택, 미리 쉬기 시각, 음성 스키마입니다.

## 시험 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 기기 | Pleos Connect Emulator 2.1.0 (Android Automotive, API 34) |
| 빌드 | Kotlin 2.0, Jetpack Compose, compileSdk 35, minSdk 29, JDK 17 |
| 차량 SDK | Pleos Vehicle SDK 2.3.2 |
| 내비 | Pleos NaviHelper 3.0.5 (경유지 추가) |
| 음성 안내 | Pleos TextToSpeech 3.3.1 (on-device) |
| 음성 호출 | Gleo AI App Schema `app/src/main/assets/schema.json` (version 0.3.1) |

- **음성 호출:** 에뮬레이터의 Extended Controls에서 호스트 마이크를 켜야 합니다. Gleo가 말을 어느 앱으로 보낼지는 Gleo가 판단합니다. 앱 이름("차멀미 케어")을 함께 말하면 가장 확실합니다.
- **좌석 시험:** 에뮬레이터가 보고하는 좌석 착석 값으로 동작합니다. 개발 시험은 조수석과 2열 두 자리에 사람이 앉은 상태에서 했습니다.
- **공조 구역:** 에뮬레이터는 공조 전원과 내기순환을 차 전체 한 구역(5자리)으로, 풍량을 앞줄 한 구역으로 보고합니다. 좌석별 공조는 차종에 따라 다르며, 앱은 차가 알려 준 구역에 맞춰 적용합니다.
- **위치와 휴게소:** 현재 위치는 시연용 가상 위치입니다. 휴게소 목록은 한국도로공사 공공데이터(기준일 2026-09-17, 215건)를 앱에 넣어 둔 것입니다. 경유지는 NaviHelper `addWaypoint`로 넣습니다. 에뮬레이터에서는 요청과 내비 화면 전환까지 확인했고, 목적지가 정해진 경로에 실제로 경유지가 들어가는지는 확인하지 않았습니다.
- **권한:** `AndroidManifest.xml`에 차량 권한(CAR_SPEED, CAR_POWERTRAIN, pleos.car.permission.CAR_SEATS · CAR_CLIMATE · CONTROL_CAR_CLIMATE · CAR_WINDOWS · CONTROL_CAR_WINDOWS 등), 내비 권한(NAVI_ROUTE, NAVI_ROUTE_SEARCH), 음성 권한(TTS_SERVICE)을 선언했습니다.

## APK 위치

`apk/BeyondSpark_MotionCare_release.apk` (release 빌드)

## 폴더 구조

```
app/src/main/java/app/motioncare/
  core/     판단 규칙 (Android 의존 없음, 단위 시험 대상)
  car/      차량 SDK 연결 (공조, 좌석, 기어·속도), 내비 연결 (경유지)
  service/  백그라운드 서비스, 부팅 수신, Gleo 호출 수신, 음성 안내
  ui/       Compose 화면
  data/     상태 공유, 저장, 휴게소 자료
app/src/main/assets/
  schema.json      Gleo App Schema
  rest_areas.csv   휴게소 자료
```
