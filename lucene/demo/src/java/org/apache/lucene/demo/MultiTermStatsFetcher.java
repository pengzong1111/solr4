package org.apache.lucene.demo;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiTermQueryWrapperFilter;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;

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

public class MultiTermStatsFetcher {
  
  public static void main(String[] args) throws IOException {
    Directory dir = new SimpleFSDirectory(new File("Z:\\HTRC\\core0-data\\index"));
    IndexReader indexReader = DirectoryReader.open(dir);
    IndexSearcher indexSearcher = new IndexSearcher(indexReader);
    
    WildcardQuery wildCardQuery = new WildcardQuery(new Term("title", "war*"));
    PrefixQuery prefixQuery = new PrefixQuery(new Term("title", "war"));
 
 //   MultiTermQueryWrapperFilter wildcardWrapperFilter = new MultiTermQueryWrapperFilter(wildCardQuery );
   long beforeAllTerms = System.currentTimeMillis();
    List<AtomicReaderContext> leaves = indexReader.leaves();
  //  System.out.println("there are " + leaves.size() + " leaves..");
    int docFreq = 0;
    int postingNum = 0;
    for(AtomicReaderContext context : leaves) {
      AtomicReader atomicReader = context.reader();
      Fields fields = atomicReader.fields();
      Terms terms = fields.terms(wildCardQuery.getField());
      TermsEnum termsEnum = wildCardQuery.getTermsEnum(terms);
      while (termsEnum.next() != null) {
        System.out.println(termsEnum.term().utf8ToString() + " : " + termsEnum.docFreq());
        docFreq += termsEnum.docFreq();
        postingNum ++;
      }
      System.out.println("----------------------------------------");
    }
    long afterAllTerms = System.currentTimeMillis();
    System.out.println("time for getting all matching terms: " + (afterAllTerms - beforeAllTerms));
    System.out.println("overall posting length is " + docFreq);
  //  ConstantScoreQuery rewrittenQuery = (ConstantScoreQuery) wildCardQuery.rewrite(indexReader);
 //   Set<Term> terms = new HashSet<Term>();
 //   rewrittenQuery.extractTerms(terms );
//    System.out.println("written type: " + ((ConstantScoreQuery)rewrittenQuery).toString());
//    System.out.println("rewritten: " + rewrittenQuery.toString());
//    System.out.println("rewritten inner query: " + rewrittenQuery.getFilter());
//    System.out.println("terms: " + terms);
  /*  
    long t0 = System.currentTimeMillis();
    TopFieldDocs result = indexSearcher.search(wildCardQuery, 10, Sort.RELEVANCE);
    ScoreDoc[] scoreDocs = result.scoreDocs;
    long t1 = System.currentTimeMillis();
    System.out.println("overall search time: " + (t1- t0));
    System.out.println("total hits: " + result.totalHits);
    System.out.println("scoreDocs length:" + scoreDocs.length);
    
    for(ScoreDoc scoreDoc : scoreDocs){
      Document doc = indexSearcher.doc(scoreDoc.doc);
      System.out.print(doc.get("id"));
      System.out.print(" : ");
      System.out.println(doc.get("title"));
    }*/
    
    indexReader.close();
    dir.close();  
  }
  
}
