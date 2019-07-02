package org.bbottema.genericobjectpool.expirypolicies;

import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CombinedExpirationPoliciesTest {
	
	private static final long MS_IN_SECOND = 1000;
	
	private CombinedExpirationPolicies<Integer> policy;
	private PoolableObject<Integer> mockPO;
	private Map<ExpirationPolicy, Long> mockExpiries;
	private HashSet<ExpirationPolicy<Integer>> policies;
	
	@Before
	@SuppressWarnings("unchecked")
	public void setup() {
		policies = new HashSet<>();
		policy = new CombinedExpirationPolicies<>(policies);
		mockPO = mock(PoolableObject.class);
		mockExpiries = mock(Map.class);
	}
	
	@Test
	public void testExpirationPolicy_NotExpired_AllPoliciesAlign() {
		TimeoutSinceCreationExpirationPolicy<Integer> policy1 = new TimeoutSinceCreationExpirationPolicy<>(500, SECONDS);
		TimeoutSinceLastAllocationExpirationPolicy<Integer> policy2 = new TimeoutSinceLastAllocationExpirationPolicy<>(500, SECONDS);
		
		configurePoolableObjectBehavior(policy1, 500, policy2, 500);
		
		when(mockPO.ageMs()).thenReturn(499 * MS_IN_SECOND);
		when(mockPO.allocationAgeMs()).thenReturn(499 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isFalse();
		verify(mockExpiries).put(policy1, SECONDS.toMillis(500));
		verify(mockExpiries).put(policy2, SECONDS.toMillis(500));
	}
	
	@Test
	public void testExpirationPolicy_NotExpired_AllPoliciesDontAlign() {
		TimeoutSinceCreationExpirationPolicy<Integer> policy1 = new TimeoutSinceCreationExpirationPolicy<>(100, SECONDS);
		TimeoutSinceLastAllocationExpirationPolicy<Integer> policy2 = new TimeoutSinceLastAllocationExpirationPolicy<>(500, SECONDS);
		
		configurePoolableObjectBehavior(policy1, 100, policy2, 500);
		
		when(mockPO.ageMs()).thenReturn(99 * MS_IN_SECOND);
		when(mockPO.allocationAgeMs()).thenReturn(499 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isFalse();
		verify(mockExpiries).put(policy1, SECONDS.toMillis(100));
		verify(mockExpiries).put(policy2, SECONDS.toMillis(500));
	}
	
	@Test
	public void testExpirationPolicy_Expired_AllPoliciesDontAlign1() {
		TimeoutSinceCreationExpirationPolicy<Integer> policy1 = new TimeoutSinceCreationExpirationPolicy<>(100, SECONDS);
		TimeoutSinceLastAllocationExpirationPolicy<Integer> policy2 = new TimeoutSinceLastAllocationExpirationPolicy<>(500, SECONDS);
		
		configurePoolableObjectBehavior(policy1, 100, policy2, 500);
		
		when(mockPO.ageMs()).thenReturn(101 * MS_IN_SECOND);
		when(mockPO.allocationAgeMs()).thenReturn(499 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isTrue();
		verify(mockExpiries).put(policy1, SECONDS.toMillis(100));
		verify(mockExpiries).put(policy2, SECONDS.toMillis(500));
	}
	
	@Test
	public void testExpirationPolicy_Expired_AllPoliciesDontAlign2() {
		TimeoutSinceCreationExpirationPolicy<Integer> policy1 = new TimeoutSinceCreationExpirationPolicy<>(100, SECONDS);
		TimeoutSinceLastAllocationExpirationPolicy<Integer> policy2 = new TimeoutSinceLastAllocationExpirationPolicy<>(500, SECONDS);
		
		configurePoolableObjectBehavior(policy1, 100, policy2, 500);
		
		when(mockPO.ageMs()).thenReturn(99 * MS_IN_SECOND);
		when(mockPO.allocationAgeMs()).thenReturn(501 * MS_IN_SECOND);
		
		assertThat(policy.hasExpired(mockPO)).isTrue();
		verify(mockExpiries).put(policy1, SECONDS.toMillis(100));
		verify(mockExpiries).put(policy2, SECONDS.toMillis(500));
	}
	
	@SuppressWarnings("SameParameterValue")
	private void configurePoolableObjectBehavior(TimeoutSinceCreationExpirationPolicy<Integer> policy1, int expiryPolicy1, TimeoutSinceLastAllocationExpirationPolicy<Integer> policy2, int expiryPolicy2) {
		policies.add(policy1);
		policies.add(policy2);
		
		Map<ExpirationPolicy, Long> expiries = new HashMap<>();
		expiries.put(policy1, expiryPolicy1 * MS_IN_SECOND);
		expiries.put(policy2, expiryPolicy2 * MS_IN_SECOND);
		
		// 1st call -> false, 2nd call -> true, because of below thenReturn
		when(mockExpiries.containsKey(policy1)).thenReturn(false);
		when(mockExpiries.containsKey(policy2)).thenReturn(false);
		when(mockPO.getExpiriesMs())
				.thenReturn(mockExpiries).thenReturn(expiries)
				.thenReturn(mockExpiries).thenReturn(expiries);
	}
}