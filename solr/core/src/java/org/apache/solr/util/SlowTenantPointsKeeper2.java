package org.apache.solr.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
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

public class SlowTenantPointsKeeper2 {

  private static final int MAXIMUM_LOGICAL_THROUGHPUT = 33650; //computation per sec
  
//1. we think of each core as a tenant to start off. Each core has its default points. 
  private static SlowTenantPointsKeeper2 tenantPointsKeeper = new SlowTenantPointsKeeper2();
  
  public static int POINTS; // = 8000;  // initial points, also maximum size for each bucket/core/tenant
  
  //each tenant has its quota that may change with the changes of actual throughput
  private static Map<String, TenantTokenInfo> coreNameToTenantMap = new HashMap<String, TenantTokenInfo>();
  //each tenant has its current tokens left
  protected Map<String, Semaphore> coreToPointMap = new HashMap<String, Semaphore>();
  private static String slowTenant = null;
  //the timestamp when last fill/refill happens
  private long timeRefill;
  
  private static PrintWriter pw ;
  
  public static SlowTenantPointsKeeper2 getInstance() {
    return tenantPointsKeeper;
  }
  
  private SlowTenantPointsKeeper2() {
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
  
  /**
   * decrement count for coreName
   * @param coreName  coreName for tenant
   * @param count     the number of permits/points to decrease
   */
  public void decrement(String coreName, int count) {
    try {
      Semaphore sem = coreToPointMap.get(coreName);
      pw.println("decrement: " + coreName + " count: " + count + " from left: " + sem.availablePermits()); pw.flush();
      synchronized (sem) {
        int leftTokens = sem.availablePermits();
        if (leftTokens < count) {
          updateTenantTokenInfo(coreName, leftTokens, System.currentTimeMillis());
          pw.println("cannot decrement semophore by " + count + " for " + coreName); pw.flush();
          if (!coreToStatusMap.get(coreName)) {
            coreToStatusMap.put(coreName, true);
            if (isRefillNeeded()) {
              slowTenant = coreName;
              pw.println(slowTenant + " triggered refill for all tenants"); pw.flush();
              refillAllBy(100);
              resetRefillFlag();
              timeRefill = System.currentTimeMillis();
            } else {
              pw.println("no refill needed"); pw.flush();
            }
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
    for(Entry<String,Boolean> entry : coreToStatusMap.entrySet()) {
      entry.setValue(false);
    }
  }

  private boolean isRefillNeeded() {
    for (Entry<String,Boolean> entry : coreToStatusMap.entrySet()) {
      if (!entry.getValue()) {
        return false;
      }
    }
//    pw.println("need refill.."); pw.flush();
    return true;
  }

  /**
   * refill all by some percent of POINTS. 
   * This is usually used by background thread that refill bucket periodically to unblock fast tenant from waiting for the tenants that are too slow
   * @param percentage    the percentage of POINTS to refill
   */
  private void refillAllBy(int percentage) {
   // int pointsToRefill = (POINTS * percentage)/100;
    for(String coreName : coreToPointMap.keySet()) {
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
  //    sum.addAndGet(POINTS);
      timeRefill = System.currentTimeMillis();
    }
    
    ActualThroughputChecker actualThroughputChecker = new ActualThroughputChecker();
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
    private long timeInterval = 6000; // every 10,000ms, go check the actual logical throughput for each tenant
    private int count = 0; // how many times this checker checks .. for the first severl time interval, we can ingore its stats because tenants are booting up
    
    public void run() {
      
      while (true) {
        try {
          sleep(timeInterval); // first sleep 10 seconds before
          pw.println("I'm awake, I'm awake!");
        } catch (InterruptedException e) {
          throw new RuntimeException();
        }
        long currentTime = System.currentTimeMillis();
        int allTokenUsed = 0;
        for(Entry<String, TenantTokenInfo> entry : coreNameToTenantMap.entrySet()) {
          // do we need to synchronize using intrisic lock of this tenant's semophore
          String coreName = entry.getKey();
          TenantTokenInfo tenant = entry.getValue();
          int actualUsedToken = tenant.actualTokenUsed.get() /*+ (tenant.quota.get() - coreToPointMap.get(coreName).availablePermits())*/;
          allTokenUsed += actualUsedToken;
          long timeInterval = currentTime - timeRefill;
          long actualTimeUsed = tenant.acutalTimeUsed.get();
          long actualUsedTime = actualTimeUsed + timeInterval;
          int logicalThroughput = (int) ((actualUsedToken*1000) / actualUsedTime);
          pw.println("logical throughput for " + coreName + " is: 1000*" + actualUsedToken + "/(" + actualTimeUsed + "+"+ timeInterval + ") ==" + logicalThroughput); pw.flush();
          tenant.logicalThroughput = logicalThroughput;
          tenant.actualTokenUsed = new AtomicInteger(0);
          tenant.acutalTimeUsed = new AtomicLong(0);
        }
        pw.println("overall " + allTokenUsed + " token used in 6000 milliseconds");
        int overallLogicalThroughput = (int) (allTokenUsed / (timeInterval/1000));
        pw.println("======================"); pw.flush();
        if(count++ >=3 ) {
          if(overallLogicalThroughput <= MAXIMUM_LOGICAL_THROUGHPUT * 0.94) {
            pw.println("count: " + count + " overallLogicalThroughput < maximum logical throughput * 0.94: " + overallLogicalThroughput);
            adjustQuotas(coreNameToTenantMap);
          } else {
            pw.println("count: " + count + " overallLogicalThroughput >= maximum logical throughput * 0.94: " + overallLogicalThroughput);
          }
        } else {
          pw.println("count: " + count + " overallLogicalThroughput is:  " + overallLogicalThroughput);
        }
        
      }
    }

    // try max-min to refill
    @SuppressWarnings("unchecked")
    private void adjustQuotas(Map<String,TenantTokenInfo> coreNameToTenantMap) {
      System.out.println("adjusting...");
      TenantTokenInfo slowTenantInfo = coreNameToTenantMap.get(slowTenant);
      int logicalThroughput = slowTenantInfo.logicalThroughput;
      int quota = slowTenantInfo.quota;
      pw.println("slow tenant: " + slowTenant + " logicalThroughput: " + logicalThroughput ); pw.flush();
      if(logicalThroughput <= quota*0.95) {
 //       int deprived = (quota - logicalThroughput);
        slowTenantInfo.quota = (int) (quota * 0.85);
        int otherTenantQuota = (MAXIMUM_LOGICAL_THROUGHPUT - quota) / (coreNameToTenantMap.size()-1);
        for(Entry<String,TenantTokenInfo> entry : coreNameToTenantMap.entrySet()) {
          String coreName = entry.getKey();
          if(!coreName.equals(slowTenant)) {
            TenantTokenInfo tenant = entry.getValue();
            tenant.quota = otherTenantQuota;
            pw.println("other tenant: " + coreName + " new quota: " + otherTenantQuota ); pw.flush();
          } else {
            pw.println("slow tenant: " + slowTenant + " new quota: " + slowTenantInfo.quota ); pw.flush();
          }
        }
      }
    }

    /**
     * increment the last fastTenantSize in fastTenantInfos by fairDeprived
     * @param n      the last n element
     * @param list   the list of tenant token info
     * @param increment   the size to increment by
     */
    private void incrementQuota(int n,
        List<TenantTokenInfo> list, int increment) {
     /* int size = list.size();
      for(int i=size - 1; i>= size- n; i--) {
        list.get(i).quota.addAndGet(increment);
      }*/
      
      for(TenantTokenInfo tki : list) {
        tki.quota += increment;
      }
    }
    
  }
  
 public static void main(String[] args) {
   AtomicInteger quota = new AtomicInteger(1);
   quota.addAndGet(8);
   System.out.println(quota.get());
 }
}
