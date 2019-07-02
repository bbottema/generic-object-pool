package org.bbottema.genericobjectpool.expirypolicies;

import org.assertj.core.util.Maps;
import org.bbottema.genericobjectpool.PoolableObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TimeoutSinceLastAllocationExpirationPolicyTest {
	
	private static final long MS_IN_SECOND = 1000;
	
	private TimeoutSinceLastAllocationExpirationPolicy<Integer> policy;
	private PoolableObject<Integer> mockPO;
	private Map<Object, Long> mockExpiries;
	
	@Before
	@SuppressWarnings("unchecked")
	public void setup() {
		policy = new TimeoutSinceLastAllocationExpirationPolicy<>(500, SECONDS);
		mockPO = mock(PoolableObject.class);
		mockExpiries = mock(Map.class);
		// 1st call -> false, 2nd call -> true, because of below thenReturn
		when(mockExpiries.containsKey(policy)).thenReturn(false);
		when(mockPO.getExpiriesMs())
				.thenReturn(mockExpiries)
				.thenReturn(Maps.newHashMap((Object) policy, 500 * MS_IN_SECOND));
	}
	
	@Test
	public void testExpirationPolicy_NotExpired_Ignore_CreationAge() {
		when(mockPO.ageMs()).thenReturn(Long.MAX_VALUE);
		when(mockPO.allocationAgeMs()).thenReturn(499 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isFalse();
		verify(mockExpiries).put(policy, SECONDS.toMillis(500));
	}
	
	@Test
	public void testExpirationPolicy_Expired_Ignore_CreationAge() {
		when(mockPO.ageMs()).thenReturn(Long.MIN_VALUE);
		when(mockPO.allocationAgeMs()).thenReturn(501 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isTrue();
		verify(mockExpiries).put(policy, SECONDS.toMillis(500));
	}
}