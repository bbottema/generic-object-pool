package org.bbottema.genericobjectpool.expirypolicies;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

@NonFinal@Value
@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "Generated code")
public class CombinedExpirationPolicies<T> implements ExpirationPolicy<T> {
	
	@NotNull
	private final Set<ExpirationPolicy<T>> expirationPolicies;
	
	@Override
	public boolean hasExpired(@NotNull PoolableObject<T> poolableObject) {
		boolean expired = false;
		for (ExpirationPolicy<T> expirationPolicy : expirationPolicies) {
			if (expirationPolicy.hasExpired(poolableObject)) {
				expired = true; // don't return, expiry policies mutate poolable objects, which might be useful for inspection
			}
		}
		return expired;
	}
}
