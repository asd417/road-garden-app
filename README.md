# 로드 가든 (Road Garden)

안전하게 달릴수록 내 차의 가상정원에 잎사귀와 꽃이 피어나는, 잔소리 대신 칭찬으로 좋은 운전습관을 만드는 Pleos Connect 정원 앱입니다.

![엠블럼 정원 만개](demo/09_성장4_만개.png)

## APK

`apk/이영훈_로드가든_release.apk` (release 빌드, v2·v3 서명, minSdk 28 / targetSdk 35)

## 실행 방법

1. Pleos Connect 에뮬레이터를 켭니다 (아래 테스트 환경).
2. APK를 설치합니다. `-g`는 속도·충전·위치 권한을 미리 허용합니다.
   ```
   adb install -g apk/이영훈_로드가든_release.apk
   ```
3. 앱 목록에서 **Road Garden**을 실행합니다. 처음 실행하면 정원은 덩굴 새싹부터 시작합니다.
4. 차량 값은 Virtual Console로 바꿉니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
   PC 브라우저에서 `http://127.0.0.1:8088/` → 기어(P/D), 속도, 브레이크, 와이퍼, 충전 스위치·충전구를 바꿔 가며 확인합니다.
   - 기어 D + 부드럽게 속도 올리기: 물방울이 쌓이고 정원이 자람 (주행 중엔 글자 없는 어두운 정원 화면)
   - 1초에 12km/h 이상 급가속·급제동: 잎이 누렇게 움츠러들고 성장도가 줄어듦
   - 속도 0 (기어 D): 신호 대기
   - 기어 P: 모은 물방울이 자동으로 정원에 들어가고 칭찬 자막·음성
   - 와이퍼 Low: 비 효과와 "비가 와서 정원이 촉촉해졌어요."
   - 충전구 열기 + 충전 스위치 켜기: 충전 화면, 「정원 꾸미기」로 배경(별빛·숲속·연못·벚꽃) 변경

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
| --- | --- |
| Pleos Connect 에뮬레이터 | v2.1.0 (Android 14, API 34), 시스템 이미지 `Pleos Connect v2.1.0 APIs` |
| 화면 규격 | 2560×1440, 160dpi. 앱 영역 1682×1210 (왼쪽 주행 정보 패널 옆) |
| AVD 설정 | Automotive, RAM 4GB, Default boot = Cold |
| 네트워크 | 날씨 조회에만 사용 (현재 위치를 약 1km로 반올림해 Open-Meteo에 조회). 없어도 정원은 동작 |
| 음성 | Android 기본 TTS. Pleos 에뮬레이터에는 앱이 쓸 수 있는 TTS 엔진이 없어 자막만 표시됨 (Pleos TextToSpeech SDK 공개 후 교체 예정) |
| 정원 저장 | 성장도·물방울·날별 운전 기록은 앱 안에 저장되어 다시 실행해도 이어짐 |

에뮬레이터가 Windows 절전 후 꺼지면 `emulator -avd <이름> -gpu host -feature -Vulkan`으로 실행합니다.

## 주요 기능

- **엠블럼 정원**: 덩굴이 엠블럼 뒤에서 자라 나와 링과 H 사이를 엮으며 뻗고, 잎이 채워진 뒤 백합·코스모스·별꽃·목련이 핍니다.
- **상태별 안전 설계**: 주행 중엔 터치·글자 없이 아이콘만 있는 어두운 화면 (플랫폼의 주행 중 UX 제한 상태를 따름), 주차·충전 중에만 전체 화면과 칭찬.
- **자동 물 주기**: 주차·충전하면 주행 중 모은 물방울이 버튼 없이 정원에 들어갑니다.
- **나비**: 운전한 날 기준 7일 연속 급가감속 0회이면 나비가 찾아오고, 피어 있는 꽃에만 앉습니다.
- **충전 중 꾸미기**: 정원 배경 5종.

## 사용한 차량 데이터

| 값 | 읽는 방법 |
| --- | --- |
| 속도·기어·주차 브레이크·충전 상태·주행 중 UX 제한 | Android Automotive 표준 (`CarPropertyManager`, `CarUxRestrictionsManager`) |
| 브레이크 페달·와이퍼 | Pleos Vehicle SDK 2.3.2 |
| 날씨·기온 | 현재 위치 + Open-Meteo |

## 소스 구성

| 경로 | 내용 |
| --- | --- |
| `android/` | Android 앱 (Kotlin). 정원 화면은 WebView로 띄우고 차량 값을 넘김. three.js는 앱 안에 포함 |
| `prototype.html` | 정원 화면 본체 (three.js). 빌드할 때 APK 안으로 복사됨. 브라우저로 열면 차량 시뮬레이터와 함께 실행 |
| `flower_model.js` | 꽃 4종 3D 모델 |
| `logo_mesh.js` | 엠블럼 3D 메시 |
| `demo/` | 화면 스크린샷 (설명은 `demo/README.md`) |

직접 빌드: JDK 17, Android SDK 35, `android/local.properties`에 `sdk.dir=<SDK 경로>` 후 `cd android && gradlew assembleDebug`. debug 빌드에는 시연용 디버그 패널(🐞)이 있습니다.
