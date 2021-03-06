/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.shell;

import java.io.IOException;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.rya.accumulo.MiniAccumuloSingleton;
import org.apache.rya.accumulo.RyaTestInstanceRule;
import org.apache.zookeeper.ClientCnxn;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.springframework.shell.Bootstrap;
import org.springframework.shell.core.JLineShellComponent;

import org.apache.rya.accumulo.MiniAccumuloClusterInstance;

/**
 * All Rya Shell integration tests should extend this one. It provides startup
 * and shutdown hooks for a Mini Accumulo Cluster when you start and stop testing.
 * It also creates a new shell to test with between each test.
 */
public class RyaShellAccumuloITBase {

    /**
     * The bootstrap that was used to initialize the Shell that will be tested.
     */
    private Bootstrap bootstrap;

    /**
     * The shell that will be tested.
     */
    private JLineShellComponent shell;

    @Rule
    public RyaTestInstanceRule testInstance = new RyaTestInstanceRule(false);

    @BeforeClass
    public static void killLoudLogs() {
        Logger.getLogger(ClientCnxn.class).setLevel(Level.ERROR);
    }

    @Before
    public void startShell() throws IOException, InterruptedException, AccumuloException, AccumuloSecurityException {
        // Bootstrap the shell with the test bean configuration.
        bootstrap = new Bootstrap(new String[]{}, new String[]{"file:src/test/resources/RyaShellTest-context.xml"});
        shell = bootstrap.getJLineShellComponent();
    }

    @After
    public void stopShell() throws IOException, InterruptedException {
        shell.stop();
    }

    /**
     * @return The bootstrap that was used to initialize the Shell that will be tested.
     */
    public Bootstrap getTestBootstrap() {
        return bootstrap;
    }

    /**
     * @return The shell that will be tested.
     */
    public JLineShellComponent getTestShell() {
        return shell;
    }

    /**
     * @return The cluster that is hosting the test.
     */
    public MiniAccumuloCluster getCluster() {
        return MiniAccumuloSingleton.getInstance().getCluster();
    }

    public String getInstanceName() {
        return testInstance.getRyaInstanceName();
    }

}