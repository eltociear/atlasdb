/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.timelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.timelock.api.DisableNamespacesRequest;
import com.palantir.atlasdb.timelock.api.Namespace;
import com.palantir.atlasdb.timelock.api.SingleNodeUpdateResponse;
import com.palantir.atlasdb.timelock.management.DisabledNamespaces;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TimelockNamespacesTest {
    private static final String CLIENT_A = "a-client";
    private static final String CLIENT_B = "b-client";

    private static final int DEFAULT_MAX_NUMBER_OF_CLIENTS = 5;

    private final TimeLockServices servicesA = mock(TimeLockServices.class);
    private final TimeLockServices servicesB = mock(TimeLockServices.class);

    @Mock
    private Function<String, TimeLockServices> serviceFactory;

    @Mock
    private Supplier<Integer> maxNumberOfClientsSupplier;

    @Mock
    private DisabledNamespaces disabledNamespaces;

    private final MetricsManager metricsManager =
            new MetricsManager(new MetricRegistry(), DefaultTaggedMetricRegistry.getDefault(), unused -> false);
    private TimelockNamespaces namespaces;

    @Before
    public void before() {
        namespaces =
                new TimelockNamespaces(metricsManager, serviceFactory, maxNumberOfClientsSupplier, disabledNamespaces);
        when(serviceFactory.apply(any())).thenReturn(mock(TimeLockServices.class));
        when(serviceFactory.apply(CLIENT_A)).thenReturn(servicesA);
        when(serviceFactory.apply(CLIENT_B)).thenReturn(servicesB);
        when(disabledNamespaces.isEnabled(any())).thenReturn(true);

        when(maxNumberOfClientsSupplier.get()).thenReturn(DEFAULT_MAX_NUMBER_OF_CLIENTS);
    }

    @Test
    public void returnsProperServiceForEachClient() {
        assertThat(namespaces.get(CLIENT_A)).isEqualTo(servicesA);
        assertThat(namespaces.get(CLIENT_B)).isEqualTo(servicesB);
    }

    @Test
    public void servicesAreOnlyCreatedOncePerClient() {
        namespaces.get(CLIENT_A);
        namespaces.get(CLIENT_A);

        verify(serviceFactory, times(1)).apply(any());
    }

    @Test
    public void cannotCreateServiceForDisabledNamespace() {
        when(disabledNamespaces.isEnabled(Namespace.of(CLIENT_A))).thenReturn(false);

        assertThatThrownBy(() -> namespaces.get(CLIENT_A)).isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    public void canGetIgnoringDisabledWithoutFillingCache() {
        when(disabledNamespaces.isEnabled(Namespace.of(CLIENT_A))).thenReturn(false);

        TimeLockServices servicesIgnoringDisabled = namespaces.getIgnoringDisabled(CLIENT_A);
        assertThat(servicesIgnoringDisabled).isEqualTo(servicesA);

        assertThatThrownBy(() -> namespaces.get(CLIENT_A)).isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    public void doesNotCreateNewClientsAfterMaximumNumberHasBeenReached() {
        createMaximumNumberOfClients();

        assertThatThrownBy(() -> namespaces.get(uniqueClient())).isInstanceOf(IllegalStateException.class);

        verify(serviceFactory, times(DEFAULT_MAX_NUMBER_OF_CLIENTS)).apply(any());
        verifyNoMoreInteractions(serviceFactory);
    }

    @Test
    public void returnsMaxNumberOfClients() {
        createMaximumNumberOfClients();
        assertThat(namespaces.getNumberOfActiveClients()).isEqualTo(DEFAULT_MAX_NUMBER_OF_CLIENTS);
    }

    @Test
    public void onClientCreationIncreaseNumberOfClients() {
        assertThat(namespaces.getNumberOfActiveClients()).isEqualTo(0);
        namespaces.get(uniqueClient());
        assertThat(namespaces.getNumberOfActiveClients()).isEqualTo(1);
    }

    @Test
    public void canDynamicallyIncreaseMaxAllowedClients() {
        createMaximumNumberOfClients();

        when(maxNumberOfClientsSupplier.get()).thenReturn(DEFAULT_MAX_NUMBER_OF_CLIENTS + 1);

        namespaces.get(uniqueClient());
    }

    @Test
    public void numberOfActiveClientsUpdatesAsNewClientsCreated() {
        assertNumberOfActiveClientsIs(0);
        assertMaxClientsIs(DEFAULT_MAX_NUMBER_OF_CLIENTS);

        namespaces.get(uniqueClient());

        assertNumberOfActiveClientsIs(1);
        assertMaxClientsIs(DEFAULT_MAX_NUMBER_OF_CLIENTS);

        namespaces.get(uniqueClient());

        assertNumberOfActiveClientsIs(2);
        assertMaxClientsIs(DEFAULT_MAX_NUMBER_OF_CLIENTS);
    }

    @Test
    public void maxNumberOfClientsRespondsToChanges() {
        assertNumberOfActiveClientsIs(0);
        assertMaxClientsIs(DEFAULT_MAX_NUMBER_OF_CLIENTS);

        when(maxNumberOfClientsSupplier.get()).thenReturn(1);

        assertNumberOfActiveClientsIs(0);
        assertMaxClientsIs(1);

        when(maxNumberOfClientsSupplier.get()).thenReturn(77);

        assertNumberOfActiveClientsIs(0);
        assertMaxClientsIs(77);
    }

    @Test
    public void pathsForTimelockAndLockWatchServicesAreNotValid() {
        assertThat(TimelockNamespaces.IS_VALID_NAME.test("tl")).isFalse();
        assertThat(TimelockNamespaces.IS_VALID_NAME.test("lw")).isFalse();
        assertThat(TimelockNamespaces.IS_VALID_NAME.test("tlblah")).isTrue();
        assertThat(TimelockNamespaces.IS_VALID_NAME.test("lwbleh")).isTrue();
    }

    @Test
    public void invalidationDelegatesClosure() {
        // This is required to ensure we get different mock objects on each invocation (well, each of the first two).
        when(serviceFactory.apply(any()))
                .thenReturn(mock(TimeLockServices.class))
                .thenReturn(mock(TimeLockServices.class));

        String client = uniqueClient();
        TimeLockServices services = namespaces.get(client);
        namespaces.invalidateResourcesForClient(client);
        verify(services).close();

        TimeLockServices newServices = namespaces.get(client);
        assertThat(newServices).as("should have gotten a new set of delegates").isNotEqualTo(services);

        namespaces.invalidateResourcesForClient(client);
        verify(newServices).close();
    }

    @Test
    public void handlesInvalidationOfNonexistentClients() {
        assertThatCode(() -> namespaces.invalidateResourcesForClient("somethingUnknown"))
                .doesNotThrowAnyException();
    }

    @Test
    public void disableInvalidatesServices() {
        String client = uniqueClient();
        TimeLockServices services = namespaces.get(client);

        String lockId = "lockId";
        when(disabledNamespaces.disable(any())).thenReturn(SingleNodeUpdateResponse.successful());

        namespaces.disable(DisableNamespacesRequest.of(ImmutableSet.of(Namespace.of(client)), lockId));
        verify(services).close();
    }

    @Test
    public void disableDoesNotInvalidateServicesOnFailure() {
        String client = uniqueClient();
        TimeLockServices services = namespaces.get(client);

        ImmutableSet<Namespace> namespacesToDisable = ImmutableSet.of(Namespace.of(client));
        String lockId = "lockId";
        Map<Namespace, String> lockedNamespace = ImmutableMap.of(Namespace.of(client), lockId);
        when(disabledNamespaces.disable(any())).thenReturn(SingleNodeUpdateResponse.failed(lockedNamespace));

        namespaces.disable(DisableNamespacesRequest.of(namespacesToDisable, lockId));
        verify(services, never()).close();
    }

    private void createMaximumNumberOfClients() {
        for (int i = 0; i < DEFAULT_MAX_NUMBER_OF_CLIENTS; i++) {
            namespaces.get(uniqueClient());
        }
    }

    private String uniqueClient() {
        return UUID.randomUUID().toString();
    }

    private void assertNumberOfActiveClientsIs(int expected) {
        assertThat(getGaugeValueForTimeLockResource(TimelockNamespaces.ACTIVE_CLIENTS))
                .isEqualTo(expected);
    }

    private void assertMaxClientsIs(int expected) {
        assertThat(getGaugeValueForTimeLockResource(TimelockNamespaces.MAX_CLIENTS))
                .isEqualTo(expected);
    }

    private int getGaugeValueForTimeLockResource(String gaugeName) {
        Object value = Optional.ofNullable(metricsManager
                        .getRegistry()
                        .getGauges()
                        .get(TimelockNamespaces.class.getCanonicalName() + "." + gaugeName)
                        .getValue())
                .orElseThrow(() -> new IllegalStateException("Gauge with gauge name " + gaugeName + " did not exist."));
        return (int) value;
    }
}
