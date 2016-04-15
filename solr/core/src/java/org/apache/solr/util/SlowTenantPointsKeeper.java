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

public class SlowTenantPointsKeeper {

  private static final int MAXIMUM_LOGICAL_THROUGHPUT = 33000; //computation per sec
  
//1. we think of each core as a tenant to start off. Each core has its default points. 
  private static SlowTenantPointsKeeper tenantPointsKeeper = new SlowTenantPointsKeeper();
  
  public static int POINTS; // = 8000;  // initial points, also maximum size for each bucket/core/tenant
  
  //each tenant has its quota that may change with the changes of actual throughput
  private static Map<String, TenantTokenInfo> coreNameToTenantMap = new HashMap<String, TenantTokenInfo>();
  //each tenant has its current tokens left
  protected Map<String, Semaphore> coreToPointMap = new HashMap<String, Semaphore>();
  //the timestamp when last fill/refill happens
  private long timeRefill;
  //actual throughput in between previous refill and current refill
  private Map<String, Integer> tenantToActualThroughput = new ConcurrentHashMap<String, Integer>();
  
  private static PrintWriter pw ;
  
  public static SlowTenantPointsKeeper getInstance() {
    return tenantPointsKeeper;
  }
  
  private SlowTenantPointsKeeper() {
    try {
      pw = new PrintWriter(new File("slow-tenant.log"));
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
              pw.println("refilled for all tenants"); pw.flush();
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
      int quota = coreNameToTenantMap.get(coreName).quota.get();
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
    AtomicInteger quota; // tenant token quota
    
    AtomicLong acutalTimeUsed; // actual time used during specified time interval
    
    AtomicInteger actualTokenUsed; // actual #token used during specified time interval
    
    AtomicInteger logicalThroughput; // actual logicThroughput consumed
    
    public TenantTokenInfo(int quota, long acutalTimeUsed, int actualTokenUsed, int logicalThroughput) {
      this.quota = new AtomicInteger(quota);
      this.acutalTimeUsed = new AtomicLong(acutalTimeUsed);
      this.actualTokenUsed = new AtomicInteger(actualTokenUsed);
      this.logicalThroughput = new AtomicInteger(logicalThroughput);
    }

    @Override
    public int compareTo(Object o) {
      if(o instanceof TenantTokenInfo) {
        TenantTokenInfo tki = (TenantTokenInfo)o;
        if(this.logicalThroughput.get() > tki.logicalThroughput.get()) {
          return 1;
        } else if(this.logicalThroughput.get() < tki.logicalThroughput.get()) {
          return -1;
        } else {
          return 0;
        }
      }
      return -1;
    }
  }
  
  class ActualThroughputChecker extends Thread {
    private long timeInterval = 15000; // every 10,000ms, go check the actual logical throughput for each tenant
    
    public void run() {
      
      while (true) {
        try {
          sleep(timeInterval); // first sleep 10 seconds before
        } catch (InterruptedException e) {
          throw new RuntimeException();
        }
        long currentTime = System.currentTimeMillis();
        for(Entry<String, TenantTokenInfo> entry : coreNameToTenantMap.entrySet()) {
          // do we need to synchronize using intrisic lock of this tenant's semophore
          String coreName = entry.getKey();
          TenantTokenInfo tenant = entry.getValue();
          int actualUsedToken = tenant.actualTokenUsed.get() /*+ (tenant.quota.get() - coreToPointMap.get(coreName).availablePermits())*/;
          long timeInterval = currentTime - timeRefill;
          long actualTimeUsed = tenant.acutalTimeUsed.get();
          long actualUsedTime = actualTimeUsed + timeInterval;
          int logicalThroughput = (int) ((actualUsedToken*1000) / actualUsedTime);
          pw.println("logical throughput for " + coreName + " is: 1000*" + actualUsedToken + "/(" + actualTimeUsed + "+"+ timeInterval + ") ==" + logicalThroughput); pw.flush();
          tenant.logicalThroughput = new AtomicInteger(logicalThroughput);
          tenant.actualTokenUsed = new AtomicInteger(0);
          tenant.acutalTimeUsed = new AtomicLong(0);
        }
        pw.println("======================"); pw.flush();
        adjustQuotas(coreNameToTenantMap);
      }
    }

    // try max-min to refill
    @SuppressWarnings("unchecked")
    private void adjustQuotas(Map<String,TenantTokenInfo> coreNameToTenantMap) {
      //if all tenants 
     List<String> slowTenants = new LinkedList<String>();
     List<String> fastTenants = new LinkedList<String>();
     List<TenantTokenInfo> fastTenantInfos = new LinkedList<TenantTokenInfo>();
      int deprived = 0; // the #tokens deprived from slow tenant
      for(Entry<String,TenantTokenInfo> entry : coreNameToTenantMap.entrySet()) {
        String coreName = entry.getKey();
        TenantTokenInfo tenant = entry.getValue();
        int logicalThroughput = tenant.logicalThroughput.get();
        int quota = tenant.quota.get();
        if(logicalThroughput <= quota*0.8) { // for slow tenant, reduce quota and recycle the reduced quota
          deprived += quota-logicalThroughput;
          tenant.quota.set(logicalThroughput);
          slowTenants.add(coreName);
        } else /*if(tenant.logicalThroughput.get() >= POINTS*0.9*//**1.2*//*)*/ { // if there are other tenants that requires more that POINTs, then allocate them some more
          fastTenants.add(coreName);
          fastTenantInfos.add(tenant);
        }
      }
      
      pw.println("slow tenant: " + slowTenants); pw.flush();
      pw.println("fast tenant: " + fastTenants); pw.flush();
      pw.println("deprived: " + deprived); pw.flush();
      int fastTenantSize = fastTenants.size();
      if(deprived > 0 && fastTenants.size() > 0 ) {
        int fairDeprived = deprived / fastTenants.size();
        
        if(fairDeprived <= 0) return;
        incrementQuota(fastTenantSize, fastTenantInfos, fairDeprived);
        
        
/*        Collections.sort(fastTenantInfos);
        for(TenantTokenInfo tenant : fastTenantInfos) {
          int higher = tenant.logicalThroughput.get() - POINTS;
          if(higher >= fairDeprived) {
            incrementQuota(fastTenantSize, fastTenantInfos, fairDeprived);
            return;
          } else {
            tenant.quota.addAndGet(higher);
            deprived -= higher;
            fairDeprived = deprived/(--fastTenantSize);
            
          }
        }*/
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
        tki.quota.addAndGet(increment);
      }
    }
    
  }
  
 public static void main(String[] args) {
   AtomicInteger quota = new AtomicInteger(1);
   quota.addAndGet(8);
   System.out.println(quota.get());
 }
}
