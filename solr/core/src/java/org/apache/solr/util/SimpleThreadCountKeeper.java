package org.apache.solr.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

public class SimpleThreadCountKeeper {
  private Map<String, AtomicInteger> coreNameToThreadCountMap = new ConcurrentHashMap<String, AtomicInteger>();
  
  public void add(String coreName, int count) {
    coreNameToThreadCountMap.put(coreName, new AtomicInteger(count));
  }
  
  public void incrementThreadCount(String coreName) {
    if(coreNameToThreadCountMap.containsKey(coreName)) {
      coreNameToThreadCountMap.get(coreName).incrementAndGet();
    } 
  }
  
  public int get(String coreName) {
    return coreNameToThreadCountMap.get(coreName).get();
  }
  
  public String toString() {
    return coreNameToThreadCountMap.toString();
    
  }
}
