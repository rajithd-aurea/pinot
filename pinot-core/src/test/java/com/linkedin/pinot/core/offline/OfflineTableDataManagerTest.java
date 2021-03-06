/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.offline;

import com.google.common.collect.ImmutableList;
import com.linkedin.pinot.common.metrics.ServerMetrics;
import com.yammer.metrics.core.MetricsRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.core.data.manager.config.TableDataManagerConfig;
import com.linkedin.pinot.core.data.manager.offline.AbstractTableDataManager;
import com.linkedin.pinot.core.data.manager.offline.OfflineSegmentDataManager;
import com.linkedin.pinot.core.data.manager.offline.OfflineTableDataManager;
import com.linkedin.pinot.core.data.manager.offline.SegmentDataManager;
import com.linkedin.pinot.core.data.manager.offline.TableDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import static org.mockito.Mockito.*;


public class OfflineTableDataManagerTest {
  private static final String tableName = "T1";
  private static final String segmentPrefix = "S";
  private static final ReadMode readMode = ReadMode.heap;

  private static final String refCntFieldName = "_refcnt";

  // Set once for the suite
  private File _tmpDir;

  // Set once for every test
  private volatile  int _nDestroys;
  private volatile  boolean _closing;
  private Set<IndexSegment> _allSegments = new HashSet<>();
  private Set<SegmentDataManager> _accessedSegManagers = Collections.newSetFromMap(new ConcurrentHashMap<SegmentDataManager, Boolean>());
  private Set<SegmentDataManager> _allSegManagers = Collections.newSetFromMap(new ConcurrentHashMap<SegmentDataManager, Boolean>());
  private AtomicInteger _numQueries = new AtomicInteger(0);
  private Map<String, OfflineSegmentDataManager> _internalSegMap;
  private Throwable _exception;
  private Thread _masterThread;
  // Segment numbers in place.
  // When we add a segment, we add hi+1, and bump _hi.
  // When we remove a segment, we remove _lo and bump _lo
  // When we replace a segment, we pick a number between _hi and _lo (inclusive)
  private volatile int _lo;
  private volatile int _hi;

  @BeforeSuite
  public void setUp() throws Exception {
    _tmpDir = File.createTempFile("OfflineTableDataManagerTest", null);
    _tmpDir.deleteOnExit();
  }

  @AfterSuite
  public void tearDown() {
    if (_tmpDir != null) {
      org.apache.commons.io.FileUtils.deleteQuietly(_tmpDir);
    }
  }

  @BeforeMethod
  public void beforeMethod() {
    _nDestroys = 0;
    _closing = false;
    _allSegments.clear();
    _accessedSegManagers.clear();
    _allSegManagers.clear();
    _numQueries.set(0);
    _exception = null;
    _masterThread = null;
  }


  private OfflineTableDataManager makeTestableManager() throws Exception {
    OfflineTableDataManager tableDataManager = new OfflineTableDataManager();
    TableDataManagerConfig config;
    {
      config = mock(TableDataManagerConfig.class);
      when(config.getTableName()).thenReturn(tableName);
      when(config.getDataDir()).thenReturn(_tmpDir.getAbsolutePath());
      when(config.getNumberOfTableQueryExecutorThreads()).thenReturn(1);  // we do not spawn any real executor threads.
      when(config.getReadMode()).thenReturn(readMode.toString());
      when(config.getIndexLoadingConfigMetadata()).thenReturn(null);
    }
    tableDataManager.init(config, new ServerMetrics(new MetricsRegistry()));
    tableDataManager.start();
    Field segsMapField = AbstractTableDataManager.class.getDeclaredField("_segmentsMap");
    segsMapField.setAccessible(true);
    _internalSegMap = (Map<String, OfflineSegmentDataManager>)segsMapField.get(tableDataManager);
    return tableDataManager;
  }

  private IndexSegment makeIndexSegment(String name, int totalDocs) {
    IndexSegment indexSegment = mock(IndexSegment.class);
    SegmentMetadata segmentMetadata = mock(SegmentMetadata.class);
    when(indexSegment.getSegmentMetadata()).thenReturn(segmentMetadata);
    when(indexSegment.getSegmentName()).thenReturn(name);
    when(indexSegment.getSegmentMetadata().getTotalDocs()).thenReturn(totalDocs);
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock invocationOnMock)
          throws Throwable {
        _nDestroys++;
        return null;
      }
    }).when(indexSegment).destroy();
    _allSegments.add(indexSegment);
    return indexSegment;
  }

  @Test
  public void basicTest() throws Exception {
    OfflineTableDataManager tableDataManager = makeTestableManager();
    final String segmentName = "TestSegment";
    final int totalDocs = 23456;
    // Add the segment, get it for use, remove the segment, and then return it.
    // Make sure that the segment is not destroyed before return.
    IndexSegment indexSegment = makeIndexSegment(segmentName, totalDocs);
    tableDataManager.addSegment(indexSegment);
    SegmentDataManager segmentDataManager = tableDataManager.acquireSegment(segmentName);
    verifyCount(segmentDataManager, 2);
    tableDataManager.removeSegment(segmentName);
    verifyCount(segmentDataManager, 1);
    Assert.assertEquals(_nDestroys, 0);
    tableDataManager.releaseSegment(segmentDataManager);
    verifyCount(segmentDataManager, 0);
    Assert.assertEquals(_nDestroys, 1);

    // Now the segment should not be available for use.Also, returning a null reader is fine
    segmentDataManager = tableDataManager.acquireSegment(segmentName);
    Assert.assertNull(segmentDataManager);
    ImmutableList<SegmentDataManager> segmentDataManagers = tableDataManager.acquireAllSegments();
    Assert.assertEquals(segmentDataManagers.size(), 0);
    tableDataManager.releaseSegment(segmentDataManager);

    // Removing the segment again is fine.
    tableDataManager.removeSegment(segmentName);

    // Add a new segment and remove it in order this time.
    final String anotherSeg = "AnotherSegment";
    IndexSegment ix1 = makeIndexSegment(anotherSeg, totalDocs);
    tableDataManager.addSegment(ix1);
    SegmentDataManager sdm1 = tableDataManager.acquireSegment(anotherSeg);
    verifyCount(sdm1, 2);
    // acquire all segments
    ImmutableList<SegmentDataManager> segmentDataManagersList = tableDataManager.acquireAllSegments();
    Assert.assertEquals(segmentDataManagersList.size(), 1);
    verifyCount(sdm1, 3);
    for (SegmentDataManager dataManager : segmentDataManagersList) {
      tableDataManager.releaseSegment(dataManager);
    }
    // count is back to original
    verifyCount(sdm1, 2);
    tableDataManager.releaseSegment(sdm1);
    verifyCount(sdm1, 1);
    // Now replace the segment with another one.
    IndexSegment ix2  = makeIndexSegment(anotherSeg, totalDocs+1);
    tableDataManager.addSegment(ix2);
    // Now the previous one should have been destroyed, and
    verifyCount(sdm1, 0);
    verify(ix1, times(1)).destroy();
    // Delete ix2 without accessing it.
    SegmentDataManager sdm2 = _internalSegMap.get(anotherSeg);
    verifyCount(sdm2, 1);
    tableDataManager.removeSegment(anotherSeg);
    verifyCount(sdm2, 0);
    verify(ix2, times(1)).destroy();
    tableDataManager.shutDown();
  }

  /*
   * These tests simulate the access of segments via OfflineTableDataManager.
   * Two flavors are simulated : One to replace segments via OFFLINE/ONLINE transitions
   * and the other to replace segments via helix message.
   *
   * It creates 31 segments (0..30) to start with and adds them to the tableDataManager (hi = 30, lo = 0)
   * It spawns 10 "query" threads, and one "helix" thread.
   *
   * The query threads pick up a random of 70% the segments and 'get' them, wait a random period of time (5 to 80ms)
   * and then 'release' the segments back, and does this in a continuous loop.
   *
   * The helix thread decides to do one of the following:
   * - Add a segment (hi+1), and bumps hi by 1 (does this 20% of the time)
   * - Remove a segment (lo) and bumps up lo by 1 (does this 20% of the time)
   * - Replaces a segment (a randomm one between (lo,hi), 60% of the time)
   * and then waits for a random of 50-300ms before attempting one of the ops again.
   */
  @Test
  public void testOfflineOnline() throws Exception {
    runStressTest(false);
  }

  @Test
  public void testReplace() throws Exception {
    runStressTest(true);
  }

  private void runStressTest(boolean replaceSegments) throws  Exception {
    _lo = 0;
    _hi = 30;   // Total number of segments we have in the server.
    final int numQueryThreads = 10;
    final int runTimeSec = 30;
    // With the current parameters, 3k ops take about 15 seconds, create about 90 segments and drop about half of them
    // Running with coverage, it provides complete coverage of the (relevant) lines in OfflineTableDataManager

    Random random = new Random();
    TableDataManager tableDataManager = makeTestableManager();

    for (int i = _lo; i <= _hi; i++) {
      final String segName = segmentPrefix + i;
      tableDataManager.addSegment(makeIndexSegment(segName, random.nextInt()));
      _allSegManagers.add(_internalSegMap.get(segName));
    }

    runStorageServer(numQueryThreads, runTimeSec, tableDataManager, replaceSegments);  // replaces segments while online

//    System.out.println("Nops = " + _numQueries + ",nDrops=" + _nDestroys + ",nCreates=" + _allSegments.size());
    tableDataManager.shutDown();
  }

  private void runStorageServer(int numQueryThreads, int runTimeSec, TableDataManager tableDataManager, boolean replaceSegments) throws Exception {
    // Start 1 helix worker thread and as many query threads as configured.
    List<Thread> queryThreads = new ArrayList<>(numQueryThreads);
    for (int i = 0; i < numQueryThreads; i++) {
      TestSegmentUser segUser = new TestSegmentUser(tableDataManager);
      Thread segUserThread = new Thread(segUser);
      queryThreads.add(segUserThread);
      segUserThread.start();
    }

    TestHelixWorker helixWorker = new TestHelixWorker(tableDataManager, replaceSegments);
    Thread helixWorkerThread = new Thread(helixWorker);
    helixWorkerThread.start();
    _masterThread = Thread.currentThread();

    try {
      Thread.sleep(runTimeSec * 1000);
    } catch (InterruptedException e) {

    }
    _closing = true;

    helixWorkerThread.join();
    for (Thread t : queryThreads) {
      t.join();
    }

    if (_exception != null) {
      Assert.fail("One of the threads failed", _exception);
    }

    // tableDataManager should be quiescent now.

    // All segments we ever created must have a corresponding segment manager.
    Assert.assertEquals(_allSegManagers.size(), _allSegments.size());

    final int nSegsAcccessed = _accessedSegManagers.size();
    for (SegmentDataManager segmentDataManager : _internalSegMap.values()) {
      verifyCount(segmentDataManager, 1);
      // We should never have called destroy on these segments. Remove it from the list of accessed segments.
      verify(segmentDataManager.getSegment(), never()).destroy();
      _allSegManagers.remove(segmentDataManager);
      _accessedSegManagers.remove(segmentDataManager);
    }

    // For the remaining segments in accessed list, destroy must have been called exactly once.
    for (SegmentDataManager segmentDataManager : _allSegManagers) {
      verify(segmentDataManager.getSegment(), times(1)).destroy();
      // Also their count should be 0
      verifyCount(segmentDataManager, 0);
    }

    // The number of segments we accessed must be <= total segments created.
    Assert.assertTrue(nSegsAcccessed <= _allSegments.size(),
        "Accessed=" + nSegsAcccessed + ",created=" + _allSegments.size());
    // The number of segments we have seen and that are not there anymore, must be <= number destroyed.
    Assert.assertTrue(_accessedSegManagers.size() <= _nDestroys, "SeenButUnavailableNow=" + _accessedSegManagers.size() + ",Destroys=" + _nDestroys);

    // The current number of segments must be the as expected (hi-lo+1)
    Assert.assertEquals(_internalSegMap.size(), _hi-_lo+1);
  }

  private void verifyCount(SegmentDataManager segmentDataManager, int value) throws Exception {
    Field refcntField = SegmentDataManager.class.getDeclaredField(refCntFieldName);
    refcntField.setAccessible(true);
    AtomicInteger refCnt = (AtomicInteger)refcntField.get(segmentDataManager);
    int actualCount = refCnt.get();
    Assert.assertEquals(actualCount, value,
        segmentDataManager.getSegmentName() + " had " + actualCount + " instead of " + value);
  }

  private class TestSegmentUser implements Runnable {
    private final Random _random = new Random();
    private final int _minUseTimeMs = 5;
    private final int _maxUseTimeMs = 80;
    private final int _nSegsPercent = 70; // We use 70% of the segments for any query.
    private final TableDataManager _tableDataManager;
    private final double acquireAllProbability = 0.20;

    private TestSegmentUser(TableDataManager tableDataManager) {
      _tableDataManager = tableDataManager;
    }

    @Override
    public void run() {
      while (!_closing) {
        try {
          List<SegmentDataManager> segmentDataManagers = null;
          double probability = _random.nextDouble();
          if (probability <= acquireAllProbability) {
            segmentDataManagers = _tableDataManager.acquireAllSegments();
          } else {
            Set<Integer> segmentIds = pickSegments();
            List<String> segmentList = new ArrayList<>(segmentIds.size());
            for (Integer segmentId : segmentIds) {
              segmentList.add(segmentPrefix + segmentId);
            }
            segmentDataManagers = _tableDataManager.acquireSegments(segmentList);
          }
          // Some of them may be rejected, but that is OK.

          // Keep track of all segment data managers we ever accessed.
          for (SegmentDataManager segmentDataManager : segmentDataManagers) {
            _accessedSegManagers.add(segmentDataManager);
          }
          // To simulate real use case, may be we can add a small percent that is returned right away after pruning?
          try {
            int sleepTime = _random.nextInt(_maxUseTimeMs - _minUseTimeMs + 1) + _minUseTimeMs;
            Thread.sleep(sleepTime);
          } catch (InterruptedException e) {
            _closing = true;
          }
          for (SegmentDataManager segmentDataManager : segmentDataManagers) {
            _tableDataManager.releaseSegment(segmentDataManager);
          }
        } catch (Throwable t) {
          _masterThread.interrupt();
          _exception = t;
        }
      }
    }

    private Set<Integer> pickSegments() {
      int hi = _hi; int lo = _lo; int totalSegs = hi-lo+1;
      Set<Integer> segmentIds = new HashSet<>(totalSegs);
      final int nSegments = totalSegs * _nSegsPercent / 100;
      while (segmentIds.size() != nSegments) {
        segmentIds.add(_random.nextInt(totalSegs) + lo);
      }
      return segmentIds;
    }
  }

  private class TestHelixWorker implements Runnable {
    private final int _removePercent;
    private final int _replacePercent;
    private final int _addPercent;
    private final int _minSleepMs;
    private final int _maxSleepMs;
    private final Random _random = new Random();
    private final TableDataManager _tableDataManager;
    private final boolean _replaceSegments;

    private TestHelixWorker(TableDataManager tableDataManager, boolean replaceSegments) {
      _tableDataManager = tableDataManager;

      _removePercent = 20;
      _addPercent = 20;
      _replacePercent = 60;
      _minSleepMs = 50;
      _maxSleepMs = 300;
      _replaceSegments = replaceSegments;
    }

    @Override
    public void run() {
      while (!_closing) {
        try {
          int nextInt = _random.nextInt(100);
          if (nextInt < _removePercent) {
            removeSegment();
          } else if (nextInt < _removePercent + _replacePercent) {
            replaceSegment();
          } else {
            addSegment();
          }
          try {
            int sleepTime = _random.nextInt(_maxSleepMs - _minSleepMs + 1) + _minSleepMs;
            Thread.sleep(sleepTime);
          } catch (InterruptedException e) {
            _closing = true;
          }
        } catch (Throwable t) {
          _masterThread.interrupt();
          _exception = t;
        }
      }

    }

    // Add segment _hi + 1,bump hi.
    private void addSegment() {
      final int segmentToAdd = _hi+1;
      final String segName = segmentPrefix + segmentToAdd;
      _tableDataManager.addSegment(makeIndexSegment(segName, _random.nextInt()));
      _allSegManagers.add(_internalSegMap.get(segName));
      _hi = segmentToAdd;
    }

    // Replace a segment between _lo and _hi
    private void replaceSegment() {
      int segToReplace = _random.nextInt(_hi-_lo+1) + _lo;
      final String segName = segmentPrefix + segToReplace;
      if (!_replaceSegments) {
        _tableDataManager.removeSegment(segName);
        try {
          Thread.sleep(4);
        } catch (InterruptedException e) {
        }
      }
      _tableDataManager.addSegment(makeIndexSegment(segName, _random.nextInt()));
      _allSegManagers.add(_internalSegMap.get(segName));
    }

    // Remove the segment _lo and then bump _lo
    private void removeSegment() {
      // Keep at least one segment in place.
      if (_hi > _lo) {
        _tableDataManager.removeSegment(segmentPrefix + _lo);
        _lo++;
      } else {
        addSegment();
      }
    }
  }
}
