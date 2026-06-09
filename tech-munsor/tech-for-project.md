# 프로젝트를 진행하면서 배경지식으로써 필요한 공부자료들을 모아둔 문서입니다. 프로젝트의 큰 그림이 완성되지 않은 시점에서 적힌, 순수 기술적인 이야기들이 뒤죽박죽 담겨있는 점 양해바랍니다. 

만약에, 인터넷 환경을 활용한다면 이는 TCP 기반이 될 것이고 TCP소켓을 열어둔 것은 유지에 따로 비용이 든다. TCP의 기본적인 표준은, 2시간동안 패킷교환이 없느 상태라면 75초마다 1번씩 총 9번 살아있는지 여부 체크하는 용의 신호를 보내고 답이 오지 않는다면 TCP연결은 종료된다. 표준이 이렇단거고 모든 인터넷세상이 이렇게 설정되어 있지는 않지만, 암튼 TCP는 그만큼 오랫동안 소켓에서 패킷교환이 없더라도 딱히 이상한게 아니다. 하지만 이론이 이렇단거고 ipv4 NAT이 꼬여있는 인터넷 환경과 DHCP 리뉴얼이 잦은 무선통신 특성상 이론에만 의지하면 절대 안됨. 주기적인 heartbeat가 TCP 연결 보장에 필수적임. 

이 지점에서, 그리고 mesh 를 활용하는데에 있어도 장비의 idle정책의 low level 고려가 중요하다. 억지로 무선통신장비를 깨우는 행위는 최소화를 해줘야한다. 

최적화에 있어서 중요한 지점

1. 짧은 heartbeat는 절대 피해야한다. 1바이트라도 통신패킷이 이동하려면 유휴상태에서 깨어나야 하니 배터리 활용에 있어서 치명적이다.
2. 연결 애매하게 살아있을 때 재시도에서 connect자체와 TLS handshake, 그외 인증과 동기화가 배터리 갉아먹으니 자제가 필요하다. 
3. 너무 많은 패킷이 오가지 않도록 해야 한다. 암호화를 빡세게 하다 보면 MTU가 줄고 패킷이 늘면 배터리 활용량이 크게크게 는다. 헤더랍시고 많은 정보가 들어가는 것도 특히 지양해야. 프로토콜에 정보를 주고받는 부분을 추가할 때 정보는 최대한 축약해서 보내도록 신경써야
4. 긴 주기에 persistent connection or 외부 push notification api 활용 or foreground에서만 알림이 울리도록 해줘야함. 알림때문에 polling X
5. CPU wake lock의 락이 통신 과정에서 걸리는거 금기시돼야
6. 타이밍 정밀함을 지향하지 마라. 가끔 휴대폰을 활용하다보면 푸쉬알림이 좀 늦게온 경험 있잖아 그거 다 배터리최적화를 위해 그런거임
7. 암호화와 동기화에 들어가는 CPU 작업 cost도 배터리 최적화에 있어서 강하게 신경을 써줘야한다. 
8. DHCP renewal 환경에서 TCP소켓도 기존거 없애고 다시 연다면 연결 애매하게 살아있을 때 재시도에서 비용 쓸데없이 들어간다. 이 지점은 다른 라이브러리 참고할 지점 많을거임
9. 무선통신 환경에선 무조건무조건 exponential backoff는 기본중에 기본이다.

추가로, iOS와 Android의 정책은 foreground가 아닌 상황에서는 어느 정도의 유휴가 들어가서 100퍼센트 퍼포먼스를 못 뽑는 것이 당연하다. 그렇다고 하나의 프로그램 때문에 장치를 24시간 항시 foreground로 돌리는 식의 설계는 금지된다.


------------------------

### 통신 과정에서 암호화가 돌아가는 산업동향

현대 암호체계를 크게 암호화와 복호화에 쓰이는 키가 같은 **대칭 암호화**와, 암호화와 복호화에 쓰이는 키가 다른 **비대칭 암호화**로 나눌 수 있다. 비대칭 암호화엔 수학적으로 큰 오버헤드가 존재해 보통 모든 정보를 비대칭암호화 해줄 수 없다. 초기 인증에 대해 비대칭암호화를 진행하며 이를 통해 키교환과 식별을 다양한 방법으로 진행해준 다음에, 메시지 평문에 대해서는 대칭 암호화를 이행해주는 것이 일반적이다.

예를 들어서 인터넷 환경에서 Secure을 제공해주는 현대 TLS 1.3은 다음과 같은 구조를 따른다: 

1단계: 서로 어떤 암호 방식을 지원하는지 협상
Client  ->  Server
"나는 TLS 1.3, X25519, AES-GCM, ChaCha20-Poly1305 등을 지원함"

2단계: 비대칭 키 교환
Client  <->  Server
Diffie Helman key exchange:키 값을 전달하는 것이 아니라 역연산이 불가능에 가깝게 ‘키 값을 만드는 법’을 정보로써 공유해 통신 참여자만 아는 공통의 키를 얻을 수 있게 한다.

3단계: 인증
Server -> Client
서버 인증서와 서명으로 "내가 진짜 서버임"을 클라이언트에 증명

4단계: 키 파생
shared secret -> HKDF -> client_write_key, server_write_key, nonce 등 생성

5단계: 실제 데이터 암호화
HTTP 요청, 응답, 메신저 메시지, VPN 패킷 등을
AES-GCM 또는 ChaCha20-Poly1305 같은 대칭 AEAD로 암호화

### 1. 비대칭 암호화:X25519 등의 타원곡선 암호화

보통의 비대칭 암호화에 쓰이는 알고리즘은 RSA이지만 모바일 환경에서는 RSA보다 타원곡선 암호화가 훨씬 많이 활용된다.

타원곡선 암호화의 원리는 짧게 요약하자면 다음과 같다:

‘시계산술’이란 개념이 있다. 군대가 아니면 14시라고 얘기 안 하고 2시라고 얘기하고 18시라고 얘기 안 하고 6시라고 얘기하는 것처럼 모든 덧셈,뺄셈,곱셈 연산 결과에 대해서 특정 숫자로 나눠줘 결과를 제한하는 것을 시계산술이라고 한다. 임의의 정수에 대해서 시계산술로 닫혀있는 군을 만들고, 나눗셈은 ‘역원의 곱셈’으로 정의해주고 교환법칙까지 성립해두도록 정의해주면 이를 **아벨 군**이라고 한다. 

임의의 큰 소수 p(2,3이면 안 됨)에 대해서 닫힌 아벨군에 있는 타원곡선 $y^2 = x^3 + ax + b  (mod \quad p)$에 대해서($4a^3 + 27b^2 ≠ 0$) 일때 위 타원곡선(Short Weierstrass 식이라고 한다)에 대해 만나는 직선과 그 만나는 점을 P라고 하면- 이 점 P를 기반으로 2P를 구하는 법과 다른 만나는 지점 P+Q를 구하는 법은 알려져있다. 따라서 특정 숫자, p가 엄청 크다고 감안했을 때 27P를 구하고 싶다면 16P+8P+2P+P 형태로 2배하고 더하고 2배하고2배하고 더하고 2배하고 더하는 방식의 구하기를 빠르게 가능하다. 하지만 점 P에 대한 ‘스칼라배’는 쉽게 계산하는 법이 알려져있지 않다. 따라서 이 지점에서 암호화가 가능하다.

위 연산에서 연산할 수 있는 최대 양이 한정되어 있다. 어느순간 더하다가 항등원 O 에 도달하면 그 다음에 항등원에 P를 더해주면 P고 2P 더해주면 2P이고 그런 상태가 되어 한계가 정해져있는데 이를 point order이라고 하고 그래서 p가 소수가 아닐때는 point order이 작아져버릴 수 있다. 

실제 이를 응용한 가장 잘 알려진 암호화기법이 X25519 비대칭 암호화인데, $2^{255} - 19$ 는 소수라고 알려져있으며 이 거대한 소수 기반 군에 다음과 같은 타원곡선을 정의한다: $y^2 = x^3 + 486662x^2 + x$ (short Weierstrass 식 을 계산 편의를 위해 변형해줬다는데 설명생략..) 

```
`#이 위에서 다음과 같은 계산을 진행해준다`

`공통으로 알고 있는 값:
BASE = 9`

`Alice:
a = random_32_bytes()          # Alice의 비밀값
A = X25519(a, BASE)            # Alice의 공개값
Bob에게 A를 보냄`

`Bob:
b = random_32_bytes()          # Bob의 비밀값
B = X25519(b, BASE)            # Bob의 공개값
Alice에게 B를 보냄`

`Alice:
shared_A = X25519(a, B)        # 내 비밀값 + 상대 공개값`

`Bob:
shared_B = X25519(b, A)        # 내 비밀값 + 상대 공개값`

`결과:
shared_A == shared_B`
```

### 2. AES

AES 는 Advanced Encryption Standard의 줄임말이다. 미국 NIST가 AES를 표준으로 정해 현대 암호화의 근간이 되는 알고리즘이다. 기본적으로 블록크기는 128비트이며 키의 크기는 128비트, 192비트, 256비트 중 하나를 활용해 AES-128, AES-196, AES-256 으로 각각 명칭된다. 키의 크기가 커질수록 암호화의 정도가 복잡해지며 그래도 AES-128만 써도 양자컴퓨터를 제외한 당장의 위협에는 면역이 있는 것으로 알려져있다. 한 AES암/복호화는 128비트짜리 블록 하나를 암호화하는 함수이다.

AES의 원리는 비트 치환→내부 비트 회전→ 열 단위로 값 섞기(확산)→라운드 키와 내부 상태 XOR연산 진행을 하나의 라운드라고 할 때 AES-128은 10라운드, AES-192는 12라운드, AES-256은 14라운드를 진행해주는 것이다. AES의 기본 원리는 이렇고 이를 약간씩 변형해서 실제 통신 암호화를 진행하는데, 현재 쓰이는 방식은 ‘AES-GCM’(Galois/Counter Mode)이다. 인증태그를 해시값으로써 만들어 보안을 더 견고하게 해준다.

이 AES의 큰 특징이 있다. 정말 많은 CPU에서 ‘하드웨어 가속’을 지원한다. 컴퓨터는 일부 저전력 프로세서를 제외하고 다 지원한다.

대표적인 지원 휴대폰 모델

Google: Pixel 6, 7, 8, 9 시리즈 (Tensor 칩셋)

Apple: iPhone 13, 14, 15, 16 시리즈 및 최신 SE 모델

Samsung: Galaxy S21, S22, S23, S24 시리즈, Galaxy Z Fold/Flip 3, 4, 5, 6, Galaxy A54/A55 등

하드웨어 가속 기반 AES를 지원하는 휴대폰의 경우 무조건 암호화에서 AES를 써주는게 압도적으로 좋다. ASIC으로 암호화를 지원하기에 전력 효율이 굉장히 높기 때문이다.

### 3. 대칭암호화- ChaCha20-Poly1305

ChaCha20:
스트림 암호
평문과 keystream을 XOR하여 암호문 생성

Poly1305:
메시지 인증 코드, MAC
ciphertext와 AAD에 대한 인증 태그 생성

ChaCha20-Poly1305:
암호화 + 위변조 검출을 함께 제공하는 AEAD

XOR은 대표적인 유명한 대칭 암호화이다. ChaCha20은 내부적으로 덧셈-회전-XOR을 반복해줘 빠른 키 기반 대칭암호화를 가능하게 해준다. AES의 하드웨어 지원이 없는 환경에서 AES보다 3배 빠르게 암호화가 가능해 휴대폰에 AES 하드웨어가속이 없던 시절에 많이 도입된 알고리즘이다. 근데 대칭암호화에선 원문 내용을 정확히 몰라도 중간자가 조작을 해줬을 때 결과가 완전 이상한값이 안 되어 조작이 판별되지 않을 위험이 존재한다. 따라서 Poly1305를 붙인다. 통신 송수신자가 같은 tag를 공유하여 tag기반으로 복원을 시도하는 방식을 도입하여 통신 중간에 내용이 조작되었을 때 조작되었음을 알 수 있다.

### 4. 저전력 대칭암호화- ASCON이란

2023년에 미국 NIST에서 경량 암호 표준으로 지정한 대칭 암호화 알고리즘이다. ChaCha20-Poly1305보다 더 적은 연산량을 필요로해 IoT, 센서 분야에서 활용도가 높은 알고리즘이다. ChaCha20과 마찬가지로 XOR과 rotation, 치환 기반으로 대칭 암호화를 진행해준다.

### 5. 우리 프로젝트에서

비대칭 암호화는 모바일 환경과 블록체인 분야에서 많이 활용되어 검증된 X25519를 쓰는데, 대칭 암호화 파트에서 if AES 지원: AES else: 다른거 <ㅡ 이 부분에서 고려해줘야 할 부분이 많다. 일단 단순히 같은 암호화를 쓰더라도 cpu활용을 최소로 진행해줘야 할 것이고 그리고 multihop을 진행한다면- 패킷에 암호화되지 않은 부분/암호화된 부분 이 정확하게 세분화되어 이동해야 할 것이다.

------------------------

결론부터 적자면, ‘재난통신’에 초점을 맞춘다면 굳이 고성능이 필요없기에 WiFi Direct보다 블루투스(BLE)를 근거리 통신 옵션으로써 활용하는 것을 원칙으로 한다. - WiFi Direct는 실제 도달거리가 차폐 없을 때 기준으로 약 60m정도로 언급되고 BLE는 bitchat 직접 써본 기준으로 약 16~18m정도이니 BLE로 안 되는 통신 WiFi Direct로 시도 옵션을 고려해보자면… < WiFi Direct 자체가 현재 안드로이드 환경 기준으로 WPA2 보안인증을 요구하기에 실효성이 떨어짐. WPA2 연결과정자체가 키교환을 요구하기에 시도 하나하나가 배터리소모가 상당함.. 부분옵션으로써 고려는 해볼만 하지만 실제 서비스 굴리는 과정에서 직접 실효성이 있는지 판단해야 할 문제일듯..

## 블루투스

1998년 표준화된 근거리 저전력 무선연결 표준이다. 2.4GHz 영역을 활용하며 WiFi나 다른 2.4GHz 영역을 점유하는 통신신호와의 간섭을 완화하기 위해 FHSS 주파수도약을 기반으로 짧은 거리 통신을 주고받는다. 

BLE는 2010년에 표준화된 저전력 블루투스 송수신이다. 필요한 순간 짧게 깨서 필요한 데이터만 주고받는 그런 통신을 말한다. 

BLE를 제대로 이해하려면 GAP, ATT, GATT가 중요합니다.

### GAP

Generic Access Profile입니다. 장치가 어떻게 발견되고, 연결되고, 역할을 정하는지와 관련됩니다. 예를 들어 Central/Peripheral, 광고 패킷, 스캔, 연결 파라미터 같은 것들이 GAP 쪽 개념입니다.

### ATT

Attribute Protocol입니다. BLE에서 데이터를 “속성(attribute)” 단위로 다룹니다. 각 attribute는 대략 handle, type/UUID, value, permission 같은 개념을 갖습니다. Bluetooth Core 문서도 GATT가 ATT를 이용해 데이터 구조와 절차를 제공한다고 설명합니다.

### GATT

Generic Attribute Profile입니다. BLE 애플리케이션 데이터의 기본 구조입니다. Bluetooth Core 문서는 GATT가 ATT 기반의 서비스 프레임워크를 정의하며, 서비스·특성의 발견, 읽기, 쓰기, notification, indication 절차를 포함한다고 설명합니다.

GATT 구조는 보통 이렇게 생겼습니다.

Device
└── GATT Server
├── Service: Heart Rate Service
│    ├── Characteristic: Heart Rate Measurement
│    └── Characteristic: Body Sensor Location
└── Service: Battery Service
└── Characteristic: Battery Level

| 개념 | 의미 |
| --- | --- |
| **GATT Server** | 데이터를 제공하는 쪽. 예: 심박 센서 |
| **GATT Client** | 데이터를 읽거나 구독하는 쪽. 예: 스마트폰 앱 |
| **Service** | 기능 묶음. 예: Battery Service |
| **Characteristic** | 실제 데이터 항목. 예: Battery Level |
| **Descriptor** | characteristic의 메타데이터 |
| **Notification** | 서버가 클라이언트에 비동기 전송, ACK 없음 |
| **Indication** | 서버가 클라이언트에 비동기 전송, ACK 있음 |

중요한 실무 포인트: **Central/Peripheral 역할과 GATT Client/Server 역할은 같지 않습니다.** 스마트폰이 Central이면서 GATT Client인 경우가 흔하지만, 설계에 따라 Central이 GATT Server일 수도 있습니다. Core 문서도 GATT client/server 역할이 고정된 장치 역할이 아니며, 장치가 동시에 양쪽 역할을 할 수 있다고 설명합니다.

### 용어	의미

Pairing	두 기기가 암호학적 절차를 통해 키를 생성하는 과정
Bonding	pairing 결과 키를 저장해서 다음 연결 때 재사용 가능하게 하는 것
Encryption	생성/저장된 키로 실제 링크를 암호화하는 것

BLE pairing은 크게 3단계로 볼 수 있습니다. Core 문서에 따르면 Phase 1은 Pairing Feature Exchange, Phase 2는 legacy pairing의 STK 생성 또는 LE Secure Connections의 LTK 생성, Phase 3은 선택적 transport-specific key distribution입니다.

Pairing 방식은 대표적으로 네 가지입니다.

| 방식 | 설명 | MITM(중간자 공격) 보호 |
| --- | --- | --- |
| **Just Works** | 사용자 입력 거의 없음. 헤드리스 기기에 편함 | 일반적으로 약함 |
| **Passkey Entry** | 6자리 숫자 입력 | 있음 |
| **Numeric Comparison** | 양쪽 화면의 숫자가 같은지 확인 | 있음, LE Secure Connections에서 사용 |
| **Out-of-Band(OOB)** | NFC 등 다른 채널로 인증 정보 전달 | OOB 채널 보안성에 의존 |

Core 문서는 Pairing Feature Exchange에서 인증 요구사항과 IO capability를 교환한 뒤 Just Works, Numeric Comparison, Passkey Entry, OOB 중 사용할 방식을 정한다고 설명합니다. Numeric Comparison은 LE Secure Connections 전용입니다.

보안 관점에서 Just Works는 편하지만 MITM에 약합니다. BLE 장치가 “NoInput NoOutput”이면 현실적으로 Just Works로 떨어지는 경우가 많아, 도어락·의료기기·산업제어처럼 민감한 장치에서는 OOB, Passkey, Numeric Comparison, 인증된 앱/프로비저닝 흐름을 신중히 설계해야 합니다.

### BLE 보안 모드와 레벨

BLE에는 보안 모드/레벨이 있습니다. Core 문서 기준으로 LE Security Mode 1에는 다음 레벨이 있습니다.

Mode 1 레벨	의미
Level 1	인증 없음, 암호화 없음
Level 2	비인증 pairing + 암호화
Level 3	인증 pairing + 암호화
Level 4	인증된 LE Secure Connections pairing + 128-bit strength encryption key

또한 Core 문서는 Secure Connections Only mode에서는 일부 Level 1 서비스 예외를 제외하고 Security Mode 1 Level 4만 사용한다고 설명합니다.

연구/제품 보안 관점에서 민감 데이터라면 최소 기준을 이렇게 잡는 게 안전합니다.

| 상황 | 권장 |
| --- | --- |
| 단순 공개 비콘 | 암호화 불필요 가능 |
| 배터리 잔량·단순 센서 | 위험도에 따라 Level 2 이상 |
| 개인 건강정보 | LE Secure Connections + authenticated pairing |
| 도어락·차량키·결제성 기능 | OOB/Numeric/Passkey + 앱 계층 인증 + 재전송/릴레이 방어 |
| 펌웨어 업데이트 | 서명 검증, anti-rollback, 암호화 링크, 권한 분리 |

## BLE Mesh란

블루투스를 1:1이 아닌 다:다 구조로 네트워크화한 개념이다. 중앙이 없는 분산구조로 활용될 수 있으며 네트워크의 규모와 성능을 개선했다고 한다. ‘멀티홉 통신’을 BLE Mesh 위에서 굴린다면 서로 직접적으로 블루투스 연결이 불가능한 지점에 있는 통신 장비끼리 중간 장비를 거침으로써 통신이 가능하도록 한다.

| 개념 | 설명 |
| --- | --- |
| **Node** | Mesh에 참여하는 장치 |
| **Element** | 하나의 노드 안에 있는 논리적 기능 단위 |
| **Model** | 조명 on/off, dimming 같은 기능 모델 |
| **Publish/Subscribe** | 특정 주소/그룹에 메시지 발행·구독 |
| **Relay** | 메시지를 다른 노드로 전달 |
| **Provisioning** | 새 장치를 Mesh 네트워크에 안전하게 가입 |
| **AppKey/NetKey/DeviceKey** | Mesh 보안 키 계층 |

BLE Mesh 실활용에서 고려해줘야 할 지점은 다음과 같다

| 항목 | 권장 |
| --- | --- |
| Service UUID | 표준 서비스가 있으면 표준 사용, 없으면 vendor-specific UUID |
| Characteristic 권한 | read/write/notify/indicate를 최소 권한으로 설정 |
| 민감 데이터 | encrypted + authenticated requirement 설정 |
| Write | write with response와 write without response 구분 |
| Notify | CCCD 설정 흐름 처리 |
| MTU | 작은 기본 MTU에서도 동작하게 설계 |
| Versioning | GATT schema version characteristic 고려 |
| 오류 | ATT error code를 명확하게 사용 |

번외로 bluetooth 말고 근거리 저전력 무선통신 기술이 몇개 있는데

1. NFC-삼성페이로 잘 알려진 NFC이다. 거리가 극히 짧고 OOB Pairing 에 쓰인다.
2. Zigbee(IEEE802.15.4)-IoT 기술에서 초저전력 통신을 지원하기 위해 고안된 기술인데, 스마트폰에서 직접적으로 활용되지는 않는다. 아예 외부전력이 없는 상태에 특화된 기술임
3. UWB-Ultra WideBand, 우리말로 초광대역이란 이름으로 저주파수부터 고주파수까지 매우 넓은 범위의 주파수를 활용하여 ‘좁은 펄스기반 통신을 통해 낮은 전력으로-기존통신과 간섭없이-DFS,국가별 규제 등 주파수 제약 없이’ 활용 가능한 통신기술이다. 100m 범위에서까지 블루투스보다 더 빠른 속도로 정보의 전달이 가능하다. BLE는 한 채널에 대역폭 2MHz 할당, WiFi는 보통 한 채널에 대역폭을 80 or 160MHz를 할당하는데 UWB는 500MHz를 할당할 여력이 되어 더 쾌적한 통신이 가능하다.UWB는 플래그쉽 스마트폰엔 2020년대 이후(iPhone은 2019년 출시된 11 이후, Galaxy는 2021년 출시된 21 이후)로 대체로 탑재되었단 점에서 호환성 문제는 적다. 하지만 전력사용에서 약점이 존재한다.

## WiFi Direct

WiFi 연결에서 연결을 받는 WiFi 장비를 AP라고 하고 연결이 AP에 되는 장비들을 STA라고 한다. WiFi Direct는 STA들 중 하나를 소프트 AP처럼 동작하는 Group Owner로 세우고 다른 STA들을 이 GO에 연결하는 프로토콜이다. 데이터가 오가는 순간에는 GO가 AP의 역할로 중계를 진행해준다.

2.4GHz, 5GHZ의 기존 80211 채널을 쓰며 따로 어떤 전용 통신 알고리즘을 굴리진 않지만 ‘관리’를 Go가 진행해준다.  초기 p2p연결에서 Go를 정해주고 Go와 STA가 다른 AP에 연결되는처럼 WPA2 기반 암호화로 WiFi로 연결이 된다.  연결되면 GO가 DHCP 서버역할을 해주며 GO가 만약 외부 인터넷으로 Cross Connect를 제공한다면 인터넷 gateway까지 연결해준다. 휴대폰에 사용되는 핫스팟과의 차이첨은 핫스팟을 켜서 AP역할이 부여된 모바일 디바이스는 1:N 중계역할을 해준다는 것이고 WiFi Direct의 GO역할을 하는 모바일 디바이스는 1:1 직접연결이 된다는 것이다. 

번외로 WiFi TDLS 기능이 있다. TDLS는 동일한 AP에 연결된 STA들 간에 1홉 데이터 전송을 지원하는 기술이다. 하지만 TDLS와 메시를 혼합해서 굴리는 것은 지양되어야 하는데, TDLS는 ‘AP에 강하게 의존’하는 기술이다. ‘softAP’형태의 WiFi Direct는 TDLS의 AP 중계역할로써 활용되지 못한다. 같은 AP에 이어져있어야 가능하고 따라서 TDLS는 보조적 hint로써만 가능하면 활용하는 정도로 이용되는 정도여야만 하고 특히 ‘재난통신’에선 없는 개념으로 봐야함. 

## 요약:

BLE: 필요한 순간 깨서 데이터만 주고받고 저전력으로 굴러감

Advertising, Scanning, Connection:GAP

Pairing/Encryption (보안 필요시) Pairing/Bonding(재사용)여부 체크 필요

보안 정도

1. Just Works-그냥 연결
2. Passkey Entry: 6자리 숫자로 패스키 만들어 연결
3. Numeric Comparison: 연결하는 두 대상에 대해 양쪽 수 확인
4. OOB: 다른 별개의 인증채널 도입(실물 카드 등)

GATT Discovery-어떤 서비스 어떻게 있는지 체크

Read/Write/Notify/Indicate: 실제 데이터 교환

Disconnect/Sleep: 연결 종료/유휴 상태 돌입

GATT Throughput: 전력- Advertising interval, Scan window/interval, Slave Latency, PHY, TX Power, Notification 빈도, Reconnection 정책

GATT 성능- Connection Interval, 1커넥션당 전송패킷, ATT MTU, PDU 크기, PHY, OS, App Scheduling

GATT 설계시 고려사항- UUID 표준 맞추기 vs 다르게, Characteristic 권한 최소, 민감 데이터 설정, Write/Notify 설정, MTU 작아도 되도록 설계, versioning, 오류처리, 멱등성 고려

### BLE 실제 연결 적용할 때 고려해줘야 하는 가장 중요한 지점

5월 5일, 리눅스 환경에서 ble 연결을 쉽게 도와주는 bluez 라이브러리 기반으로 이것저것 해본 결과, BLE GATT가 맺어질 때 **한 쪽이 Peripheral(Server)- 다른 한 쪽이 Central(Client) 역할**이 되도록 흐름을 자연스럽게 짜주는게 정말 중요하다고 생각된다. 복잡한 합의 알고리즘 없이 먼저 킨 쪽이 서버를 하고 나중에 킨 쪽이 클라이언트가 되는 구조는 ‘대칭성 문제’가 존재한다. 둘이 같은 타이밍에 코드가 실행되면 같은 결론이 내려지는 문제가 존재한다. 그니까 주기적으로 polling-만약 블루투스 신호 흐름이 느껴진다면 클라이언트가 되는 식으로 코드를 짠다면 최초의 서버는 누가 할 것인지에 대한 문제가 존재하고 이걸 mesh로 구성한다고 하면 1명의 Peripheral mode- 나머지의 Central 모드가 항상 보장이 돼야 하는데 이 지점에서의 고민이 필요함.
