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

public class QueryCostEvaluator {
  private static final double OR_INTERCEPT = 2.323e-01;
  private static final double OR_TERMS_DICT_WEIGHT = 6.722e-01;
  private static final double OR_POSTINGS_WEIGHT = 6.781e-05;
  private static final double OR_HEAPIFY_WEIGHT = 1.246e-05;
  
  /**
   * lm(formula = actual_search_time ~ postings + I(postings * log2(terms)) +
   * terms, data = trainingSet)
   * 
   * @param termNum
   *          number of terms in this query
   * @param postings
   *          overall number of postings for all given terms
   */
  public static long evaluateDisjunctiveQuery(int termNum, int postings) {
    double cost = OR_INTERCEPT + OR_TERMS_DICT_WEIGHT * termNum + OR_POSTINGS_WEIGHT * (double) postings + OR_HEAPIFY_WEIGHT * (double) postings * logOfBase(2, termNum);
    return (long)cost; // OK, for now, we use bottom of cost double value. May need to try ceiling in future
  }
  
  private static double logOfBase(int base, int num) {
    return Math.log(num) / Math.log(base);
}

  public static void main(String[] args) {
    System.out.println((int)evaluateDisjunctiveQuery(3, 27176));
  }
  
}
