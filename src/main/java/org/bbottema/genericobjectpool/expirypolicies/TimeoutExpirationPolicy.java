/*
 * Copyright (C) 2019 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
		final Map<Object, Long> expiriesMs = poolableObject.getExpiriesMs();
		if (!expiriesMs.containsKey(this)) {
			expiriesMs.put(this, expiryAgeMs);
		}
		return _hasExpired(poolableObject);
	}
	
	abstract boolean _hasExpired(@NotNull PoolableObject<T> poolableObject);
}
