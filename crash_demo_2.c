/*
 * crash_demo_2.c
 * 코어 덤프 분석 예시용 — 멀티스레드 NULL 포인터 역참조 + 잘못된 캐스트 시나리오
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <stdint.h>

/* ── 데이터 구조 ─────────────────────────────────────────── */
typedef struct {
    int   id;
    char  name[32];
    int  *value_ptr;   /* 의도적으로 NULL이 될 수 있는 포인터 */
} Record;

typedef struct {
    Record **entries;
    int      count;
} RecordStore;

/* ── 워커 스레드 (정상 대기) ─────────────────────────────── */
static void *io_worker(void *arg) {
    (void)arg;
    while (1) sleep(30);
    return NULL;
}

static void *cache_worker(void *arg) {
    (void)arg;
    while (1) sleep(30);
    return NULL;
}

/* ── 비즈니스 로직 (크래시 경로) ────────────────────────── */
static void update_record_value(Record *r, int delta) {
    /* 버그: value_ptr 가 NULL인지 확인하지 않고 역참조 */
    *r->value_ptr += delta;   /* SIGSEGV 발생 지점 */
}

static void process_batch(RecordStore *store, int start, int end) {
    for (int i = start; i < end; i++) {
        Record *r = store->entries[i];
        printf("  [batch] record id=%d name=%s\n", r->id, r->name);
        update_record_value(r, 10);
    }
}

static void run_pipeline(RecordStore *store) {
    printf("[pipeline] %d개 레코드 배치 처리 시작\n", store->count);
    process_batch(store, 0, store->count);
    printf("[pipeline] 완료\n");
}

static RecordStore *build_store(void) {
    RecordStore *s = malloc(sizeof(RecordStore));
    s->count   = 4;
    s->entries = malloc(sizeof(Record *) * s->count);

    for (int i = 0; i < s->count; i++) {
        Record *r = malloc(sizeof(Record));
        r->id = i + 1;
        snprintf(r->name, sizeof(r->name), "item_%02d", i + 1);
        if (i < s->count - 1) {
            /* 정상 레코드: value_ptr 할당 */
            r->value_ptr = malloc(sizeof(int));
            *r->value_ptr = i * 100;
        } else {
            /* 마지막 레코드: 버그 — value_ptr 미초기화(NULL) */
            r->value_ptr = NULL;
        }
        s->entries[i] = r;
    }
    return s;
}

/* ── main ────────────────────────────────────────────────── */
int main(void) {
    printf("crash_demo_2 시작 (PID=%d)\n", (int)getpid());

    /* 워커 스레드 2개 */
    pthread_t t1, t2;
    pthread_create(&t1, NULL, io_worker,    NULL);
    pthread_create(&t2, NULL, cache_worker, NULL);
    pthread_detach(t1);
    pthread_detach(t2);

    RecordStore *store = build_store();
    printf("[main] 스토어 구성 완료 (entries=%d)\n", store->count);

    /* 마지막 레코드의 value_ptr == NULL → SIGSEGV */
    run_pipeline(store);

    /* 정상 도달 불가 */
    return 0;
}
