package ddbt.transformer

import java.util.Locale

import ch.epfl.data.sc.pardis.ir.Statement
import ch.epfl.data.sc.pardis.optimization.{RecursiveRuleBasedTransformer, RuleBasedTransformer}
import ch.epfl.data.sc.pardis.types.UnitType
import ddbt.lib.store.deep.{ProfileEnd, ProfileStart, StoreDSL}

/**
  * Created by sachin on 14/3/17.
  */
class Profiler(override val IR: StoreDSL) extends RecursiveRuleBasedTransformer[StoreDSL](IR) {

  import IR._

  def super_optimize[T: TypeRep](node: Block[T]): Block[T] = {
    val analyseProgram = classOf[RuleBasedTransformer[StoreDSL]].getDeclaredMethod("analyseProgram", classOf[Block[T]], classOf[TypeRep[T]])
    analyseProgram.setAccessible(true)
    val isDone = classOf[RecursiveRuleBasedTransformer[StoreDSL]].getDeclaredField("isDone")
    isDone.setAccessible(true)
    var currentBlock = node
    var counter = 0
    while (isDone.get(this) == false && counter < THRESHOLD) {

      analyseProgram.invoke(this, currentBlock, implicitly[TypeRep[T]])
      postAnalyseProgram(currentBlock)
      isDone.set(this, true)
      currentBlock = transformProgram(currentBlock)
      counter += 1
    }
    if (counter >= THRESHOLD) {
      System.err.println(s"Recursive transformer ${getName} is not converted yet after [${scala.Console.RED}$counter${scala.Console.RESET}] rounds")
    }
    currentBlock
  }

  override def optimize[T: TypeRep](node: Block[T]): Block[T] = {
    val res = super_optimize(node)
    ruleApplied()
    res
  }

  val alreadyProfiled = collection.mutable.ArrayBuffer[Sym[_]]()

  rewrite += statement {
    case sym -> (node@StoreGetCopy(s: Sym[_], i, key)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Get,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(node.typeE)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreUpdateCopy(s: Sym[_], _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Update,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreSliceCopy(s: Sym[_], _, _, _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Slice,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreInsert(s: Sym[_], _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Insert,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreUnsafeInsert(s: Sym[_], _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},UnsafeInsert,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreDelete1(s: Sym[_], _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Delete,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
    case sym -> (node@StoreForeach(s: Sym[_], _)) if !alreadyProfiled.contains(sym) =>
      val name = unit(s"${s.name},Foreach,${sym.id}")
      toAtom(ProfileStart(name))(UnitType)
      val rep = toAtom(node)(UnitType)
      alreadyProfiled += rep.asInstanceOf[Sym[_]]
      toAtom(ProfileEnd(name))(UnitType)
      rep
  }
}
