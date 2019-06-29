package org.bbottema.genericobjectpool.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Value;

import java.util.concurrent.TimeUnit;

@Value
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "Generated code")
public final class Timeout {
	private final long duration;
	private final TimeUnit timeUnit;
	private final long durationMs;
	
	public Timeout(long duration, TimeUnit timeUnit) {
		this.duration = duration;
		this.timeUnit = timeUnit;
		this.durationMs = timeUnit.toMillis(duration);
	}
}