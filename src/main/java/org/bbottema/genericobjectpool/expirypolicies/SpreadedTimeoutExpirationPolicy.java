package org.bbottema.genericobjectpool.expirypolicies;

import lombok.Value;
import lombok.experimental.NonFinal;
import org.jetbrains.annotations.NotNull;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

@NonFinal@Value
abstract class SpreadedTimeoutExpirationPolicy<T> implements ExpirationPolicy<T> {
	
	private final long lowerBoundMs;
	private final long upperBoundMs;
	
	SpreadedTimeoutExpirationPolicy(long lowerBound, long upperBound, @NotNull TimeUnit unit) {
		if (lowerBound < 1) {
			throw new IllegalArgumentException("The lower bound cannot be less than 1.");
		}
		if (upperBound <= lowerBound) {
			throw new IllegalArgumentException("The upper bound must be greater than the lower bound.");
		}
		this.lowerBoundMs = unit.toMillis(lowerBound);
		this.upperBoundMs = unit.toMillis(upperBound);
	}
	
	@Override
	public boolean hasExpired(@NotNull PoolableObject<T> poolableObject) {
		if (poolableObject.getExpiryTimestamp() == null) {
			poolableObject.setExpiryTimestamp(lowerBoundMs + (long) (Math.random() * (upperBoundMs - lowerBoundMs)));
		}
		return _hasExpired(poolableObject);
	}
	
	abstract boolean _hasExpired(@NotNull PoolableObject<T> poolableObject);
}
