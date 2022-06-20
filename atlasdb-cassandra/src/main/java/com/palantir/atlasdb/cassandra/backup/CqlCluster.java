/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.cassandra.backup;

import com.datastax.driver.core.Cluster;
import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.collect.RangeSet;
import com.palantir.atlasdb.cassandra.CassandraServersConfigs.CassandraServersConfig;
import com.palantir.atlasdb.cassandra.backup.transaction.TransactionsTableInteraction;
import com.palantir.atlasdb.keyvalue.cassandra.LightweightOppToken;
import com.palantir.atlasdb.keyvalue.cassandra.async.client.creation.ClusterFactory;
import com.palantir.atlasdb.keyvalue.cassandra.async.client.creation.ClusterFactory.CassandraClusterConfig;
import com.palantir.atlasdb.timelock.api.Namespace;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class CqlCluster implements Closeable {
    private static final int RETRY_COUNT = 5;

    private final Cluster cluster;
    private final CassandraServersConfig cassandraServersConfig;
    private final Namespace namespace;
    private final Retryer<CqlSession> cqlSessionRetryer;

    // VisibleForTesting
    public CqlCluster(Cluster cluster, CassandraServersConfig cassandraServersConfig, Namespace namespace) {
        this.cluster = cluster;
        this.cassandraServersConfig = cassandraServersConfig;
        this.namespace = namespace;
        this.cqlSessionRetryer = new Retryer<>(
                StopStrategies.stopAfterAttempt(RETRY_COUNT),
                WaitStrategies.fixedWait(10L, TimeUnit.SECONDS),
                Attempt::hasResult);
    }

    public static CqlCluster create(
            CassandraClusterConfig cassandraClusterConfig,
            CassandraServersConfig cassandraServersConfig,
            Namespace namespace) {
        Cluster cluster =
                new ClusterFactory(Cluster::builder).constructCluster(cassandraClusterConfig, cassandraServersConfig);
        return new CqlCluster(cluster, cassandraServersConfig, namespace);
    }

    @Override
    public void close() throws IOException {
        cluster.close();
    }

    public Map<InetSocketAddress, RangeSet<LightweightOppToken>> getTokenRanges(String tableName) {
        try (CqlSession session = createSessionWithRetry()) {
            return new TokenRangeFetcher(session, namespace, cassandraServersConfig).getTokenRange(tableName);
        }
    }

    public Map<String, Map<InetSocketAddress, RangeSet<LightweightOppToken>>> getTransactionsTableRangesForRepair(
            List<TransactionsTableInteraction> transactionsTableInteractions) {
        try (CqlSession session = createSessionWithRetry()) {
            return new RepairRangeFetcher(session, namespace, cassandraServersConfig)
                    .getTransactionTableRangesForRepair(transactionsTableInteractions);
        }
    }

    public void abortTransactions(long timestamp, List<TransactionsTableInteraction> transactionsTableInteractions) {
        try (CqlSession session = createSessionWithRetry()) {
            new TransactionAborter(session, namespace).abortTransactions(timestamp, transactionsTableInteractions);
        }
    }

    private CqlSession createSessionWithRetry() {
        try {
            return cqlSessionRetryer.call(() -> new CqlSession(cluster.connect()));
        } catch (ExecutionException e) {
            throw new SafeIllegalStateException(
                    "Failed to execute CqlSession connect", e, SafeArg.of("namespace", namespace));
        } catch (RetryException e) {
            throw new SafeIllegalStateException(
                    "Failed to execute CqlSession connect even with retry", e, SafeArg.of("namespace", namespace));
        }
    }
}
