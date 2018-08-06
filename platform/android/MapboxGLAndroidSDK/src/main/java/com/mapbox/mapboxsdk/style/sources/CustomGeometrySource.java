package com.mapbox.mapboxsdk.style.sources;

import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;

import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.mapboxsdk.geometry.LatLngBounds;
import com.mapbox.mapboxsdk.style.expressions.Expression;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Custom Vector Source, allows using FeatureCollections.
 */
public class CustomGeometrySource extends Source {
  public static final String THREAD_PREFIX = "CustomGeom";
  public static final int THREAD_POOL_LIMIT = 4;
  private static final AtomicInteger poolCount = new AtomicInteger();
  private final Lock executorLock = new ReentrantLock();
  private ThreadPoolExecutor executor;
  private GeometryTileProvider provider;
  private final Map<TileID, LinkedList<GeometryTileRequest>> overlappingTasksMap = new HashMap<>();
  private final Map<TileID, AtomicBoolean> inProgressTasksMap = new HashMap<>();

  /**
   * Create a CustomGeometrySource
   *
   * @param id       The source id.
   * @param provider The tile provider that returns geometry data for this source.
   */
  @UiThread
  public CustomGeometrySource(String id, GeometryTileProvider provider) {
    this(id, provider, new CustomGeometrySourceOptions());
  }

  /**
   * Create a CustomGeometrySource with non-default CustomGeometrySourceOptions.
   * <p>Supported options are minZoom, maxZoom, buffer, and tolerance.</p>
   *
   * @param id       The source id.
   * @param provider The tile provider that returns geometry data for this source.
   * @param options  CustomGeometrySourceOptions.
   */
  @UiThread
  public CustomGeometrySource(String id, GeometryTileProvider provider, CustomGeometrySourceOptions options) {
    super();
    this.provider = provider;
    initialize(id, options);
  }

  /**
   * Invalidate previously provided features within a given bounds at all zoom levels.
   * Invoking this method will result in new requests to `GeometryTileProvider` for regions
   * that contain, include, or intersect with the provided bounds.
   *
   * @param bounds The region in which features should be invalidated at all zoom levels
   */
  public void invalidateRegion(LatLngBounds bounds) {
    nativeInvalidateBounds(bounds);
  }

  /**
   * Invalidate the geometry contents of a specific tile. Invoking this method will result
   * in new requests to `GeometryTileProvider` for visible tiles.
   *
   * @param zoomLevel Tile zoom level.
   * @param x         Tile X coordinate.
   * @param y         Tile Y coordinate.
   */
  public void invalidateTile(int zoomLevel, int x, int y) {
    nativeInvalidateTile(zoomLevel, x, y);
  }

  /**
   * Set or update geometry contents of a specific tile. Use this method to update tiles
   * for which `GeometryTileProvider` was previously invoked. This method can be called from
   * background threads.
   *
   * @param zoomLevel Tile zoom level.
   * @param x         Tile X coordinate.
   * @param y         Tile Y coordinate.
   * @param data      Feature collection for the tile.
   */
  public void setTileData(int zoomLevel, int x, int y, FeatureCollection data) {
    nativeSetTileData(zoomLevel, x, y, data);
  }

  /**
   * Queries the source for features.
   *
   * @param filter an optional filter expression to filter the returned Features
   * @return the features
   */
  @NonNull
  public List<Feature> querySourceFeatures(@Nullable Expression filter) {
    checkThread();
    Feature[] features = querySourceFeatures(filter != null ? filter.toArray() : null);
    return features != null ? Arrays.asList(features) : new ArrayList<Feature>();
  }

  @Keep
  protected native void initialize(String sourceId, Object options);

  @Keep
  private native Feature[] querySourceFeatures(Object[] filter);

  @Keep
  private native void nativeSetTileData(int z, int x, int y, FeatureCollection data);

  @Keep
  private native void nativeInvalidateTile(int z, int x, int y);

  @Keep
  private native void nativeInvalidateBounds(LatLngBounds bounds);

  @Override
  @Keep
  protected native void finalize() throws Throwable;

  private void setTileData(TileID tileId, FeatureCollection data) {
    nativeSetTileData(tileId.z, tileId.x, tileId.y, data);
  }

  /**
   * Tile data request can come from a number of different threads.
   * To remove race condition for requests targeting the same tile id we are first checking if there is already a queue
   * of requests for that tile id (might be empty if the request is being processed),
   * if not, we are pushing the request straight to the executor.
   */
  @WorkerThread
  @Keep
  private void fetchTile(int z, int x, int y) {
    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    TileID tileID = new TileID(z, x, y);
    GeometryTileRequest request =
      new GeometryTileRequest(tileID, provider, overlappingTasksMap, inProgressTasksMap, this, cancelFlag);

    synchronized (overlappingTasksMap) {
      LinkedList<GeometryTileRequest> queue = overlappingTasksMap.get(tileID);
      if (queue != null) {
        queue.offer(request);
      } else {
        executorLock.lock();
        try {
          if (executor != null && !executor.isShutdown()) {
            executor.execute(request);
          }
        } finally {
          executorLock.unlock();
        }
      }
    }
  }

  /**
   * We want to cancel only the oldest request, therefore we are first checking if it's in progress,
   * if not, we are searching for any request in the executor's queue,
   * if not, we are looking for requests in the additional queue of requests targeting this tile id.
   * <p>
   * {@link GeometryTileRequest#equals(Object)} is overridden to cover only the tile id,
   * therefore, we can use and empty request to search the queues.
   */
  @WorkerThread
  @Keep
  private void cancelTile(int z, int x, int y) {
    TileID tileID = new TileID(z, x, y);

    synchronized (overlappingTasksMap) {
      synchronized (inProgressTasksMap) {
        AtomicBoolean cancelFlag = inProgressTasksMap.get(tileID);
        if (cancelFlag != null) {
          cancelFlag.compareAndSet(false, true);
        } else {
          GeometryTileRequest emptyRequest =
            new GeometryTileRequest(tileID, null, null, null, null, null);
          if (!executor.getQueue().remove(emptyRequest)) {
            LinkedList<GeometryTileRequest> queue = overlappingTasksMap.get(tileID);
            if (queue != null) {
              queue.remove(emptyRequest);
            }
          }
        }
      }
    }
  }

  @Keep
  private void startThreads() {
    executorLock.lock();
    try {
      if (executor != null && !executor.isShutdown()) {
        executor.shutdownNow();
      }

      executor = new ThreadPoolExecutor(THREAD_POOL_LIMIT, THREAD_POOL_LIMIT,
        0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
        final AtomicInteger threadCount = new AtomicInteger();
        final int poolId = poolCount.getAndIncrement();

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
          return new Thread(
            runnable,
            String.format(Locale.US, "%s-%d-%d", THREAD_PREFIX, poolId, threadCount.getAndIncrement()));
        }
      });
    } finally {
      executorLock.unlock();
    }
  }

  @Keep
  private void releaseThreads() {
    executorLock.lock();
    try {
      executor.shutdownNow();
    } finally {
      executorLock.unlock();
    }
  }

  @Keep
  private boolean isCancelled(int z, int x, int y) {
    return inProgressTasksMap.get(new TileID(z, x, y)).get();
  }

  static class TileID {
    public int z;
    public int x;
    public int y;

    TileID(int _z, int _x, int _y) {
      z = _z;
      x = _x;
      y = _y;
    }

    public int hashCode() {
      return Arrays.hashCode(new int[] {z, x, y});
    }

    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }

      if (object == null || getClass() != object.getClass()) {
        return false;
      }

      if (object instanceof TileID) {
        TileID other = (TileID) object;
        return this.z == other.z && this.x == other.x && this.y == other.y;
      }
      return false;
    }
  }

  static class GeometryTileRequest implements Runnable {
    private final TileID id;
    private final GeometryTileProvider provider;
    private final Map<TileID, LinkedList<GeometryTileRequest>> overlapping;
    private final Map<TileID, AtomicBoolean> inProgress;
    private final WeakReference<CustomGeometrySource> sourceRef;
    private final AtomicBoolean cancelled;

    GeometryTileRequest(TileID _id, GeometryTileProvider p,
                        Map<TileID, LinkedList<GeometryTileRequest>> overlapping,
                        Map<TileID, AtomicBoolean> m,
                        CustomGeometrySource _source, AtomicBoolean _cancelled) {
      id = _id;
      provider = p;
      this.overlapping = overlapping;
      inProgress = m;
      sourceRef = new WeakReference<>(_source);
      cancelled = _cancelled;
    }

    public void run() {
      synchronized (overlapping) {
        synchronized (inProgress) {
          if (overlapping.get(id) == null) {
            overlapping.put(id, new LinkedList<>());
          }

          if (inProgress.put(id, cancelled) != null) {
            // request targeting this tile id is already being processed,
            // scenario that should occur only if the tile is being requested when
            // another request is switching threads to execute or is in executor's queue
            overlapping.get(id).offer(this);
            return;
          }
        }
      }

      if (!isCancelled()) {
        FeatureCollection data = provider.getFeaturesForBounds(LatLngBounds.from(id.z, id.x, id.y), id.z);
        CustomGeometrySource source = sourceRef.get();
        if (!isCancelled() && source != null && data != null) {
          source.setTileData(id, data);
        }
      }

      synchronized (overlapping) {
        synchronized (inProgress) {
          inProgress.remove(id);
          // executing the next request targeting the same tile or cleaning up if none waiting
          GeometryTileRequest queuedRequest = overlapping.get(id).poll();
          CustomGeometrySource source = sourceRef.get();
          if (source != null && queuedRequest != null) {
            source.executor.execute(queuedRequest);
          }

          // if there are no more requests waiting,
          // remove the queue in case the last request is cancelled in the executor's queue
          if (overlapping.get(id).size() == 0) {
            overlapping.remove(id);
          }
        }
      }
    }

    private Boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      GeometryTileRequest request = (GeometryTileRequest) o;
      return id.equals(request.id);
    }
  }
}
