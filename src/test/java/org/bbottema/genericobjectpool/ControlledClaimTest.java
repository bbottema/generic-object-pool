package org.bbottema.genericobjectpool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bbottema.genericobjectpool.PoolTestResources.await;
import static org.bbottema.genericobjectpool.PoolTestResources.result;

class ControlledClaimTest {

	private final PoolTestResources resources = new PoolTestResources();

	@AfterEach
	void cleanUp() throws Exception {
		resources.close();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preCancelledAndZeroBudgetNeverPrepare(final boolean matching) throws Exception {
		final CountingAllocator allocator = new CountingAllocator();
		final GenericObjectPool<Integer> pool = pool(0, 1, allocator);
		final ClaimControl control = new ClaimControl();
		control.requestCancellation();
		assertThatThrownBy(() -> claim(pool, matching, options(control))).isInstanceOf(CancellationException.class);
		assertThat(claim(pool, matching, ClaimOptions.withTimeout(0, SECONDS))).isNull();
		assertThat(allocator.created.get()).isZero();
		assertThat(pool.getPoolMetrics().getCurrentlyWaitingCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancellationOnlyWakesItsWaiterAndDoesNotInterruptTheNextJob(final boolean matching) throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final PoolableObject<Integer> held = resources.remember(pool.claim());
		final ClaimControl control = new ClaimControl();
		final ExecutorService worker = resources.singleWorker();
		final Future<?> cancelled = worker.submit(() -> claim(pool, matching, options(control)));
		final Future<PoolableObject<Integer>> survivor = resources.workers.submit(() -> claim(pool, matching, ClaimOptions.withoutTimeout()));
		await("both claims waiting", () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == 2);
		assertThat(control.requestCancellation()).isTrue();
		assertThatThrownBy(() -> result(cancelled)).hasCauseInstanceOf(CancellationException.class);
		assertThat(result(worker.submit(() -> Thread.currentThread().isInterrupted()))).isFalse();
		assertThat(survivor.isDone()).isFalse();
		held.release();
		assertThat(result(survivor)).isSameAs(held);
	}

	@Test
	void poolLockContentionIsCancellable() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final ReentrantLock lock = internalLock(pool, "claimLock");
		final ClaimControl control = new ClaimControl();
		lock.lock();
		try {
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			await("waiting for bookkeeping lock", lock::hasQueuedThreads);
			control.requestCancellation();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
		} finally {
			lock.unlock();
		}
	}

	@Test
	void allocatorGateContentionDoesNotBlockCancellationOrMetrics() throws Exception {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final GenericObjectPool<Integer> pool = pool(0, 2, new CountingAllocator() {
			@Override public Integer allocate() {
				entered.countDown();
				awaitLatch(finish);
				return super.allocate();
			}
		});
		try {
			final Future<?> first = resources.workers.submit(() -> resources.remember(pool.claim()));
			awaitLatch(entered);
			final ClaimControl control = new ClaimControl();
			final Future<?> second = resources.workers.submit(() -> pool.claim(options(control)));
			await("waiting for allocator gate", () -> internalLock(pool, "allocatorLock").hasQueuedThreads());
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isZero();
			control.requestCancellation();
			assertThatThrownBy(() -> result(second)).hasCauseInstanceOf(CancellationException.class);
			assertThat(first.isDone()).isFalse();
			finish.countDown();
			result(first);
		} finally {
			finish.countDown();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void latePreparationIsDisposedBeforeCancellationSettles(final boolean reuse) throws Exception {
		final CountDownLatch preparing = new CountDownLatch(1);
		final CountDownLatch finishPreparation = new CountDownLatch(1);
		final CountDownLatch disposing = new CountDownLatch(1);
		final CountDownLatch finishDisposal = new CountDownLatch(1);
		final CountingAllocator allocator = new CountingAllocator() {
			@Override public Integer allocate(final AllocationContext context) {
				preparing.countDown();
				awaitLatch(finishPreparation);
				return super.allocate();
			}
			@Override public void allocateForReuse(final Integer resource, final AllocationContext context) {
				preparing.countDown();
				awaitLatch(finishPreparation);
			}
			@Override public void deallocate(final Integer resource) {
				disposing.countDown();
				awaitLatch(finishDisposal);
				super.deallocate(resource);
			}
		};
		final GenericObjectPool<Integer> pool = pool(0, 1, allocator);
		if (reuse) {
			resources.remember(pool.claim()).release();
		}
		try {
			final ClaimControl control = new ClaimControl();
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			awaitLatch(preparing);
			control.requestCancellation();
			assertThat(pending.isDone()).isFalse();
			finishPreparation.countDown();
			awaitLatch(disposing);
			assertThat(pending.isDone()).isFalse();
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isZero();
			final Future<Void> shutdown = pool.shutdown();
			assertThat(shutdown.isDone()).isFalse();
			finishDisposal.countDown();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
			result(shutdown);
			assertThat(allocator.disposed.get()).isEqualTo(1);
			assertThat(pool.getCurrentlyAllocated()).isZero();
		} finally {
			finishPreparation.countDown();
			finishDisposal.countDown();
		}
	}

	@Test
	void cooperativeHookCanUnblockPreparationAndIsDetachedAtHandoff() throws Exception {
		final AtomicReference<AllocationContext> captured = new AtomicReference<>();
		final AtomicInteger aborts = new AtomicInteger();
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public Integer allocate(final AllocationContext context) {
				captured.set(context);
				context.onCancellation(aborts::incrementAndGet);
				return super.allocate();
			}
		});
		final ClaimControl control = new ClaimControl();
		final PoolableObject<Integer> first = resources.remember(pool.claim(options(control)));
		first.release();
		final PoolableObject<Integer> second = resources.remember(pool.claim());
		control.requestCancellation();
		assertThat(aborts.get()).isZero();
		assertThat(second.getAllocatedObject()).isEqualTo(1);
		assertThatThrownBy(() -> captured.get().onCancellation(aborts::incrementAndGet)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cancellationHandlerUnblocksCooperativeAllocation() throws Exception {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch abort = new CountDownLatch(1);
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public Integer allocate(final AllocationContext context) {
				context.onCancellation(abort::countDown);
				started.countDown();
				awaitLatch(abort);
				context.throwIfCancellationRequested();
				return super.allocate();
			}
		});
		try {
			final ClaimControl control = new ClaimControl();
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			awaitLatch(started);
			control.requestCancellation();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
			assertThat(pool.getPoolMetrics().getTotalAllocated()).isZero();
		} finally {
			abort.countDown();
		}
	}

	@Test
	void cooperativeAbortFailureIsTheCauseOfCancellation() throws Exception {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch abort = new CountDownLatch(1);
		final IllegalStateException closed = new IllegalStateException("connection aborted");
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public Integer allocate(final AllocationContext context) {
				context.onCancellation(abort::countDown);
				started.countDown();
				awaitLatch(abort);
				throw closed;
			}
		});
		try {
			final ClaimControl control = new ClaimControl();
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			awaitLatch(started);
			control.requestCancellation();
			try {
				result(pending);
				throw new AssertionError("Claim should be cancelled");
			} catch (ExecutionException failure) {
				assertThat(failure.getCause()).isInstanceOf(CancellationException.class).hasCause(closed);
			}
		} finally {
			abort.countDown();
		}
	}

	@Test
	void disposalCompletionCannotBeForgedAndCleanupFailureIsVisible() throws Exception {
		final CountDownLatch disposing = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final IllegalStateException failure = new IllegalStateException("cleanup failed");
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public void deallocate(final Integer resource) {
				disposing.countDown();
				awaitLatch(finish);
				throw failure;
			}
		});
		try {
			final PoolableObject<Integer> lease = resources.remember(pool.claim());
			final CompletableFuture<Void> fake = lease.getDisposalCompletion().toCompletableFuture();
			fake.complete(null);
			lease.release();
			assertThat(lease.getDisposalCompletion().toCompletableFuture().isDone()).isFalse();
			lease.invalidate();
			awaitLatch(disposing);
			assertThat(lease.getDisposalCompletion().toCompletableFuture().isDone()).isFalse();
			finish.countDown();
			assertThatThrownBy(() -> result(lease.getDisposalCompletion().toCompletableFuture())).hasCause(failure);
		} finally {
			finish.countDown();
		}
	}

	@Test
	void releasePreparationDoesNotHoldBookkeepingAndShutdownIncludesIt() throws Exception {
		final CountDownLatch releasing = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public void deallocateForReuse(final Integer resource) {
				releasing.countDown();
				awaitLatch(finish);
			}
		});
		try {
			final PoolableObject<Integer> lease = resources.remember(pool.claim());
			final Future<?> release = resources.workers.submit(lease::release);
			awaitLatch(releasing);
			assertThat(pool.getCurrentlyAllocated()).isEqualTo(1);
			final ClaimControl control = new ClaimControl();
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			await("waiter while release is blocked", () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == 1);
			control.requestCancellation();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
			lease.invalidate();
			final Future<Void> shutdown = pool.shutdown();
			assertThat(shutdown.isDone()).isFalse();
			finish.countDown();
			result(release);
			result(shutdown);
		} finally {
			finish.countDown();
		}
	}

	@Test
	void coreAllocationIsPoolOwnedAndSurvivesCancellationOfAWaiter() throws Exception {
		final CountDownLatch allocating = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final GenericObjectPool<Integer> pool = pool(1, 1, new CountingAllocator() {
			@Override public Integer allocate() {
				allocating.countDown();
				awaitLatch(finish);
				return super.allocate();
			}
		});
		try {
			awaitLatch(allocating);
			final ClaimControl control = new ClaimControl();
			final Future<?> pending = resources.workers.submit(() -> pool.claim(options(control)));
			await("waiting for core allocation", () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == 1);
			control.requestCancellation();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(CancellationException.class);
			finish.countDown();
			assertThat(resources.remember(pool.claim()).getAllocatedObject()).isEqualTo(1);
		} finally {
			finish.countDown();
		}
	}

	@Test
	void expiryAndInterruptionAreDistinctAndMatchingNeverAllocates() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		assertThat(pool.claimMatching(value -> true, ClaimOptions.withTimeout(30, MILLISECONDS))).isNull();
		assertThat(pool.getPoolMetrics().getTotalAllocated()).isZero();
		resources.remember(pool.claim());
		final AtomicReference<Thread> thread = new AtomicReference<>();
		final Future<?> pending = resources.workers.submit(() -> {
			thread.set(Thread.currentThread());
			return pool.claim(ClaimOptions.withoutTimeout());
		});
		await("waiter registered", () -> pool.getPoolMetrics().getCurrentlyWaitingCount() == 1);
		thread.get().interrupt();
		assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(InterruptedException.class);
	}

	@Test
	void handoffRacesNeverLeakCapacityOrAffectTheNextBorrower() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		for (int round = 0; round < 50; round++) {
			final ClaimControl control = new ClaimControl();
			final CyclicBarrier start = new CyclicBarrier(2);
			final Future<PoolableObject<Integer>> pending = resources.workers.submit(() -> {
				start.await(5, SECONDS);
				return resources.remember(pool.claim(options(control)));
			});
			start.await(5, SECONDS);
			control.requestCancellation();
			try {
				final PoolableObject<Integer> lease = result(pending);
				assertThat(lease.getAllocatedObject()).isNotNull();
				lease.release();
			} catch (ExecutionException failure) {
				assertThat(failure.getCause()).isInstanceOf(CancellationException.class);
			}
			final PoolableObject<Integer> next = resources.remember(pool.claim());
			assertThat(pool.getCurrentlyAllocated()).isEqualTo(1);
			assertThat(pool.getPoolMetrics().getCurrentlyClaimed()).isEqualTo(1);
			next.release();
		}
	}

	@Test
	void allocationCallbacksRemainSerializedAndReservationsRespectCapacity() throws Exception {
		final AtomicInteger callbacks = new AtomicInteger();
		final AtomicInteger maxCallbacks = new AtomicInteger();
		final CountingAllocator allocator = new CountingAllocator() {
			@Override public Integer allocate() {
				maxCallbacks.accumulateAndGet(callbacks.incrementAndGet(), Math::max);
				try {
					return super.allocate();
				} finally {
					callbacks.decrementAndGet();
				}
			}
		};
		final GenericObjectPool<Integer> pool = pool(1, 3, allocator);
		final List<Future<?>> jobs = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			jobs.add(resources.workers.submit(() -> {
				final PoolableObject<Integer> lease = resources.remember(pool.claim(ClaimOptions.withTimeout(3, SECONDS)));
				assertThat(lease).isNotNull();
				lease.release();
				return null;
			}));
		}
		for (Future<?> job : jobs) {
			result(job);
		}
		assertThat(maxCallbacks.get()).isEqualTo(1);
		assertThat(allocator.created.get()).isLessThanOrEqualTo(3);
	}

	@Test
	void repeatedSignalsDoNotRestartTheTotalBudget() throws Exception {
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator());
		final AtomicInteger checks = new AtomicInteger();
		final AtomicBoolean signal = new AtomicBoolean(true);
		final Future<?> signalling = resources.workers.submit(() -> {
			while (signal.get()) {
				final ReentrantLock lock = internalLock(pool, "claimLock");
				lock.lock();
				try {
					for (ClaimAttempt waiter : waitingClaims(pool)) {
						waiter.signal();
					}
				} finally {
					lock.unlock();
				}
				Thread.yield();
			}
		});
		try {
			resources.remember(pool.claim()).release();
			final long started = System.nanoTime();
			assertThat(pool.claimMatching(lease -> { checks.incrementAndGet(); return false; },
					ClaimOptions.withTimeout(80, MILLISECONDS))).isNull();
			assertThat(checks.get()).isGreaterThan(1);
			assertThat(MILLISECONDS.convert(System.nanoTime() - started, NANOSECONDS)).isLessThan(1500L);
		} finally {
			signal.set(false);
			result(signalling);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void lateAllocationCannotEscapeAfterTimeoutOrInterruption(final boolean interrupt) throws Exception {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final AtomicReference<AllocationContext> context = new AtomicReference<>();
		final AtomicReference<Thread> worker = new AtomicReference<>();
		final CountingAllocator allocator = new CountingAllocator() {
			@Override public Integer allocate(final AllocationContext current) {
				context.set(current);
				entered.countDown();
				boolean interrupted = false;
				while (true) {
					try {
						assertThat(finish.await(5, SECONDS)).isTrue();
						break;
					} catch (InterruptedException ignored) {
						interrupted = true;
					}
				}
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
				return super.allocate();
			}
		};
		final GenericObjectPool<Integer> pool = pool(0, 1, allocator);
		try {
			final Future<?> pending = resources.workers.submit(() -> {
				worker.set(Thread.currentThread());
				return pool.claim(ClaimOptions.withTimeout(interrupt ? 5000 : 80, MILLISECONDS));
			});
			awaitLatch(entered);
			if (interrupt) {
				worker.get().interrupt();
			} else {
				await("allocation budget expired", () -> context.get().isTimedOut());
			}
			finish.countDown();
			if (interrupt) {
				assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(InterruptedException.class);
			} else {
				assertThat(result(pending)).isNull();
			}
			assertThat(allocator.disposed.get()).isEqualTo(1);
			assertThat(pool.getCurrentlyAllocated()).isZero();
		} finally {
			finish.countDown();
		}
	}

	@Test
	void reuseFailureKeepsItsIdentityAndSuppressedCleanupFailure() throws Exception {
		final IllegalStateException preparation = new IllegalStateException("reuse failed");
		final IllegalStateException cleanup = new IllegalStateException("cleanup failed");
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public void allocateForReuse(final Integer resource) { throw preparation; }
			@Override public void deallocate(final Integer resource) { throw cleanup; }
		});
		resources.remember(pool.claim()).release();
		assertThatThrownBy(() -> pool.claim(ClaimOptions.withoutTimeout())).isSameAs(preparation);
		assertThat(preparation.getSuppressed()).containsExactly(cleanup);
		assertThat(pool.getCurrentlyAllocated()).isZero();
		assertThat(resources.remember(pool.claim()).getAllocatedObject()).isEqualTo(2);
	}

	@Test
	void failedReleaseInvalidatesAndRestoresCapacity() throws Exception {
		final IllegalStateException failure = new IllegalStateException("release failed");
		final GenericObjectPool<Integer> pool = pool(0, 1, new CountingAllocator() {
			@Override public void deallocateForReuse(final Integer resource) { throw failure; }
		});
		final PoolableObject<Integer> first = resources.remember(pool.claim());
		assertThatThrownBy(first::release).isSameAs(failure);
		result(first.getDisposalCompletion().toCompletableFuture());
		assertThat(resources.remember(pool.claim()).getAllocatedObject()).isEqualTo(2);
	}

	@Test
	void shutdownDuringForegroundPreparationWaitsAndDisposesTheLateObject() throws Exception {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final CountingAllocator allocator = new CountingAllocator() {
			@Override public Integer allocate() {
				entered.countDown();
				awaitLatch(finish);
				return super.allocate();
			}
		};
		final GenericObjectPool<Integer> pool = pool(0, 1, allocator);
		try {
			final Future<?> pending = resources.workers.submit(() -> pool.claim(ClaimOptions.withoutTimeout()));
			awaitLatch(entered);
			final Future<Void> shutdown = pool.shutdown();
			assertThat(shutdown.isDone()).isFalse();
			finish.countDown();
			assertThatThrownBy(() -> result(pending)).hasCauseInstanceOf(IllegalStateException.class);
			result(shutdown);
			assertThat(allocator.disposed.get()).isEqualTo(1);
		} finally {
			finish.countDown();
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ClaimAttempt> waitingClaims(final GenericObjectPool<?> pool) {
		try {
			final Field field = GenericObjectPool.class.getDeclaredField("waitingClaims");
			field.setAccessible(true);
			return (List<ClaimAttempt>) field.get(pool);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private GenericObjectPool<Integer> pool(final int core, final int max, final Allocator<Integer> allocator) {
		return resources.pool(PoolConfig.<Integer>builder().corePoolsize(core).maxPoolsize(max).build(), allocator);
	}

	private PoolableObject<Integer> claim(final GenericObjectPool<Integer> pool, final boolean matching, final ClaimOptions options)
			throws InterruptedException {
		return resources.remember(matching ? pool.claimMatching(value -> true, options) : pool.claim(options));
	}

	private static ClaimOptions options(final ClaimControl control) {
		return ClaimOptions.withTimeout(5, SECONDS).withClaimControl(control);
	}

	private static ReentrantLock internalLock(final GenericObjectPool<?> pool, final String name) {
		try {
			final Field field = GenericObjectPool.class.getDeclaredField(name);
			field.setAccessible(true);
			return (ReentrantLock) field.get(pool);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void awaitLatch(final CountDownLatch latch) {
		try {
			assertThat(latch.await(5, SECONDS)).as("test latch released").isTrue();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private static class CountingAllocator extends Allocator<Integer> {
		final AtomicInteger created = new AtomicInteger();
		final AtomicInteger disposed = new AtomicInteger();
		@Override public Integer allocate() { return created.incrementAndGet(); }
		@Override public void deallocate(final Integer resource) { disposed.incrementAndGet(); }
	}
}
