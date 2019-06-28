package org.bbottema.genericobjectpool.expirypolicies;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("unused")
public class SpreadedTimeoutSinceLastAllocationExpirationPolicy<T> extends SpreadedTimeoutExpirationPolicy<T> {
	public SpreadedTimeoutSinceLastAllocationExpirationPolicy(long lowerBound, long upperBound, @NotNull TimeUnit unit) {
		super(lowerBound, upperBound, unit);
	}
	
	@Override
	@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "False positive")
	boolean _hasExpired(@NotNull PoolableObject<T> poolableObject) {
		return poolableObject.allocationAgeMs() >= requireNonNull(poolableObject.getExpiryTimestamp());
	}
}
