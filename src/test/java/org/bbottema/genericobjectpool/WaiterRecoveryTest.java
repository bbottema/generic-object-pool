package org.bbottema.genericobjectpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bbottema.genericobjectpool.PoolTestResources.await;
import static org.bbottema.genericobjectpool.PoolTestResources.result;

/** Exercises the hand-off to callers that were already blocked before capacity or an object became available. */
class WaiterRecoveryTest {

	private final PoolTestResources resources = new PoolTestResources();

	@AfterEach
	void cleanUp() throws Exception {
		resources.close();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void invalidationWakesAnAlreadyWaitingOrdinaryClaim(final int coreSize) throws Exception {
		final GenericObjectPool<Integer> pool = pool(coreSize, 1, new CountingAllocator());
		final PoolableObject<Integer> first = claim(pool);
		final int originalValue = first.getAllocatedObject();
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);

		first.invalidate();

		final PoolableObject<Integer> replacement = result(waiting);
		assertThat(replacement).isNotNull().isNotSameAs(first);
		assertThat(replacement.getAllocatedObject()).isNotEqualTo(originalValue);
		assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(1);
		assertThat(pool.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
		replacement.release();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void ordinaryReleaseStillWakesAnAlreadyWaitingClaim(final int coreSize) throws Exception {
		final GenericObjectPool<Integer> pool = pool(coreSize, 1, new CountingAllocator());
		final PoolableObject<Integer> first = claim(pool);
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);
		first.release();
		assertThat(result(waiting)).isSameAs(first);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 3})
	void invalidatingAllBusyObjectsWakesEveryWaitingClaim(final int coreSize) throws Exception {
		final GenericObjectPool<Integer> pool = pool(coreSize, 3, new CountingAllocator());
		final List<PoolableObject<Integer>> originals = new ArrayList<>();
		final List<Future<PoolableObject<Integer>>> waiting = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			originals.add(claim(pool));
		}
		for (int i = 0; i < 3; i++) {
			waiting.add(waitingClaim(pool));
		}
		awaitWaiters(pool, 3);
		for (final PoolableObject<Integer> original : originals) {
			original.invalidate();
		}
		final Set<Integer> replacements = ConcurrentHashMap.newKeySet();
		for (final Future<PoolableObject<Integer>> future : waiting) {
			final PoolableObject<Integer> replacement = result(future);
			assertThat(replacement).isNotNull().isNotIn(originals);
			assertThat(replacements.add(replacement.getAllocatedObject())).isTrue();
		}
		assertThat(pool.getPoolMetrics().getCurrentlyAllocated()).isEqualTo(3);
		assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void oneFreedSlotDoesNotHandTheSameLeaseToMultipleWaiters(final int coreSize) throws Exception {
		final GenericObjectPool<Integer> pool = pool(coreSize, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final List<Future<PoolableObject<Integer>>> waiting = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			waiting.add(waitingClaim(pool));
		}
		awaitWaiters(pool, 4);
		original.invalidate();
		for (int completed = 0; completed < 4; completed++) {
			await("one waiter completed", () -> waiting.stream().anyMatch(Future::isDone));
			final Future<PoolableObject<Integer>> ready = waiting.stream().filter(Future::isDone).findFirst().get();
			waiting.remove(ready);
			final PoolableObject<Integer> lease = result(ready);
			assertThat(lease).isNotNull();
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(1);
			assertThat(waiting).allMatch(future -> !future.isDone());
			lease.release();
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void cleanupNeedNotFinishBeforeAWaitingCallerCanUseReplacement(final int coreSize) throws Exception {
		final CountDownLatch cleanupStarted = new CountDownLatch(1);
		final CountDownLatch finishCleanup = new CountDownLatch(1);
		final GenericObjectPool<Integer> pool = pool(coreSize, 1, new CountingAllocator() {
			@Override
			public void deallocate(final Integer value) {
				cleanupStarted.countDown();
				awaitLatch(finishCleanup);
			}
		});
		try {
			final PoolableObject<Integer> original = claim(pool);
			final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
			awaitWaiters(pool, 1);
			original.invalidate();
			assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(result(waiting)).isNotNull();
		} finally {
			finishCleanup.countDown();
		}
	}

	@Test
	void failingFinalCleanupDoesNotStrandWaitingClaims() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override
			public void deallocate(final Integer value) {
				throw new IllegalStateException("scripted cleanup failure");
			}
		});
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThat(result(waiting)).isNotNull();
	}

	@Test
	void allocationFailureReachesTheWaitingCallerAndALaterClaimCanRecover() throws Exception {
		final AtomicInteger allocations = new AtomicInteger();
		final IllegalStateException failure = new IllegalStateException("scripted allocation failure");
		final GenericObjectPool<Integer> pool = pool(0, 1, new Allocator<Integer>() {
			@Override
			public Integer allocate() {
				final int value = allocations.incrementAndGet();
				if (value == 2) {
					throw failure;
				}
				return value;
			}
		});
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThatThrownBy(() -> result(waiting)).hasCause(failure);
		assertThat(pool.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
		assertThat(claim(pool).getAllocatedObject()).isEqualTo(3);
	}

	@Test
	void waitingMatchingClaimCanUseACoreReplacement() throws Exception {
		final GenericObjectPool<Integer> pool = pool(1, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final int originalValue = original.getAllocatedObject();
		final Future<PoolableObject<Integer>> waiting = resources.workers.submit(() -> resources.remember(
				pool.claimMatching(lease -> lease.getAllocatedObject() != originalValue, 2, TimeUnit.SECONDS)));
		awaitWaiters(pool, 1);
		original.invalidate();
		final PoolableObject<Integer> replacement = result(waiting);
		assertThat(replacement).isNotNull().isNotSameAs(original);
	}

	@Test
	void matchingClaimDoesNotAllocateAfterInvalidationInALazyPool() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = resources.workers.submit(() -> resources.remember(
				pool.claimMatching(lease -> true, 200, TimeUnit.MILLISECONDS)));
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThat(result(waiting)).isNull();
		assertThat(pool.getPoolMetrics().getTotalAllocated()).isEqualTo(1);
		assertThat(pool.getCurrentlyAllocated()).isZero();
	}

	@Test
	void anUnlimitedClaimAlsoWakesAfterInvalidation() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = resources.workers.submit(() -> resources.remember(pool.claim()));
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThat(result(waiting)).isNotNull().isNotSameAs(original);
	}

	@Test
	void partialCoreAllocationRemainsUsableWhenTheNextAllocationFails() throws Exception {
		final CountDownLatch startMaintenance = new CountDownLatch(1);
		final AtomicBoolean allowFurtherAllocation = new AtomicBoolean();
		final AtomicInteger allocations = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();
		final PoolConfig<Integer> config = PoolConfig.<Integer>builder().corePoolsize(2).maxPoolsize(2)
				.threadFactory(task -> new Thread(() -> {
					awaitLatch(startMaintenance);
					task.run();
				})).build();
		final GenericObjectPool<Integer> pool = resources.pool(config, new Allocator<Integer>() {
			@Override
			public Integer allocate() {
				if (allocations.get() != 0 && !allowFurtherAllocation.get()) {
					failures.incrementAndGet();
					throw new IllegalStateException("scripted core allocation failure");
				}
				return allocations.incrementAndGet();
			}
		});
		try {
			final Future<PoolableObject<Integer>> first = resources.workers.submit(() -> resources.remember(
					pool.claimMatching(lease -> true, 2, TimeUnit.SECONDS)));
			awaitWaiters(pool, 1);
			startMaintenance.countDown();
			assertThat(result(first).getAllocatedObject()).isEqualTo(1);
			// Publishing each allocation no longer holds the claim lock across the following allocator callback.
			await("a subsequent core allocation failed", () -> failures.get() > 0);
			assertThat(pool.getPoolMetrics().getTotalAllocated()).isEqualTo(1);

			final Future<PoolableObject<Integer>> second = resources.workers.submit(() -> resources.remember(
					pool.claimMatching(lease -> true, 2, TimeUnit.SECONDS)));
			awaitWaiters(pool, 1);
			allowFurtherAllocation.set(true);
			assertThat(result(second).getAllocatedObject()).isEqualTo(2);
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(2);
		} finally {
			allowFurtherAllocation.set(true);
			startMaintenance.countDown();
		}
	}

	@Test
	void invalidatingAnAvailableObjectLetsAMatchingWaiterUseItsReplacement() throws Exception {
		final GenericObjectPool<Integer> pool = pool(1, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final int originalValue = original.getAllocatedObject();
		original.release();
		final Future<PoolableObject<Integer>> waiting = resources.workers.submit(() -> resources.remember(
				pool.claimMatching(lease -> lease.getAllocatedObject() != originalValue, 2, TimeUnit.SECONDS)));
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThat(result(waiting)).isNotNull().isNotSameAs(original);
		assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void shutdownTerminatesMaintenanceThreadsAfterReplacement(final int coreSize) throws Exception {
		final Set<Thread> maintenanceThreads = ConcurrentHashMap.newKeySet();
		final PoolConfig<Integer> config = PoolConfig.<Integer>builder().corePoolsize(coreSize).maxPoolsize(1)
				.threadFactory(task -> {
					final Thread thread = new Thread(task);
					maintenanceThreads.add(thread);
					return thread;
				}).build();
		final GenericObjectPool<Integer> pool = resources.pool(config, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);
		original.invalidate();
		result(waiting).release();
		result(pool.shutdown());
		await("pool threads terminated", () -> maintenanceThreads.stream().noneMatch(Thread::isAlive));
	}

	@Test
	void timedOutClaimIsRemovedAndCannotTakeALaterReplacement() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = resources.workers.submit(() -> resources.remember(
				pool.claim(150, TimeUnit.MILLISECONDS)));
		awaitWaiters(pool, 1);
		assertThat(result(waiting)).isNull();
		assertThat(pool.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
		original.invalidate();
		assertThat(claim(pool)).isNotNull().isNotSameAs(original);
	}

	@Test
	void interruptedWaiterDoesNotConsumeAReplacementOrStrandAnotherWaiter() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final AtomicReference<Thread> interruptedThread = new AtomicReference<>();
		final Future<Throwable> interrupted = resources.workers.submit(() -> {
			interruptedThread.set(Thread.currentThread());
			try {
				resources.remember(pool.claim());
				return null;
			} catch (final InterruptedException e) {
				return e;
			}
		});
		final Future<PoolableObject<Integer>> surviving = waitingClaim(pool);
		awaitWaiters(pool, 2);
		interruptedThread.get().interrupt();
		assertThat(result(interrupted)).isInstanceOf(InterruptedException.class);
		awaitWaiters(pool, 1);
		original.invalidate();
		assertThat(result(surviving)).isNotNull();
	}

	@Test
	void shutdownWakesWaitersButDoesNotAllocateReplacements() throws Exception {
		final GenericObjectPool<Integer> pool = pool(1, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		final Future<PoolableObject<Integer>> waiting = waitingClaim(pool);
		awaitWaiters(pool, 1);
		final Future<Void> shutdown = pool.shutdown();
		assertThatThrownBy(() -> result(waiting)).hasCauseInstanceOf(InterruptedException.class);
		assertThat(shutdown.isDone()).isFalse();
		original.invalidate();
		result(shutdown);
		assertThat(pool.getPoolMetrics().getTotalAllocated()).isEqualTo(1);
		assertThat(pool.getCurrentlyAllocated()).isZero();
	}

	@Test
	void shutdownCannotFinishInTheInvalidationToCleanupHandoff() throws Exception {
		final Set<Thread> poolThreads = ConcurrentHashMap.newKeySet();
		final CountDownLatch finishCleanup = new CountDownLatch(1);
		final CountDownLatch cleanupStarted = new CountDownLatch(1);
		final PoolConfig<Integer> config = PoolConfig.<Integer>builder().maxPoolsize(1).threadFactory(task -> {
			final Thread thread = new Thread(task);
			poolThreads.add(thread);
			return thread;
		}).build();
		final GenericObjectPool<Integer> pool = resources.pool(config, new CountingAllocator() {
			@Override
			public void deallocate(final Integer value) {
				cleanupStarted.countDown();
				awaitLatch(finishCleanup);
			}
		});
		final PoolableObject<Integer> original = claim(pool);
		final Future<Void> shutdown = pool.shutdown();
		await("shutdown entered its wait loop", () -> poolThreads.stream().anyMatch(thread ->
				Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("waitUntilShutDown"))));

		// Hold the actual queue lock to stop invalidation precisely after removing the claim but before queuing cleanup.
		// This is deliberately a white-box synchronization point, not a timing-based attempt to win the race.
		final ReentrantLock claimLock = internalLock(pool, "claimLock");
		final ReentrantLock queueLock = internalLock(pool, "deallocateLock");
		final AtomicReference<Thread> invalidatorThread = new AtomicReference<>();
		final CountDownLatch ownsClaimLock = new CountDownLatch(1);
		final CountDownLatch allowInvalidation = new CountDownLatch(1);
		try {
			final Future<?> invalidation = resources.workers.submit(() -> {
				invalidatorThread.set(Thread.currentThread());
				claimLock.lock();
				try {
					ownsClaimLock.countDown();
					awaitLatch(allowInvalidation);
					original.invalidate();
				} finally {
					claimLock.unlock();
				}
			});
			assertThat(ownsClaimLock.await(5, TimeUnit.SECONDS)).isTrue();
			queueLock.lock();
			allowInvalidation.countDown();
			await("invalidation reached the cleanup queue", () -> invalidatorThread.get() != null
					&& queueLock.hasQueuedThread(invalidatorThread.get()));
			assertThatThrownBy(() -> shutdown.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			queueLock.unlock();
			result(invalidation);
			assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(shutdown.isDone()).isFalse();
		} finally {
			allowInvalidation.countDown();
			if (queueLock.isHeldByCurrentThread()) {
				queueLock.unlock();
			}
			finishCleanup.countDown();
		}
		result(shutdown);
	}

	private static ReentrantLock internalLock(final GenericObjectPool<?> pool, final String name) throws Exception {
		final Field field = GenericObjectPool.class.getDeclaredField(name);
		field.setAccessible(true);
		return (ReentrantLock) field.get(pool);
	}

	@Test
	void duplicateConcurrentInvalidationIsIdempotent() throws Exception {
		final AtomicInteger deallocated = new AtomicInteger();
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override
			public void deallocate(final Integer value) {
				deallocated.incrementAndGet();
			}
		});
		for (int round = 0; round < 12; round++) {
			final PoolableObject<Integer> lease = claim(pool);
			final CyclicBarrier start = new CyclicBarrier(8);
			final List<Future<?>> invalidators = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				invalidators.add(resources.workers.submit(() -> {
					start.await(5, TimeUnit.SECONDS);
					lease.invalidate();
					return null;
				}));
			}
			for (final Future<?> invalidator : invalidators) {
				result(invalidator);
			}
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isZero();
			assertThat(pool.getCurrentlyAllocated()).isZero();
		}
		result(pool.shutdown());
		assertThat(deallocated.get()).isEqualTo(12);
	}

	@Test
	void invalidatingAnAvailableObjectDoesNotResurrectItOnRelease() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> original = claim(pool);
		original.release();
		original.invalidate();
		original.release();
		original.invalidate();
		assertThat(pool.getCurrentlyAllocated()).isZero();
		assertThat(claim(pool)).isNotSameAs(original);
	}

	private GenericObjectPool<Integer> pool(final int coreSize, final int maxSize, final Allocator<Integer> allocator) {
		return resources.pool(PoolConfig.<Integer>builder().corePoolsize(coreSize).maxPoolsize(maxSize).build(), allocator);
	}

	private PoolableObject<Integer> claim(final GenericObjectPool<Integer> pool) throws InterruptedException {
		return resources.remember(pool.claim(2, TimeUnit.SECONDS));
	}

	private Future<PoolableObject<Integer>> waitingClaim(final GenericObjectPool<Integer> pool) {
		return resources.workers.submit(() -> resources.remember(pool.claim(10, TimeUnit.SECONDS)));
	}

	private static void awaitWaiters(final GenericObjectPool<?> pool, final int count) throws InterruptedException {
		await("blocked claimers = " + count, () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == count);
	}

	private static void awaitLatch(final CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("test latch timed out");
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static class CountingAllocator extends Allocator<Integer> {
		private final AtomicInteger counter = new AtomicInteger();

		@Override
		public Integer allocate() {
			return counter.incrementAndGet();
		}
	}
}
