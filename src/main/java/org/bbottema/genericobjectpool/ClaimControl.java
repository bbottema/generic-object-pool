package org.bbottema.genericobjectpool;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Optional, caller-owned control over pending resource claims. Creating a control does not cancel anything.
 * A job may share one control among its pending claims; cancellation never revokes a resource already handed off.
 * Controls are thread-safe and one-shot: use a new instance for another job.
 *
 * @since 2.5.0
 */
public final class ClaimControl {

	private static final Logger LOGGER = getLogger(ClaimControl.class);
	private final List<Registration> registrations = new ArrayList<>();
	private volatile boolean cancellationRequested;

	/**
	 * Requests cancellation of the associated pending claims. Does not wait for their allocation or cleanup to finish.
	 * Registered cooperative handlers run on this thread and must be quick and non-blocking.
	 *
	 * @return true for the first request, not confirmation that all associated work has stopped
	 */
	public boolean requestCancellation() {
		final List<Registration> pending;
		synchronized (this) {
			if (cancellationRequested) {
				return false;
			}
			cancellationRequested = true;
			pending = new ArrayList<>(registrations);
			registrations.clear();
		}
		for (Registration registration : pending) {
			registration.notifyCancellation();
		}
		return true;
	}

	/** Returns whether cancellation was requested, which is different from confirmation that a claim has settled. */
	public boolean isCancellationRequested() {
		return cancellationRequested;
	}

	CancellationRegistration register(final Runnable action) {
		final Registration registration = new Registration(requireNonNull(action, "action"));
		final boolean alreadyRequested;
		synchronized (this) {
			alreadyRequested = cancellationRequested;
			if (!alreadyRequested) {
				registrations.add(registration);
			}
		}
		if (alreadyRequested) {
			registration.notifyCancellation();
		}
		return registration;
	}

	/** The pool already holds its bookkeeping lock; the supplied handoff must contain no blocking work. */
	synchronized boolean handOffUnlessCancelled(final BooleanSupplier handoff) {
		return !cancellationRequested && handoff.getAsBoolean();
	}

	private final class Registration implements CancellationRegistration {
		private final Runnable action;
		private boolean active = true;

		private Registration(final Runnable action) {
			this.action = action;
		}

		private synchronized void notifyCancellation() {
			if (active) {
				active = false;
				try {
					action.run();
				} catch (RuntimeException failure) {
					LOGGER.warn("Ignoring a failed claim cancellation handler", failure);
				}
			}
		}

		@Override
		public void close() {
			synchronized (this) {
				// Waiting for an already-running handler fences it out of the next borrower's lifetime.
				active = false;
			}
			synchronized (ClaimControl.this) {
				registrations.remove(this);
			}
		}
	}
}
