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

package com.palantir.atlasdb.keyvalue.api.cache;

import com.palantir.atlasdb.keyvalue.api.watch.StartTimestamp;
import com.palantir.lock.watch.CommitUpdate;

interface CacheStore {
    TransactionScopedCache getOrCreateCache(StartTimestamp timestamp);

    TransactionScopedCache getCache(StartTimestamp timestamp);

    void removeCache(StartTimestamp timestamp);

    void reset();

    void createReadOnlyCache(StartTimestamp timestamp, CommitUpdate commitUpdate);

    TransactionScopedCache getReadOnlyCache(StartTimestamp timestamp);
}