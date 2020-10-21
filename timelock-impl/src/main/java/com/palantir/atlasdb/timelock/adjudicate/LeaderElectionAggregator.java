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

package com.palantir.atlasdb.timelock.adjudicate;

import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.atlasdb.util.SlidingWindowWeightedMeanGauge;
import com.palantir.timelock.feedback.LeaderElectionStatistics;

public final class LeaderElectionAggregator {
    private final SlidingWindowWeightedMeanGauge weightedGaugeP99;
    private final SlidingWindowWeightedMeanGauge weightedGaugeP95;
    private final SlidingWindowWeightedMeanGauge weightedGaugeMean;

    public LeaderElectionAggregator(MetricsManager metricsManager) {
        weightedGaugeP99 = SlidingWindowWeightedMeanGauge.create();
        weightedGaugeP95 = SlidingWindowWeightedMeanGauge.create();
        weightedGaugeMean = SlidingWindowWeightedMeanGauge.create();
        metricsManager.registerOrGetGauge(
                LeaderElectionAggregator.class, "leaderElectionImpactMean", () -> weightedGaugeMean);
        metricsManager.registerOrGetGauge(
                LeaderElectionAggregator.class, "leaderElectionImpactP95", () -> weightedGaugeP95);
        metricsManager.registerOrGetGauge(
                LeaderElectionAggregator.class, "leaderElectionImpactP99", () -> weightedGaugeP99);
    }

    void report(LeaderElectionStatistics statistics) {
        long count = statistics.getCount().longValue();
        weightedGaugeMean.update(statistics.getMean(), count);
        weightedGaugeP95.update(statistics.getP95(), count);
        weightedGaugeP99.update(statistics.getP99(), count);
    }
}