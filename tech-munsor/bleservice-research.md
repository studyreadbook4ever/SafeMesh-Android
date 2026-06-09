# 근거리 통신 서비스를 지원하는 다른 프로그램들에 대한 조사를 진행한 내용입니다.

> 인터넷 없이 블루투스(특히 BLE) 메시 네트워크로 통신하는 서비스들에 대한 리서치 자료입니다. bitchat 이전에도 비슷한 시도들이 여러 번 있었고, 각각 어떤 강점과 한계가 있었는지 정리했습니다.
> 

---

## 사전 지식: BLE 메시 메시징이 뭔가요?

BLE(Bluetooth Low Energy)는 우리가 흔히 쓰는 블루투스의 저전력 버전입니다. 이어폰·스마트워치 같은 기기에서 쓰이는 그 블루투스 맞습니다.

**기본 아이디어**는 이렇습니다:

- 스마트폰끼리 직접 BLE로 연결합니다 (서버·인터넷 없이).
- 직접 도달 거리는 보통 **30~100m** 수준입니다.(직접 ble기반서비스 활용해보니 벽이 끼어있으면 전파수율 확 떨어지고 보통 안정적으로 메시지를 받는 마지노선이 갤럭시 S24U x S24U 기준 차폐 없이 18~20m정도였습니다)
- 이걸 넘기 위해 **멀티홉(multi-hop) 릴레이**를 씁니다. 즉, A → B → C → D 식으로 중간 사람들의 폰이 메시지를 받아 다시 뿌려주는 방식이에요.
- 이렇게 폰들이 서로 연결돼 만들어진 임시 네트워크가 **메시(mesh) 네트워크**입니다.

**왜 필요한가요?**

- 인터넷·셀룰러 망이 끊기는 상황 (재난, 산속, 비행기, 페스티벌 인파 폭주, 통신 차단 등)
- 정부 주도 인터넷 셧다운에 대한 저항 수단
- 서버에 의존하지 않는 검열 저항·프라이버시

이제 이 컨셉으로 만들어진 주요 서비스들을 하나씩 보겠습니다.

---

## 1. FireChat (2014~2019, 단종)

블루투스 메시 메시징을 대중에게 처음 알린 **선구자**입니다.

| 항목 | 내용 |
| --- | --- |
| 개발사 | Open Garden (미국) |
| 출시 | 2014 |
| 현재 상태 | **2019년 서비스 종료** |
| 통신 방식 | Bluetooth + Wi-Fi Direct |
| 직접 도달 거리 | 약 30~100m |

**특징**

- 블루투스와 Wi-Fi Direct를 동시에 활용해서 멀티홉 메시를 구성했습니다.
- 사용자가 많아질수록 네트워크가 자동으로 커지는 구조였어요.
- 공개 채팅(주변 모든 유저가 봄)과 1:1 암호화 메시지를 모두 지원했습니다.

**유명한 사용 사례 — 2014 홍콩 우산 시위**

- 시위 중 통신망이 마비될 것을 우려한 시위대가 단 하루 만에 10만 회 넘게 다운로드하면서 화제가 됐습니다.
- 이 사건으로 "BLE 메시 = 시위 도구"라는 이미지가 처음 만들어졌습니다.

**한계 — 왜 사라졌나**

- **밀집 환경에서의 RF 간섭**: 사람이 너무 많이 모이면 블루투스 신호들이 서로 간섭해서 메시지가 수십 초씩 지연되거나 유실됐습니다.
- **배터리 소모 심각**: 백그라운드에서 계속 스캔/광고를 돌려야 해서 배터리가 빠르게 소모됐습니다.
- 평상시에는 쓸 일이 거의 없어 사용자가 빠지면서 결국 종료됐습니다.

> 💡 **시사점**: 위급 상황 한 번에만 폭발하는 앱은 평상시 사용자 풀이 없으면 메시 자체가 작동을 안 합니다. "평상시 사용 동기"를 어떻게 만들지가 중요한 과제예요.
> 

📚 **참고**

- Why P2P Mesh Network Chats Like 'bitchat mesh' Haven't Succeeded… Yet (Blake's Notes) — FireChat·Bridgefy 실패 원인 분석
- 3 messaging apps that don't rely on internet connection (Daily Star)
- 4 Apps To Chat And Text With No Internet Connection Via Mesh Network (Gecko & Fly)

---

## 2. Bridgefy (2014~현재)

FireChat 이후 가장 널리 알려진 BLE 메시 앱이고, 여러 시위에서 실전 사용된 케이스입니다.

| 항목 | 내용 |
| --- | --- |
| 개발사 | Bridgefy (멕시코) |
| 출시 | 2014 |
| 현재 상태 | 운영 중 (앱 + SDK 라이선스 사업) |
| 통신 방식 | Bluetooth (BLE / Classic) |
| 직접 도달 거리 | 약 100m |
| 누적 다운로드 | 1,200만 이상 (2024 기준) |

**특징 — 3가지 모드 제공**

1. **Person-to-Person 모드**: 1:1 직접 통신
2. **Mesh 모드**: 중간 사용자들을 거쳐 멀리까지 메시지 릴레이
3. **Broadcast 모드**: 주변 모든 Bridgefy 사용자에게 메시지 일괄 전송 (재난 알림, 공지에 유용)

**실전 사용 사례**

- 2017 멕시코시티 지진 (재난 통신)
- **2019~2020 홍콩 시위**: 사용량 4,000% 폭증
- 인도 시민권 개정 시위
- 짐바브웨, 벨라루스, 이란, 미얀마 시위
- 위 사례들로 "시위 앱"으로 유명세를 탔습니다.

**🚨 결정적인 보안 문제 (꼭 알아야 함)**
2020년 영국 Royal Holloway 대학의 보안 연구진이 Bridgefy를 분석한 논문 "**Mesh Messaging in Large-scale Protests: Breaking Bridgefy**"를 발표했습니다. 결과는 충격적이었어요:

- 사용자 추적 가능
- 메시지 위조 (impersonation) 가능
- 종단간 암호화가 사실상 무력
- 악의적인 메시지 하나로 전체 네트워크 중단 가능 (DoS)

이후 Bridgefy는 2020년 10월 **Signal 프로토콜**을 도입했지만, **2022년 같은 연구진이 "Signal 프로토콜을 잘못 적용해서 여전히 취약하다"는 후속 논문**을 냈습니다. 2023년에 7asecurity의 펜테스트로 일부 문제는 수정됐지만 여전히 보안 신뢰도는 낮은 편입니다.

> 💡 **시사점**: "큰 행사·재난용"으로 만든 앱이 시위·검열 회피 같은 **공격자가 존재하는 환경**으로 쓰임이 바뀌면, 원래 설계로는 절대 못 막습니다. 처음부터 위협 모델을 분명히 설정해야 합니다.
> 

📚 **참고**

- Bridgefy — Wikipedia — 회사 개요, 주요 사건 타임라인
- Mesh Messaging in Large-Scale Protests: Breaking Bridgefy (논문 PDF, IACR ePrint) — 2020 보안 분석 원문
- Springer 게재 버전 (Financial Cryptography 2021)
- 저자 블로그 — malb::blog — 연구 배경, 비전문가용 요약
- Bridgefy FAIL: Insecure for Use in Protests (Security Boulevard)
- Protest App Bridgefy Riddled with Vulnerabilities (Adam Levin)
- Hong Kong Protestors Using Bluetooth Mesh App 'Bridgefy' (UMA Technology) — 홍콩 시위 사용 맥락

---

## 3. Briar (2018~현재)

프라이버시·보안 자체에 가장 진심인 앱입니다. 활동가·기자 대상으로 설계됐어요.

| 항목 | 내용 |
| --- | --- |
| 개발사 | Briar Project (오픈소스) |
| 통신 방식 | Bluetooth Classic + Wi-Fi + **Tor** |
| 플랫폼 | Android (iOS 미지원) |
| 라이선스 | 오픈소스 (GPL) |

**특징**

- 인터넷이 있을 때는 **Tor 네트워크**로 동기화 → 익명성 보장
- 인터넷이 없을 때는 **Bluetooth / Wi-Fi**로 동기화
- 메시지를 클라우드가 아닌 **사용자 기기에만** 저장 (서버 자체가 없음)
- 종단간 암호화 기본

**⚠️ 주의 — Briar는 엄밀히 말해 "메시"가 아닙니다**
연구 논문에 따르면 Briar는 **블루투스 클래식**을 써서 **점대점(point-to-point) 연결**만 합니다. 즉, A와 B가 직접 블루투스 거리 내에 있어야 하고, **자동 멀티홉 릴레이는 지원하지 않습니다**. 더 멀리 보내려면 사람이 수동으로 중계해야 해요.

**한계**

- iOS 미지원 (애플의 BLE 백그라운드 제약 때문)
- 일반 사용자에게 UX가 어렵습니다 (Tor 동기화 등)
- 첨부파일 송수신 불가 (보안상 의도된 제한)

> 💡 **시사점**: 보안성은 가장 강하지만 진짜 메시는 아니라는 점, 그리고 일반인 대상 UX의 어려움이 교훈입니다.
> 

📚 **참고**

- Use Briar instead of Bridgefy (Paolo Redaelli) — Briar vs Bridgefy 비교
- IACR 논문 §2.4 Briar 분석 부분 — Briar가 메시가 아니라 1홉 P2P라는 것을 짚은 학술적 근거
- Bridgefy Alternatives — AlternativeTo — Briar 위치/평가

---

## 4. Berkanan (2019~)

오픈소스 SDK 형태로 제공되는 BLE 메시 메시징 프레임워크입니다.

| 항목 | 내용 |
| --- | --- |
| 개발자 | Zsombor Szabo (개인 개발자) |
| 라이선스 | 오픈소스 (Swift Package) |
| 직접 도달 거리 | 약 70m |
| 형태 | SDK + 레퍼런스 앱 (Berkanan Messenger) |

**특징**

- 누구나 자기 앱에 BLE 메시 기능을 붙일 수 있게 SDK로 제공됩니다.
- iOS 네이티브(Swift)로 구현돼 iOS 친화적입니다.
- SDK가 깔린 앱들이 많아질수록 메시 도달 범위가 자동으로 늘어나는 구조 (생태계형 접근).

**한계**

- 아직 사용자 풀이 작아서 실전 도달 거리가 제한적
- SDK를 채택한 메이저 앱이 거의 없음

> 💡 **시사점**: "내 앱 하나"가 아니라 **"표준 SDK"** 형태로 가면 채택만 되면 자동 확장이 가능하지만, 그 채택이 가장 큰 허들입니다.
> 

📚 **참고**

- BerkananSDK GitHub 공식 저장소 — README, API 문서, 도달 거리 70m 명시 출처

---

## 5. Berty (2018~현재)

분산형 P2P 메시징을 표방하는 오픈소스 프로젝트입니다.

| 항목 | 내용 |
| --- | --- |
| 개발 | Berty Technologies (프랑스, 비영리) |
| 통신 방식 | Bluetooth + IPFS + Wi-Fi |
| 라이선스 | 오픈소스 |
| 플랫폼 | iOS, Android |

**특징**

- 자체 프로토콜인 **Berty Protocol** 위에서 동작합니다.
- 인터넷이 있을 때는 **IPFS**(분산 파일 시스템) 기반의 P2P 통신을 활용합니다.
- 인터넷이 없을 때는 BLE로 폴백(fallback)합니다.
- 계정·전화번호 불필요, 메타데이터 최소화

**특이점 — 프라이버시 마인드셋**

- "신뢰가 필요 없는(trustless) 통신"을 목표로 합니다.
- 서버, 인터넷, 네트워크 신뢰 없이도 동작 가능한 설계.

**한계**

- 사용자 수가 매우 적음
- BLE 메시 자체보다는 P2P 전반을 다루는 프로젝트라 BLE 부분의 성숙도는 상대적으로 낮음

📚 **참고**

- Bridgefy Alternatives — AlternativeTo — Berty 항목 (오픈소스, 프라이버시 중심)

---

## 6. Meshtastic (2020~현재) — 보너스: LoRa + BLE 하이브리드

엄밀히 BLE 메시는 아니지만 매우 중요해서 추가합니다.

| 항목 | 내용 |
| --- | --- |
| 개발 | Kevin Hester + 오픈소스 커뮤니티 |
| 통신 방식 | **LoRa (메시) + BLE (폰 연결용)** |
| 직접 도달 거리 | 수 km ~ 수십 km |
| 플랫폼 | iOS, Android (전용 LoRa 하드웨어 필요) |

**특징 — 가장 중요한 차이**

- 폰끼리 직접 BLE로 통신하지 **않습니다**.
- 대신 **LoRa 라디오 모듈(저렴한 ESP32 기반 보드)**을 메시 노드로 사용하고, 그 노드와 폰을 **BLE로 연결**합니다.
- LoRa는 BLE보다 훨씬 멀리 갑니다 (km 단위). 따라서 진짜 광범위한 오프그리드 통신이 가능해요.
- 등산, 패러글라이딩, 스키, 재난 대응에 실제 사용 중

**한계**

- 별도 하드웨어 필요 (보드 가격 ~$30)
- BLE 메시 앱들과는 완전히 다른 카테고리

> 💡 **시사점**: "폰만으로 진짜 멀리 가기"는 BLE만으로는 한계가 명확하다는 반증입니다. 하드웨어를 끌어들이면 도달 거리가 폭발적으로 늘어납니다.
> 

📚 **참고**

- Meshtastic 공식 사이트
- Meshtastic — Wikipedia — LoRa + BLE 구조, ESP32/nRF52840 하드웨어 명세
- Meshtastic 공식 GitHub — 펌웨어, 클라이언트
- Meshtastic 101: Off-Grid Chat Without Cell Service (Dave Wigstone) — 학부생 친화적 기술 가이드

---

## 7. 기타 / 사실상 동작 안 함

리서치 중 나오긴 하지만 추천하기 어려운 옵션들입니다.

- **Signal Offline Messenger** (이름과 다르게 Signal 본가와 무관, Wi-Fi Direct 위주)
- **Vojer** (iOS 전용, 업데이트 멈춤)
- **Hype SDK by HypeLabs** (Bridgefy 유사, 채택 사례 미미. IACR 논문 §2.4에서 언급)
- **AirChat / Bluetooth Chat** (도달 거리 100m, 단순 P2P)
- **Serval Mesh** (호주 프로젝트, 사실상 중단)

📚 **참고**

- 5 Best Offline Messaging Apps (Droid Guy)
- 6 Best Offline Messaging Apps (Tweak Library)
- Bluetooth Chat Apps Compared: BitChat, Bridgefy, Briar & More

---

## 8. bitchat (2025~) — 비교 기준

리서치의 출발점이었던 bitchat의 핵심 사양입니다.

| 항목 | 내용 |
| --- | --- |
| 개발 | Jack Dorsey (Block Inc., 트위터 공동창업자) |
| 출시 | 2025년 7월 |
| 통신 방식 | BLE 메시 + Nostr (인터넷 폴백) |
| 멀티홉 | **최대 7 hops** |
| 암호화 | Noise Protocol Framework |
| 추가 기능 | LZ4 압축, Store-and-forward 캐시(12시간), 패닉 모드(트리플 탭으로 데이터 삭제) |

**특이점**

- 이전 세대(Bridgefy 등)와 가장 큰 차이는 **하이브리드 구조** — BLE가 안 되면 Nostr 프로토콜(인터넷 기반 분산 SNS)로 폴백.
- 마다가스카르(2025.9), 네팔 시위(2025.9 하루 5만 다운로드), 우간다·이란 인터넷 차단(2026.1) 시기에 실사용됐습니다.
- 출시 직후 사용자 사칭 취약점이 발견되어 보안 감사 미완 상태가 알려진 상황.

**실사용 후기**

주제가 제대로 정해져있지 않은 시점에 bitchat을 직접 실사용해봤다. 부모님 폰으로 bitchat 앱을 깔아서 이것저것 해봤는데 가장 큰 문제지점은 ‘내가 이 사람이랑 통신해야지’에 대한 ux가 특히 엉망이였다.

그리고 주제를 짜는 초기에 리서치 과정에서 비트챗은 7홉까지 ‘직접 연결이 안 된 상태’더라도 p2p로 장비간 라우팅 방식으로 통신이 전달된다는 내용을 봤었는데, 이상적이고 이론적인 얘기지 활용도가 많이많이 떨어진다. 아예 탄탄한 메시를 기대했는데 broadcast 띡 시도해보고 안되면 포기하는 그런 방식이 많이 아쉽다. 7홉 전달을 허용한다지만 비트챗 어플리케이션이 백그라운드로 돌아가는 사람이 hop으로 20미터 간격 안으로 전달을 해줘야만 통신이 가능하며 따라서 ‘통신을 받았다’를 보장받지 못한다. DM에선 홉이 15초간 메시지 내용을 보관했다가 메시에 새로운 엔드디바이스 붙을 시에 거기에도 전달하는 그런 인터벌을 두기는 함.

또한 bitchat이 private한 dm을 다른 장비들을 패킷이 거쳐만 가는 방식으로 지원하긴 하지만 활용도가 굉장히 낮다. 비트챗이 UX적으로 ~~랑 통신해야지 가 자유롭지 못하며, 마찬가지로 이 지점에서도 mesh 네트워크 형성이 튼튼하지 못하면서 동시에 ‘보냄’이 ‘받음’을 보장하지 못하기에 그렇다.

📚 **참고**

- bitchat 공식 GitHub (permissionlesstech/bitchat) — 7홉, Noise Protocol, LZ4 등 명세 출처
- BitChat — Wikipedia — 실사용 사례 (마다가스카르, 네팔, 이란 등) 출처
- bitchat 공식 페이지
- What is Bitchat? — TechTarget — 학부생 친화적 작동 원리 설명
- How Jack Dorsey's new app lets you chat without the internet — Cointelegraph

---

## 9. 한눈에 비교표

| 서비스 | 출시 | 상태 | 통신 | 멀티홉 | 보안 | 플랫폼 | 핵심 특징 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FireChat | 2014 | **종료** | BT+Wi-Fi | O | 약함 | iOS/Android | 최초 대중화 |
| Bridgefy | 2014 | 운영 | BLE/Classic | O | **취약 사례 다수** | iOS/Android | 시위 앱으로 유명 |
| Briar | 2018 | 운영 | BT+Wi-Fi+Tor | **X** (1홉) | 강함 | Android only | Tor 통합, 활동가용 |
| Berkanan | 2019 | 운영 | BLE | O | 보통 | iOS 중심 | SDK 형태 |
| Berty | 2018 | 운영 | BLE+IPFS | O | 강함 | iOS/Android | 분산형, IPFS 기반 |
| Meshtastic | 2020 | 활성 | **LoRa**+BLE | O (km단위) | 강함 | 하드웨어 필요 | km 단위 도달 |
| bitchat | 2025 | 활성 | BLE+Nostr | O (7홉) | 미완 | iOS/Android | 하이브리드 폴백 |

---

## 10. 공통적으로 드러난 한계와 시사점

여러 서비스를 종합하면 **BLE 메시가 매번 부딪히는 벽**이 보입니다. 우리 프로젝트에서 미리 고려해야 할 포인트들입니다.

### 기술적 한계

1. **iOS 백그라운드 제약**: 애플은 배터리·프라이버시 명목으로 백그라운드 BLE 스캔을 강하게 제한합니다. 폰을 꺼두거나 앱이 닫혀 있으면 메시지를 못 받는 경우가 많아요. (근거: Blake's Notes)
2. **운영체제별 파편화**: 제조사마다 BLE 동작이 달라 일관된 동작 보장이 어렵다.
3. **밀집 환경에서의 RF 간섭**: 사람이 많을수록 좋아져야 하는데 실제로는 신호 충돌로 더 느려지는 역설. (FireChat 홍콩 시위 사례)
4. **모빌리티 문제**: 사람이 걷거나 차로 이동하면 메시 링크가 계속 끊겼다 붙었다 합니다.
5. **배터리 드레인**: 항시 스캔/광고는 배터리에 치명적이다. 그나마 이 지점은 오프라인 블루투스 환경에선 배터리를 그나마 덜 쓸 low level최적화 지점이 많이 존재한다.

### 채택·UX 한계

1. **닭과 달걀 문제**: 평상시 사용자가 적으면 위급할 때 주변에 노드가 없어서 메시가 형성되지 않습니다.
2. **위급 상황 한정 사용 → 종료**: FireChat 사례처럼 평상시 동기가 없으면 결국 단종됩니다.
3. **UX 어려움**: 익명 통신을 위해 ‘나는 이 사람과 통신하겠어’를 익명성 보장과 함께 구현하는 것도 어렵고 메시지를 보낸 것에 대한 수신의 멱등성 보장이 어렵다. 

### 보안 한계

1. **"오프라인 = 안전"이 아님**: BLE 도달 범위 안에 공격자가 있으면 그대로 노출됩니다. broadcast방식의 통신을 하는 한 중간자공격 문제는 항상 존재한다.
2. **위협 모델 설정의 중요성**: Bridgefy처럼 "재난용"으로 만들어 놓고 시위에서 쓰이면 보안이 무너집니다. (근거: Breaking Bridgefy 논문)

### 정책·구조 한계

1. 주파수 정책: 장거리 통신을 사설로 시도하는 경우 이 지점에선 국가별 규제에 의한 문제가 발생할 수 있다. 
2. **표준 부재**: 각 앱이 자기 프로토콜을 써서 상호운용이 안 됩니다 (한 동네에 다른 앱 쓰는 사람들끼리는 통신 불가).

---

## 11. 우리 프로젝트가 가져갈 만한 교훈

리서치를 종합한 결론입니다.

- ✅ **위협 모델을 명확히 정하자**: "누가 공격자인가"를 기획 단계에서 정의해야 보안 설계가 흐트러지지 않습니다.
- ✅ **하이브리드 구조 검토**: bitchat처럼 BLE가 안 될 때의 폴백(Nostr, IPFS, Tor 등)을 같이 고려하면 실용성이 올라갑니다.
- ✅ **평상시 사용 동기 만들기**: 위급용으로만 만들면 사용자가 안 깔립니다. 일상에서 가치 있는 기능을 같이 넣어야 합니다.
- ✅ **OS별 백그라운드 제약을 처음부터 고려**: 설계 후반에 발견하면 답이 없어집니다. 또한 만약 mesh를 밀고 나간다면 노트북에서의 구현까지 이번학기 목표로 무조건 잡고싶음.
- ✅ **밀집 환경 시뮬레이션 필수**: 실험실에서는 잘 되는데 실제 사용 환경에서 무너지는 게 BLE 메시의 고질병입니다.
- ✅ **오픈소스 + 외부 보안 감사**: Bridgefy의 실패가 보여주듯 자체 검증만으로는 부족합니다.>학부생 레벨에서 최선은 잡다한 기능 없이! ‘재난통신 본연’에 강하게 집중해야 함.

---

## 📚 전체 참고자료

### 학술 논문

- Albrecht, Blasco, Jensen, Marekova — **Mesh Messaging in Large-Scale Protests: Breaking Bridgefy** (Financial Cryptography 2021)
    - PDF (IACR ePrint 2021/214)
    - Springer 게재본
    - ResearchGate 페이지
    - 저자 블로그 요약 (malb::blog)
- Eikenberg, R. — **Breaking Bridgefy, again** (2022) — Bridgefy의 Signal 프로토콜 적용 후속 분석 (HandWiki Bridgefy 항목 ref.8 참조)

### 공식 사이트 / 저장소

- bitchat: GitHub · 공식 페이지
- Berkanan SDK: GitHub
- Meshtastic: 공식 사이트 · GitHub Org

### 위키피디아

- BitChat
- Bridgefy
- Meshtastic
- Bridgefy (HandWiki) — Wikipedia보다 상세한 출처 트래킹

### 보안 분석·뉴스

- Bridgefy FAIL: Insecure for Use in Protests — Security Boulevard
- Protest App Bridgefy Riddled with Vulnerabilities — Adam Levin
- Hong Kong Protestors Using Bridgefy — UMA Technology
- Why P2P Mesh Network Chats Haven't Succeeded… Yet — Blake's Notes

### 일반 매체 / 학부생 친화 자료

- What is Bitchat? — TechTarget
- How Jack Dorsey's new app lets you chat without the internet — Cointelegraph
- Bitchat: TechRadar 리뷰
- 3 messaging apps that don't rely on internet — Daily Star
- Bluetooth Chat Apps Compared
- Meshtastic 101 — Dave Wigstone
- Bridgefy Alternatives — AlternativeTo
- Use Briar instead of Bridgefy — Paolo Redaelli
- 5 Best Offline Messaging Apps — Droid Guy
- 6 Best Offline Messaging Apps — Tweak Library
- 4 Apps To Chat With No Internet — Gecko & Fly

---

*리서치 정리: bitchat 외 BLE 메시 메시징 서비스 비교 / 2026.04 기준*
