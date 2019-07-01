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
package org.bbottema.genericobjectpool;

import lombok.Value;
import lombok.experimental.NonFinal;

@NonFinal@Value
public class PoolMetrics {
    private final int currentlyClaimed;
	private final int currentlyWaitingCount;
	private final int currentlyAllocated;
	private final int corePoolsize;
	private final int maxPoolsize;
	private final long totalAllocated;
	private final long totalClaimed;
}