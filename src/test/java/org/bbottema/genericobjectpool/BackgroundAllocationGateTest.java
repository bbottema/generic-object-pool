package org.bbottema.genericobjectpool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.bbottema.genericobjectpool.PoolTestResources.await;

class BackgroundAllocationGateTest {
    @Test
    void deferredBackgroundWorkDoesNotReserveCapacityOrPreventExplicitClaims() throws Exception {
        final GateAllocator allocator = new GateAllocator();
        final GenericObjectPool<Integer> pool = pool(allocator);
        try {
            assertThat(allocator.checked.await(5, SECONDS)).isTrue();
            assertThat(pool.getPoolMetrics().getCurrentlyAllocated()).isZero();
            final PoolableObject<Integer> claimed = pool.claim(ClaimOptions.withTimeout(1, SECONDS));
            assertThat(claimed).isNotNull();
            assertThat(allocator.allocations.get()).isEqualTo(1);
            claimed.release();
            final PoolableObject<Integer> reused = pool.claim(ClaimOptions.withTimeout(1, SECONDS));
            assertThat(reused).isSameAs(claimed);
            reused.release();
            assertThat(allocator.allocations.get()).isEqualTo(1);
        } finally {
            pool.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void allowingBackgroundWorkResumesExistingMaintenanceWorker() throws Exception {
        final GateAllocator allocator = new GateAllocator();
        final GenericObjectPool<Integer> pool = pool(allocator);
        try {
            assertThat(allocator.checked.await(5, SECONDS)).isTrue();
            allocator.allowed.set(true);
            await("core replenished", () -> pool.getPoolMetrics().getCurrentlyAllocated() == 1);
            allocator.allowed.set(false);
            final PoolableObject<Integer> resource = pool.claim(ClaimOptions.withTimeout(1, SECONDS));
            assertThat(resource).isNotNull();
            resource.release();
            assertThat(allocator.allocations.get()).isEqualTo(1);
        } finally {
            pool.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void permissionIsRecheckedAfterReservingAndDecliningReleasesTheReservation() throws Exception {
        final AtomicInteger checks = new AtomicInteger();
        final GateAllocator allocator = new GateAllocator() {
            @Override public boolean isBackgroundAllocationAllowed() {
                return checks.incrementAndGet() == 1;
            }
        };
        final GenericObjectPool<Integer> pool = pool(allocator);
        try {
            await("second gate check", () -> checks.get() >= 2);
            final PoolableObject<Integer> claimed = pool.claim(ClaimOptions.withTimeout(1, SECONDS));
            assertThat(claimed).isNotNull();
            assertThat(allocator.allocations.get()).isEqualTo(1);
            claimed.release();
        } finally {
            pool.shutdown().get(5, SECONDS);
        }
    }

    private static GenericObjectPool<Integer> pool(final Allocator<Integer> allocator) {
        return new GenericObjectPool<>(PoolConfig.<Integer>builder().corePoolsize(1).maxPoolsize(1).build(), allocator);
    }

    private static class GateAllocator extends Allocator<Integer> {
        private final AtomicBoolean allowed = new AtomicBoolean();
        private final AtomicInteger allocations = new AtomicInteger();
        private final CountDownLatch checked = new CountDownLatch(1);

        @Override public boolean isBackgroundAllocationAllowed() {
            checked.countDown();
            return allowed.get();
        }

        @Override public Integer allocate() {
            return allocations.incrementAndGet();
        }
    }
}
