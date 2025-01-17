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

package com.palantir.atlasdb.timelock;

import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.lock.v2.IdentifiedTimeLockRequest;
import com.palantir.lock.v2.LockImmutableTimestampResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.RefreshLockResponseV2;
import java.util.Set;

// View containing only the methods necessary for AtlasBackup/RestoreService functionality
public interface BackupTimeLockServiceView {
    long getFreshTimestamp();

    void fastForwardTimestamp(long currentTimestamp);

    LockImmutableTimestampResponse lockImmutableTimestamp(IdentifiedTimeLockRequest request);

    ListenableFuture<RefreshLockResponseV2> refreshLockLeases(Set<LockToken> tokens);

    ListenableFuture<Set<LockToken>> unlock(Set<LockToken> tokens);
}
