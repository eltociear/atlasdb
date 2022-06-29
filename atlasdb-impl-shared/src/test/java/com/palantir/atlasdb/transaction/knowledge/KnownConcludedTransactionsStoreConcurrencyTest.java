/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.transaction.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

import com.google.common.collect.Range;
import com.palantir.atlasdb.keyvalue.api.AutoDelegate_KeyValueService;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.common.concurrent.PTExecutors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.Test;
import org.mockito.Mockito;

@SuppressWarnings("UnstableApiUsage") // RangeSet usage
public class KnownConcludedTransactionsStoreConcurrencyTest {
    private final AtomicBoolean blockCalls = new AtomicBoolean(false);
    private final CountDownLatch latch = new CountDownLatch(1);

    private final KeyValueService delegateKeyValueService = spy(new InMemoryKeyValueService(true));
    private final KeyValueService blockingKeyValueService = (AutoDelegate_KeyValueService) () -> {
        if (blockCalls.get()) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return delegateKeyValueService;
    };
    private final KnownConcludedTransactionsStore knownConcludedTransactionsStore =
            KnownConcludedTransactionsStore.create(blockingKeyValueService);
    private ExecutorService taskExecutor = PTExecutors.newCachedThreadPool();

    @Test
    public void batchesReadsUnderHighConcurrency() throws InterruptedException {
        Map<Integer, Integer> invocationCounts = new HashMap<>();
        for (int i = 1; i < 101; i++) {
            knownConcludedTransactionsStore.supplement(Range.closedOpen(10L, 50L));

            startBlockingKeyValueServiceCalls();
            int numThreads = 100;

            List<Future<Optional<TimestampRangeSet>>> readFutures =
                    scheduleTasksInParallel(numThreads, taskIndex -> knownConcludedTransactionsStore.get());

            List<Optional<TimestampRangeSet>> reads = letTasksRunToCompletion(readFutures, false);
            for (Optional<TimestampRangeSet> read : reads) {
                assertThat(read).contains(TimestampRangeSet.singleRange(Range.closedOpen(10L, 50L)));
            }

            int invocations = Mockito.mockingDetails(delegateKeyValueService)
                    .getInvocations()
                    .size();
            System.err.println(invocations);
            invocationCounts.put(invocations, invocationCounts.getOrDefault(invocations, 0) + 1);

            taskExecutor = PTExecutors.newCachedThreadPool();
            Mockito.reset(delegateKeyValueService);
        }
        System.err.println(invocationCounts);
    }

    @Test
    public void writesPreserveCorrectnessUnderHighConcurrency() throws InterruptedException {
        startBlockingKeyValueServiceCalls();

        int numThreads = 300;
        List<Range<Long>> candidateTimestampRanges = LongStream.range(0, numThreads)
                .mapToObj(index -> Range.closed(2 * index, 2 * index + 1))
                .collect(Collectors.toList());

        List<Future<Void>> supplementFutures = scheduleTasksInParallel(numThreads, taskIndex -> {
            knownConcludedTransactionsStore.supplement(candidateTimestampRanges.get(taskIndex));
            return null;
        });

        letTasksRunToCompletion(supplementFutures, true);

        Optional<TimestampRangeSet> rangesInDb = knownConcludedTransactionsStore.get();
        assertThat(rangesInDb).hasValueSatisfying(timestampRangeSet -> assertThat(
                        timestampRangeSet.timestampRanges().asRanges())
                .isSubsetOf(candidateTimestampRanges));
    }

    @Test
    public void writesOfMostlySimilarRangesAreCoalescedCorrectly() throws InterruptedException {
        startBlockingKeyValueServiceCalls();

        int numThreads = 300;
        int threadsPerRange = 25;
        List<Range<Long>> candidateTimestampRanges = LongStream.range(0, numThreads / threadsPerRange)
                .mapToObj(index -> Range.closed(2 * index, 2 * index + 1))
                .collect(Collectors.toList());

        List<Future<Void>> supplementFutures = scheduleTasksInParallel(numThreads, taskIndex -> {
            knownConcludedTransactionsStore.supplement(candidateTimestampRanges.get(taskIndex / threadsPerRange));
            return null;
        });

        letTasksRunToCompletion(supplementFutures, true);

        Optional<TimestampRangeSet> rangesInDb = knownConcludedTransactionsStore.get();
        assertThat(rangesInDb).hasValueSatisfying(timestampRangeSet -> assertThat(
                        timestampRangeSet.timestampRanges().asRanges())
                .as("Given similarity of ranges, concurrency should be handled smoothly")
                .hasSameElementsAs(candidateTimestampRanges));
    }

    private void startBlockingKeyValueServiceCalls() {
        blockCalls.set(true);
    }

    private <T> List<T> letTasksRunToCompletion(List<Future<T>> taskFutures, boolean failuresPermitted)
            throws InterruptedException {
        latch.countDown();
        taskExecutor.shutdown();
        taskExecutor.awaitTermination(5, TimeUnit.SECONDS);

        List<T> resultAccumulator = new ArrayList<>();
        for (Future<T> taskFuture : taskFutures) {
            try {
                resultAccumulator.add(taskFuture.get());
            } catch (ExecutionException e) {
                if (!failuresPermitted) {
                    Throwable throwable = e.getCause();
                    if (throwable instanceof RuntimeException) {
                        throw (RuntimeException) throwable;
                    }
                    throw new RuntimeException(throwable);
                }
            }
        }
        return resultAccumulator;
    }

    private <T> List<Future<T>> scheduleTasksInParallel(int tasks, Function<Integer, T> taskFactory) {
        return IntStream.range(0, tasks)
                .mapToObj(index -> taskExecutor.submit(() -> taskFactory.apply(index)))
                .collect(Collectors.toList());
    }
}