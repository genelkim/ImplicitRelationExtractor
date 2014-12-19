package extractor

import java.util

import com.typesafe.config.{ConfigObject, Config, ConfigList, ConfigFactory}
import edu.knowitall.repr.sentence
import edu.knowitall.repr.sentence._
import edu.knowitall.taggers.TaggerCollection
import edu.knowitall.tool.chunk.ChunkedToken
import edu.knowitall.tool.stem.MorphaStemmer
import edu.knowitall.tool.typer.Type
import edu.stanford.nlp.ling
import edu.stanford.nlp.ling.{CoreLabel, IndexedWord, Word}
import edu.stanford.nlp.parser.lexparser.LexicalizedParser
import edu.stanford.nlp.trees._

import scala.collection.JavaConversions._
import scala.collection.immutable.StringOps
import scala.collection.mutable

/**
 * TODO: add comment
 * TODO: consider replacing IndexedString with stanford's IndexedWord
 *
 * Created by Gene on 11/10/2014.
 */
class NounToNounRelationExtractor(tagger: TaggerCollection[sentence.Sentence with Chunked with Lemmatized]) {
  val config = ConfigFactory.load("extractor.conf")

  private val PARSER_MODEL = config.getString("parser-model-file")
  private val parser = LexicalizedParser.loadModel(PARSER_MODEL)
  private val lemmatizer = MorphaStemmer

  private val relationPatterns =
    constructRelationPatterns(config.getConfigList("relation-patterns").toList)
  private val tagId = config.getString("tag-id")
  private val enclosingPunctuation = constructEnclosingPunctuation(
    config.getConfigList("enclosing-punctuation").toList)

  // memo function caches
  private val tagCache = mutable.Map[String, List[Type]]()
  private val parseCache = mutable.Map[String, (Tree, List[TypedDependency])]()
  private val tokenCache = mutable.Map[String, Seq[ChunkedToken]]()

  type Phrase = String
  type TagName = String
  type RelationPattern = Map[String, List[Rule]]
  type IDTable = mutable.Map[String, Set[IndexedString]]
  case class Rule(rel: String, gov: String, dep: String)
  case class NounToNounTDL(tdl: List[TypedDependency], tag: NounToNounRelation)
  case class EnclosingPunctuation(open: String, close: String)

  // Used for tokenizer.
  def process(text: String): sentence.Sentence with Chunked with Lemmatized = {
    new sentence.Sentence(text) with Chunker with Lemmatizer {
      val chunker = tagger.chunker
      val lemmatizer = MorphaStemmer
    }
  }

  // Extracts implicit relations for a string.
  def extractRelations(line: String): List[NounToNounRelation] = {
    // Process uses the same chunker.
    val tags = getTags(line)
    val tokens = tagger.chunker.chunk(line)
    val (parse, tdl) = getParse(line)

    // Add indices to the tree for the relation identifying phase.
    parse.indexLeaves()

    // Raw extractions in terms of typed dependency lists
    val processedTdl = processDependencies(tags, tdl, tokens)

    // Refined results as noun to noun relations
    val results = getNounToNounRelations(parse, processedTdl, tokens, line)

    // Add the full sentence to the results.
    results.foreach(nnr => nnr.sentence = line)
    results
  }

  def getTokens(line: String): Seq[ChunkedToken] = {
    tokenCache.get(line) match {
      case None =>
        val newtokens = tagger.chunker.chunk(line)
        tokenCache.put(line, newtokens)
        newtokens
      case Some(tokens) => tokens
    }
  }

  // Memoized tagger.
  def getTags(line: String): List[Type] = {
    tagCache.get(line) match {
      case None =>
        val newtags = tagger.tag(process(line)).toList
        tagCache.put(line, newtags)
        newtags
      case Some(tags) => tags
    }
  }

  // Memoized parser.
  def getParse(line: String): (Tree, List[TypedDependency]) = {
    parseCache.get(line) match {
      case None =>
        val tokens = tagger.chunker.chunk(line)
        val tokenizedSentence: scala.collection.immutable.List[Word] = tokens.toList.map(a => new Word(a.string))
        val rawWords: util.List[CoreLabel] = ling.Sentence.toCoreLabelList(tokenizedSentence)
        val parse: Tree = parser.apply(rawWords)

        val tlp: TreebankLanguagePack = new PennTreebankLanguagePack
        val gsf: GrammaticalStructureFactory = tlp.grammaticalStructureFactory
        val gs: GrammaticalStructure = gsf.newGrammaticalStructure(parse)
        val list = gs.typedDependenciesCCprocessed.toList
        parseCache.put(line, (parse, list))
        (parse, list)
      case Some(parse) => parse
    }
  }

  // pre: each index can have at most 1 tag
  // Creates a mapping from the token index to the TagInfo.
  def createTagMap(tags: List[Type]): Map[Int, TagInfo] = {
    def tagMapHelper (acc: Map[Int, TagInfo], tags: List[Type]): Map[Int, TagInfo] = {
      tags match {
        case Nil => acc
        case (tag :: tail) =>
          val newacc = acc + ((tag.tokenInterval.end, new TagInfo(tag)))
          tagMapHelper(newacc, tail)
      }
    }
    tagMapHelper(Map(), tags)
  }

  /**
   * Methods for testing.
   */

  /*
    To get hops from tag:
      Get word that is modified by tag: the govenor word
      go through every dependency, where the tagged word is included.
      Get all dependencies where the dep of the previous
    */
  // Pre: tdl has all the tagged dependencies for a specific tag
  // tagged and tdl must come from the same extraction.  Otherwise the result is undefined.
  // Pull out any hop that includes the tag's govenor word.
  def getSingleHops(tagged: List[TypedDependency], tdl: List[TypedDependency]): List[TypedDependency] = {
    tagged.map(tagDep => tdl.filter(td => td.gov().endPosition() == tagDep.gov().endPosition() || td.dep().endPosition() == tagDep.gov().endPosition())).flatten
  }

  def expandByOneHop(curtdl: List[List[TypedDependency]], tdl: List[TypedDependency]): List[List[TypedDependency]] = {
    curtdl.foldLeft(List[List[TypedDependency]]())((acc, cur: List[TypedDependency]) =>
      tdl.filter(td =>
        (cur.head.dep().index() == td.dep().index() ||
          cur.head.dep().index() == td.gov().index() ||
          cur.head.gov().index() == td.dep().index() ||
          cur.head.gov().index() == td.gov().index()) &&
          !cur.contains(td)
      ).map(td => td::cur):::acc
    )
  }

  def expandAllByOneHop(tdlMaps: Map[TagInfo, List[List[TypedDependency]]],
                        tdl: List[TypedDependency]): Map[TagInfo, List[List[TypedDependency]]] = {
    val newMap = mutable.Map[TagInfo, List[List[TypedDependency]]]()
    for ((k, v) <- tdlMaps) {
      newMap.put(k, expandByOneHop(v, tdl))
    }
    newMap.toMap
  }

  def dependencyHopsByTag(tags: List[Type], tdl: List[TypedDependency])
  : Map[TagInfo, List[TypedDependency]] = {
    val tagMap = createTagMap(tags)

    val results = mutable.Map[TagInfo, List[TypedDependency]]()

    for (td <- tdl) {
      tagMap.get(td.dep().index()) match {
        case None => null
        case Some(tagInfo: TagInfo) =>
          results.get(tagInfo) match {
            case None => results.put(tagInfo, td::Nil)
            case Some(current) => results.put(tagInfo, td::current)
          }
      }
      tagMap.get(td.gov().index()) match {
        case None => null
        case Some(tagInfo: TagInfo) =>
          results.get(tagInfo) match {
            case None => results.put(tagInfo, td::Nil)
            case Some(current) => results.put(tagInfo, td::current)
          }
      }
    }
    results.toMap
  }

  /**
   * Private functions.
   */

  private def getNounToNounRelations(parseTree: Tree, nntdls: List[NounToNounTDL], tokens: Seq[ChunkedToken], sentence: String): List[NounToNounRelation] = {
    nntdls.map(nntdl =>
      new NounToNounRelation(nntdl.tag.tag, nntdl.tag.relation,
        getNounPhrase(parseTree, nntdl.tdl, tokens, sentence),
        nntdl.tag.sentence, nntdl.tag.relationTrace))
          .filter(nnr => nnr.np != null)
  }

  private def getNounPhrase(tree: Tree, tdl: List[TypedDependency], tokenizedSentence: Seq[ChunkedToken], sentence: String): IndexedString = {
    // Assume that the full tag noun phrase is a child of the noun phrase
    // containing the dep and gov.
    // If incorrect - TO-DO: expand dep to the full phrase.
    def getNPRoot(tree: Tree, dep: IndexedWord, gov: IndexedWord): (Tree, Boolean, Boolean) = {
      if (tree.isLeaf) {
        val label = tree.label()toString()
        if (label.equalsIgnoreCase(s"${dep.value()}-${dep.index()}")) {
          (tree, true, false)
        } else if (label.equalsIgnoreCase(s"${gov.value()}-${gov.index()}")) {
          (tree, false, true)
        } else {
          (tree, false, false)
        }
      } else {
        val childResults = tree.children()
          .map(t => getNPRoot(t, dep, gov))
        val correctChildren = childResults.filter(t => t._1.label().toString == "NP" && t._2 && t._3)
        if (correctChildren.size > 0) {
          return correctChildren(0)
        }
        val curResult = childResults.foldLeft((false, false))((acc, cur) => (acc._1 || cur._2, acc._2 || cur._3))
        (tree, curResult._1, curResult._2)
      }
    }

    // Gets the indexed words that represent the left most and rightmost indices
    def getLeftAndRight(tdl: List[TypedDependency]): (IndexedWord, IndexedWord) = {
      tdl.foldLeft(null: IndexedWord, null: IndexedWord)((acc, cur) =>
        acc match {
          case (null, null) =>
            if (cur.dep.index() < cur.gov.index()) {
              (cur.dep, cur.gov)
            } else {
              (cur.gov, cur.dep)
            }
          case _ =>
            val (accleft, accright, curd, curg) = (acc._1, acc._2, cur.dep, cur.gov)
            List(accleft, accright, curd, curg)
              .foldLeft((accleft, accright))((acc, cur) => {
              val left = if (acc._1.index() < cur.index()) {
                acc._1
              } else {
                cur
              }
              val right = if (acc._2.index() > cur.index()) {
                acc._2
              } else {
                cur
              }
              (left, right)
            })
        })
    }

    if (tdl.size == 0) {
      return null
    }
    // get the leftmost and rightmost terms.
    val (leftmost, rightmost) = getLeftAndRight(tdl)

    // Get root of noun phrase that contains both the dependent and govenor words.
    val npRoot = getNPRoot(tree, leftmost, rightmost)._1

    // Cut out the appropriate noun phrase from the sentence.
    // Phrase tokens don't have character offsets so get them from the chunked sentence.
    val phraseTokens = phraseTokensFromTree(npRoot)
    val firstChunk = tokenizedSentence(phraseTokens(0).index - 1)
    val lastChunk = tokenizedSentence(phraseTokens(phraseTokens.size - 1).index - 1)

    val (startIndex, endIndex, wordIndex) = extendToEnclosePunctuation(
      tree, sentence, firstChunk.offset, lastChunk.offset + lastChunk.string.length,
      phraseTokens(0).index, phraseTokens(phraseTokens.size - 1).index, enclosingPunctuation)

    new IndexedString(
      sentence.substring(startIndex, endIndex), wordIndex)
  }

  def phraseTokensFromTree(tree: Tree): List[IndexedString] = {
    val labels = tree.`yield`().map(l => l.toString).toList
    // Only labels of leaf nodes will have a dash.
    // All others will be a name for a phrase type.
    labels.filter(l => l.contains("-"))
      .map(l => {
      val (string, negIndex) = l.splitAt(l.lastIndexOf('-'))
      new IndexedString(string, 0 - negIndex.toInt)
    })
  }

  /**
   * Determines the new offsets after extending the string extraction window
   * to close any open paired punctuation.  The specified punctuation to do this
   * is specified in extractor.conf in the field "enclosing-punctuation".
   *
   * @param tree Tree parse of the entire sentence.
   * @param sentence Entire source sentence string.
   * @param start Current starting character index of the extraction window. (inclusive)
   * @param end Current ending character index of the extraction window. (exclusive)
   * @param firstWordIndex Current starting parse index of the extraction window.
   * @param lastWordIndex Current ending parse index of the extraction window.
   * @param punct List of punctuation to handle, (specified in config).
   * @return A triple of ints.  Th first and second are the character offsets
   *         of the sentence for extracting the phrase after extending to
   *         close all open punctuation.  The third is the parse index, which
   *         needs to be added to NounToNounRelations.
   */
  def extendToEnclosePunctuation(tree: Tree, sentence: String, start: Int, end: Int,
                                 firstWordIndex: Int, lastWordIndex: Int,
                                 punct: List[EnclosingPunctuation]): (Int, Int, Int) = {
    val tokens = phraseTokensFromTree(tree)
    val chunks = getTokens(sentence) // token chunks
    punct.foldLeft(start, end, lastWordIndex)((punctAcc, punctCur) => {
      val lastOpen = tokens.lastIndexWhere(t => t.string.contains(punctCur.open), lastWordIndex)
      val lastClose = tokens.lastIndexWhere(t => t.string.contains(punctCur.close), lastWordIndex)
      lastOpen > lastClose && lastOpen >= firstWordIndex match {
        case true =>
          val newLastWordIndex = tokens.indexWhere(t => t.string.contains(punctCur.close), lastWordIndex + 1)
          if (newLastWordIndex <= punctAcc._3) {
            return punctAcc
          }
          val newLastCharIndex = chunks(newLastWordIndex).offset +
            chunks(newLastWordIndex).string.length
          (punctAcc._1, newLastCharIndex, newLastWordIndex + 1)
        case false => punctAcc
      }
    })

    // We won't handle nested enclosed punctuation, because that will likely overgeneralize.
    // Just extend to the right
/*
    punct.foldLeft(start, end)((acc, cur) => {
      val strOps = new StringOps(src)
      val newend =
        if (strOps.lastIndexOf(cur.open, end - 1) >
            strOps.lastIndexOf(cur.close, end - 1)) {
          strOps.indexOf(cur.close, end) + 1
        } else { end }
      (start, Math.max(acc._2, newend))
    })
*/
  }

  def genRelationFilter(relations: Set[String])(td: TypedDependency) = {
     relations.contains(td.reln().getShortName)
  }


  // Givens an id, id value, a list of rules returns a function that checks that a typed dependency
  // satisfies the rules and identifier constraints.
  // If satisfied, returns the next step id and idValue.
  def genIdRuleMap(id: String, idValue: IndexedString, rules: List[Rule], tokens: Seq[ChunkedToken])
                  (td: TypedDependency): (TypedDependency, String, IndexedString) = {
    rules.foldLeft(null: (TypedDependency, String, IndexedString))((acc, cur) => {
      if (acc != null) {
        return acc
      }
      val matchesRelation = td.reln().toString.equals(cur.rel)

      val matchesDep = cur.dep.equals(id) &&
        tokens(idValue.index - 1).string.equals(td.dep.value()) &&
        idValue.index == td.dep.index()
      val matchesGov = cur.gov.equals(id) &&
        tokens(idValue.index - 1).string.equals(td.gov.value()) &&
        idValue.index == td.gov.index()
      val result = matchesRelation && (matchesDep || matchesGov) && !(matchesDep && matchesGov)

      if (result && matchesDep) {
        (td, cur.gov, new IndexedString(td.gov()))
      } else if (result && matchesGov) {
        (td, cur.dep, new IndexedString(td.dep()))
      } else {
        null
      }
    })
  }

  // Find relations that match the given id/idValue and satisfy the relation
  // rules.  Expand each of those relations by the corresponding pattern
  // and concatenate all the results.
  def expandByPattern(tdl: List[TypedDependency],
                      id: String,
                      idValue: IndexedString,
                      patterns: RelationPattern,
                      tokens: Seq[ChunkedToken]): List[TypedDependency] = {
    // filter by relation
    // map relations to the next hop id and idval
    // map to expand pattern on the filtered results
    // fold to merge
    val rules = patterns.getOrElse(id, Nil)
    if (rules == Nil) {
      return Nil
    }
    tdl.map(genIdRuleMap(id, idValue, rules, tokens))
       .filter(x => x != null)
       .map(triple => triple._1::expandByPattern(tdl, triple._2, triple._3, patterns, tokens))
       .fold(Nil: List[TypedDependency])((acc, cur) => cur:::acc)
  }

  private def processDependencies(tags: List[Type], tdl: List[TypedDependency],
                                  tokens: Seq[ChunkedToken]): List[NounToNounTDL] = {
    def constructIdTable(tags: List[Type]): mutable.Map[String, Set[IndexedString]] = {
      // Only put in the last word.
      tags.foldLeft(mutable.Map[String, Set[IndexedString]]())((acc, cur) => {
        acc.put(tagId, acc.getOrElse(tagId, Set[IndexedString]()) + new IndexedString(cur.text.toLowerCase, cur.tokenInterval.end))
        acc
      })
    }
    val idTable = constructIdTable(tags)
    val tagMap = createTagMap(tags)

    val tagWords = tdl
      .map(td => tagMap.getOrElse(td.dep.index, tagMap.getOrElse(td.gov.index, null)))
      .filter(w => w != null).toSet.toList

    val expansions = tagWords.map(tag =>
      (tag, expandByPattern(tdl, tagId, tag.asIndexedString, relationPatterns, tokens)))

    expansions.map(pair => {
        val nnTag = new NounToNounRelation(pair._1.asIndexedString, pair._1.tag,
          IndexedString.emptyInstance, "", pair._2)
        NounToNounTDL(pair._2, nnTag)
      }
    )
  }

  // Constructs a mapping of relation patterns given the config object for those patterns.
  private def constructRelationPatterns(relConfs: List[Config]): RelationPattern = {
    def getRuleList(acc: List[Rule], rulesConf: List[Config]): List[Rule] = {
      rulesConf match {
        case Nil => acc
        case head::tail =>
          val ruleVals = head.getStringList("rule")
          val rule = Rule(ruleVals.get(0), ruleVals.get(1), ruleVals.get(2))
          getRuleList(rule::acc, tail)
      }
    }

    relConfs match {
      case Nil => Map[String, List[Rule]]()
      case head::tail =>
        val rulesConf = head.getConfigList("rules").toList
        val expansionId = head.getString("expansion-id")
        val patterns = getRuleList(Nil, rulesConf)
        constructRelationPatterns(tail) + ((expansionId, patterns))
    }
  }

  // Constructs a list of enclosing punctuation from the given config.
  private def constructEnclosingPunctuation(punctConfs: List[Config]): List[EnclosingPunctuation] = {
    punctConfs.map(pc => EnclosingPunctuation(pc.getString("open"), pc.getString("close")))
  }

  /**
   * Printing functions (used for debugging).
   */

  // Prints a list of NounToNounRelations.
  private def printNounToNounRelations(relns: Iterable[NounToNounRelation]) {
    println("NounToNounRelations")
    for (reln <- relns) {
      println(reln)
    }
    println()
  }

  // Prints the labels of a tree.
  private def printLabels(tree: Tree) {
    println(s"${tree.label()}, ${tree.isLeaf}")
    for (child <- tree.children()) {
      printLabels(child)
    }
  }

  // Prints details a of tdl.
  private def printTdl(tdl: List[TypedDependency], message: String) {
    println(message)
    for (i <- 0 until tdl.size){
      val td = tdl(i)
      println(s"${td.dep()}, ${td.gov()}, ${td.reln().getShortName}, ${td.dep().tag()}")
    }
    println()
  }
}
