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