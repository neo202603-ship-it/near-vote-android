# Roadmap

## Current Prototype

- Vanilla JavaScript SPA
- LocalStorage 기반 템플릿과 원장 저장
- Web Crypto 기반 ECDSA 서명, SHA-256 해시
- 근거리 네트워크는 시뮬레이션
- 제안자도 투표 참여 가능
- `src/core/protocol.js`에 Poll, Vote, ResultBlock 생성/검증 로직 분리
- 투표 직후 VoteReceipt 생성 및 결과 블록 포함 여부 확인
- 참여자 간 vote hash gossip 시뮬레이션 및 결과 블록 누락 감사

## Next

- Android Nearby Connections proof of concept
- iOS MultipeerConnectivity proof of concept
- 결과 누락 이의 제기 화면 추가
- React Native 또는 Kotlin Multiplatform 전환
