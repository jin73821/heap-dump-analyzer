#!/usr/bin/env python3
"""rag-knowledge CSV 컬럼 밀림 복구 (원본 → v2).

원본 `rag-knowledge-20260430.csv` 는 84행 전부가 파싱 불가 상태다.
`tags` 값이 인용 없이 콤마를 포함해(`OOM,G1GC,JDK11,OldGen,Heap`) 헤더 8컬럼
대비 데이터가 11~16필드로 읽힌다. 표준 파서로는 tags="OOM", source="G1GC",
severity="JDK11" 이 되고, **정상 8컬럼 행은 0건**이다.

복구가 결정적으로 가능한 이유:
  - 앞 4컬럼(id,category,title,content) 은 content 가 인용돼 있어 정확하다
  - 뒤 3컬럼(source,severity,created_at) 은 끝에서 세면 정확하다
  - 그 사이 전부가 tags 다
검증은 created_at 이 YYYY-MM-DD 이고 severity 가 5종 enum 인지 대조한다.

⚠ 2026-08-31: 이 도구는 **역슬래시 이스케이프(\\")를 처리하지 못한다.** 원본에 그 표기가
있으면(mat-oql-002) 따옴표가 필드를 거기서 끝내 본문 일부가 tags 로 접혀 들어가는데,
뒤에서 세는 규칙 특성상 검증도 통과해 **조용히 잘린 채** 산출된다(실측: content 570→449자).
앱의 가져오기(RagCorpusService.parseLearningFile)는 이 경우까지 바로잡으므로
**화면에서 원본 CSV 를 그대로 가져오는 쪽을 쓰는 게 맞다.** 이 도구는 오프라인 점검용으로만 남긴다.

v2 는 원본 8컬럼에 `synthetic` 을 더한 9컬럼이다. troubleshooting 11건이
가상 사례이고(source 에 '(예시 — 실제 사례로 교체 필요)') 그대로 색인하면
RAG 가 허구를 근거로 답하기 때문에, 검색 단계에서 걸러낼 수 있어야 한다.
"""
import argparse, csv, re, sys, os

SEVERITIES = {"critical", "high", "medium", "low", "info"}
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
HERE = os.path.dirname(os.path.abspath(__file__))
DEF_SRC = os.path.join(HERE, "..", "rag-knowledge-20260430.csv")
OUT_COLS = ["id", "category", "title", "content", "tags",
            "source", "severity", "created_at", "synthetic"]


def recover(row):
    """밀린 행 → 정규화 dict. 뒤 3컬럼 고정 규칙."""
    return {
        "id": row[0], "category": row[1], "title": row[2], "content": row[3],
        "tags": ",".join(row[4:-3]),
        "source": row[-3], "severity": row[-2], "created_at": row[-1],
    }


def main():
    # ⚠ 2026-08-31: 종전에는 src/dst 가 하드코딩이라 이 도구를 그냥 돌리면 rag-knowledge-v2.csv 를
    #    **덮어썼다**. 원본이 84행에서 204행으로 늘어난 지금 그러면 v2 를 85행으로 고정한
    #    CsvCodecTest 골든이 깨지고, 색인기 기본 경로(sources.CSV_PATH)의 내용도 조용히 바뀐다.
    #    그래서 출력 경로를 인자로 받고, 이미 있는 파일은 --force 없이는 덮지 않는다.
    ap = argparse.ArgumentParser(description="rag-knowledge CSV 컬럼 밀림 복구")
    ap.add_argument("--src", default=DEF_SRC)
    ap.add_argument("--dst", required=True)
    ap.add_argument("--force", action="store_true", help="기존 출력 파일을 덮어쓴다")
    a = ap.parse_args()
    SRC, DST = a.src, a.dst
    if os.path.exists(DST) and not a.force:
        raise SystemExit(f"이미 있는 파일입니다 — 덮어쓰려면 --force: {DST}")

    with open(SRC, encoding="utf-8", newline="") as f:
        rows = list(csv.reader(f))
    header, data = rows[0], rows[1:]

    assert header[:8] == OUT_COLS[:8], f"예상치 못한 헤더: {header}"

    recs, bad = [], []
    for i, row in enumerate(data, start=2):
        if len(row) < 8:
            bad.append((i, f"필드 {len(row)}개 — 복구 불가")); continue
        r = recover(row)
        if not DATE_RE.match(r["created_at"]):
            bad.append((i, f"created_at 형식 이상: {r['created_at']!r}"))
        if r["severity"] not in SEVERITIES:
            bad.append((i, f"severity 미지값: {r['severity']!r}"))
        if not r["id"] or not r["content"]:
            bad.append((i, "id/content 비어 있음"))
        r["synthetic"] = "true" if "예시" in r["source"] else "false"
        recs.append(r)

    if bad:
        for line, why in bad[:10]:
            print(f"  [실패] line {line}: {why}", file=sys.stderr)
        raise SystemExit(f"복구 실패 {len(bad)}건 — 중단(부분 결과를 쓰지 않는다)")

    with open(DST, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=OUT_COLS, quoting=csv.QUOTE_MINIMAL)
        w.writeheader(); w.writerows(recs)

    # 왕복 검증 — 쓴 파일을 표준 파서로 되읽어 9컬럼인지 확인한다.
    with open(DST, encoding="utf-8", newline="") as f:
        back = list(csv.DictReader(f))
    assert len(back) == len(recs), f"왕복 행수 불일치: {len(back)} != {len(recs)}"
    for r in back:
        assert len(r) == len(OUT_COLS) and None not in r.values(), f"컬럼 수 이상: {r.get('id')}"
    assert [r["id"] for r in back] == [r["id"] for r in recs], "id 순서/내용 불일치"

    syn = sum(1 for r in back if r["synthetic"] == "true")
    print(f"복구 완료: {len(back)}행 → {DST}")
    print(f"  왕복 검증 통과 ({len(OUT_COLS)}컬럼 × {len(back)}행)")
    print(f"  synthetic(가상 사례): {syn}건 — 검색 기본 제외 대상")
    return 0


if __name__ == "__main__":
    sys.exit(main())
