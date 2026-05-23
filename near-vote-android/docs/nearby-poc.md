# Nearby Connections PoC

## Goal

두 대 이상의 Android 기기가 서버 없이 서로를 발견하고 JSON 메시지를 교환할 수 있는지 확인합니다.

## Flow

1. 기기 A에서 `광고 시작`
2. 기기 B에서 `탐색 시작`
3. 연결 요청이 오면 자동 수락
4. `PING 전송`으로 UTF-8 JSON payload 전송
5. 수신 로그 확인

## Message Shape

```json
{
  "type": "PING",
  "senderId": "NearVote-Pixel",
  "payloadJson": "{}"
}
```

웹 프로토타입의 `Poll`, `Vote`, `VoteReceipt`, `ResultBlock`, `Gossip`도 같은 wrapper 안의 `payloadJson`에 실어 보낼 예정입니다.

