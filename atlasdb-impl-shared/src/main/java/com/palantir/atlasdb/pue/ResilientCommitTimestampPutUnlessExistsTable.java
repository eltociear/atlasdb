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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.immutables.value.Value;

public class ResilientCommitTimestampPutUnlessExistsTable implements PutUnlessExistsTable<Long, Long> {
    private final ConsensusForgettingStore store;
    private final TwoPhaseEncodingStrategy encodingStrategy;
    private final LoadingCache<CellAndValue, RequiresPut> needsPutCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .build(new CacheLoader<>() {
                @Override
                public RequiresPut load(CellAndValue cellAndValue) {
                    return touchAndReturn(cellAndValue);
                }
            });

    private RequiresPut touchAndReturn(CellAndValue cellAndValue) {
        Cell cell = cellAndValue.cell();
        byte[] actual = cellAndValue.value();
        try {
            store.checkAndTouch(cell, actual);
            return RequiresPut.YES;
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
            return RequiresPut.NO;
        }
    }

    public ResilientCommitTimestampPutUnlessExistsTable(
            ConsensusForgettingStore store, TwoPhaseEncodingStrategy encodingStrategy) {
        this.store = store;
        this.encodingStrategy = encodingStrategy;
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

    private Map<Long, Long> processReads(Map<Cell, byte[]> reads, Map<Long, Cell> startTsToCell) {
        Set<CellAndValue> checkAndTouch = Sets.newHashSet();
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
            try {
                ImmutableCellAndValue cellAndValue = ImmutableCellAndValue.of(cell, startTs, actual);
                RequiresPut result = needsPutCache.get(cellAndValue);
                if (result == RequiresPut.YES) {
                    checkAndTouch.add(cellAndValue);
                }
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            resultBuilder.put(startTs, commitTs);
        }
        store.put(KeyedStream.of(checkAndTouch.stream())
                .mapKeys(CellAndValue::cell)
                .map(CellAndValue::value)
                .map(encodingStrategy::transformStagingToCommitted)
                .collectToMap());
        checkAndTouch.forEach(cellAndValue -> needsPutCache.put(cellAndValue, RequiresPut.NO));
        return resultBuilder.build();
    }

    @Value.Immutable
    interface CellAndValue {
        @Value.Parameter
        Cell cell();

        @Value.Parameter
        long startTs();

        @Value.Parameter
        byte[] value();
    }

    private enum RequiresPut {
        YES,
        NO;
    }
}
