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

package com.palantir.paxos;

import java.sql.Connection;
import java.util.Arrays;
import java.util.function.Supplier;

import org.junit.Test;

public class SqlitePaxosStateLogTest {
    private final Supplier<Connection> connectionSupplier = Sqlites.createDatabaseForTests();
    private final SqlitePaxosStateLog<PaxosValue> stateLog = new SqlitePaxosStateLog<>(connectionSupplier);

    @Test
    public void canWriteAValue() {
        System.out.println(Arrays.toString(stateLog.readRound(52)));
    }
}
