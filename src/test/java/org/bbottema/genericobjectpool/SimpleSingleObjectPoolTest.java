package org.bbottema.genericobjectpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bbottema.genericobjectpool.ObjectPoolTestHelper.createAllocator;
import static org.bbottema.genericobjectpool.PoolTestResources.await;
import static org.bbottema.genericobjectpool.PoolTestResources.result;

public class SimpleSingleObjectPoolTest {

	private final PoolTestResources resources = new PoolTestResources();
	private GenericObjectPool<String> pool1;
	private GenericObjectPool<String> pool2;

	@BeforeEach
	public void setup() {
		pool1 = resources.pool(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("a"));
		pool2 = resources.pool(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("b"));
	}

	@AfterEach
	public void cleanUp() throws Exception {
		resources.close();
	}

	@Test
	public void waitForObjectWithTimeoutTest() throws Exception {
		final PoolableObject<String> held = resources.remember(pool2.claim());
		final Future<PoolableObject<String>> waiting = resources.workers.submit(() ->
				resources.remember(pool2.claim(200, TimeUnit.MILLISECONDS)));
		await("caller is blocked", () -> pool2.getPoolMetrics().getCurrentlyWaitingCount() == 1);
		assertThat(result(waiting)).isNull();
		assertThat(pool2.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
		held.release();
		assertThat(resources.remember(pool2.claim())).isSameAs(held);
	}

	@Test
	public void manyThreadsBlockingUntilObtainedPool2() throws Exception {
		final PoolableObject<String> held = resources.remember(pool2.claim());
		final List<Future<?>> waiting = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			waiting.add(resources.workers.submit(() -> {
				final PoolableObject<String> lease = resources.remember(pool2.claim(3, TimeUnit.SECONDS));
				assertThat(lease).isNotNull();
				try {
					assertThat(pool2.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(1);
				} finally {
					lease.release();
				}
				return null;
			}));
		}
		await("all ten callers are blocked", () -> pool2.getPoolMetrics().getCurrentlyWaitingCount() == 10);
		held.release();
		for (final Future<?> worker : waiting) {
			result(worker);
		}
		assertThat(pool2.getPoolMetrics().getTotalClaimed()).isEqualTo(11);
		assertThat(pool2.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
		verifyPool1RemainsUnaffected();
	}

	@Test
	public void singleClaimAndRelease() throws Exception {
		final PoolableObject<String> lease = resources.remember(pool2.claim());
		assertThat(lease).isNotNull();
		lease.release();
		assertThat(pool2.getPoolMetrics().getCurrentlyClaimed()).isZero();
		verifyPool1RemainsUnaffected();
	}

	@Test
	public void verifyPool1RemainsUnaffectedAfterClaimingPool2() throws Exception {
		resources.remember(pool2.claim()).invalidate();
		assertThat(pool2.getPoolMetrics().getTotalClaimed()).isEqualTo(1);
		verifyPool1RemainsUnaffected();
	}

	private void verifyPool1RemainsUnaffected() {
		final PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics.getCurrentlyClaimed()).isZero();
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
		assertThat(metrics.getTotalAllocated()).isZero();
		assertThat(metrics.getTotalClaimed()).isZero();
	}

	@Test
	public void testShutdown() throws Exception {
		resources.remember(pool2.claim()).release();
		result(pool1.shutdown());
		result(pool2.shutdown());
		assertThatThrownBy(pool2::claim).isInstanceOf(IllegalStateException.class);
	}

	@Test
	public void shutdownDoesNotCompleteWhileAllocatorIsStillDeallocating() throws Exception {
		final CountDownLatch deallocationStarted = new CountDownLatch(1);
		final CountDownLatch allowDeallocationToFinish = new CountDownLatch(1);
		final CountDownLatch deallocationFinished = new CountDownLatch(1);
		final GenericObjectPool<Boolean> pool = resources.pool(
				PoolConfig.<Boolean>builder().maxPoolsize(1).build(),
				new Allocator<Boolean>() {
					@Override
					public Boolean allocate() {
						return true;
					}

					@Override
					public void deallocate(final Boolean object) {
						deallocationStarted.countDown();
						try {
							if (!allowDeallocationToFinish.await(5, TimeUnit.SECONDS)) {
								throw new AssertionError("cleanup latch timed out");
							}
						} catch (final InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new AssertionError(e);
						} finally {
							deallocationFinished.countDown();
						}
					}
				});

		try {
			resources.remember(pool.claim()).invalidate();
			assertThat(deallocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
			final Future<Void> shutdown = pool.shutdown();
			assertThatThrownBy(() -> shutdown.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			allowDeallocationToFinish.countDown();
			result(shutdown);
			assertThat(deallocationFinished.getCount()).isZero();
		} finally {
			allowDeallocationToFinish.countDown();
		}
	}

	@Test
	public void testObjectLifecycle() throws Exception {
		assertLifecycle(false);
	}

	@Test
	public void testThreadLeakFixGitHub() throws Exception {
		assertLifecycle(true);
	}

	private void assertLifecycle(final boolean failCleanup) throws Exception {
		final TestLifecycleAllocator allocator = new TestLifecycleAllocator(failCleanup);
		final GenericObjectPool<Boolean> pool = resources.pool(
				PoolConfig.<Boolean>builder().maxPoolsize(1).build(), allocator);
		for (int round = 1; round <= 2; round++) {
			final PoolableObject<Boolean> lease = resources.remember(pool.claim());
			lease.release();
			assertThat(resources.remember(pool.claim())).isSameAs(lease);
			lease.invalidate();
			assertThat(allocator.cleanupCompleted.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
			assertThat(allocator.allocations.get()).isEqualTo(round);
			assertThat(allocator.reuses.get()).isEqualTo(round);
			assertThat(allocator.returns.get()).isEqualTo(round);
			assertThat(allocator.deallocations.get()).isEqualTo(round);
			assertThat(pool.getPoolMetrics().getCurrentlyAllocated()).isZero();
		}
		result(pool.shutdown());
	}

	private static final class TestLifecycleAllocator extends Allocator<Boolean> {
		private final boolean failCleanup;
		private final AtomicInteger allocations = new AtomicInteger();
		private final AtomicInteger reuses = new AtomicInteger();
		private final AtomicInteger returns = new AtomicInteger();
		private final AtomicInteger deallocations = new AtomicInteger();
		private final Semaphore cleanupCompleted = new Semaphore(0);

		private TestLifecycleAllocator(final boolean failCleanup) {
			this.failCleanup = failCleanup;
		}

		@Override
		public Boolean allocate() {
			allocations.incrementAndGet();
			return true;
		}

		@Override
		public void allocateForReuse(final Boolean object) {
			reuses.incrementAndGet();
		}

		@Override
		public void deallocateForReuse(final Boolean object) {
			returns.incrementAndGet();
		}

		@Override
		public void deallocate(final Boolean object) {
			deallocations.incrementAndGet();
			cleanupCompleted.release();
			if (failCleanup) {
				throw new IllegalStateException("scripted cleanup failure");
			}
		}
	}
}
