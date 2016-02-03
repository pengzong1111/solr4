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
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;

public class DummySearcher {

  public static void main(String[] args) throws IOException {
    
    Directory dir = new SimpleFSDirectory(new File("Z:\\HTRC\\core0-data\\index"));
    IndexReader indexReader = DirectoryReader.open(dir);
    IndexSearcher indexSearcher = new IndexSearcher(indexReader);
    System.out.println("how many docs in title field? : " + indexReader.getDocCount("title"));
    
    Query query1 = new TermQuery(new Term("title", "world"));
    Query query2 = new TermQuery(new Term("title", "war"));
    Query query3 = new TermQuery(new Term("title", "war"));
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("title", "world"));
    phraseQuery.add(new Term("title", "war"));
    phraseQuery.add(new Term("title", "ii"));
//    System.out.println(phraseQuery.toString());
    
  //  query1.createWeight(indexSearcher).explain(context, doc)
    
    BooleanQuery bquery = new BooleanQuery();
    bquery.add(query1, BooleanClause.Occur.MUST);
    bquery.add(query2, BooleanClause.Occur.MUST);
    bquery.add(query3, BooleanClause.Occur.MUST);
    
    WildcardQuery wildCardQuery = new WildcardQuery(new Term("title", "war*"));
    
    Query rewrittenQuery = bquery.rewrite(indexReader);
    Set<Term> terms = new HashSet<Term>();
    rewrittenQuery.extractTerms(terms );
//    System.out.println("written type: " + ((ConstantScoreQuery)rewrittenQuery).toString());
    System.out.println("rewritten: " + rewrittenQuery.toString());
    System.out.println("rewritten class: " + rewrittenQuery.getClass().getCanonicalName());
  //  System.out.println("rewritten inner query: " + rewrittenQuery.getFilter());
    System.out.println("terms: " + terms);
    
    long t0 = System.currentTimeMillis();
    TopFieldDocs result = indexSearcher.search(bquery, 10, Sort.RELEVANCE);
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
    
    indexReader.close();
    dir.close();
    
    System.out.println("===============================");
  //  System.out.println("posting length for war: " + getPostingLength(new Term("title", "great")));
  }
  
  public static int getPostingLength(Term term) throws IOException {
    Directory dir = new SimpleFSDirectory(new File("Z:\\HTRC\\core0-data\\index"));
    IndexReader indexReader = DirectoryReader.open(dir);
 //   IndexSearcher indexSearcher = new IndexSearcher(indexReader);
    long t0 = System.currentTimeMillis();
    int docFreq = indexReader.docFreq(term);
    long t1 = System.currentTimeMillis();
    System.out.println("time for getting posting length: " + (t1 - t0));
    return docFreq;
  }
}
