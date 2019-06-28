package org.bbottema.genericobjectpool.expirypolicies;

import lombok.Value;
import lombok.experimental.NonFinal;
import org.jetbrains.annotations.NotNull;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;

import java.util.concurrent.TimeUnit;

@NonFinal@Value
abstract class TimeoutExpirationPolicy<T> implements ExpirationPolicy<T> {
	
	private final long expiryAgeMs;
	
	TimeoutExpirationPolicy(long expiryAge, @NotNull TimeUnit unit) {
		if (expiryAge < 1) {
			throw new IllegalArgumentException("Max permitted age cannot be less than 1");
		}
		this.expiryAgeMs = unit.toMillis(expiryAge);
	}
	
	@Override
	public final boolean hasExpired(@NotNull PoolableObject<T> poolableObject) {
		// not strictly nescesary, but might be useful to the end-user
		if (poolableObject.getExpiryTimestamp() == null) {
			poolableObject.setExpiryTimestamp(expiryAgeMs);
		}
		
		return _hasExpired(poolableObject);
	}
	
	abstract boolean _hasExpired(@NotNull PoolableObject<T> poolableObject);
}
