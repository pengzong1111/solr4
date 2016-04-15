package org.apache.solr.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class SlowTenantPointsKeeperWithQs {

  private static final Queue<String> FAST_QUEUE = new ConcurrentLinkedQueue<String>();
  private static final Queue<String> SLOW_QUEUE = new ConcurrentLinkedQueue<String>();
  
  private static final int MAXIMUM_LOGICAL_THROUGHPUT = 33000; //computation per sec
  
//1. we think of each core as a tenant to start off. Each core has its default points. 
  private static SlowTenantPointsKeeperWithQs tenantPointsKeeper = new SlowTenantPointsKeeperWithQs();
  
  public static int POINTS; // = 8000;  // initial points, also maximum size for each bucket/core/tenant
  
  //each tenant has its quota that may change with the changes of actual throughput
  private static Map<String, TenantTokenInfo> coreNameToTenantMap = new HashMap<String, TenantTokenInfo>();
  //each tenant has its current tokens left
  protected Map<String, Semaphore> coreToPointMap = new HashMap<String, Semaphore>();
  private static int CACHE_SIZE = 3;
  private static List<String> SLOW_TENANTS = new LinkedList<String>();
  //the timestamp when last fill/refill happens for tenants in fast queue
  private long timeRefill;
  
  private static PrintWriter pw ;
  
  public static SlowTenantPointsKeeperWithQs getInstance() {
    return tenantPointsKeeper;
  }
  
  private SlowTenantPointsKeeperWithQs() {
    try {
      pw = new PrintWriter(new File("slow-tenant-2.log"));
    } catch (FileNotFoundException e) {
      throw new RuntimeException();
    }
  }
  
  
  // core name map to the status whether refill is needed
  private Map<String, Boolean> coreToStatusMap = new HashMap<String, Boolean>();
  
  // keep a sum of points for all tenants
  //private AtomicInteger sum = new AtomicInteger(0);

  public boolean hasTenant(String coreName) {
    return coreToPointMap.containsKey(coreName);
  }
  
  
  public String getSlowTenant() {
    if(SLOW_TENANTS.size() < CACHE_SIZE) {
      return null;
    } else if(SLOW_TENANTS.size() > CACHE_SIZE) {
      pw.println("WARNING: SLOW_TENANTS is with size of " + SLOW_TENANTS.size());
      return null;
    } else {
      Map<String, Integer> map = new HashMap<String,Integer>();
      for(String slowTenant : SLOW_TENANTS) {
        if(map.containsKey(slowTenant)) {
          map.put(slowTenant, map.get(slowTenant) + 1);
        } else {
          map.put(slowTenant, 1);
        }
      }
      for(Entry<String, Integer> entry : map.entrySet()) {
        if(entry.getValue() > CACHE_SIZE/2) {
          return entry.getKey();
        }
      }
      //if no such slow tenant
      return SLOW_TENANTS.get(CACHE_SIZE - 1);
    }
  }
  
  /**
   * decrement count for coreName
   * @param coreName  coreName for tenant
   * @param count     the number of permits/points to decrease
   */
  public void decrement(String coreName, int count) {
    try {
      Semaphore sem = coreToPointMap.get(coreName);
      pw.println("decrement: " + coreName + " tokens: " + count + " from left: " + sem.availablePermits()); pw.flush();
      synchronized (sem) {
        int leftTokens = sem.availablePermits();
        if (leftTokens < count) {
          pw.println("cannot decrement semophore by " + count + " for " + coreName); pw.flush();
          if(SLOW_QUEUE.contains(coreName)) {
            refillSlowTenant(coreName);
          } else if(FAST_QUEUE.contains(coreName)) {
            updateTenantTokenInfo(coreName, leftTokens, System.currentTimeMillis());
            if (!coreToStatusMap.get(coreName)) {
              coreToStatusMap.put(coreName, true);
              if (isRefillNeeded()) {
                addToSLowTenants(coreName); // slowest in fast queue
                pw.println(coreName + " triggered refill for fast tenants"); pw.flush();
                refillAllBy(100);
                resetRefillFlag();
                timeRefill = System.currentTimeMillis();
              } else {
                pw.println("no refill needed"); pw.flush();
              }
            }
          } else {
            pw.println("WARNING: a tenant is in neither FAST_QUEUE nor SLOW_QUEUE: " + coreName);
          }
        }
        sem.acquire(count);
        coreNameToTenantMap.get(coreName).actualTokenUsed.addAndGet(count);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException( 
          "thread interrupted when acquiring semaphore!!", e);
    }
  }
  
  private void refillSlowTenant(String coreName) {
    if(SLOW_QUEUE.contains(coreName)) {
      Semaphore sem = coreToPointMap.get(coreName);
      int quota = coreNameToTenantMap.get(coreName).quota;
      int tokensToAdd = quota - sem.availablePermits();
      pw.println("quota for slow tenant: " + coreName + " is " + quota + " so release " + tokensToAdd);
      if(tokensToAdd > 0) { 
        sem.release(tokensToAdd);
      }
    }
  }

  private void addToSLowTenants(String coreName) {
    if(SLOW_TENANTS.size() > CACHE_SIZE) {
      pw.println("WARNING: SLOW_TENANTS caches more than configured!!");
    } else if(SLOW_TENANTS.size() < CACHE_SIZE) {
      SLOW_TENANTS.add(coreName);
    } else {
      SLOW_TENANTS.remove(0);
      pw.println("add to SLOW_TENANTS: " + coreName);
      SLOW_TENANTS.add(coreName);
    }
  }

  /**
   * get actual logical throughput for specified coreName
   * @param coreName                  coreName for the tenant
   * @param leftTokens                token left since last refill for the tenant
   * @param currentTimeMillis         current timestamp used to calculate the time interval between last refill and now
   */
  private void updateTenantTokenInfo(String coreName, int leftTokens, long currentTimeMillis) {
    TenantTokenInfo tenantInfo = coreNameToTenantMap.get(coreName);
    long atime = tenantInfo.acutalTimeUsed.addAndGet(currentTimeMillis - timeRefill);
  //  int atoken = tenantInfo.actualTokenUsed.addAndGet(tenantInfo.quota.get() - leftTokens);
    pw.println("updated token info for " + coreName + " to actualTimeUsed: " + atime + ", actualTokenUsed: " + tenantInfo.actualTokenUsed);
 //   int actualLogicalThroughput = (int) ((tenantToQuotaMap.get(coreName) - leftTokens)*1000 / (currentTimeMillis - timeRefill)); // computation per sec
//    tenantToActualThroughput.put(coreName, actualLogicalThroughput);
  }
  
  // set all refill flag to false for each tenant
  private void resetRefillFlag() {
    /*for(Entry<String,Boolean> entry : coreToStatusMap.entrySet()) {
      if(FAST_QUEUE.contains(entry.getKey())) {
        entry.setValue(false);
      }
    }*/
    for(String tenantName : FAST_QUEUE) {
      coreToStatusMap.put(tenantName, false);
    }
  }

  private boolean isRefillNeeded() { // see if refill is needed for fast queue
    for(String tenant: FAST_QUEUE) {
      if(!coreToStatusMap.get(tenant)) {
        return false;
      }
    }
   //    pw.println("need refill.."); pw.flush();
    return true;
  }

  /**
   * refill all tenants in fast queue by some percent of POINTS. 
   * This is usually used by background thread that refill bucket periodically to unblock fast tenant from waiting for the tenants that are too slow
   * @param percentage    the percentage of POINTS to refill
   */
  private void refillAllBy(int percentage) {
    pw.println("refillAll..");
   // int pointsToRefill = (POINTS * percentage)/100;
    for(String coreName : FAST_QUEUE) {
      Semaphore sem = coreToPointMap.get(coreName);
      int quota = coreNameToTenantMap.get(coreName).quota;
      int tokensToAdd = quota - sem.availablePermits();
      pw.println("quota for tenant: " + coreName + " is " + quota + " so release " + tokensToAdd);
      if(tokensToAdd > 0) { 
        sem.release(tokensToAdd);
    //    sumToAdd += tokensToAdd;
      }
    //  sum.addAndGet(sumToAdd);
    }
  }

  /**
   * initialize all required fields for this singleton
   * @param coreNames   a collection of core names, each corresponding to a tenant
   */
  public void initialize(Collection<String> coreNames) {
    int size = coreNames.size();
    POINTS = MAXIMUM_LOGICAL_THROUGHPUT / size; // initially, we can evenly allocate logical throughput
    for(String coreName : coreNames) {
      pw.println("======initialize core: " + coreName + " and points: " + POINTS);
      coreNameToTenantMap.put(coreName, new TenantTokenInfo(POINTS, 0, 0, 0));
      coreToPointMap.put(coreName, new Semaphore(POINTS, true));
      coreToStatusMap.put(coreName, false); // here false means "no refill needed"
      SLOW_QUEUE.add(coreName); // initially all tenants are considered as slow
  //    sum.addAndGet(POINTS);
      timeRefill = System.currentTimeMillis();
    }
    
    ActualThroughputChecker actualThroughputChecker = new ActualThroughputChecker(coreNames);
    actualThroughputChecker.start();
  }
  
  class TenantTokenInfo implements Comparable{
    int quota; // tenant token quota
    
    AtomicLong acutalTimeUsed; // actual time used during specified time interval
    
    AtomicInteger actualTokenUsed; // actual #token used during specified time interval
    
    int logicalThroughput; // actual logicThroughput consumed
    
    public TenantTokenInfo(int quota, long acutalTimeUsed, int actualTokenUsed, int logicalThroughput) {
      this.quota = quota;
      this.acutalTimeUsed = new AtomicLong(acutalTimeUsed);
      this.actualTokenUsed = new AtomicInteger(actualTokenUsed);
      this.logicalThroughput = logicalThroughput;
      
    }

    @Override
    public int compareTo(Object o) {
      if(o instanceof TenantTokenInfo) {
        TenantTokenInfo tki = (TenantTokenInfo)o;
        if(this.logicalThroughput > tki.logicalThroughput) {
          return 1;
        } else if(this.logicalThroughput < tki.logicalThroughput) {
          return -1;
        } else {
          return 0;
        }
      }
      return -1;
    }
  }
  
  class ActualThroughputChecker extends Thread {
    private Map<String, List<Integer>> tenantToRecentActualThroughputsMap = new HashMap<String, List<Integer>>();
    private List<Integer> overallThroughputs = new LinkedList<Integer>();
    private int cacheSize = 3; // how many latest actual throughput we keep for each tenant & how many overall latest throughputs to keep for overall performance
    private long timeInterval = 4000; // every 10,000ms, go check the actual logical throughput for each tenant
    private int count = 0; // how many times this checker checks .. for the first severl time interval, we can ingore its stats because tenants are booting up
   
    public ActualThroughputChecker(Collection<String> coreNames) {
      for(String coreName : coreNames) {
        List<Integer> recentActualThroughputs = new LinkedList<Integer>();
        tenantToRecentActualThroughputsMap.put(coreName, recentActualThroughputs);
      }
    }
    
    public void run() {
      while (true) {
        try {
          sleep(timeInterval); // first sleep 10 seconds before
          pw.println("I'm awake, I'm awake!");
          //count++;
        } catch (InterruptedException e) {
          throw new RuntimeException();
        }
        long currentTime = System.currentTimeMillis();
        int allTokenUsed = 0;
        for(Entry<String, TenantTokenInfo> entry : coreNameToTenantMap.entrySet()) {
          // do we need to synchronize using intrinsic lock of this tenant's semophore
          String coreName = entry.getKey();
          TenantTokenInfo tenant = entry.getValue();
          int actualUsedToken = tenant.actualTokenUsed.get();
          allTokenUsed += actualUsedToken;
          
         // long interval = 0;
        //  long actualTimeUsed = 0;
          long overallUsedTime = 0;
          if(FAST_QUEUE.contains(coreName)) {
            long interval = currentTime - timeRefill;
            long actualTimeUsed = tenant.acutalTimeUsed.get();
            overallUsedTime = actualTimeUsed + interval;
          } else if(SLOW_QUEUE.contains(coreName)) {
            overallUsedTime = timeInterval;
          }
         
          int logicalThroughput = (int) ((actualUsedToken*1000) / overallUsedTime);
          addToPerTenantCache(coreName, logicalThroughput); // add to latest cache for actual logical throughput of each tenant
          pw.println("logical throughput for " + coreName + " is: 1000*" + actualUsedToken + "/(" + overallUsedTime + ") ==" + logicalThroughput); pw.flush();
          tenant.logicalThroughput = logicalThroughput;
          tenant.actualTokenUsed = new AtomicInteger(0);
          tenant.acutalTimeUsed = new AtomicLong(0);
        }
        pw.println("overall " + allTokenUsed + " token used in 6000 milliseconds");
        int overallLogicalThroughput = (int) (allTokenUsed / (timeInterval/1000));
        addToOverallThroughputCache(overallLogicalThroughput);
        pw.println("count:" + count + " FAST_QUEUE: " + FAST_QUEUE.toString());
        pw.println("count:" + count + " SLOW_QUEUE: " + SLOW_QUEUE.toString());
        pw.println("count:" + count + " SLOW_TENANTS: " + SLOW_TENANTS.toString());
        pw.println("======================"); pw.flush();
        pw.println("count: " + count + " overallLogicalThroughput is:  " + overallLogicalThroughput);
        pw.println("count: " + count + " coreToStatusMap is:  " + coreToStatusMap.toString());//coreToStatusMap
        
        if(count++ > 6 ) {
        // when overall logical throughput is smaller than maximum value, it means search engine is not overloaded, 
          if (isOverallThroughputMostlyLessThan(MAXIMUM_LOGICAL_THROUGHPUT * 0.94)) {
            pw.println("count: " + count + " overallLogicalThroughput < threadhold(" + MAXIMUM_LOGICAL_THROUGHPUT * 0.94 + "): " + overallLogicalThroughput);
            if (FAST_QUEUE.isEmpty()) { // if there is no fast tenant, it means
                                        // all slow tenants together cannot even
                                        // use up max logical throughput, then
                                        // simply do nothing.
              // do nothing;
            } else {
              handleSlowTenantInFastQueue();
            }
          } else {
            pw.println("count: " + count + " overallLogicalThroughput >= threadhold(" + MAXIMUM_LOGICAL_THROUGHPUT * 0.94 + "): "
                + overallLogicalThroughput);
            if (FAST_QUEUE.isEmpty()) {
              // move all to fast queue
              pw.println("count: " + count + " moving all tenants up to FAST_QUEUE: ");
              FAST_QUEUE.addAll(SLOW_QUEUE);
              SLOW_QUEUE.removeAll(SLOW_QUEUE);
            } else if (!SLOW_QUEUE.isEmpty()) {
              pw.println("count: " + count + " checking speedup tenant in SLOW_QUEUE... ");
              checkSpeedupTenantInSlowQueue();
            }
          }
        }
      }
    }

    private void checkSpeedupTenantInSlowQueue() {
      if(tenantToRecentActualThroughputsMap == null) {
        pw.println("WARNING: tenantToRecentActualThroughputsMap is null" );
        return;
      }
      List<String> speedupTenants = new LinkedList<String>();
      // if a tenant's actual logical throughput successively increase twice and is recognized as slow tenant. Then we consider its getting faster and need to allocate more tokens to it
      for(String coreName : SLOW_QUEUE) {
        List<Integer> recentThroughputs = tenantToRecentActualThroughputsMap.get(coreName);
    //    List<Integer> latestThroughputs = tenantToRecentActualThroughputsMap.get(coreName);
        if(recentThroughputs == null) {
          pw.println("count: " + count + "WARNING: latestThroughputs cache is null for tenant: " + coreName);
          return;
        } else if(recentThroughputs.size() < cacheSize) {
          pw.println("count: " + count + "WARNING: no enough history records in latestThroughputs cache for tenant: " + coreName );
          return;
        } else if(recentThroughputs.size() > cacheSize) {
          pw.println("count: " + count + "WARNING: too many history records in latestThroughputs cache for tenant: " + coreName );
          return;
        } else {
          boolean isIncreasing = true;
          for(int i=1; i<cacheSize; i++) {
            if(recentThroughputs.get(i) >= 1.10*recentThroughputs.get(i-1) ) {
              isIncreasing = isIncreasing && true;
            } else {
              isIncreasing = isIncreasing && false;
            }
          }
          if(isIncreasing) {
           // speedupTenants.add(coreName);
            pw.println("count: " + count + " move a tenant from SLOW_QUEUEto FAST_QUEUE: " + coreName);
            speedupTenants.add(coreName);
          }
        }
      }
      if(!speedupTenants.isEmpty()) {
        SLOW_QUEUE.remove(speedupTenants) ;
        FAST_QUEUE.addAll(speedupTenants);
      }
    }

    private void handleSlowTenantInFastQueue() {
      // adjustQuotas(coreNameToTenantMap);
      String slowTenant = getSlowTenant();
      if (FAST_QUEUE.contains(slowTenant)) {
        FAST_QUEUE.remove(slowTenant);
        SLOW_QUEUE.add(slowTenant);
        SLOW_TENANTS.clear();
      } else {
        pw.println("WARNING: slow tenant is already in SLOW_QUEUE: "
            + slowTenant);
        pw.flush();
      }
    }
    
    private boolean isOverallThroughputMostlyLessThan(double threshold) {
      if(overallThroughputs.size() < cacheSize) {
        return false;
      }
      pw.println("count " + count + "overallLogicalThroughput cache is: " + overallThroughputs.toString());
      if(overallThroughputs.get(cacheSize-1) >= threshold) {
        return false;
      }
      
      int trueCount = 0;
      int falseCount = 0;
      for(int overallLogicalThroughput: overallThroughputs) {
        if(overallLogicalThroughput < threshold) {
          trueCount ++ ;
        } else {
          falseCount ++;
        }
      }
      
      if(trueCount > falseCount) {
        return true;
      } else return false;
    }

    private void addToOverallThroughputCache(int overallLogicalThroughput) {
      if(overallThroughputs.size() > cacheSize) {
        pw.println("WARNING: " + "cache size for overall throughput cache is " + overallThroughputs.size());
      } else if(overallThroughputs.size() < cacheSize) {
        overallThroughputs.add(overallLogicalThroughput);
      } else {
        overallThroughputs.remove(0);
        overallThroughputs.add(overallLogicalThroughput);
      }
    }

    private void addToPerTenantCache(String coreName, int logicalThroughput) {
      if(tenantToRecentActualThroughputsMap.containsKey(coreName)) {
        List<Integer> recentThroughputs = tenantToRecentActualThroughputsMap.get(coreName);
        if(recentThroughputs.size() > cacheSize) {
          pw.println("WARNING: " + coreName + "'s recent actual throughputs cache has size of " + recentThroughputs.size());
        } else if(recentThroughputs.size() < cacheSize) {
          recentThroughputs.add(logicalThroughput);
        } else {
          recentThroughputs.remove(0);
          recentThroughputs.add(logicalThroughput);
        }
      } else {
        pw.println("WARNING: " + coreName + " does not have recent actual throughputs cache!!!");
      }
    }
  }
  
 public static void main(String[] args) {
   AtomicInteger quota = new AtomicInteger(1);
   quota.addAndGet(8);
   System.out.println(quota.get());
 }
}
