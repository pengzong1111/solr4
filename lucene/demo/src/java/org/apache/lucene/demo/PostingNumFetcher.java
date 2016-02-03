package org.apache.lucene.demo;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
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

public class PostingNumFetcher {
  
  public static void main(String[] args) throws IOException {
    
    Directory dir = new SimpleFSDirectory(new File("Z:\\HTRC\\core0-data\\index"));
    IndexReader indexReader = DirectoryReader.open(dir);
    IndexSearcher indexSearcher = new IndexSearcher(indexReader);
    
    Query query1 = new TermQuery(new Term("title", "merry"));
    Query query2 = new TermQuery(new Term("title", "merry"));
    Query query3 = new TermQuery(new Term("title", "santa"));
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("title", "love"));
    phraseQuery.add(new Term("title", "you"));
    phraseQuery.add(new Term("title", "do"));
    System.out.println(phraseQuery.toString());
    
  //  query1.createWeight(indexSearcher).explain(context, doc)
    
    BooleanQuery bquery = new BooleanQuery();
    bquery.add(query1, BooleanClause.Occur.SHOULD);
    bquery.add(query2, BooleanClause.Occur.SHOULD);
    bquery.add(query3, BooleanClause.Occur.SHOULD);
    
    //consider wildcard and other automatom later
 //   WildcardQuery wildCardQuery = new WildcardQuery(new Term("title", "war*"));
   // QParser parser = QParser.getParser(rb.getQueryString(), "lucene", req);
  //  Query q = parser.getQuery();
    Query rewrittenQuery = bquery.rewrite(indexReader);
    Set<Term> terms = new HashSet<Term>();
    rewrittenQuery.extractTerms(terms );
    System.out.println("written type: " + rewrittenQuery.getClass().getCanonicalName());
    System.out.println("rewritten: " + rewrittenQuery.toString());
    System.out.println("terms: " + terms);
    System.out.println("overall posting length of " + bquery.toString() + ": " + getAllPostingLength(terms, indexReader));
    
    long t0 = System.currentTimeMillis();
    TopFieldDocs result = indexSearcher.search(phraseQuery, 10, Sort.RELEVANCE);
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
    }
    
    System.out.println("===============================");
    System.out.println("posting length for war: " + getPostingLength(new Term("title", "merri"), indexReader));
    indexReader.close();
    dir.close();
  }
  
  public static int getPostingLength(Term term, IndexReader indexReader) throws IOException {
    long t0 = System.currentTimeMillis();
    int docFreq = indexReader.docFreq(term);
    long t1 = System.currentTimeMillis();
    System.out.println("time for getting posting length of " + docFreq +": " + (t1 - t0));
    return docFreq;
  }
  
  public static int getAllPostingLength(Set<Term> terms, IndexReader indexReader) throws IOException {
    int count = 0;
    for(Term term : terms) {
      count += getPostingLength(term, indexReader);
    }
    return count;
  }
  
}
