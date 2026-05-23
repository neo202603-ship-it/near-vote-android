# Near Vote Android

Android Native Kotlin 기반 Nearby Connections PoC 프로젝트입니다.

목표는 웹 프로토타입의 근거리 네트워크 시뮬레이션을 실제 Android 기기간 발견, 연결, 메시지 교환으로 바꾸는 것입니다.

## 현재 범위

- Android 프로젝트 골격
- Nearby Connections 권한 선언
- 런타임 권한 요청
- 광고/탐색 시작 버튼
- 연결 요청/수락
- UTF-8 JSON 텍스트 payload 송수신
- Poll/Vote/Receipt/Result/Gossip 메시지 타입 초안

## 열기

Android Studio에서 이 폴더를 열고 Gradle sync를 실행합니다.

```text
/Users/neo/Documents/여러가지/near-vote-android
```

이 로컬 환경에는 `gradle` 명령이 없어 터미널 빌드는 아직 확인하지 못했습니다.

## 참고

- Nearby Connections 공식 시작 문서: https://developers.google.com/nearby/connections/android/get-started

