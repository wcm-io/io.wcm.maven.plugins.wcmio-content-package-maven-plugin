/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2022 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.apache.sling.junit.teleporter.customizers;

import org.apache.sling.junit.rules.TeleporterRule;
import org.apache.sling.testing.serversetup.instance.SlingTestBase;
import org.apache.sling.testing.teleporter.client.ClientSideTeleporter;
import org.apache.sling.testing.timeouts.TimeoutsProvider;

/** This is required by the TeleporterRule, to setup the client-side
 *  teleporter with (at least) the test server URL.
 */
public class SM_TeleporterCustomizer implements TeleporterRule.Customizer {

    private final static SlingTestBase S = new SlingTestBase();

    @Override
    public void customize(TeleporterRule t, String options) {
        final ClientSideTeleporter cst = (ClientSideTeleporter)t;
        cst.setBaseUrl(S.getServerBaseUrl());
        cst.setServerCredentials(S.getServerUsername(), S.getServerPassword());
        cst.setTestReadyTimeoutSeconds(TimeoutsProvider.getInstance().getTimeout(5));
    }

}