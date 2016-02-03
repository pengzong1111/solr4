package org.apache.solr.util;

import java.util.HashMap;
import java.util.Map;
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
  private Map<String, Semaphore> coreToPointMap = new HashMap<String, Semaphore>();
  public static final int POINTS = 5000000; 
  
  // keep a sum of points for all tenants
  private AtomicInteger sum = new AtomicInteger(0);
  public void addCoreAndPoints(String coreName, int points) {
    System.out.println("======added core: " + coreName + " and points: " + points);
    coreToPointMap.put(coreName, new Semaphore(points, true));
    sum.addAndGet(points);
  }
  
  public boolean hasTenant(String coreName) {
    return coreToPointMap.containsKey(coreName);
  }
  
 /* public boolean isTenantPointEmpty(String coreName) {
    return coreToPointMap.get(coreName).availablePermits() > 0 ? false : true;
  }*/

  public void decrement(String coreName) {
    try {
      coreToPointMap.get(coreName).acquire();
      sum.decrementAndGet();
      checkAndRefill();
    } catch (InterruptedException e) {
      throw new RuntimeException("thread interrupted when acquiring semaphore!!", e);
    }
  }
  
  /**
   * see if all points are used up. if so, refill
   * 
   */
  public synchronized void checkAndRefill(){
    if(sum.get() == 0) {
      refillAll();
    }
    //we can add some logic here to unblock some tenant caused by slow tenant
  }

  public void refillAll() {
    for(String coreName : coreToPointMap.keySet()) {
      coreToPointMap.get(coreName).release(POINTS);
      sum.addAndGet(POINTS);
    }
  }

  /**
   * refill all by some percent of POINTS. 
   * This is usually used by background thread that refill bucket periodically to unblock fast tenant from waiting for the tenants that are too slow
   * @param percentage    the percentage of POINTS to refill
   */
  public synchronized void refillAllBy(int percentage) {
    int pointsToRefill = (POINTS * percentage)/100;
    int sumToAdd = 0;
    for(String coreName : coreToPointMap.keySet()) {
      Semaphore sem = coreToPointMap.get(coreName);
      if(sem.availablePermits() == 0) {
        System.out.println("refill " + coreName + " by " + pointsToRefill);
        coreToPointMap.get(coreName).release(pointsToRefill);
        sumToAdd += pointsToRefill;
      }
      sum.addAndGet(sumToAdd);
    }
  }
}
