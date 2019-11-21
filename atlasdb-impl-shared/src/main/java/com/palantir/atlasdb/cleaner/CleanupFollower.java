/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.atlasdb.cleaner;

import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.palantir.atlasdb.cleaner.api.CleanupFollowerConfig;
import com.palantir.atlasdb.cleaner.api.OnCleanupTask;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.transaction.api.Transaction.TransactionType;
import com.palantir.atlasdb.transaction.api.TransactionManager;
import com.palantir.logsafe.Preconditions;

public final class CleanupFollower implements Follower {
    private final ImmutableMultimap<TableReference, OnCleanupTask> cleanupTasksByTable;
    private final Supplier<CleanupFollowerConfig> cleanupFollowerConfig;

    public static CleanupFollower create(Iterable<Schema> schemas, Supplier<CleanupFollowerConfig> config) {
        ImmutableMultimap.Builder<TableReference, OnCleanupTask> cleanupTasksByTable = ImmutableMultimap.builder();
        for (Schema schema : schemas) {
            cleanupTasksByTable.putAll(schema.getCleanupTasksByTable());
        }
        return new CleanupFollower(cleanupTasksByTable.build(), config);
    }

    public static CleanupFollower create(Schema schema, Supplier<CleanupFollowerConfig> config) {
        return new CleanupFollower(schema.getCleanupTasksByTable(), config);
    }

    private CleanupFollower(
            Multimap<TableReference, OnCleanupTask> cleanupTasksByTable,
            Supplier<CleanupFollowerConfig> cleanupFollowerConfig) {
        this.cleanupTasksByTable = ImmutableMultimap.copyOf(cleanupTasksByTable);
        this.cleanupFollowerConfig = cleanupFollowerConfig;
    }

    @Override
    public void run(
            TransactionManager txManager,
            TableReference tableRef,
            Set<Cell> cells,
            TransactionType transactionType) {
        Collection<OnCleanupTask> nextTasks = cleanupTasksByTable.get(tableRef);
        while (!nextTasks.isEmpty()) {
            final Collection<OnCleanupTask> cleanupTasks = nextTasks;
            nextTasks = txManager.runTaskWithRetry(tx -> {
                Collection<OnCleanupTask> toRetry = Lists.newArrayList();
                Preconditions.checkArgument(transactionType == TransactionType.HARD_DELETE
                        || transactionType == TransactionType.AGGRESSIVE_HARD_DELETE);
                tx.setTransactionType(transactionType);
                for (OnCleanupTask task : cleanupTasks) {
                    boolean needsRetry = task.cellsCleanedUp(tx, cells, cleanupFollowerConfig.get());
                    if (needsRetry) {
                        toRetry.add(task);
                    }
                }
                return toRetry;
            });
        }
    }
}
