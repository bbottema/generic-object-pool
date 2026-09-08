package org.bbottema.genericobjectpool;

import org.bbottema.genericobjectpool.util.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Read-only cancellation state and remaining budget of one acquisition, shared through pool integration layers.
 * Allocators may observe a request and register a cooperative abort, but cannot request cancellation themselves.
 * They must apply the remaining budget to their own blocking work; this context cannot forcibly stop arbitrary code.
 * Do not reuse a started context for an independent claim; start reusable {@link ClaimOptions} again instead.
 *
 * @since 2.5.0
 */
public final class AllocationContext {
	private final long startedAtNanos;
	private final long timeoutNanos;
	private final ClaimControl claimControl;
	private final List<CancellationRegistration> registrations = new ArrayList<>();
	private boolean handlersDetached;

	AllocationContext(final long startedAtNanos, final long timeoutNanos, final ClaimControl claimControl) {
		this.startedAtNanos = startedAtNanos;
		this.timeoutNanos = timeoutNanos;
		this.claimControl = claimControl;
	}

	/** Returns whether the caller requested cancellation, not whether cleanup is finished. */
	public boolean isCancellationRequested() {
		return claimControl != null && claimControl.isCancellationRequested();
	}

	/** Lets a cooperative allocator stop before another side effect when the caller no longer wants the resource. */
	public void throwIfCancellationRequested() {
		if (isCancellationRequested()) {
			throw new CancellationException("Resource claim cancellation requested");
		}
	}

	/** Returns the remaining monotonic budget, rounded down in the supplied unit; unlimited returns Long.MAX_VALUE. */
	public long getRemainingTime(final TimeUnit unit) {
		requireNonNull(unit, "unit");
		if (timeoutNanos == Long.MAX_VALUE) {
			return Long.MAX_VALUE;
		}
		final long elapsed = System.nanoTime() - startedAtNanos;
		return unit.convert(Math.max(0, timeoutNanos - elapsed), TimeUnit.NANOSECONDS);
	}

	/** Returns whether the acquisition budget has expired, independently of an explicit cancellation request. */
	public boolean isTimedOut() {
		return getRemainingTime(TimeUnit.NANOSECONDS) == 0;
	}

	/**
	 * Narrows this running budget using a configured pool limit, without resetting its original starting time.
	 * Integrations apply this before delegating to another pool, not after registering allocator callbacks.
	 */
	public AllocationContext limitedTo(final Timeout timeout) {
		requireNonNull(timeout, "timeout");
		final long limit = Math.max(0, timeout.getTimeUnit().toNanos(timeout.getDuration()));
		return new AllocationContext(startedAtNanos, Math.min(timeoutNanos, limit), claimControl);
	}

	/**
	 * Registers a quick, non-blocking action for an explicit cancellation request. An earlier request invokes it
	 * immediately. Runtime exceptions are logged and ignored. Close the registration when its resource is no longer
	 * owned; the pool also detaches remaining handlers before handoff or failed-claim settlement.
	 * Deadline expiry is observed through the remaining budget, not by requesting cancellation of the caller's job.
	 */
	public CancellationRegistration onCancellation(final Runnable action) {
		requireNonNull(action, "action");
		synchronized (registrations) {
			if (handlersDetached) {
				throw new IllegalStateException("This acquisition has already finished preparing its resource");
			}
			final CancellationRegistration registration = claimControl == null ? () -> { } : claimControl.register(action);
			registrations.add(registration);
			return registration;
		}
	}

	void detachCancellationHandlers() {
		final List<CancellationRegistration> current;
		synchronized (registrations) {
			handlersDetached = true;
			current = new ArrayList<>(registrations);
			registrations.clear();
		}
		for (CancellationRegistration registration : current) {
			registration.close();
		}
	}

	boolean handOffIfActive(final Runnable handoff) {
		final BooleanSupplier complete = () -> {
			if (isTimedOut()) {
				return false;
			}
			handoff.run();
			return true;
		};
		return claimControl == null ? complete.getAsBoolean() : claimControl.handOffUnlessCancelled(complete);
	}
}
