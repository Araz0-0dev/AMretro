/* Regression test for the ARMv7 libco context switch used by ares.
 * Compile this test in Thumb mode and libco.c in ARM mode. Besides the
 * Thumb/ARM transition, verify every AAPCS callee-saved VFP register d8-d15
 * across switches in both directions. This is a native test, not a UI test.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "libco/libco.h"

#if !defined(__arm__) || !defined(__ANDROID__) || !defined(__ARM_FP)
#error "This test requires Android ARMv7 with VFP"
#endif

static cothread_t parent, child;

static inline void write_vfp(uint32_t lo, uint32_t hi) {
    __asm__ volatile(
        "vmov d8, %0, %1\n\t"  "vmov d9, %0, %1\n\t"
        "vmov d10, %0, %1\n\t" "vmov d11, %0, %1\n\t"
        "vmov d12, %0, %1\n\t" "vmov d13, %0, %1\n\t"
        "vmov d14, %0, %1\n\t" "vmov d15, %0, %1\n\t"
        : : "r"(lo), "r"(hi) : "d8", "d9", "d10", "d11", "d12", "d13", "d14", "d15");
}

#define CHECK_REG(reg, expected_lo, expected_hi) do { \
    uint32_t actual_lo, actual_hi; \
    __asm__ volatile("vmov %0, %1, " #reg : "=r"(actual_lo), "=r"(actual_hi)); \
    assert(actual_lo == (expected_lo) && actual_hi == (expected_hi)); \
} while (0)

static inline void check_vfp(uint32_t lo, uint32_t hi) {
    CHECK_REG(d8, lo, hi);  CHECK_REG(d9, lo, hi);
    CHECK_REG(d10, lo, hi); CHECK_REG(d11, lo, hi);
    CHECK_REG(d12, lo, hi); CHECK_REG(d13, lo, hi);
    CHECK_REG(d14, lo, hi); CHECK_REG(d15, lo, hi);
}

static void worker(void) {
    write_vfp(0x55667788u, 0x11223344u);
    co_switch(parent);
    check_vfp(0x55667788u, 0x11223344u);
    write_vfp(0x12345678u, 0x90abcdefu);
    co_switch(parent);
    assert(0 && "parent must not resume the finished worker");
}

int main(void) {
    assert(!co_create(64, worker)); /* A context must fit before the stack. */
    parent = co_active();
    child = co_create(65536, worker);
    assert(parent && child);
    write_vfp(0xaabbccddu, 0x33445566u);
    co_switch(child);
    check_vfp(0xaabbccddu, 0x33445566u);
    write_vfp(0x0badcafeu, 0x76543210u);
    co_switch(child);
    check_vfp(0x0badcafeu, 0x76543210u);
    co_delete(child);
    puts("LIBCO_ARM_THUMB_VFP_TEST_OK");
    return 0;
}
