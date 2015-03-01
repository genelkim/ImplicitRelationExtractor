package experimenting

import java.io.PrintWriter

import extractor.{ImplicitRelationExtractor, TaggerLoader}
import utils.ExtractionFormatUtils

import scala.collection.mutable
import scala.io.Source

/**
 * Created by Gene on 12/24/2014.
 */
object VerboseExtractor {
  val sentenceFile = "test_sentences.txt"
  val outputFile = "test_verbose-extractions.txt"

  def main(args: Array[String]) {
    val tagger = TaggerLoader.defaultTagger
    val extractor = new ImplicitRelationExtractor(tagger)
    val out = new PrintWriter(outputFile)

    for (sentence <- Source.fromFile(sentenceFile).getLines()) {
      val extractions = extractor.extractRelations(sentence)
      out.println(sentence)

      for (extraction <- extractions) {
        out.println(s"extraction:$extraction")
        out.println(s"\ttrace:${extraction.relationTrace}")
      }

      // tag hops
      out.println(ExtractionFormatUtils.extractionInfo(extractor)(sentence))

      // parse tree + dependency relations
      out.println(ExtractionFormatUtils.verboseOutput(extractor)(sentence))
      out.println()
    }
    out.close()
  }
}
