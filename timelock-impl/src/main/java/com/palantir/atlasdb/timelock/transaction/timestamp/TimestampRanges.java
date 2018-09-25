/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.timelock.transaction.timestamp;

import com.google.common.base.Preconditions;
import com.palantir.timestamp.TimestampRange;

/**
 * Utility methods for manipulating ranges of timestamps.
 */
public final class TimestampRanges {
    private TimestampRanges() {
        // utility
    }

    /**
     * Returns a timestamp in the provided timestamp range that has the provided residue class modulo the provided
     * modulus.
     *
     * We do not make any guarantees on which timestamp is provided if multiple timestamps in the range reside
     * in the provided residue class. For example, if the TimestampRange is from 1 to 5 inclusive and we want a
     * timestamp with residue 1 modulo 2, this method may return any of 1, 3 and 5.
     *
     * It is expected that the {@link TimestampRange} passed to this method contains at least one timestamp satisfying
     * the residue/modulus criteria.
     *
     * @param range timestamp range to extract a single timestamp from
     * @param residue desired residue class of the timestamp returned
     * @param modulus modulus used to partition numbers into residue classes
     * @return a timestamp in the given range in the relevant residue class modulo modulus
     * @throws IllegalArgumentException if residue >= modulus, or the range doesn't contain any timestamps satisfying
     *         our condition
     */
    public static long getTimestampMatchingModulus(TimestampRange range, int residue, int modulus) {
        Preconditions.checkArgument(residue < modulus,
                "Residue %s is less than modulus %s - no solutions",
                residue,
                modulus);

        long lowerBound = range.getLowerBound();
        long lowerBoundModulus = lowerBound % modulus;
        long shift = residue < lowerBoundModulus ? modulus + residue - lowerBoundModulus :
                residue - lowerBoundModulus;
        long candidate = lowerBound + shift;

        Preconditions.checkArgument(range.getUpperBound() >= candidate,
                "The provided timestamp range %s doesn't include any timestamp congruent to %s mod %s",
                range,
                residue,
                modulus);
        return candidate;
    }
}
