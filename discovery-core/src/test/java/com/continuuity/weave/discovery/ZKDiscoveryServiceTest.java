/**
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.discovery;

import com.continuuity.weave.common.Cancellable;
import com.continuuity.weave.common.Services;
import com.continuuity.weave.internal.zookeeper.InMemoryZKServer;
import com.continuuity.weave.zookeeper.RetryStrategies;
import com.continuuity.weave.zookeeper.ZKClientService;
import com.continuuity.weave.zookeeper.ZKClientServices;
import com.continuuity.weave.zookeeper.ZKClients;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test Zookeeper based discovery service.
 */
public class ZKDiscoveryServiceTest {
  private static InMemoryZKServer zkServer;
  private static ZKClientService zkClient;

  @BeforeClass
  public static void beforeClass() {
    zkServer = InMemoryZKServer.builder().setTickTime(100000).build();
    zkServer.startAndWait();

    zkClient = ZKClientServices.delegate(
      ZKClients.retryOnFailure(
        ZKClients.reWatchOnExpire(
          ZKClientService.Builder.of(zkServer.getConnectionStr()).build()),
        RetryStrategies.fixDelay(1, TimeUnit.SECONDS)));
    zkClient.startAndWait();
  }

  @AfterClass
  public static void afterClass() {
    Futures.getUnchecked(Services.chainStop(zkClient, zkServer));
  }

  private Cancellable register(DiscoveryService service, final String name, final String host, final int port) {
    return service.register(new Discoverable() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(host, port);
      }
    });
  }


  private boolean waitTillExpected(int expected, Iterable<Discoverable> discoverables) throws Exception {
    for (int i = 0; i < 10; ++i) {
      TimeUnit.MILLISECONDS.sleep(10);
      if (Iterables.size(discoverables) == expected) {
        return true;
      }
    }
    return (Iterables.size(discoverables) == expected);
  }

  @Test
  public void simpleDiscoverable() throws Exception {
    DiscoveryService discoveryService = new ZKDiscoveryService(zkClient);
    DiscoveryServiceClient discoveryServiceClient = new ZKDiscoveryService(zkClient);

    // Register one service running on one host:port
    Cancellable cancellable = register(discoveryService, "foo", "localhost", 8090);
    Iterable<Discoverable> discoverables = discoveryServiceClient.discover("foo");

    // Discover that registered host:port.
    Assert.assertTrue(waitTillExpected(1, discoverables));

    // Remove the service
    cancellable.cancel();

    // There should be no service.

    discoverables = discoveryServiceClient.discover("foo");

    Assert.assertTrue(waitTillExpected(0, discoverables));
  }

  @Test
  public void manySameDiscoverable() throws Exception {
    List<Cancellable> cancellables = Lists.newArrayList();
    DiscoveryService discoveryService = new ZKDiscoveryService(zkClient);
    DiscoveryServiceClient discoveryServiceClient = new ZKDiscoveryService(zkClient);

    cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 1));
    cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 2));
    cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 3));
    cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 4));
    cancellables.add(register(discoveryService, "manyDiscoverable", "localhost", 5));

    Iterable<Discoverable> discoverables = discoveryServiceClient.discover("manyDiscoverable");
    Assert.assertTrue(waitTillExpected(5, discoverables));

    for (int i = 0; i < 5; i++) {
      cancellables.get(i).cancel();
      Assert.assertTrue(waitTillExpected(4 - i, discoverables));
    }
  }

  @Test
  public void multiServiceDiscoverable() throws Exception {
    List<Cancellable> cancellables = Lists.newArrayList();
    DiscoveryService discoveryService = new ZKDiscoveryService(zkClient);
    DiscoveryServiceClient discoveryServiceClient = new ZKDiscoveryService(zkClient);

    cancellables.add(register(discoveryService, "service1", "localhost", 1));
    cancellables.add(register(discoveryService, "service1", "localhost", 2));
    cancellables.add(register(discoveryService, "service1", "localhost", 3));
    cancellables.add(register(discoveryService, "service1", "localhost", 4));
    cancellables.add(register(discoveryService, "service1", "localhost", 5));

    cancellables.add(register(discoveryService, "service2", "localhost", 1));
    cancellables.add(register(discoveryService, "service2", "localhost", 2));
    cancellables.add(register(discoveryService, "service2", "localhost", 3));

    cancellables.add(register(discoveryService, "service3", "localhost", 1));
    cancellables.add(register(discoveryService, "service3", "localhost", 2));

    Iterable<Discoverable> discoverables = discoveryServiceClient.discover("service1");
    Assert.assertTrue(waitTillExpected(5, discoverables));

    discoverables = discoveryServiceClient.discover("service2");
    Assert.assertTrue(waitTillExpected(3, discoverables));

    discoverables = discoveryServiceClient.discover("service3");
    Assert.assertTrue(waitTillExpected(2, discoverables));

    cancellables.add(register(discoveryService, "service3", "localhost", 3));
    Assert.assertTrue(waitTillExpected(3, discoverables)); // Shows live iterator.

    for (Cancellable cancellable : cancellables) {
      cancellable.cancel();
    }

    Assert.assertTrue(waitTillExpected(0, discoveryServiceClient.discover("service1")));
    Assert.assertTrue(waitTillExpected(0, discoveryServiceClient.discover("service2")));
    Assert.assertTrue(waitTillExpected(0, discoveryServiceClient.discover("service3")));
  }
}
