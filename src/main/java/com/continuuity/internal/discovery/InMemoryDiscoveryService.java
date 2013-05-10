package com.continuuity.internal.discovery;

import com.continuuity.zookeeper.Cancellable;
import com.continuuity.discovery.Discoverable;
import com.continuuity.discovery.DiscoveryService;
import com.continuuity.discovery.DiscoveryServiceClient;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.AbstractIdleService;

import java.util.Iterator;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple in memory implementation of {@link DiscoveryService} and {@link DiscoveryServiceClient}.
 */
public class InMemoryDiscoveryService extends AbstractIdleService implements DiscoveryService, DiscoveryServiceClient {

  private Multimap<String, Discoverable> services;
  private final Lock lock = new ReentrantLock();

  @Override
  protected void startUp() throws Exception {
    services = HashMultimap.create();
  }

  @Override
  protected void shutDown() throws Exception {
    lock.lock();
    try {
      services.clear();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Cancellable register(final Discoverable discoverable) {
    Preconditions.checkState(isRunning(), "Service is not running");
    lock.lock();
    try {
      final Discoverable wrapper = new DiscoverableWrapper(discoverable);
      services.put(wrapper.getName(), wrapper);
      return new Cancellable() {
        @Override
        public void cancel() {
          lock.lock();
          try {
            services.remove(wrapper.getName(), wrapper);
          } finally {
            lock.unlock();
          }
        }
      };
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Iterable<Discoverable> discover(final String name) {
    Preconditions.checkState(isRunning(), "Service is not running");
    return new Iterable<Discoverable>() {
      @Override
      public Iterator<Discoverable> iterator() {
        lock.lock();
        try {
          Preconditions.checkState(isRunning(), "Service is not running");
          return ImmutableList.copyOf(services.get(name)).iterator();
        } finally {
          lock.unlock();
        }
      }
    };
  }
}
