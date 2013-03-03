/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.parse.rst

import laika.tree.Elements._
import scala.annotation.tailrec

/**
 * @author Jens Halm
 */
trait BlockBaseParsers extends laika.parse.BlockParsers {

  
  /** The maximum level of block nesting. Some block types like lists
   *  and blockquotes contain nested blocks. To protect against malicious
   *  input or accidentally broken markup, the level of nesting is restricted.
   */
  val maxNestLevel: Int = 12
  
  
  /** Parses all of the standard reStructuredText blocks, except normal paragraphs. 
   * 
   *  @param pos the current parsing position 
   */
  def standardRstBlock: Parser[Block]

  /** Parses reStructuredText blocks, except normal paragraphs
   *  and blocks that allow nesting of blocks. Only used in rare cases when the maximum
   *  nest level allowed had been reached
   */
  def nonRecursiveRstBlock: Parser[Block]
  
  def paragraph: Parser[Paragraph]
  
  
  override def ws = anyOf(' ') // other whitespace has been replaced with spaces by preprocessor

  
  def parseNestedBlocks (block: IndentedBlock): List[Block] = 
    parseNestedBlocks(block.lines, block.nestLevel)
  
  def parseNestedBlocks (lines: List[String], nestLevel: Int): List[Block] = {
    val parser = if (nestLevel < maxNestLevel) nestedBlock else nonRecursiveRstBlock 
    val block = parser <~ opt(blankLines) 
    parseMarkup(block *, lines mkString "\n")
  }
  
  /** Parses a full block based on the specified helper parsers, expecting an indentation for
   *  all lines except the first. The indentation must be as specified by the first parameter
   *  for all lines of these blocks.
   * 
   *  @param minIndent the minimum indentation that each line in this block must have
   *  @param linePrefix parser that recognizes the start of subsequent lines that still belong to the same block
   *  @param nextBlockPrefix parser that recognizes whether a line after one or more blank lines still belongs to the same block 
   *  @return a parser that produces a tuple holding the minimum amount of indentation for all parsed lines and the lines itself
   *  with the minimum indentation already removed
   */
  def fixedIndentedBlock (fixedIndent: Int = 1,
                     linePrefix: => Parser[Any] = not(blankLine), 
                     nextBlockPrefix: => Parser[Any] = not(blankLine)): Parser[IndentedBlock] = {
    
    lazy val line = (ws take fixedIndent) ~ linePrefix ~> restOfLine
    
    lazy val nextBlock = blankLines <~ guard(nextBlockPrefix) ^^ { _.mkString("\n") }
    
    withNestLevel {
      restOfLine ~ ( (line | nextBlock)* ) ^^ { res => (fixedIndent, mkList(res)) }
    } ^^ { case (nestLevel, (indent, lines)) => IndentedBlock(nestLevel, indent, lines) }
  }
  
  case class IndentedBlock (nestLevel: Int, minIndent: Int, lines: List[String])
  
  /** Parses a full block based on the specified helper parsers, expecting an indentation for
   *  all lines except the first. The indentation may vary between the parts of the indented
   *  block, so that this parser only cuts off the minimum indentation shared by all lines.
   * 
   *  @param minIndent the minimum indentation that each line in this block must have
   *  @param linePrefix parser that recognizes the start of subsequent lines that still belong to the same block
   *  @param nextBlockPrefix parser that recognizes whether a line after one or more blank lines still belongs to the same block 
   *  @return a parser that produces a tuple holding the minimum amount of indentation for all parsed lines and the lines itself
   *  with the minimum indentation already removed
   */
  def varIndentedBlock (minIndent: Int = 1,
                     linePrefix: => Parser[Any] = not(blankLine), 
                     nextBlockPrefix: => Parser[Any] = not(blankLine)): Parser[IndentedBlock] = {
    
    val firstLine = restOfLine ^^ { (-1, _) }
    
    lazy val line = (((ws min minIndent) ^^ (_.length)) ~ 
                    (linePrefix ~> restOfLine)) ^^ { case indent ~ text => (indent, text.trim) }
    
    lazy val nextBlock = blankLines <~ guard(nextBlockPrefix) ^^ { res => (-1, res.mkString("\n")) }
    
    withNestLevel {
      firstLine ~ ( (line | nextBlock)* ) ^^ { res => 
        val lines = mkList(res)
        val minIndent = lines map (_._1) filter (_ == -1) min;
        (minIndent, lines map (line => if (line == -1) line._2 else " " * (line._1 - minIndent) + line._2))
      }
    } ^^ { case (nestLevel, (indent, lines)) => IndentedBlock(nestLevel, indent, lines) }
  }
  
  def withNestLevel [T] (p: => Parser[T]) = Parser { in =>
    p(in) match {
      case Success(res, next) => Success((0, res), next) // TODO - determine nestLevel from next
      case ns: NoSuccess      => ns
    }
  }
  
  
}