package org.bbottema.genericobjectpool.expirypolicies;

import lombok.Value;
import lombok.experimental.NonFinal;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@NonFinal@Value
public abstract class TimeoutExpirationPolicy<T> implements ExpirationPolicy<T> {
	
	private final long expiryAgeMs;
	
	TimeoutExpirationPolicy(long expiryAge, TimeUnit unit) {
		if (expiryAge < 1) {
			throw new IllegalArgumentException("Max permitted age cannot be less than 1");
		}
		this.expiryAgeMs = unit.toMillis(expiryAge);
	}
	
	@Override
	public final boolean hasExpired(@NotNull PoolableObject<T> poolableObject) {
		// not strictly necessary, but might be useful to the end-user
		final Map<ExpirationPolicy, Long> expiriesMs = poolableObject.getExpiriesMs();
		if (!expiriesMs.containsKey(this)) {
			expiriesMs.put(this, expiryAgeMs);
		}
		return _hasExpired(poolableObject);
	}
	
	abstract boolean _hasExpired(@NotNull PoolableObject<T> poolableObject);
}
