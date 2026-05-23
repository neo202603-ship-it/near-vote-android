# Protocol Sketch

## Actors

- Proposer: 설문을 만들고 제한 시간 이후 결과 블록을 생성합니다.
- Participant: 설문에 참여하고 투표에 서명한 뒤 최종 블록을 검증합니다.
- Local mesh: 서버 대신 근거리 기기 간 메시지를 전파하는 임시 네트워크입니다.

## Poll

```json
{
  "pollId": "poll_...",
  "question": "오늘 점심 메뉴는?",
  "options": ["한식", "일식", "중식"],
  "deadline": "2026-05-23T03:00:00.000Z",
  "proposerPublicKey": "..."
}
```

## Vote

```json
{
  "pollId": "poll_...",
  "voterId": "device_...",
  "voterPublicKey": "...",
  "choice": "한식",
  "createdAt": "2026-05-23T02:57:00.000Z",
  "signature": "..."
}
```

## Result Block

```json
{
  "index": 1,
  "pollId": "poll_...",
  "previousHash": "GENESIS",
  "createdAt": "2026-05-23T03:00:10.000Z",
  "proposerId": "device_proposer",
  "votesRoot": "...",
  "result": {
    "한식": 3,
    "일식": 2
  },
  "blockHash": "...",
  "proposerSignature": "..."
}
```

## Trust Model

첫 MVP는 제안자가 블록 생성자 역할을 합니다. 참여자는 최종 블록을 받은 뒤 다음을 확인합니다.

- 블록 해시가 본문과 일치하는가
- 제안자 서명이 유효한가
- 내 투표가 최종 투표 목록에 포함되어 있는가
- 동일 voterId의 중복 투표가 없는가

제안자가 투표를 누락할 수 있는 문제는 남아 있습니다. 다음 버전에서는 투표 접수증과 참여자 간 vote hash gossip을 추가해 누락을 탐지합니다.

