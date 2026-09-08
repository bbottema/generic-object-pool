package org.bbottema.genericobjectpool;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, reusable settings for opt-in acquisition. The timeout covers this claim, including lock waiting and
 * preparation; non-cooperative callbacks and required cleanup may delay its return beyond that budget.
 * A retained {@link ClaimControl} remains live rather than being copied as a snapshot.
 *
 * @since 2.5.0
 */
public final class ClaimOptions {
	private final long timeoutNanos;
	private final ClaimControl claimControl;

	private ClaimOptions(final long timeoutNanos, final ClaimControl claimControl) {
		this.timeoutNanos = timeoutNanos;
		this.claimControl = claimControl;
	}

	/** Creates settings with a non-negative total acquisition budget; zero never starts preparation. */
	public static ClaimOptions withTimeout(final long duration, final TimeUnit unit) {
		if (duration < 0) {
			throw new IllegalArgumentException("Claim timeout must not be negative");
		}
		return new ClaimOptions(requireNonNull(unit, "unit").toNanos(duration), null);
	}

	/** Creates settings with no time limit; a claim control can still cancel the acquisition. */
	public static ClaimOptions withoutTimeout() {
		return new ClaimOptions(Long.MAX_VALUE, null);
	}

	/** Returns new settings using this control; neither object requests cancellation merely by being configured. */
	public ClaimOptions withClaimControl(final ClaimControl control) {
		return new ClaimOptions(timeoutNanos, requireNonNull(control, "control"));
	}

	/**
	 * Starts one monotonic acquisition budget. Pool integrations call this before selection and pass the resulting
	 * context down without restarting it. Ordinary callers can pass these options directly to a pool's claim method.
	 */
	public AllocationContext start() {
		return new AllocationContext(System.nanoTime(), timeoutNanos, claimControl);
	}
}
