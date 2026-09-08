package org.bbottema.genericobjectpool;

import org.bbottema.genericobjectpool.util.Timeout;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimControlTest {
	@Test
	void controlIsOptInOneShotAndOptionsAreImmutable() {
		final ClaimControl control = new ClaimControl();
		final ClaimOptions plain = ClaimOptions.withTimeout(30, SECONDS);
		final AllocationContext attached = plain.withClaimControl(control).start();
		assertThat(attached.isCancellationRequested()).isFalse();
		assertThat(control.requestCancellation()).isTrue();
		assertThat(control.requestCancellation()).isFalse();
		assertThat(attached.isCancellationRequested()).isTrue();
		assertThat(plain.start().isCancellationRequested()).isFalse();
		assertThatThrownBy(attached::throwIfCancellationRequested).isInstanceOf(CancellationException.class);
	}

	@Test
	void validatesOptionsAndSaturatesHugeDurations() {
		assertThatThrownBy(() -> ClaimOptions.withTimeout(-1, SECONDS)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClaimOptions.withTimeout(1, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ClaimOptions.withoutTimeout().withClaimControl(null)).isInstanceOf(NullPointerException.class);
		assertThat(ClaimOptions.withTimeout(0, SECONDS).start().isTimedOut()).isTrue();
		assertThat(ClaimOptions.withTimeout(Long.MAX_VALUE, DAYS).start().getRemainingTime(NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void limitingAnOuterBudgetDoesNotRestartItsClock() {
		final AllocationContext elapsed = new AllocationContext(System.nanoTime() - SECONDS.toNanos(5), SECONDS.toNanos(10), null);
		assertThat(elapsed.limitedTo(new Timeout(3, SECONDS)).isTimedOut()).isTrue();
		assertThat(elapsed.limitedTo(new Timeout(30, SECONDS)).getRemainingTime(SECONDS)).isBetween(3L, 5L);
	}

	@Test
	void handlersAreRemovedIsolatedAndInvokedForEarlierRequests() {
		final ClaimControl control = new ClaimControl();
		final AllocationContext context = ClaimOptions.withoutTimeout().withClaimControl(control).start();
		final AtomicInteger calls = new AtomicInteger();
		context.onCancellation(calls::incrementAndGet).close();
		context.onCancellation(() -> { throw new IllegalStateException("test handler"); });
		context.onCancellation(calls::incrementAndGet);
		control.requestCancellation();
		assertThat(calls.get()).isEqualTo(1);
		context.onCancellation(calls::incrementAndGet);
		assertThat(calls.get()).isEqualTo(2);
		context.detachCancellationHandlers();
		context.detachCancellationHandlers();
		assertThatThrownBy(() -> context.onCancellation(calls::incrementAndGet)).isInstanceOf(IllegalStateException.class);
	}
}
