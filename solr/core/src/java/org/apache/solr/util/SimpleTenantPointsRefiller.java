package org.apache.solr.util;

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

/**
 * a background thread that refills each tenant points regularly at pre-configured rate.
 * the refill rate is preferred to be slow so that it just unblocks the fast tenants when there is a slow tenant. 
 */
public class SimpleTenantPointsRefiller extends Thread {
  
 // private SimpleTenantPointsKeeper tenantPointsKeeper;
  private int sleepTimeInMillisecond = 1000;
  public SimpleTenantPointsRefiller() {
 //   this.tenantPointsKeeper = tenantPointsKeeper;
  }
  
  @Override
  public void run() {
    
    while(true) {
      try {
        Thread.sleep(sleepTimeInMillisecond);
      } catch (InterruptedException e) {
        System.out.println("background points refiller is interrupted: " + e.getMessage());
      }
      //only refill 20%
      System.out.println("try refilling...");
 //     SimpleTenantPointsKeeper.getInstance().refillAllBy(20);;
    }
    
  }
}
