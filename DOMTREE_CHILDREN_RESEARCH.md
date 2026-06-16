# Dominator Tree Children 조회 구현 연구 노트

작성일: 2026-06-15  
목적: ClassLoader Dominator Tree children 조회 (MAT GUI 기준 25개 재현)

---

## 1. 문제 배경

### 사용자 요구사항
MAT GUI에서 `jeus.servlet.loader.ContextLoader @ 0x8216b620`을 Dominator Tree에서 클릭 시:
- **"Σ Total: 25 of 27,044 entries"** 표시
- children 예: `resourceMap java.util.concurrent.ConcurrentHashMap`, `parallelLockMap`, `classTable`, `class org.springframework.cglib...`

### 현재 앱 동작 (문제)
- classloaderexplorerquery → OQL: `SELECT * FROM java.lang.Class c WHERE c.@classLoaderId = N`
- 반환: Java 클래스 정의 목록 7,053개 (cap=500)

### 차이 원인
| 뷰 | 의미 | 개수 |
|----|------|------|
| MAT GUI Dominator Tree | ClassLoader가 직접 dominate하는 객체들 | 27,044 (25 기본표시) |
| 현재 앱 | ClassLoader가 **정의한** Java 클래스 목록 | 7,053 (500 cap) |

완전히 다른 뷰임.

---

## 2. classLoaderId 추출 버그

### 현상
- classloaderexplorerquery HTML에서 `classLoaderId=311451` 추출됨 (Python 직접 분석)
- 앱 로그에서는 `classLoaderId=330648`이 사용됨
- OQL 검증: `objectId=311451 → address=0x8216b620` (ContextLoader 맞음)
- `objectId=330648 → domOut children=0` (잘못된 값)

### 현재 코드 위치
- `MatReportParser.java` `extractClassLoaderIdNearAddress()` - TR별 파싱, addr 포함 TR에서 첫 classLoaderId 추출
- `HeapReportApiController.java` `classLoaderClassesSse()` - Step 1에서 classloaderexplorerquery 실행

---

## 3. MAT CLI로 Dominator Tree children 조회 시도 (모두 실패)

| 방법 | 결과 |
|------|------|
| `oql "SELECT * FROM ... WHERE s.@dominatorId = N"` | `InstanceImpl has no property dominatorId` |
| `oql "SELECT * FROM OBJECTS snapshot.getImmediateDominatedIds(N)"` | OQL 파서 오류 (괄호 허용 안함) |
| `dominator_tree 311451` | `No unflagged parameters available for argument '311451'` |
| `dominator_tree -objects` | `Query 'Dominator Tree' has no argument 'objects'` |
| `oql "SELECT * FROM OBJECTS outbounds(heap.findObject(0x8216b620))"` | ZIP 생성되나 결과 HTML 비어있음 |

---

## 4. .domOut.index 바이너리 파싱 분석

### 파일 구조 (역공학 완료)

```
[Body 섹션: 0 ~ headerPos]
  - ArrayIntCompressed 페이지들 (varyingBits, trailingBits + packed bit array)
  - 각 entry: count | child0 | child1 | ... | childN
  - 마지막 {numPages}개 long: pageStart 배열
  - 마지막 16 bytes: (long pageStartPos, int pageSize, int elementCount)

[Header 섹션: headerPos ~ fileSize-8]  
  - ArrayLongCompressed 페이지들 (objectId → body 내 position 매핑)
  - 포맷 동일 (varyingBits/trailingBits + packed bit array)

[파일 끝 8 bytes: headerPos (long, big-endian)]
```

### wgdist_1_heapdump_20260326.domOut.index 파싱 결과

```
File size: 10,339,040
headerPos: 6,867,012
Body: elementCount=2,615,987, pageSize=1,000,000, pages=[0, 2625002, 5250004, 6866972]
Header: elementCount=1,307,994, pageSize=1,000,000, pages=[6867012, 9492014, 10339000]
```

### Python 파싱 코드 (검증됨)

```python
def get_val(page_data, index_in_page):
    vb = page_data[0] & 0xFF   # varyingBits
    tb = page_data[1] & 0xFF   # trailingClearBits
    if vb == 0:
        return 0
    bit_offset = index_in_page * vb
    byte_start = 2 + bit_offset // 8
    bit_pos = bit_offset % 8
    bits_needed = vb + bit_pos
    bytes_needed = (bits_needed + 7) // 8
    raw = 0
    for i in range(bytes_needed):
        if byte_start + i < len(page_data):
            raw = (raw << 8) | (page_data[byte_start + i] & 0xFF)
    shift = bytes_needed * 8 - bit_pos - vb
    val = (raw >> shift) & ((1 << vb) - 1)
    return val << tb
```

### MAT 클래스 매핑 (역공학)

```
IndexManager.DOMINATED = "domOut" → IntIndex1NReader
  - header = PositionIndexReader (objectId → body position)
  - body = IntIndexReader (count | child0..N)

IntIndex1NReader.get(int objectId):
  pos = header.getPos(objectId)   // body 내 element index
  count = body.get(pos)           // children 수
  return body.getNext(pos+1, count) // children objectIds
```

### 파싱 결과 (미검증 상태)

```
objectId=311451 (ContextLoader @ 0x8216b620):
  body position = 705883
  count = 1
  children = [311451]  ← 자기 자신?? (이상함, 미검증)

objectId=330648:
  count = 0  (children 없음)

objectId=0 (root):
  count = 112,200  (전체 Dominator Tree root children)
```

**⚠️ 문제**: children=[311451]이 자기 자신 objectId와 동일 → 파싱 오류 가능성 또는 MAT 내부 표현 방식 차이

---

## 5. 이어서 해야 할 작업

### 5-1. domOut.index 파싱 검증 (우선순위 HIGH)

**방법 A**: oom-test로 교차검증

```bash
# oom-test dominator_tree 실행
sh /opt/mat/ParseHeapDump.sh /tmp/oom-test.hprof \
  -command='dominator_tree' org.eclipse.mat.api:query

# ZIP HTML에서 상위 노드들 주소 추출
# → 해당 주소의 objectId를 OQL로 확인
# → domOut.index 파싱 결과와 비교
```

**방법 B**: OQL로 특정 objectId의 주소를 확인하고 domOut 파싱 결과 일치 여부

```sql
-- objectId=0의 children 중 objectId=1635의 주소
SELECT s.@objectAddress FROM INSTANCEOF java.lang.Object s WHERE s.@objectId = 1635
-- → 이 주소가 dominator_tree 결과 HTML에 나타나는지 확인
```

**방법 C**: MAT JAR 서명 우회 → 같은 패키지에 넣어서 직접 실행 (보안 예외 발생 - 대안 필요)

- 대안: JAR 서명 제거 후 재패키징
  ```bash
  mkdir /tmp/mat_unsigned
  cd /tmp/mat_unsigned
  jar -xf /opt/mat/plugins/org.eclipse.mat.parser_1.16.1.202501091339.jar
  rm -rf META-INF/*.SF META-INF/*.RSA META-INF/*.DSA
  jar -cf mat_parser_unsigned.jar .
  # 같은 방식으로 org.eclipse.mat.report JAR도 서명 제거
  javac ... && java -cp mat_parser_unsigned.jar:... DomChildReader ...
  ```

### 5-2. 파싱 성공 시 앱 통합 방법 (두 가지 옵션)

**옵션 A: 독립 Java 프로그램 방식**

```java
// HeapReportApiController에서:
ProcessBuilder pb = new ProcessBuilder(
    "java", "-cp", MAT_UNSIGNED_CP,
    "org.eclipse.mat.parser.index.DomChildReader",
    domOutIndexPath, String.valueOf(classLoaderId)
);
// stdout 파싱 → children objectIds 목록
```

**옵션 B: idx.index를 이용한 역매핑**

- idx.index (LongIndexReader): objectId → native address
- domOut.index children objectIds → 각 address 조회
- address → 클래스명은 OQL 또는 o2c.index + 클래스명 테이블로 변환

### 5-3. children objectIds → 표시 정보 변환

children objectId 목록이 있으면:
1. idx.index에서 각 objectId → address 조회
2. o2c.index에서 각 objectId → classId 조회
3. class name 테이블에서 classId → className 조회
4. o2ret.index에서 각 objectId → retained heap 크기 조회

**또는** 더 단순하게:
```sql
-- OQL로 children objectIds를 address로 변환
SELECT * FROM OBJECTS (objectIds...)
```

MAT OQL `FROM OBJECTS` 구문으로 특정 objectId 목록의 정보를 한 번에 조회 가능.

### 5-4. classLoaderId 추출 버그 수정 (별도 우선순위 HIGH)

현재 `extractClassLoaderIdNearAddress`가 `330648`을 반환하는 이유:

- wgdist의 classloaderexplorerquery HTML에서 TR[1]의 실제 구조 재분석 필요
- 한 TR 내에 여러 classLoaderId 패턴이 있을 경우 첫 번째가 잘못된 값일 수 있음
- 수정 방안: TR 내에서 address hex가 포함된 `<a href>` 태그 바로 뒤의 classLoaderId 추출

---

## 6. 관련 파일/클래스 목록

| 파일 | 역할 |
|------|------|
| `HeapReportApiController.java` | `classLoaderClassesSse()` - SSE 엔드포인트 |
| `MatReportParser.java` | `extractClassLoaderIdNearAddress()` - classLoaderId 추출 |
| `analyze.js` | `handleClSseEvent()`, `_renderClassLoaderTable()` - 프론트엔드 렌더링 |
| `LoadedClassEntry.java` | 클래스 항목 모델 |
| `/opt/heapdumps/data/wgdist_1_heapdump_20260326/*.index` | MAT 분석 인덱스 파일들 |
| `/opt/mat/plugins/org.eclipse.mat.parser_*.jar` | MAT parser (IntIndex1NReader 등) |

---

## 7. 기술 참조

### ArrayIntCompressed/ArrayLongCompressed 포맷
```
data[0] = varyingBits   (각 값에 사용되는 비트 수)
data[1] = trailingClearBits  (하위 0 비트 수)
data[2..] = packed bit array
  - 각 값: (actual_value >>> trailingClearBits)를 varyingBits 비트로 저장
  - 복원: packed_value << trailingClearBits
```

### IntIndex1NReader 파일 레이아웃 (domOut.index)
```
[offset 0]
  Body 데이터 (페이지들) - IntIndexReader
  ├─ Page 0: ArrayIntCompressed bytes
  ├─ Page 1: ArrayIntCompressed bytes
  └─ ...
  pageStart 배열 (numPages * 8 bytes, long[], 절대 파일 위치)
  메타 (16 bytes): long pageStartPos, int pageSize, int elementCount
[offset headerPos]
  Header 데이터 (페이지들) - PositionIndexReader (extends IntIndexReader)
  ├─ Page 0: ArrayLongCompressed bytes (objectId → body position)
  └─ ...
  pageStart 배열 (numPages * 8 bytes)
  메타 (16 bytes): long pageStartPos, int pageSize, int elementCount
[offset fileSize-8]
  headerPos (long, big-endian)
```

### IndexManager 클래스 매핑
```
DOMINATED = "domOut" → IntIndex1NReader    (objectId → [childId1, childId2, ...])
DOMINATOR = "domIn"  → IntIndexReader      (objectId → dominatorId, 1:1)
OUTBOUND  = "outbound" → IntIndex1NSortedReader
INBOUND   = "inbound"  → InboundReader
IDENTIFIER = "idx"   → LongIndexReader    (objectId → native address)
O2CLASS   = "o2c"    → IntIndexReader     (objectId → classId)
O2RETAINED = "o2ret" → LongIndexReader   (objectId → retained heap size)
```
