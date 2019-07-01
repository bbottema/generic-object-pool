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