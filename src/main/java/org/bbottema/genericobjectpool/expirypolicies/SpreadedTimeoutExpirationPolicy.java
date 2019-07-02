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
public abstract class SpreadedTimeoutExpirationPolicy<T> implements ExpirationPolicy<T> {
	
	private final long lowerBoundMs;
	private final long upperBoundMs;
	
	SpreadedTimeoutExpirationPolicy(long lowerBound, long upperBound, TimeUnit unit) {
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
		final Map<Object, Long> expiriesMs = poolableObject.getExpiriesMs();
		if (!expiriesMs.containsKey(this)) {
			expiriesMs.put(this, lowerBoundMs + (long) (Math.random() * (upperBoundMs - lowerBoundMs)));
		}
		return _hasExpired(poolableObject);
	}
	
	abstract boolean _hasExpired(@NotNull PoolableObject<T> poolableObject);
}
