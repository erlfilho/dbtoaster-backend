package sc.tpcc

import java.io.{File, PrintWriter}
import java.nio.file.Files._
import java.nio.file.Paths._
import java.nio.file.StandardCopyOption._

import ch.epfl.data.sc.pardis.ir.CTypes.PointerType
import ch.epfl.data.sc.pardis.ir._
import ch.epfl.data.sc.pardis.types.PardisType
import ch.epfl.data.sc.pardis.utils.document._
import ddbt.codegen.{Optimizer, TransactionProgram}
import ddbt.codegen.prettyprinter.{StoreCodeGenerator, StoreCppCodeGenerator, StoreScalaCodeGenerator}
import ddbt.lib.store.deep.StoreDSL
import ddbt.transformer.Index

import scala.util.parsing.json.JSON


/**
  * Created by sachin on 14/09/16.
  */
trait TpccPardisGen {
  def header: String


  val infoFile = new File(s"../runtime/stats/${if (Optimizer.infoFileName == "") "default" else Optimizer.infoFileName}.json")
  val infoFilePath = infoFile.getAbsolutePath
  var StoreArrayLengths = Map[String, String]()
  var idxSymNames: List[String] = null
  if (Optimizer.initialStoreSize) {
    if (infoFile.exists()) {
      System.err.println(s"Loading runtime info from ${infoFilePath}")
      val txt = new java.util.Scanner(infoFile).useDelimiter("\\Z").next()
      val allinfo: Map[String, _] = JSON.parseFull(txt).get.asInstanceOf[Map[String, _]]
      StoreArrayLengths = allinfo.map(t => t._1 -> t._2.asInstanceOf[Map[String, String]].getOrElse("OptArrayLength", "0"))
    } else {
      System.err.println("Runtime info file missing!!  Using default initial sizes")
    }
  }
  val codeGen: StoreCodeGenerator
  val genDir = "../runtime/tpcc/pardisgen"


  def generate[T](optTP: TransactionProgram[T])

  val file: PrintWriter
}

class TpccPardisScalaGen(IR: StoreDSL) extends TpccPardisGen {
  override def header: String =
    """
      |package tpcc.sc
      |import ddbt.lib.store._
      |import math.Ordering.{String => _}
      |import scala.collection.mutable.{ArrayBuffer,Set}
      |import java.util.Date
      | """.stripMargin


  override val file: PrintWriter = new PrintWriter(s"$genDir/TpccGenSC.scala")

  implicit def toPath(filename: String) = get(filename)

  if (Optimizer.analyzeEntry) {
    if (Optimizer.secondaryIndex) {
      copy(s"$genDir/SCTxSplEntry.txt", s"$genDir/SCTx.scala", REPLACE_EXISTING)
    } else {
      copy(s"$genDir/SCTxSplEntry-SE.txt", s"$genDir/SCTx.scala", REPLACE_EXISTING)
    }
  } else {
    copy(s"$genDir/SCTxGenEntry.txt", s"$genDir/SCTx.scala", REPLACE_EXISTING)
  }
  override val codeGen: StoreScalaCodeGenerator = new StoreScalaCodeGenerator(IR)

  override def generate[T](optTP: TransactionProgram[T]): Unit = {
    import IR._
    import codeGen.expLiftable, codeGen.tpeLiftable, codeGen.ListDocumentOps2
    var codestr = Document.nest(2, codeGen.blockToDocumentNoBraces(optTP.initBlock)).toString
    var i = codestr.lastIndexOf("1")
    val allstores = optTP.globalVars.map(_.name).mkDocument(", ")
    val executor =
      s"""class SCExecutor {
          |  implicit object StringNoCase extends Ordering[String]{
          |   override def compare(x: String, y: String): Int = x.compareToIgnoreCase(y)
          |  }
          |
          |  class DoubleExtra(var d: Double) {
          |    def toSB : StringBuilder = {
          |      var i = d.toInt
          |      val sb = new StringBuilder(15)
          |      sb.append(i); sb.append('.')
          |      val len = sb.length
          |      d = ((d - i) * 1000000); i=d.toInt ; sb.append(i)
          |      var x = sb.length - len
          |      while(x < 6) {
          |       sb.append('0');
          |        x+=1;
          |       }
          |      sb
          |    }
          |  }
          |
          """.stripMargin + codestr.substring(0, i) + "\n" +
        (if (Optimizer.initialStoreSize && !StoreArrayLengths.isEmpty) {
          val tbls = StoreArrayLengths.keys.toList.groupBy(_.split("Idx")(0)).map(t => t._1 -> t._2.map(StoreArrayLengths.getOrElse(_, "1")))
          tbls.map(t => doc"  ${t._1}.setInitialSizes(List(${t._2.mkString(",")}))").mkString("\n") + "\n"
        }
        else "") +
        s"""
           |  val newOrderTxInst = new NewOrderTx($allstores)
           |  val paymentTxInst = new PaymentTx($allstores)
           |  val orderStatusTxInst = new OrderStatusTx($allstores)
           |  val deliveryTxInst = new DeliveryTx($allstores)
           |  val stockLevelTxInst = new StockLevelTx($allstores)
           |
      """.stripMargin
    file.println(header)
    val entries = optTP.structs.map(codeGen.getStruct).mkDocument("\n")
    file.println(entries)
    file.println(executor)
    val entryIdxes = optTP.entryIdxDefs.map(codeGen.nodeToDocument).mkDocument("\n")
    implicit val tp = IntType.asInstanceOf[TypeRep[Any]]
    val tempVars = optTP.tempVars.map(t => codeGen.stmtToDocument(Statement(t._1, t._2))).mkDocument("\n")
    val r = Document.nest(2, entryIdxes :/: tempVars)
    file.println(r)
    //    val txns = new PrintWriter("TpccTxns.scala")
    optTP.codeBlocks.foreach { case (className, args: List[Sym[_]], body) => {
      val argsWithTypes = optTP.globalVars.map(m => doc"$m : Store[${storeType(m).tp}]").mkDocument(", ")
      val genCode = doc"  class $className($argsWithTypes) extends ((${args.map(_.tp).mkDocument(", ")}) => ${body.typeT} ) {" :/:
        doc"    def apply(${args.map(s => doc"$s : ${s.tp}").mkDocument(", ")}) = "
      val cgDoc = Document.nest(4, codeGen.blockToDocument(body))
      file.println(genCode + cgDoc.toString + "\n  }")
      //      txns.println(genCode + cgDoc.toString + "\n  }")
    }
    }
    //    txns.close()
    file.println("\n}")

    //    new TpccCompiler(Context).compile(codeBlock, "test/gen/tpcc")
    file.close()
  }
}

class TpccPardisCppGen(val IR: StoreDSL) extends TpccPardisGen {

  import IR._

  def genInitArrayLengths = {
    val tbls = idxSymNames.groupBy(_.split("Idx")(0)).map(t => t._1 -> t._2.map(StoreArrayLengths.getOrElse(_, "1")))
    tbls.map(t => doc"const size_t ${t._1}ArrayLengths[] = ${t._2.mkString("{", ",", "};")}").toList.mkDocument("\n")
  }

  override def header: String = codeGen.header +
    s"""
       |#define SC_GENERATED 1
       |#define USING_GENERIC_ENTRY ${!Optimizer.analyzeEntry}
       |
       |#include <algorithm>
       |#include <vector>
       |#include <unordered_set>
       |#include <mmap.hpp>
       |#include <valgrind/callgrind.h>
       |#include <iomanip>
       |#include <fstream>
       |#include <locale>
       |
       |#include <thread>
       |#include <sched.h>
       |#include <pthread.h>
       |
       |${if (Optimizer.profileBlocks || Optimizer.profileStoreOperations) "#define EXEC_PROFILE 1" else ""}
       |#include "ExecutionProfiler.h"
       |
       |using namespace std;
       |#include "hpds/pstring.hpp"
       |#include "hpds/pstringops.hpp"
       |#include "program_base.hpp"
       |
       |#ifdef NUMWARE
       |  const int numWare = NUMWARE;
       |#else
       |  const int numWare = 2;
       |#endif
       |#ifdef NUMPROG
       |  const size_t numPrograms = NUMPROG;
       |#else
       |  const size_t numPrograms = 100;
       |#endif
       |
       |struct Partition;
       |const int numThreads = 3;
       |std::thread workers[numThreads];
       |volatile bool isReady[numThreads];
       |volatile bool startExecution, hasFinished;
       |
       |#define CORE_FOR_W(x) (x%numThreads)
       |
       |#define setAffinity(thread_id)\\
       |    cpu_set_t cpuset;\\
       |    CPU_ZERO(&cpuset);\\
       |    CPU_SET(thread_id+1, &cpuset);\\
       |    auto s = sched_setaffinity(0, sizeof (cpu_set_t), &cpuset);\\
       |    if (s != 0)\\
       |        throw std::runtime_error("Cannot set affinity");
       |
       |#define setSched(type)\\
       |    sched_param param;\\
       |    param.__sched_priority =  sched_get_priority_max(type);\\
       |    s = sched_setscheduler(0, type, &param);\\
       |    if (s != 0)\\
       |        cerr << "Cannot set scheduler" << endl;
       |
       |uint failedOS = 0;
       |uint failedDel = 0;
       |uint failedNO = 0;
       |
       |const size_t warehouseTblSize = 8 * (numWare / 8 + 1);
       |const size_t itemTblSize = 100000 * 1.5;
       |const size_t districtTblSize = 8 * ((numWare * 10) / 8 + 1);
       |const size_t customerTblSize = districtTblSize * 3000;
       |const size_t orderTblSize = customerTblSize * 1.5 + 0.5 * numPrograms;
       |const size_t newOrderTblSize = orderTblSize * 0.3 + 0.5 * numPrograms;
       |const size_t orderLineTblSize = orderTblSize * 12;
       |const size_t stockTblSize = numWare * itemTblSize;
       |const size_t historyTblSize = orderTblSize;
       |
       |${genInitArrayLengths}
       |
       |const size_t warehouseTblPoolSizes[] = {8, 0};
       |const size_t itemTblPoolSizes[] = {65536*2, 0};
       |const size_t districtTblPoolSizes[] = {16, 0};
       |const size_t customerTblPoolSizes[] = {16384*2, 0, 16384};
       |const size_t orderTblPoolSizes[] = {262144*2, 65536, 0};
       |const size_t newOrderTblPoolSizes[] = {8192*2, 2048, 0};
       |const size_t orderLineTblPoolSizes[] = {4194304*2, 1048576, 2097152};
       |const size_t stockTblPoolSizes[] = {65536*2, 0};
       |const size_t historyTblPoolSizes[] = {262144*2, 65536};
     """.stripMargin

  override val codeGen = new StoreCppCodeGenerator(IR)


  override val file: PrintWriter = new PrintWriter(s"$genDir/TpccGenSC.cpp")
  val showOutput = false

  override def generate[T](optTP: TransactionProgram[T]): Unit = {

    codeGen.currentProgram = PardisProgram(optTP.structs, optTP.main, Nil)
    codeGen.refSymbols ++= optTP.tempVars.map(_._1)
    import codeGen.expLiftable, codeGen.tpeLiftable, codeGen.ListDocumentOps2
    val idxes = optTP.globalVars.map(s => s ->(collection.mutable.ArrayBuffer[(Sym[_], String, Boolean, Int)](), collection.mutable.ArrayBuffer[String]())).toMap
    optTP.initBlock.stmts.collect {
      case Statement(s, StoreNew3(_, Def(ArrayApplyObject(Def(LiftedSeq(ops)))))) => {
        val names = ops.collect {
          case Def(EntryIdxApplyObject(_, _, Constant(name))) => name
          case Def(n: EntryIdxGenericOpsObject) =>
            val cols = n.cols.asInstanceOf[Constant[List[Int]]].underlying.mkString("")
            if (cols.isEmpty)
              s"GenericOps"
            else {
              s"GenericOps_$cols"
            }
          case Def(n: EntryIdxGenericCmpObject[_]) =>
            val ord = Index.getColumnNumFromLambda(Def.unapply(n.f).get.asInstanceOf[PardisLambda[_, _]])
            val cols = n.cols.asInstanceOf[Constant[List[Int]]].underlying.mkString("")
            s"GenericCmp_${cols.mkString("")}_$ord"

          case Def(n: EntryIdxGenericFixedRangeOpsObject) =>
            val cols = n.colsRange.asInstanceOf[Constant[List[(Int, Int, Int)]]].underlying.map(t => s"${t._1}f${t._2}t${t._3}").mkString("_")
            s"GenericFixedRange_$cols"
        }
        idxes(s)._2.++=(names)
      }
      case Statement(sym, StoreIndex(s, _, Constant(typ), Constant(uniq), Constant(other))) => idxes(s.asInstanceOf[Sym[Store[_]]])._1.+=((sym, typ, uniq, other))
    }
    val idx2 = idxes.map(t => t._1 -> (t._2._1 zip t._2._2 map (x => (x._1._1, x._1._2, x._1._3, x._1._4, x._2))).toList) // Store -> List[Sym, Type, unique, otherInfo, IdxName ]
    def idxToDoc(idx: (Sym[_], String, Boolean, Int, String), entryTp: PardisType[_], allIdxs: List[(Sym[_], String, Boolean, Int, String)]): Document = {
      idx._2 match {
        case "IHash" => doc"HashIndex<$entryTp, char, ${idx._5}, ${unit(idx._3)}>"
        case "IDirect" => doc"ArrayIndex<$entryTp, char, ${idx._5}, ${unit(idx._4)}>"
        case "ISliceHeapMax" => val idx2 = allIdxs(idx._4); doc"SlicedHeapIndex<$entryTp, char, ${idx2._5}, ${idx._5}, ${unit(true)}>"
        case "ISliceHeapMin" => val idx2 = allIdxs(idx._4); doc"SlicedHeapIndex<$entryTp, char, ${idx2._5}, ${idx._5}, ${unit(false)}>"
        case "ISlicedHeapMed" => val idx2 = allIdxs(idx._4); doc"SlicedMedHeapIndex<$entryTp, char, ${idx2._5}, ${idx._5}>"
        case "IList" => doc"ListIndex<$entryTp, char, ${idx._5}, ${unit(idx._3)}>"
      }

    }

    val (stTypdef, stDecl, stInit) = optTP.globalVars.map(s => {
      def idxTypeName(i: Int) = s.name :: "Idx" :: i :: "Type"

      val entryTp = s.tp.asInstanceOf[StoreType[_]].typeE
      val idx3 = idx2(s).filter(_._2 != "INone")
      val idxTypes = idx3.map(idxToDoc(_, entryTp, idx2(s))).zipWithIndex
      val idxTypeDefs = idxTypes.map(t => doc"typedef ${t._1} ${idxTypeName(t._2)};").mkDocument("\n")

      val storeTypeDef = doc"typedef MultiHashMap<${entryTp}, char," :: idxTypes.map(t => idxTypeName(t._2)).mkDocument(", ") :: doc"> ${s.name}StoreType;"

      val storeDecl = s.name :: "StoreType  " :: s.name :: ";"
      val idxDecl = idx3.zipWithIndex.map(t => doc"${idxTypeName(t._2)}& ${t._1._1};").mkDocument("\n")

      val storeinit = s.name :: (if (Optimizer.initialStoreSize) doc"(${s.name}ArrayLengths, ${s.name}PoolSizes)" else doc"()") :: ", "
      val idxInit = idx3.zipWithIndex.map(t => doc"${t._1._1}(*(${idxTypeName(t._2)} *)${s.name}.index[${t._2}])").mkDocument(", ")
      (idxTypeDefs :\\: storeTypeDef :: "\n", storeDecl :\\: idxDecl :: "\n", storeinit :: idxInit)
    }).reduce((a, b) => (a._1 :\\: b._1, a._2 :\\: b._2, a._3 :: ", " :\\: b._3))

    val entryIdxes = optTP.entryIdxDefs.map(codeGen.nodeToDocument).mkDocument("\n")
    def structToDoc(s: PardisStructDef[_]) = s match {
      case PardisStructDef(tag, fields, methods) =>
        val fieldsDoc = fields.map(x => doc"${x.tpe} ${x.name};").mkDocument("  ") :: doc"  ${tag.typeName} *prv;  ${tag.typeName} *nxt; void* backPtrs[${fields.size}];"
        val constructor = doc"${tag.typeName}() :" :: fields.map(x => {
          if (x.tpe == StringType)
            doc"${x.name}()"
          else doc"${x.name}(${nullValue(x.tpe)})"
        }).mkDocument(", ") :: ", prv(nullptr), nxt(nullptr) {}"
        val constructorWithArgs = doc"${tag.typeName}(" :: fields.map(x => doc"const ${x.tpe}& ${x.name}").mkDocument(", ") :: ") : " :: fields.map(x => doc"${x.name}(${x.name})").mkDocument(", ") :: ", prv(nullptr), nxt(nullptr) {}"
        val copyFn = doc"FORCE_INLINE ${tag.typeName}* copy() const {  ${tag.typeName}* ptr = (${tag.typeName}*) malloc(sizeof(${tag.typeName})); new(ptr) ${tag.typeName}(" :: fields.map(x => {
          //          if (x.tpe == StringType)
          //            doc"*${x.name}.copy()"
          //          else
          doc"${x.name}"
        }).mkDocument(", ") :: ");  return ptr;}"
        "struct " :: tag.typeName :: " {" :/: Document.nest(2, fieldsDoc :/: constructor :/: constructorWithArgs :/: copyFn) :/: "};"
    }

    val structs = optTP.structs.map(structToDoc).mkDocument("\n")
    val structVars = optTP.tempVars.map(st => doc"${st._2.tp} ${st._1};").mkDocument("\n")
    val structEquals = optTP.structs.map(s => {
      val sname = s.tag.typeName
      def eqFn(x: StructElemInformation) = {
        val n = x.name
        x.tpe.asInstanceOf[TypeRep[_]] match {
          case StringType => doc"o1.$n == o2.$n"
          case DoubleType => doc"(fabs(o1.$n - o2.$n) < 0.01)"
          case _ => doc"o1.$n == o2.$n"
        }
      }
      val equals = s.fields.map(eqFn).reduce(_ :: " && " :/: _)
      doc"bool operator== (const $sname& o1, const $sname& o2) {" :\\: Document.nest(2, "return " :: equals :: ";") :\\: "}"
    }).mkDocument("\n")

    val traits = doc"/* TRAITS STARTING */" :/: codeGen.getTraitSignature :/: doc" /* TRAITS ENDING   */"
    def argsDoc(args: List[Sym[_]]) = args.map(t => doc"${t.tp} ${t}").mkDocument(", ") //SBJ: These args are both input/output. Should not be made const
    //    def blockTofunction(x :(String, List[ExpressionSymbol[_]], PardisBlock[T])) = {
    //      (Sym.freshNamed(x._1)(x._3.typeT, IR), x._2, x._3)
    //    }
    //    optTP.codeBlocks.foreach(x => codeGen.functionsList += (blockTofunction(x)))
    def stPrf(n: String) = if (Optimizer.profileBlocks) doc"\nauto start$n = Now;" else doc""
    def endPrf(id: String) = if (Optimizer.profileBlocks) doc"\nauto end$id = Now;" :/:
      doc"""if(durations.find("$id") == durations.end()) {""" :/:
      doc"""  durations["$id"] = DurationNS(end$id - start$id);""" :/:
      doc"""  counters["$id"] = 1;""" :/:
      doc"} else  {" :/:
      doc"""  durations["$id"] += DurationNS(end$id- start$id);""" :/:
      doc"""  counters["$id"]++;""" :/:
      doc"}"
    else doc""

    val blocks = optTP.codeBlocks.map(x => doc"FORCE_INLINE void ${x._1}(${argsDoc(x._2)}) {" ::
      stPrf(x._1) :: Document.nest(2, codeGen.blockToDocument(x._3)) :/: "  clearTempMem();" :: endPrf(x._1) :/:
      "}").mkDocument("\n")

    idxSymNames = idx2.values.flatMap(l => l.filter(x => x._2 != "INone").map(_._1.name)).toList
    val getSizes = idxSymNames.map(i => doc"GET_RUN_STAT($i, info);").mkDocument("info << \"{\\n\";\n", "\ninfo <<\",\\n\";\n", "\ninfo << \"\\n}\\n\";")
    val threadFn =
      s"""
         |void threadFunction(uint8_t thread_id) {
         |    setAffinity(thread_id);
         |    //    setSched(SCHED_FIFO);
         |
         |    Partition& pt = partitions[thread_id];
         |    pt.partitionID = thread_id;
         |    isReady[thread_id] = true;
         |    while (!startExecution);
         |
         |    for (size_t i = 0; i < numPrograms && !hasFinished; ++i) {
         |        Program *prg = tpcc.programs[i];
         |        switch (prg->id) {
         |            case NEWORDER:
         |            {
         |                NewOrder& p = *(NewOrder *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    bool local = true;
         |                    for (int ol = 0; ol < p.o_ol_cnt; ++ol)
         |                        if (CORE_FOR_W(p.supware[ol]) != thread_id) {
         |                            local = false;
         |                            break;
         |                        }
         |                    pt.xactCounts[0]++;
         |                    p.o_all_local = local;
         |                    if (local)
         |                        pt.NewOrderTx(false, p.datetime, -1, p.w_id, p.d_id, p.c_id, p.o_ol_cnt, p.o_all_local, p.itemid, p.supware, p.quantity, p.price, p.iname, p.stock, p.bg, p.amt);
         |                    else
         |                        pt.NewOrderTxLocal(false, p.datetime, -1, p.w_id, p.d_id, p.c_id, p.o_ol_cnt, p.o_all_local, p.itemid, p.supware, p.quantity, p.price, p.iname, p.stock, p.bg, p.amt);
         |                } else {
         |                    bool remote = false;
         |                    for (int ol = 0; ol < p.o_ol_cnt; ++ol)
         |                        if (CORE_FOR_W(p.supware[ol]) == thread_id) {
         |                            remote = true;
         |                            break;
         |                        }
         |                    if (remote) {
         |                        p.o_all_local = false;
         |                        pt.xactCounts[0]++;
         |                        pt.NewOrderTxRemote(false, p.datetime, -1, p.w_id, p.d_id, p.c_id, p.o_ol_cnt, p.o_all_local, p.itemid, p.supware, p.quantity, p.price, p.iname, p.stock, p.bg, p.amt);
         |                    }
         |                }
         |                break;
         |            }
         |            case PAYMENTBYID:
         |            {
         |                PaymentById& p = *(PaymentById *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[1]++;
         |                    if (CORE_FOR_W(p.c_w_id) == thread_id)
         |                        pt.PaymentTx( false, p.datetime, -1, p.w_id, p.d_id, 0, p.c_w_id, p.c_d_id, p.c_id, nullptr, p.h_amount);
         |                    else
         |                        pt.PaymentTxLocal(false, p.datetime, -1, p.w_id, p.d_id, 0, p.c_w_id, p.c_d_id, p.c_id, nullptr, p.h_amount);
         |                } else if (CORE_FOR_W(p.c_w_id) == thread_id) {
         |                    pt.xactCounts[1]++;
         |                    pt.PaymentTxRemote(false, p.datetime, -1, p.w_id, p.d_id, 0, p.c_w_id, p.c_d_id, p.c_id, nullptr, p.h_amount);
         |                }
         |                break;
         |            }
         |            case PAYMENTBYNAME:
         |            {
         |                PaymentByName& p = *(PaymentByName *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[1]++;
         |                    if (CORE_FOR_W(p.c_w_id) == thread_id)
         |                        pt.PaymentTx(false, p.datetime, -1, p.w_id, p.d_id, 1, p.c_w_id, p.c_d_id, -1, p.c_last_input, p.h_amount);
         |                    else
         |                        pt.PaymentTxLocal(false, p.datetime, -1, p.w_id, p.d_id, 1, p.c_w_id, p.c_d_id, -1, p.c_last_input, p.h_amount);
         |                } else if (CORE_FOR_W(p.c_w_id) == thread_id) {
         |                    pt.xactCounts[1]++;
         |                    pt.PaymentTxRemote(false, p.datetime, -1, p.w_id, p.d_id, 1, p.c_w_id, p.c_d_id, -1, p.c_last_input, p.h_amount);
         |                }
         |                break;
         |            }
         |            case ORDERSTATUSBYID:
         |            {
         |                OrderStatusById &p = *(OrderStatusById *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[2]++;
         |                    pt.OrderStatusTx(false, -1, -1, p.w_id, p.d_id, 0, p.c_id, nullptr);
         |                }
         |                break;
         |            }
         |            case ORDERSTATUSBYNAME:
         |            {
         |                OrderStatusByName &p = *(OrderStatusByName *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[2]++;
         |                    pt.OrderStatusTx(false, -1, -1, p.w_id, p.d_id, 1, -1, p.c_last);
         |                }
         |                break;
         |            }
         |            case DELIVERY:
         |            {
         |                Delivery &p = *(Delivery *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[3]++;
         |                    pt.DeliveryTx(false, p.datetime, p.w_id, p.o_carrier_id);
         |                }
         |                break;
         |            }
         |            case STOCKLEVEL:
         |            {
         |                StockLevel &p = *(StockLevel *) prg;
         |                if (CORE_FOR_W(p.w_id) == thread_id) {
         |                    pt.xactCounts[4]++;
         |                    pt.StockLevelTx(false, -1, -1, p.w_id, p.d_id, p.threshold);
         |                }
         |                break;
         |            }
         |            default: cerr << "UNKNOWN PROGRAM TYPE" << endl;
         |
         |        }
         |    }
         |    hasFinished = true;
         |}
         |
       """.stripMargin
    def mainPrg =
      s"""
         |#ifndef NORESIZE
         |cout << "Index Resizing warning disabled" << endl;
         |#endif
         |
         |tpcc.loadPrograms();
         |tpcc.loadWare();
         |tpcc.loadDist();
         |tpcc.loadCust();
         |tpcc.loadItem();
         |tpcc.loadNewOrd();
         |tpcc.loadOrders();
         |tpcc.loadOrdLine();
         |tpcc.loadHist();
         |tpcc.loadStocks();
         |
         |uint xactCounts[5] = {0, 0, 0, 0, 0};
         |Timepoint startTime, endTime;
         |
         |
         |//CALLGRIND_START_INSTRUMENTATION;
         |for (uint8_t i = 0; i < numThreads; ++i) {
         |    workers[i] = std::thread(threadFunction, i);
         |}
         |bool all_ready = true;
         |//check if all worker threads are ready. Execution can be started once all threads finish startup procedure
         |while (true) {
         |    for (uint8_t i = 0; i < numThreads; ++i) {
         |        if (isReady[i] == false) {
         |            all_ready = false;
         |            break;
         |        }
         |    }
         |    if (all_ready) {
         |        startTime = Now;
         |        startExecution = true;
         |        break;
         |    }
         |
         |    all_ready = true;
         |}
         |
         |for (uint8_t i = 0; i < numThreads; ++i) {
         |    workers[i].join();
         |}
         |endTime = Now;
         |uint totalPrgsExec = 0;
         |for (int i = 0; i < numThreads; ++i) {
         |    failedNO += partitions[i].failedNO;
         |    cout << "\\n Thread " << i << " : ";
         |    for (int x = 0; x < 5; ++x) {
         |        cout << partitions[i].xactCounts[x] << "  ";
         |        xactCounts[x] += partitions[i].xactCounts[x];
         |        totalPrgsExec += partitions[i].xactCounts[x];
         |    }
         |}
         |cout << endl;
         |//CALLGRIND_STOP_INSTRUMENTATION;
         |//CALLGRIND_DUMP_STATS;
         |
         |auto execTime = DurationMS(endTime - startTime);
         |cout << "Failed NO = " << failedNO << endl;
         |cout << "Failed Del = " << failedDel << endl;
         |cout << "Failed OS = " << failedOS << endl;
         |cout << "Total time = " << execTime << " ms" << endl;
         |uint failedCount[] = {failedNO, 0, failedOS, failedDel / 10, 0};
         |cout << "Total transactions = " << totalPrgsExec << "   NewOrder = " << xactCounts[0] << endl;
         |cout << "TpmC = " << fixed << (xactCounts[0] - failedNO)* 60000.0 / execTime << endl;


         |${
        if (Optimizer.profileBlocks || Optimizer.profileStoreOperations)
          s"""
             |//counters["FailedNO"] = failedNO; counters["FailedDel"] = failedDel/10; counters["FailedOS"] = failedOS;
             |//durations["FailedNO"] = 0; durations["FailedDel"] = 0; durations["FailedOS"] = 0;
             |ExecutionProfiler::printProfileToFile();
            """.stripMargin
        else doc""
      }
         |ofstream fout("tpcc_res_cpp.csv", ios::app);
         |if(argc == 1 || atoi(argv[1]) == 1) {
         |  fout << "\\nCPP-${Optimizer.optCombination}-" << numPrograms << ",";
         |  for(int i = 0; i < 5 ; ++i)
         |     fout << xactCounts[i] - failedCount[i] << ",";
         |  fout <<",";
         | }
         |fout << execTime << ",";
         |fout.close();
         |
         |/*
         |ofstream info("$infoFilePath");
         |${getSizes}
         |info.close();
         |*/

         |#ifdef VERIFY_TPCC
         |    warehouseTblIdx0Type warehouseTblIdx0;
         |    districtTblIdx0Type districtTblIdx0;
         |    customerTblIdx0Type customerTblIdx0;
         |    orderTblIdx0Type orderTblIdx0;
         |    newOrderTblIdx0Type newOrderTblIdx0;
         |    orderLineTblIdx0Type orderLineTblIdx0;
         |    itemTblIdx0Type itemTblIdx0;
         |    stockTblIdx0Type stockTblIdx0;
         |    historyTblIdx0Type historyTblIdx0;
         |
         |    warehouseTblIdx0.idxId = 0;
         |    districtTblIdx0.idxId = 0;
         |    customerTblIdx0.idxId = 0;
         |    orderTblIdx0.idxId = 0;
         |    newOrderTblIdx0.idxId = 0;
         |    orderLineTblIdx0.idxId = 0;
         |    itemTblIdx0.idxId = 0;
         |    stockTblIdx0.idxId = 0;
         |    historyTblIdx0.idxId = 0;
         |
         |    warehouseTblIdx0.resize_(warehouseTblSize);
         |    districtTblIdx0.resize_(districtTblSize);
         |    customerTblIdx0.resize_(customerTblSize);
         |    orderTblIdx0.resize_(orderTblSize);
         |    newOrderTblIdx0.resize_(newOrderTblSize);
         |    orderLineTblIdx0.resize_(orderLineTblSize);
         |    itemTblIdx0.resize_(itemTblSize);
         |    stockTblIdx0.resize_(stockTblSize);
         |    historyTblIdx0.resize_(historyTblSize);
         |    for (int i = 0; i < numThreads; ++i) {
         |        partitions[i].warehouseTblIdx0.foreach([&](WarehouseEntry * e) {
         |            if (CORE_FOR_W(e->_1) == i)
         |                warehouseTblIdx0.add(e->copy());
         |        });
         |        partitions[i].districtTblIdx0.foreach([&](DistrictEntry * e) {
         |            if (CORE_FOR_W(e->_2) == i)
         |                districtTblIdx0.add(e->copy());
         |        });
         |        partitions[i].customerTblIdx0.foreach([&](CustomerEntry * e) {
         |            if (CORE_FOR_W(e->_3) == i) {
         |                customerTblIdx0.add(e->copy());
         |            }
         |        });
         |        partitions[i].orderTblIdx0.foreach([&](OrderEntry * e) {
         |            if (CORE_FOR_W(e->_3) == i)
         |                orderTblIdx0.add(e->copy());
         |        });
         |        partitions[i].newOrderTblIdx0.foreach([&](NewOrderEntry * e) {
         |            if (CORE_FOR_W(e->_3) == i)
         |                newOrderTblIdx0.add(e->copy());
         |        });
         |        partitions[i].orderLineTblIdx0.foreach([&](OrderLineEntry * e) {
         |            if (CORE_FOR_W(e->_3) == i)
         |                orderLineTblIdx0.add(e->copy());
         |        });
         |        partitions[0].itemTblIdx0.foreach([&](ItemEntry * e) {
         |                itemTblIdx0.add(e->copy());
         |        });
         |        partitions[i].stockTblIdx0.foreach([&](StockEntry * e) {
         |            if (CORE_FOR_W(e->_2) == i)
         |                stockTblIdx0.add(e->copy());
         |        });
         |
         |        partitions[i].historyTblIdx0.foreach([&](HistoryEntry * e) {
         |            if (CORE_FOR_W(e->_5) == i) {
         |                historyTblIdx0.add(e->copy());
         |            }
         |        });
         |    }
         |
         |    if (warehouseTblIdx0 == tpcc.wareRes) {
         |        cout << "Warehouse results are correct" << endl;
         |    } else {
         |        cerr << "Warehouse results INCORRECT!" << endl;
         |    }
         |    if (districtTblIdx0 == tpcc.distRes) {
         |        cout << "District results are correct" << endl;
         |    } else {
         |        cerr << "District results INCORRECT!" << endl;
         |    }
         |    if (customerTblIdx0 == tpcc.custRes) {
         |        cout << "Customer results are correct" << endl;
         |    } else {
         |        cerr << "Customer results INCORRECT!" << endl;
         |    }
         |    if (orderTblIdx0 == tpcc.ordRes) {
         |        cout << "Order results are correct" << endl;
         |    } else {
         |        cerr << "Order results INCORRECT!" << endl;
         |    }
         |    if (orderLineTblIdx0 == tpcc.ordLRes) {
         |        cout << "OrderLine results are correct" << endl;
         |    } else {
         |        cerr << "OrderLine results INCORRECT!" << endl;
         |    }
         |    if (newOrderTblIdx0 == tpcc.newOrdRes) {
         |        cout << "NewOrder results are correct" << endl;
         |    } else {
         |        cerr << "NewOrder results INCORRECT!" << endl;
         |    }
         |    if (itemTblIdx0 == tpcc.itemRes) {
         |        cout << "Item results are correct" << endl;
         |    } else {
         |        cerr << "Item results INCORRECT!" << endl;
         |    }
         |    if (stockTblIdx0 == tpcc.stockRes) {
         |        cout << "Stock results are correct" << endl;
         |    } else {
         |        cerr << "Stock results INCORRECT!" << endl;
         |    }
         |    if (historyTblIdx0 == tpcc.histRes) {
         |        cout << "History results are correct" << endl;
         |    } else {
         |        cerr << "History results INCORRECT!" << endl;
         |    }
         |
         |#endif
         |
      """.stripMargin
    val execProfile = if (Optimizer.profileBlocks || Optimizer.profileStoreOperations)
      s""" std::unordered_map<std::string, Timepoint> startTimes;
          | std::unordered_map<std::string, size_t> durations;
          | std::unordered_map<std::string, size_t> counters;""".stripMargin
    else ""
    //    val txns = new PrintWriter("TpccTxns.hpp")
    //    txns.print(blocks)
    //    txns.close()
    file.println(header :/: execProfile :/: structs :\\: structEquals :\\: entryIdxes :\\: stTypdef :\\:
      doc"struct Partition { " :\\: Document.nest(2, doc"Partition():" :\\:
      stInit :: doc"  {\n  memset(xactCounts, 0, sizeof(uint) *5); }\n" :\\:
      stDecl :\\: structVars ::
      s"""
         |
         |uint partitionID;
         |uint failedNO;
         |uint xactCounts[5];
       """.stripMargin :\\: blocks) :\\:
      "};" :\\:
      "Partition partitions[numThreads];" :\\:
      "#include \"TPCC.h\"\n" :\\:
      "TPCCDataGen tpcc;" :\\:
      threadFn :\\:
      traits :/: Document.nest(2, mainPrg) :/: "}")
    file.close()
  }
}

