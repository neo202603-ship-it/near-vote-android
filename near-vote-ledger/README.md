# Near Vote Ledger

서버 없이 근거리 사용자끼리 설문을 만들고, 참여자가 각자 검증 가능한 투표 원장을 공유하는 앱 프로토타입입니다.

현재 버전은 브라우저에서 바로 실행되는 정적 MVP입니다. BLE/Nearby 연결은 아직 실제 기기 API에 붙이지 않고, 근거리 참여자와 메시지 전파를 시뮬레이션합니다. 대신 투표 데이터, 서명, 블록 해시, 최종 원장 검증 흐름을 먼저 확인할 수 있습니다.

## 실행

`index.html` 파일을 브라우저에서 열면 됩니다.

```sh
open /Users/neo/Documents/여러가지/near-vote-ledger/index.html
```

## MVP 범위

- 설문 생성
- 근거리 참여자 초대 시뮬레이션
- 참여자별 일회용 키 생성
- 투표 서명
- 제한 시간 이후 제안자 블록 생성
- 모든 참여자 원장에 결과 블록 공유
- 블록 해시와 투표 포함 여부 검증

## 다음 단계

- Android Nearby Connections 또는 BLE 기반 실제 발견 계층 붙이기
- iOS MultipeerConnectivity 검토
- 투표 접수증과 누락 이의 제기 흐름 추가
- 익명 투표를 위한 commit-reveal 방식 추가
- React Native 또는 Kotlin Multiplatform 앱으로 확장

