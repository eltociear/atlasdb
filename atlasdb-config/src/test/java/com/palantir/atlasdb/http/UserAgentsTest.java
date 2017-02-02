/**
 * Copyright 2017 Palantir Technologies
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
package com.palantir.atlasdb.http;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.BlockingDeque;

import org.junit.Test;

public class UserAgentsTest {
    private static final String PACKAGE_VERSION = "1.2";
    private static final String PACKAGE_TITLE = "package";
    private static final String PACKAGE_USER_AGENT = String.format("%s-atlasdb (%s)", PACKAGE_TITLE, PACKAGE_VERSION);

    @Test
    public void userAgentIncludesAtlasDb() {
        assertThat(UserAgents.getUserAgent(PACKAGE_TITLE, PACKAGE_VERSION), is(PACKAGE_USER_AGENT));
    }

    @Test
    public void canGetUserAgentDataFromPackage() {
        Package classPackage = mock(Package.class);
        when(classPackage.getImplementationVersion()).thenReturn(PACKAGE_VERSION);
        when(classPackage.getImplementationTitle()).thenReturn(PACKAGE_TITLE);

        assertThat(UserAgents.fromPackage(classPackage), is(PACKAGE_USER_AGENT));
    }

    @Test
    public void addsDefaultUserAgentDataIfUnknown() {
        Package classPackage = mock(Package.class);
        when(classPackage.getImplementationTitle()).thenReturn(null);
        when(classPackage.getImplementationVersion()).thenReturn(null);

        assertThat(UserAgents.fromPackage(classPackage), is("unknown-atlasdb (unknown)"));
    }

    @Test
    public void canGetUserAgentDataFromClass() {
        Class<BlockingDeque> clazz = BlockingDeque.class;

        String expectedUserAgent = String.format("%s-atlasdb (%s)",
                clazz.getPackage().getImplementationTitle(),
                clazz.getPackage().getImplementationVersion());
        assertThat(UserAgents.fromClass(clazz), is(expectedUserAgent));
    }
}
