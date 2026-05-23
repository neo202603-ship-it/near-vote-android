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
- 한 기기에서 설문/투표/영수증/결과 블록을 확인하는 로컬 시뮬레이션
- 홈, 설문 작성 미리보기, 주변 투표, 결과, 개발자 진단 화면 분리

## 열기

Android Studio에서 이 폴더를 열고 Gradle sync를 실행합니다.

```text
/Users/neo/Documents/여러가지/near-vote-android
```

Android Studio의 내장 JBR을 사용하면 터미널에서도 Gradle Wrapper로 빌드할 수 있습니다.

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

## 한 기기 테스트

안드로이드 기기가 1대뿐이면 `로컬 시뮬레이션 실행`을 눌러 개발을 이어갑니다.
이 모드는 가상 참여자 3명을 만들고 설문 생성, 투표, 영수증, 결과 블록 로그를 한 번에 보여줍니다.
실제 무선 연결 검증은 아니지만, 두 번째 기기를 확보하기 전까지 앱 흐름과 원장 메시지 구조를 다듬는 데 사용합니다.

## 화면 흐름

- 홈: 내 아이디, 새 설문 만들기, 주변 투표 찾기, 로컬 시뮬레이션, 개발자 진단
- 설문 작성: 점심메뉴 템플릿 기반 작성 미리보기
- 주변 투표: 실제 Nearby 탐색으로 이어질 자리
- 시뮬레이션 결과: 참여자, 선택지, 결과, 영수증/해시 요약
- 개발자 진단: 광고, 탐색, PING, 상세 로그

홈과 주요 화면은 사용자 행동 중심 카드로 구성하고, Nearby 광고/탐색/PING 같은 개발용 기능은 개발자 진단 화면에만 둡니다.

## 참고

- Nearby Connections 공식 시작 문서: https://developers.google.com/nearby/connections/android/get-started
