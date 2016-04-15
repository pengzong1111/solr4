package org.apache.solr.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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

public class SimpleTenantPointsKeeper {
//1. we think of each core as a tenant to start off. Each core has its default points. 
  private static SimpleTenantPointsKeeper tenantPointsKeeper = new SimpleTenantPointsKeeper();
  
  //private static PrintWriter pw ;
  
  public static SimpleTenantPointsKeeper getInstance() {
    return tenantPointsKeeper;
  }
  
  private SimpleTenantPointsKeeper() {/*
    try {
      pw = new PrintWriter(new File("token-bucket.log"));
    } catch (FileNotFoundException e) {
      throw new RuntimeException();
    }
  */}
  
  private Map<String, Semaphore> coreToPointMap = new HashMap<String, Semaphore>();
  public static final int POINTS = 8000;  // initial points, also maximum size for each bucket/core/tenant
  
  // core name map to the status whether refill is needed
  private Map<String, Boolean> coreToStatusMap = new HashMap<String, Boolean>();
  
  // keep a sum of points for all tenants
  private AtomicInteger sum = new AtomicInteger(0);
  public void addCoreAndPoints(String coreName, int points) {
    System.out.println("======added core: " + coreName + " and points: " + points);
    coreToPointMap.put(coreName, new Semaphore(points, true));
    coreToStatusMap.put(coreName, false); // here false means "no refill needed"
    sum.addAndGet(points);
  }
  
  public boolean hasTenant(String coreName) {
    return coreToPointMap.containsKey(coreName);
  }
  
 /* public boolean isTenantPointEmpty(String coreName) {
    return coreToPointMap.get(coreName).availablePermits() > 0 ? false : true;
  }*/

  /**
   * decrement count for coreName
   * @param coreName  coreName for tenant
   * @param count     the number of permits/points to decrease
   */
  public void decrement(String coreName, int count) {
    try {
      Semaphore sem = coreToPointMap.get(coreName);
   //   pw.println("decrement: " + coreName + " count: " + count + " from left: " + sem.availablePermits()); pw.flush();
      synchronized (sem) {
        if (sem.availablePermits() < count) {
  //        pw.println("cannot decrement semophore by " + count + " for " + coreName); pw.flush();
          if (!coreToStatusMap.get(coreName)) {
            coreToStatusMap.put(coreName, true);
            if (isRefillNeeded()) {
  //            pw.println("refilled for all tenants"); pw.flush();
              refillAllBy(100);
              resetRefillFlag();
            } else {
  //            pw.println("no refill needed"); pw.flush();
            }
          }
        }
        
        sem.acquire(count);
      }
      
      // sum.decrementAndGet();
      // checkAndRefill();
    } catch (InterruptedException e) {
      throw new RuntimeException(
          "thread interrupted when acquiring semaphore!!", e);
    }
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
   * see if all points are used up(actually 90% of sum are used). if so, refill
   * 
   */
  public synchronized void checkAndRefill(){
    if(sum.get() < (coreToPointMap.size() * POINTS) * 0.1) {
      refillAllBy(100);
    }
    //we can add some logic here to unblock some tenant caused by slow tenant
  }

/*  public void refillAll() {
    for(String coreName : coreToPointMap.keySet()) {
      coreToPointMap.get(coreName).release(POINTS);
      sum.addAndGet(POINTS);
    }
    System.out.println("all refilled..");
  }*/

  /**
   * refill all by some percent of POINTS. 
   * This is usually used by background thread that refill bucket periodically to unblock fast tenant from waiting for the tenants that are too slow
   * @param percentage    the percentage of POINTS to refill
   */
  private void refillAllBy(int percentage) {
    int pointsToRefill = (POINTS * percentage)/100;
    int sumToAdd = 0;
    for(String coreName : coreToPointMap.keySet()) {
      Semaphore sem = coreToPointMap.get(coreName);
      if((sem.availablePermits() + pointsToRefill) <= POINTS) {
        sem.release(pointsToRefill);
        sumToAdd += pointsToRefill;
      } else {
        pointsToRefill = POINTS - sem.availablePermits();
        sem.release(pointsToRefill);
        sumToAdd += pointsToRefill;
      }

      sum.addAndGet(sumToAdd);
    }
  }
  
  public void initialize( Collection<String> coreNames) {
  for(String coreName : coreNames) {
    SimpleTenantPointsKeeper.getInstance().addCoreAndPoints(coreName,  POINTS);
  }
  }
}
