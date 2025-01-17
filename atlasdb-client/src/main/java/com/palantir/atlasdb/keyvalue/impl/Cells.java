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
package com.palantir.atlasdb.keyvalue.impl;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.transaction.api.TransactionConflictException.CellConflict;
import com.palantir.atlasdb.transaction.impl.TransactionConstants;
import com.palantir.common.annotation.Output;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;

public final class Cells {
    private static final SafeLogger log = SafeLoggerFactory.get(Cells.class);

    private Cells() {
        /* */
    }

    public static SortedSet<byte[]> getRows(Iterable<Cell> cells) {
        if (Iterables.isEmpty(cells)) {
            return ImmutableSortedSet.orderedBy(UnsignedBytes.lexicographicalComparator())
                    .build();
        }
        return FluentIterable.from(cells)
                .transform(Cell::getRowName)
                .toSortedSet(UnsignedBytes.lexicographicalComparator());
    }

    static final byte[] SMALLEST_NAME = new byte[] {0};
    static final byte[] LARGEST_NAME = getLargestName();

    static byte[] getLargestName() {
        byte[] name = new byte[Cell.MAX_NAME_LENGTH];
        for (int i = 0; i < Cell.MAX_NAME_LENGTH; i++) {
            name[i] = (byte) 0xff;
        }
        return name;
    }

    public static Cell createSmallestCellForRow(byte[] rowName) {
        return Cell.create(rowName, SMALLEST_NAME);
    }

    public static Cell createLargestCellForRow(byte[] rowName) {
        return Cell.create(rowName, LARGEST_NAME);
    }

    public static Set<Cell> cellsWithConstantColumn(Iterable<byte[]> rows, byte[] col) {
        return cellsWithConstantColumn(new HashSet<Cell>(), rows, col);
    }

    public static Set<Cell> cellsWithConstantColumn(Collection<byte[]> rows, byte[] col) {
        return cellsWithConstantColumn(Sets.<Cell>newHashSetWithExpectedSize(rows.size()), rows, col);
    }

    private static Set<Cell> cellsWithConstantColumn(@Output Set<Cell> collector, Iterable<byte[]> rows, byte[] col) {
        for (byte[] row : rows) {
            collector.add(Cell.create(row, col));
        }
        return collector;
    }

    public static <T> NavigableMap<byte[], NavigableMap<byte[], T>> breakCellsUpByRow(
            Iterable<Map.Entry<Cell, T>> map) {
        NavigableMap<byte[], NavigableMap<byte[], T>> ret = new TreeMap<>(UnsignedBytes.lexicographicalComparator());
        for (Map.Entry<Cell, T> e : map) {
            byte[] row = e.getKey().getRowName();
            NavigableMap<byte[], T> sortedMap =
                    ret.computeIfAbsent(row, rowName -> new TreeMap<>(UnsignedBytes.lexicographicalComparator()));
            sortedMap.put(e.getKey().getColumnName(), e.getValue());
        }
        return ret;
    }

    public static <T> NavigableMap<byte[], NavigableMap<byte[], T>> breakCellsUpByRow(Map<Cell, T> map) {
        return breakCellsUpByRow(map.entrySet());
    }

    public static NavigableMap<byte[], Set<Cell>> groupCellsByRow(Set<Cell> cells) {
        NavigableMap<byte[], Set<Cell>> result = new TreeMap<>(UnsignedBytes.lexicographicalComparator());
        for (Cell cell : cells) {
            byte[] row = cell.getRowName();
            result.computeIfAbsent(row, _row -> new HashSet<>()).add(cell);
        }
        return result;
    }

    /**
     * The Collection provided to this function has to be sorted and strictly increasing.
     */
    public static <T> Iterator<RowResult<T>> createRowView(final Collection<Map.Entry<Cell, T>> sortedIterator) {
        final PeekingIterator<Map.Entry<Cell, T>> it = Iterators.peekingIterator(sortedIterator.iterator());
        Iterator<Map.Entry<byte[], NavigableMap<byte[], T>>> resultIt =
                new AbstractIterator<Map.Entry<byte[], NavigableMap<byte[], T>>>() {
                    byte[] row = null;
                    NavigableMap<byte[], T> map = null;

                    @Override
                    protected Map.Entry<byte[], NavigableMap<byte[], T>> computeNext() {
                        if (!it.hasNext()) {
                            return endOfData();
                        }
                        row = it.peek().getKey().getRowName();
                        ImmutableSortedMap.Builder<byte[], T> mapBuilder =
                                ImmutableSortedMap.orderedBy(UnsignedBytes.lexicographicalComparator());
                        while (it.hasNext()) {
                            Map.Entry<Cell, T> peek = it.peek();
                            if (!Arrays.equals(peek.getKey().getRowName(), row)) {
                                break;
                            }
                            mapBuilder.put(peek.getKey().getColumnName(), peek.getValue());
                            it.next();
                        }
                        map = mapBuilder.build();
                        return Maps.immutableEntry(row, map);
                    }
                };
        return RowResults.viewOfEntries(resultIt);
    }

    public static <T> Map<Cell, T> convertRowResultsToCells(Collection<RowResult<T>> rows) {
        Map<Cell, T> ret = Maps.newHashMapWithExpectedSize(rows.size());
        for (RowResult<T> row : rows) {
            byte[] rowName = row.getRowName();
            for (Map.Entry<byte[], T> col : row.getColumns().entrySet()) {
                Cell cell = Cell.create(rowName, col.getKey());
                ret.put(cell, col.getValue());
            }
        }
        return ret;
    }

    public static <K, V> Map<K, V> constantValueMap(Set<K> keys, V value) {
        return Maps.asMap(keys, Functions.constant(value));
    }

    public static long getApproxSizeOfCell(Cell cell) {
        return cell.getColumnName().length
                + cell.getRowName().length
                + TransactionConstants.APPROX_IN_MEM_CELL_OVERHEAD_BYTES;
    }

    public static Function<byte[], String> getNameFromBytesFunction() {
        return Cells::getNameFromBytes;
    }

    public static String getNameFromBytes(byte[] name) {
        if (name == null) {
            return "null";
        }
        return BaseEncoding.base16().lowerCase().encode(name);
    }

    public static CellConflict createConflict(Cell cell, long theirStartTs, long theirCommitTs) {
        return new CellConflict(cell, getHumanReadableCellName(cell), theirStartTs, theirCommitTs);
    }

    public static String getHumanReadableCellName(Cell cell) {
        if (cell == null) {
            return "null";
        }
        return cell.toString();
    }
}
