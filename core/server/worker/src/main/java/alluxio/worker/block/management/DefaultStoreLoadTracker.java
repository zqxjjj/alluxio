/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block.management;

import alluxio.collections.ConcurrentHashSet;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.util.ThreadFactoryUtils;
import alluxio.worker.block.BlockStoreLocation;
import alluxio.worker.block.io.BlockReader;
import alluxio.worker.block.io.BlockStreamListener;
import alluxio.worker.block.io.BlockStreamTracker;
import alluxio.worker.block.io.BlockWriter;

import com.google.common.base.Preconditions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link StoreLoadTracker} that reports load based on open reader/writer streams to the
 * local block store.
 *
 * TODO(ggezer): Add a safety net against close calls not being called.
 */
public class DefaultStoreLoadTracker implements StoreLoadTracker, BlockStreamListener {
  /** Used to keep reference to stream readers/writers per location. */
  private final ConcurrentHashMap<BlockStoreLocation, Set<Object>> mStreamsPerLocation;
  /** Used for delayed removing of streams in order to emulate activity cool-down. */
  private final ScheduledExecutorService mScheduler;
  /** For how long, an activity will remain active on load state. */
  private final long mLoadDetectionCoolDownMs;

  /**
   * Creates the default load tracker instance.
   */
  public DefaultStoreLoadTracker() {
    mStreamsPerLocation = new ConcurrentHashMap<>();
    mScheduler = Executors
        .newSingleThreadScheduledExecutor(ThreadFactoryUtils.build("load-tracker-thread-%d", true));
    mLoadDetectionCoolDownMs =
        ServerConfiguration.getMs(PropertyKey.WORKER_MANAGEMENT_LOAD_DETECTION_COOL_DOWN_TIME);

    // BlockStreamTracker provides stream reader/writer events.
    BlockStreamTracker.registerListener(this);
  }

  @Override
  public boolean loadDetected(BlockStoreLocation... locations) {
    for (BlockStoreLocation location : locations) {
      for (BlockStoreLocation trackedLocation : mStreamsPerLocation.keySet()) {
        if (trackedLocation.belongsTo(location)) {
          Set<Object> streamsPerLocation = mStreamsPerLocation.get(trackedLocation);
          if (streamsPerLocation != null && streamsPerLocation.size() > 0) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public void readerOpened(BlockReader reader, BlockStoreLocation location) {
    streamOpened(reader, location);
  }

  @Override
  public void readerClosed(BlockReader reader, BlockStoreLocation location) {
    streamClosed(reader, location);
  }

  @Override
  public void writerOpened(BlockWriter writer, BlockStoreLocation location) {
    streamOpened(writer, location);
  }

  @Override
  public void writerClosed(BlockWriter writer, BlockStoreLocation location) {
    streamClosed(writer, location);
  }

  /**
   * Used to activate stream reader/writer for load tracking.
   */
  private void streamOpened(Object stream, BlockStoreLocation location) {
    Preconditions.checkState(locationValid(location));
    mStreamsPerLocation.compute(location, (k, streamSet) -> {
      if (streamSet == null) {
        streamSet = new ConcurrentHashSet<>();
      }
      streamSet.add(stream);
      return streamSet;
    });
  }

  /**
   * Used to deactivate stream reader/writer for load tracking.
   */
  private void streamClosed(Object stream, BlockStoreLocation location) {
    Preconditions.checkState(locationValid(location));
    mScheduler.schedule(() -> {
      mStreamsPerLocation.compute(location, (k, streamSet) -> {
        Preconditions.checkState(streamSet != null && !streamSet.isEmpty(),
            "Unexpected load tracker state");
        streamSet.remove(stream);
        return streamSet;
      });
    }, mLoadDetectionCoolDownMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Stream reader/writer locations are expected to be precise.
   *
   * @param location the location to check
   * @return {@code true} if location is valid
   */
  private static boolean locationValid(BlockStoreLocation location) {
    return !location.tierAlias().equals(BlockStoreLocation.ANY_TIER)
        && !location.mediumType().equals(BlockStoreLocation.ANY_MEDIUM)
        && location.dir() != BlockStoreLocation.ANY_DIR;
  }
}
