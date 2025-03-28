package dotty.tools.dotc
package transform

import core._
import Flags._
import Contexts._
import Symbols._
import SymUtils._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.quoted._
import dotty.tools.dotc.core.StagingContext._
import dotty.tools.dotc.inlines.Inlines
import dotty.tools.dotc.ast.TreeMapWithImplicits
import dotty.tools.dotc.core.DenotTransformers.IdentityDenotTransformer

import scala.collection.mutable.ListBuffer

/** Inlines all calls to inline methods that are not in an inline method or a quote */
class Inlining extends MacroTransform {

  import tpd._

  override def phaseName: String = Inlining.name

  override def description: String = Inlining.description

  override def allowsImplicitSearch: Boolean = true

  override def changesMembers: Boolean = true

  override def run(using Context): Unit =
    if ctx.compilationUnit.needsInlining || ctx.compilationUnit.hasMacroAnnotations then
      try super.run
      catch case _: CompilationUnit.SuspendException => ()

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val newUnits = super.runOn(units).filterNot(_.suspended)
    ctx.run.nn.checkSuspendedUnits(newUnits)
    newUnits

  override def checkPostCondition(tree: Tree)(using Context): Unit =
    tree match {
      case PackageDef(pid, _) if tree.symbol.owner == defn.RootClass =>
        new TreeTraverser {
          def traverse(tree: Tree)(using Context): Unit =
            tree match
              case _: GenericApply if tree.symbol.isQuote =>
                traverseChildren(tree)(using StagingContext.quoteContext)
              case _: GenericApply if tree.symbol.isExprSplice =>
                traverseChildren(tree)(using StagingContext.spliceContext)
              case tree: RefTree if !Inlines.inInlineMethod && StagingContext.level == 0 =>
                assert(!tree.symbol.isInlineMethod, tree.show)
              case _ =>
                traverseChildren(tree)
        }.traverse(tree)
      case _ =>
    }

  def newTransformer(using Context): Transformer = new Transformer {
    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      new InliningTreeMap().transform(tree)
  }

  private class InliningTreeMap extends TreeMapWithImplicits {

    /** List of top level classes added by macro annotation in a package object.
     *  These are added to the PackageDef that owns this particular package object.
     */
    private val newTopClasses = MutableSymbolMap[ListBuffer[Tree]]()

    override def transform(tree: Tree)(using Context): Tree = {
      tree match
        case tree: MemberDef =>
          if tree.symbol.is(Inline) then tree
          else if tree.symbol.is(Param) then super.transform(tree)
          else if
            !tree.symbol.isPrimaryConstructor
            && StagingContext.level == 0
            && MacroAnnotations.hasMacroAnnotation(tree.symbol)
          then
            val trees = (new MacroAnnotations).expandAnnotations(tree)
            val trees1 = trees.map(super.transform)

            // Find classes added to the top level from a package object
            val (topClasses, trees2) =
              if ctx.owner.isPackageObject then trees1.partition(_.symbol.owner == ctx.owner.owner)
              else (Nil, trees1)
            if topClasses.nonEmpty then
              newTopClasses.getOrElseUpdate(ctx.owner.owner, new ListBuffer) ++= topClasses

            flatTree(trees2)
          else super.transform(tree)
        case _: Typed | _: Block =>
          super.transform(tree)
        case _ if Inlines.needsInlining(tree) =>
          val tree1 = super.transform(tree)
          if tree1.tpe.isError then tree1
          else Inlines.inlineCall(tree1)
        case _: GenericApply if tree.symbol.isQuote =>
          super.transform(tree)(using StagingContext.quoteContext)
        case _: GenericApply if tree.symbol.isExprSplice =>
          super.transform(tree)(using StagingContext.spliceContext)
        case _: PackageDef =>
          super.transform(tree) match
            case tree1: PackageDef  =>
              newTopClasses.get(tree.symbol.moduleClass) match
                case Some(topClasses) =>
                  newTopClasses.remove(tree.symbol.moduleClass)
                  val newStats = tree1.stats ::: topClasses.result()
                  cpy.PackageDef(tree1)(tree1.pid, newStats)
                case _ => tree1
            case tree1 => tree1
        case _ =>
          super.transform(tree)
    }
  }
}

object Inlining:
  val name: String = "inlining"
  val description: String = "inline and execute macros"
