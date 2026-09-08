package org.bbottema.genericobjectpool;

import org.bbottema.genericobjectpool.util.Timeout;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/** Keeps legacy wait semantics separate from one cancellable, total-budget acquisition. */
final class ClaimAttempt implements AutoCloseable {

	private static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
	private final Timeout legacyTimeout;
	private final AllocationContext context;
	private final boolean matching;
	private final long startedAtNanos = System.nanoTime();
	private final Semaphore wakeUp = new Semaphore(0);
	private boolean waited;

	ClaimAttempt(final Timeout legacyTimeout, final AllocationContext context, final boolean matching) {
		this.legacyTimeout = legacyTimeout;
		this.context = context;
		this.matching = matching;
		if (context != null) {
			context.onCancellation(this::signal);
		}
	}

	AllocationContext getContext() {
		return context;
	}

	boolean isControlled() {
		return context != null;
	}

	boolean hasWaited() {
		return waited;
	}

	boolean canContinue() throws InterruptedException {
		if (context == null) {
			return true;
		}
		context.throwIfCancellationRequested();
		if (Thread.interrupted()) {
			throw new InterruptedException("Resource claim interrupted");
		}
		return !context.isTimedOut();
	}

	boolean acquire(final Lock lock) throws InterruptedException {
		if (context == null) {
			lock.lock();
			return true;
		}
		while (canContinue()) {
			if (lock.tryLock(Math.min(RECHECK_NANOS, context.getRemainingTime(TimeUnit.NANOSECONDS)), TimeUnit.NANOSECONDS)) {
				return true;
			}
		}
		return false;
	}

	void prepareToWait() {
		wakeUp.drainPermits();
	}

	void signal() {
		wakeUp.release();
	}

	boolean awaitAvailability() throws InterruptedException {
		waited = true;
		if (!canContinue()) {
			return false;
		}
		final long remaining = remainingWaitNanos();
		final long interval = matching ? Math.min(RECHECK_NANOS, remaining) : remaining;
		final boolean signalled = wakeUp.tryAcquire(interval, TimeUnit.NANOSECONDS);
		return canContinue() && (signalled || matching && remainingWaitNanos() > 0);
	}

	private long remainingWaitNanos() {
		if (context != null) {
			return context.getRemainingTime(TimeUnit.NANOSECONDS);
		}
		final long duration = legacyTimeout.getTimeUnit().toNanos(legacyTimeout.getDuration());
		return matching && duration != Long.MAX_VALUE ? Math.max(0, duration - (System.nanoTime() - startedAtNanos)) : duration;
	}

	boolean handOff(final Runnable handoff) throws InterruptedException {
		if (!canContinue()) {
			return false;
		}
		if (context != null) {
			return context.handOffIfActive(handoff);
		}
		handoff.run();
		return true;
	}

	Throwable preparationFailure(final Throwable failure) {
		if (context != null && context.isCancellationRequested() && !(failure instanceof CancellationException) && !(failure instanceof Error)) {
			final CancellationException cancelled = new CancellationException("Resource claim cancellation requested");
			cancelled.initCause(failure);
			return cancelled;
		}
		return failure;
	}

	@Override
	public void close() {
		if (context != null) {
			context.detachCancellationHandlers();
		}
	}
}
