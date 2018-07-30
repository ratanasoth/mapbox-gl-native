package com.mapbox.mapboxsdk.style.sources;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class GeometryTileRequestsQueue extends LinkedBlockingQueue<Runnable> {
  private final Set<CustomGeometrySource.TileID> currentlyProcessedRequests = new HashSet<>();

  @Override
  public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
    return getVerifiedTask(super.poll(timeout, unit));
  }

  @Override
  public Runnable poll() {
    return getVerifiedTask(super.poll());
  }

  @Override
  public Runnable take() throws InterruptedException {
    return getVerifiedTask(super.take());
  }

  /*
   * Returns the next task in the queue only if the task for the same tile id is not being currently processed,
   * otherwise, it pushes the task back to the end of the queue.
   */
  private Runnable getVerifiedTask(Runnable runnable) {
    if (runnable == null) {
      return null;
    }

    CustomGeometrySource.GeometryTileRequest task = (CustomGeometrySource.GeometryTileRequest) runnable;
    synchronized (currentlyProcessedRequests) {
      if (currentlyProcessedRequests.contains(task.getId())) {
        this.offer(task);
        return null;
      }

      currentlyProcessedRequests.add(task.getId());
      return task;
    }
  }

  /*
   * Called to indicate that the majority of the work for the particular tile id has been finished,
   * and new tasks targeting that tile id can be processed.
   */
  void requestProcessed(CustomGeometrySource.TileID id) {
    synchronized (currentlyProcessedRequests) {
      currentlyProcessedRequests.remove(id);
    }
  }
}
