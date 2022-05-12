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

package com.palantir.atlasdb.pue;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetException;
import com.palantir.atlasdb.keyvalue.api.KeyAlreadyExistsException;
import com.palantir.atlasdb.transaction.encoding.TwoPhaseEncodingStrategy;
import com.palantir.common.streams.KeyedStream;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import org.immutables.value.Value;

public class ResilientCommitTimestampPutUnlessExistsTable implements PutUnlessExistsTable<Long, Long> {
    private static final int TOUCH_CACHE_SIZE = 1000;

    private final ConsensusForgettingStore store;
    private final TwoPhaseEncodingStrategy encodingStrategy;
    private final Supplier<Boolean> acceptStagingReadsAsCommitted;

    private final Map<ByteBuffer, Object> rowLocks = new ConcurrentHashMap<>();

    private final LoadingCache<CellInfo, Long> touchCache = Caffeine.newBuilder()
            .maximumSize(TOUCH_CACHE_SIZE)
            .build(new CacheLoader<>() {
                @Override
                @Nonnull
                public Long load(@Nonnull CellInfo cellInfo) {
                    Object lock = rowLocks.computeIfAbsent(
                            ByteBuffer.wrap(cellInfo.cell().getRowName()), x -> x);
                    FollowUpAction followUpAction;
                    synchronized (lock) {
                        followUpAction = touchAndReturn(cellInfo);
                    }
                    if (followUpAction == FollowUpAction.PUT) {
                        store.put(ImmutableMap.of(
                                cellInfo.cell(), encodingStrategy.transformStagingToCommitted(cellInfo.value())));
                    }
                    return cellInfo.commitTs();
                }
            });

    public ResilientCommitTimestampPutUnlessExistsTable(
            ConsensusForgettingStore store, TwoPhaseEncodingStrategy encodingStrategy) {
        this(store, encodingStrategy, () -> false);
    }

    public ResilientCommitTimestampPutUnlessExistsTable(
            ConsensusForgettingStore store,
            TwoPhaseEncodingStrategy encodingStrategy,
            Supplier<Boolean> acceptStagingReadsAsCommitted) {
        this.store = store;
        this.encodingStrategy = encodingStrategy;
        this.acceptStagingReadsAsCommitted = acceptStagingReadsAsCommitted;
    }

    @Override
    public void putUnlessExistsMultiple(Map<Long, Long> keyValues) throws KeyAlreadyExistsException {
        Map<Cell, Long> cellToStartTs = keyValues.keySet().stream()
                .collect(Collectors.toMap(encodingStrategy::encodeStartTimestampAsCell, x -> x));
        Map<Cell, byte[]> stagingValues = KeyedStream.stream(cellToStartTs)
                .map(startTs -> encodingStrategy.encodeCommitTimestampAsValue(
                        startTs, PutUnlessExistsValue.staging(keyValues.get(startTs))))
                .collectToMap();
        store.putUnlessExists(stagingValues);
        store.put(KeyedStream.stream(stagingValues)
                .map(encodingStrategy::transformStagingToCommitted)
                .collectToMap());
    }

    @Override
    public ListenableFuture<Map<Long, Long>> get(Iterable<Long> cells) {
        Map<Long, Cell> startTsToCell = StreamSupport.stream(cells.spliterator(), false)
                .collect(Collectors.toMap(x -> x, encodingStrategy::encodeStartTimestampAsCell));
        ListenableFuture<Map<Cell, byte[]>> asyncReads = store.getMultiple(startTsToCell.values());
        return Futures.transform(
                asyncReads,
                presentValues -> processReads(presentValues, startTsToCell),
                MoreExecutors.directExecutor());
    }

    private FollowUpAction touchAndReturn(CellInfo cellAndValue) {
        if (acceptStagingReadsAsCommitted.get()) {
            return FollowUpAction.PUT;
        }
        Cell cell = cellAndValue.cell();
        byte[] actual = cellAndValue.value();
        try {
            store.checkAndTouch(cell, actual);
            return FollowUpAction.PUT;
        } catch (CheckAndSetException e) {
            long startTs = cellAndValue.startTs();
            PutUnlessExistsValue<Long> currentValue = encodingStrategy.decodeValueAsCommitTimestamp(startTs, actual);
            Long commitTs = currentValue.value();
            PutUnlessExistsValue<Long> kvsValue = encodingStrategy.decodeValueAsCommitTimestamp(
                    startTs, Iterables.getOnlyElement(e.getActualValues()));
            Preconditions.checkState(
                    kvsValue.equals(PutUnlessExistsValue.committed(commitTs)),
                    "Failed to persist a staging value for commit timestamp because an unexpected value "
                            + "was found in the KVS",
                    SafeArg.of("kvsValue", kvsValue),
                    SafeArg.of("stagingValue", currentValue));
            return FollowUpAction.NONE;
        }
    }

    private Map<Long, Long> processReads(Map<Cell, byte[]> reads, Map<Long, Cell> startTsToCell) {
        ImmutableMap.Builder<Long, Long> resultBuilder = ImmutableMap.builder();
        for (Map.Entry<Long, Cell> startTsAndCell : startTsToCell.entrySet()) {
            Cell cell = startTsAndCell.getValue();
            Optional<byte[]> maybeActual = Optional.ofNullable(reads.get(cell));
            if (maybeActual.isEmpty()) {
                continue;
            }

            Long startTs = startTsAndCell.getKey();
            byte[] actual = maybeActual.get();
            PutUnlessExistsValue<Long> currentValue = encodingStrategy.decodeValueAsCommitTimestamp(startTs, actual);

            Long commitTs = currentValue.value();
            if (currentValue.isCommitted()) {
                resultBuilder.put(startTs, commitTs);
                continue;
            }
            // todo(gmaretic): time this call, if it gets too slow or starts throwing we can temporarily start accepting
            // staging values
            resultBuilder.put(startTs, touchCache.get(ImmutableCellInfo.of(cell, startTs, commitTs, actual)));
        }
        return resultBuilder.build();
    }

    @Value.Immutable
    interface CellInfo {
        @Value.Parameter
        Cell cell();

        @Value.Parameter
        long startTs();

        @Value.Parameter
        long commitTs();

        @Value.Parameter
        byte[] value();
    }

    private enum FollowUpAction {
        PUT,
        NONE;
    }
}
