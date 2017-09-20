package ddbt.codegen

import ddbt.ast._

/**
 * CppGen is responsible to transform a typed AST into 
 * vanilla C++ code (String).
 *
 * @author Mohammad Dashti, Milos Nikolic
 */
class CppGen(override val cls: String = "Query", override val isReleaseMode: Boolean, override val printTiminingInfo: Boolean) extends ICppGen

trait ICppGen extends IScalaGen {
  import scala.collection.mutable.HashMap
  import ddbt.ast.M3._
  import ddbt.lib.Utils._
  import ddbt.lib.TypeHelper.Cpp._

  def isReleaseMode: Boolean = false

  def printTiminingInfo: Boolean = false

  val VALUE_NAME = "__av"

  val usingPardis = false
  val pardisExtendEntryParam = false
  val pardisProfilingOn = false

  val EXPERIMENTAL_RUNTIME_LIBRARY = false
  val EXPERIMENTAL_HASHMAP = true
  val EXPERIMENTAL_MAX_INDEX_VARS = Int.MaxValue

  //Sample entry definitions are accumulated in this variable
  var sampleEntDef = ""

  var unionDepth = 0

  protected def mapTypeToString(m: MapDef) = 
    if (m.keys.size == 0) typeToString(m.tp) else m.name + "_map"

  protected def queryTypeToString(q: Query) = 
    if (q.expr.ovars.size == 0) typeToString(q.expr.tp) else q.name + "_map"

  protected def queryRefTypeToString(q: Query) = 
    if (q.expr.ovars.size == 0) typeToString(q.expr.tp) else q.name + "_map&"

  protected def cmpToString(op: OpCmp) = op match {
    case OpEq => "=="
    case OpNe => "!="
    case OpGt => ">"
    case OpGe => ">="
  }

  private val ifReleaseMode = stringIf(isReleaseMode, "// ")
  private val ifPrintTimingInfo = stringIf(!printTiminingInfo, "// ")

  // private var mapDefs = Map[String,MapDef]() //mapName => MapDef
  private val mapDefsList = scala.collection.mutable.MutableList[(String,MapDef)]() //List(mapName => MapDef) to preserver the order
  private val tmpMapDefs = HashMap[String,(List[Type],Type)]() //tmp mapName => (List of key types and value type)
  private var deltaRelationNames = Set[String]()

  private var usedRelationDirectives = ""

  private val helperFuncUsage = HashMap[(String,String),Int]()

  def FIND_IN_MAP_FUNC(m:String) = { helperFuncUsage.update(("FIND_IN_MAP_FUNC" -> m),helperFuncUsage.getOrElse(("FIND_IN_MAP_FUNC" -> m),0)+1); m+".getValueOrDefault" }

  def SET_IN_MAP_FUNC(m:String) = { helperFuncUsage.update(("SET_IN_MAP_FUNC" -> m),helperFuncUsage.getOrElse(("SET_IN_MAP_FUNC" -> m),0)+1); m+".setOrDelOnZero" }

  def ADD_TO_MAP_FUNC(m:String) = { helperFuncUsage.update(("ADD_TO_MAP_FUNC" -> m),helperFuncUsage.getOrElse(("ADD_TO_MAP_FUNC" -> m),0)+1); m+".addOrDelOnZero" }
 
  def INSERT_TO_MAP_FUNC(m:String) = { helperFuncUsage.update(("INSERT_TO_MAP_FUNC" -> m),helperFuncUsage.getOrElse(("INSERT_TO_MAP_FUNC" -> m),0)+1); m+".insert_nocheck" }

  def ADD_TO_TEMP_MAP_FUNC(ksTp:List[Type],vsTp:Type,m:String,ks:List[String],vs:String) =
    if (EXPERIMENTAL_HASHMAP) {
      val sampleTempEnt = fresh("st")
      sampleEntDef += "  " + tupType(ksTp, vsTp) + " " + sampleTempEnt + ";\n"
      extractBooleanExp(vs) match {
        case Some((c,t)) =>
          s"(/*if */(${c}) ? ${m}.add(${sampleTempEnt}.modify(${ks.map(rn).mkString(", ")}), ${t}) : (void)0);\n"
        case _ =>
          s"${m}.add(${sampleTempEnt}.modify(${ks.map(rn).mkString(", ")}), ${vs});\n"
      }
    }
    else {
      val sampleTempEnt = fresh("st")
      sampleEntDef += "  " + tupType(ksTp, vsTp) + " " + sampleTempEnt + ";\n"
      extractBooleanExp(vs) match {
        case Some((c,t)) =>
          "(/*if */("+c+") ? add_to_temp_map"+/*"<"+tupType(ksTp, vsTp)+">"+*/"("+m+", "+sampleTempEnt+".modify"+tup(ks map rn, t)+") : (void)0);\n"
        case _ =>
          "add_to_temp_map"+/*"<"+tupType(ksTp, vsTp)+">"+*/"("+m+", "+sampleTempEnt+".modify"+tup(ks map rn, vs)+");\n"
      }
    }

  val tempTupleTypes = HashMap[String,(List[Type],Type)]()
  
  def tup(ks:List[String],vs:String) = "("+ks.mkString(",")+","+vs+")"
  
  def tupType(ksTp:List[Type], vsTp:Type):String = {
    val tupleTp="tuple"+(ksTp.size+1)+"_"+ksTp.map(typeToChar).mkString+"_"+typeToChar(vsTp)
    tempTupleTypes.update(tupleTp,(ksTp, vsTp))
    tupleTp
  }

  override def consts = cs.map{ case (Apply(f,tp,as),n) =>
    val vs=as.map(a=>cpsExpr(a))
    "/*const static*/ "+typeToString(tp)+" "+n+";\n"
  }.mkString+"\n" // constant member definition

  def constsInit = cs.map{ case (Apply(f,tp,as),n) => f match {
      case "STRING_TYPE" => n+" = "+f+"(\""+as(0).asInstanceOf[Const].v+"\");\n" // string initilization
      case _ => val vs=as.map{
          case Const(tp,v) if (tp == TypeString) => "STRING_TYPE(\""+v+"\")"
          case a => cpsExpr(a)
        }
        n+" = "+"U"+f+"("+vs.mkString(",")+");\n"
    }
  }.mkString+"\n" // constant member initilization

  // Create a variable declaration
  //XXXXX TODO
  override def genVar(n:String,vsTp:Type,ksTp:List[Type]) = if (ksTp==Nil) {
    typeToString(vsTp)+" "+n+" = "+zeroOfType(vsTp)+";\n"
  } else {
    tmpMapDefs += (n -> (ksTp,vsTp))
    n+".clear();\n"
  }

  def getIndexId(m:String,is:List[Int]):String = (if(is.isEmpty) (0 until mapDefs(m).keys.size).toList else is).mkString //slice(m,is)

  override def cmpFunc(tp: Type, op:OpCmp, arg1: String, arg2: String, withIfThenElse: Boolean) = tp match {
    // case TypeDouble => op match {
    //   case OpEq => "abs("+arg1+"-"+arg2+") < KDouble::diff_p"
    //   case OpNe => "abs("+arg1+"-"+arg2+") >= KDouble::diff_p"
    //   case _ => arg1+" "+op+" "+arg2
    // }
    case _ =>
      if(withIfThenElse)
        "(/*if */("+arg1+" "+cmpToString(op)+" "+arg2+")"+" ? 1L : 0L)"
      else
        arg1+" "+cmpToString(op)+" "+arg2
  }

  // extract cond and then branch of "if (c) t else 0"
  // no better way for finding boolean type
  // TODO: add Boolean type
  override def extractBooleanExp(s: String): Option[(String, String)] = 
    if (!s.startsWith("(/*if */(")) None 
    else {
      var d = 1
      val pInit = "(/*if */(".length
      var p = pInit
      while (d > 0) {
        if (s(p) == '(') d += 1 
        else if (s(p)==')') d -= 1
        p += 1
      }
      Some(s.substring(pInit, p - 1), s.substring(p + " ? ".length, s.lastIndexOf(":") - 1))
    }

  // Generate code bottom-up using delimited CPS and a list of bound variables
  //   ex:expression to convert
  //   co:delimited continuation (code with 'holes' to be filled by expression) similar to Rep[Expr]=>Rep[Unit]
  //   am:shared aggregation map for Add and AggSum, avoiding useless intermediate map where possible
  override def cpsExpr(ex: Expr, co: String => String = (v: String) => v, am: Option[List[(String, Type)]] = None): String = ex match {
    case Ref(n) => co(rn(n))

    case Const(tp,v) => tp match {
      case TypeLong => co(v + "L")
      case TypeString => cpsExpr(Apply("STRING_TYPE", TypeString, List(ex)), co, am)
      case _ => co(v)
    }

    case Exists(e) => cpsExpr(e, (v: String) => 
      co(cmpFunc(TypeLong, OpNe, v, zeroOfType(ex.tp))))

    case Cmp(l,r,op) =>
      co(cpsExpr(l, (ll: String) => cpsExpr(r, (rr: String) => cmpFunc(l.tp, op, ll, rr))))

    case CmpOrList(l,r) =>
      co(cpsExpr(l, (ll: String) =>
        "(/*if */((" +
        r.map(x => cpsExpr(x, (rr: String) => "(" + ll + " == " + rr + ")"))
         .mkString(" || ") +
        ")) ? 1L : 0L)"
      ))

    case Apply(fn,tp,as) => applyFunc(co,fn,tp,as)

    case MapRef(mapName,tp,ks,isTemp) =>
      val (ko, ki) = ks.zipWithIndex.partition { case((n,t),i) => ctx.contains(n) }
      val mapType = mapName + "_map"
      val mapEntry = mapName + "_entry"
      if (ks.size == 0) { // variable
        if (ctx contains mapName) co(rn(mapName)) else co(mapName)
      } 
      else if (ki.size == 0) {
        val sampleEnt = fresh("se")
        sampleEntDef += stringIf(ks.size > 0, s"  ${mapEntry} ${sampleEnt};\n");
        val argList = (ks map (x => rn(x._1))).mkString(",")
        co(s"${FIND_IN_MAP_FUNC(mapName)}(${sampleEnt}.modify(${argList}))") // all keys are bound
      } 
      else {
        val lup0 = fresh("lkup") //lookup
        val lupItr0 = lup0 + "_it"
        val lupItrNext0 = "next_" + lupItr0
        val lupEnd0 = lup0 + "_end"
        val is = ko.map(_._2)
        val iKeys = ko.map(x => rn(x._1._1))
        val iKeysTp = ko.map(x => x._1._2)
        val (k0, v0, e0) = (fresh("k"), fresh("v"), fresh("e"))

        ctx.add(ks.filter(x => !ctx.contains(x._1)).map(x => (x._1, (x._2, x._1))).toMap)

        if (!isTemp) { // slice or foreach
          val mapDef = mapDefs(mapName)
          val n0 = fresh("n")
          val idx0= fresh("i")
          val mapType = mapName + "_map"
          val idxName =
            if (EXPERIMENTAL_HASHMAP) "PrimaryHashIndex_" + mapType + "_" + getIndexId(mapName, is)
            else "HashIndex_" + mapType + "_" + getIndexId(mapName, is)
          val idxFn = mapType + "key" + getIndexId(mapName, is) + "_idxfn"
          
          val body = 
            ki.map { case ((k,ktp), i) => 
              typeToString(ktp) + " " + rn(k) + " = " + e0 + "->" + mapDef.keys(i)._1 + ";\n"
            }.mkString + 
            typeToString(tp) + " " + v0 + " = " + e0 + "->" + VALUE_NAME + ";\n" +
            co(v0)

          if (ko.size > 0) { //slice
            if (EXPERIMENTAL_RUNTIME_LIBRARY && deltaRelationNames.contains(mapName)) {
              sys.error("Delta relation requires secondary indices. Turn off experimental runtime library.")
            }

            val idxIndex = slice(mapName, is) + 1 //+1 because the index 0 is the unique index
            val sampleEnt = fresh("se")
            sampleEntDef += s"  ${mapEntry} ${sampleEnt};\n"

            val h0= fresh("h")
            if (EXPERIMENTAL_HASHMAP) {
              s"""|{ //slice
                  |  const SecondaryIdxNode<${mapEntry}>* ${n0}_head = static_cast<const SecondaryIdxNode<${mapEntry}>*>(${mapName}.slice(${sampleEnt}.modify${getIndexId(mapName,is)}(${iKeys.mkString(", ")}), ${idxIndex - 1}));
                  |  const SecondaryIdxNode<${mapEntry}>* ${n0} = ${n0}_head;
                  |  ${mapEntry}* ${e0};
                  |  while (${n0}) {
                  |    ${e0} = ${n0}->obj;
                  |${ind(body, 2)}
                  |    ${n0} = (${n0} != ${n0}_head ? ${n0}->nxt : ${n0}->child);
                  |  }
                  |}
                  |""".stripMargin
            }
            else {
              s"""|{ //slice
                  |  const HASH_RES_t ${h0} = ${idxFn}::hash(${sampleEnt}.modify${getIndexId(mapName,is)}(${iKeys.mkString(", ")}));
                  |  const ${idxName}* ${idx0} = static_cast<${idxName}*>(${mapName}.index[${idxIndex}]);
                  |  ${idxName}::IdxNode* ${n0} = &(${idx0}->buckets_[${h0} & ${idx0}->mask_]);
                  |  ${mapEntry}* ${e0};
                  |  do if ((${e0} = ${n0}->obj) && ${h0} == ${n0}->hash && ${idxFn}::equals(${sampleEnt}, *${e0})) {
                  |${ind(body, 2)}
                  |  } while ((${n0} = ${n0}->nxt));
                  |}
                  |""".stripMargin
            }
          } else { //foreach
            if (EXPERIMENTAL_RUNTIME_LIBRARY && deltaRelationNames.contains(mapName)) {
              s"""|{ //foreach
                  |  for (std::vector<${mapEntry}>::iterator ${e0} = begin; ${e0} != end; ${e0}++) {
                  |${ind(body, 2)}
                  |  }
                  |}
                  |""".stripMargin                
            }
            else {
              s"""|{ //foreach
                  |  ${mapEntry}* ${e0} = ${mapName}.head;
                  |  while (${e0}) {
                  |${ind(body, 2)}
                  |    ${e0} = ${e0}->nxt;
                  |  }
                  |}
                  |""".stripMargin
            }
          }
        } 
        else { //only foreach for Temp map
          val body = 
            ki.map { case ((k,ktp), i) => 
              s"    ${typeToString(ktp)} ${rn(k)} = ${e0}->_${(i + 1)};"
            }.mkString("\n") +
            s"""|
                |    ${typeToString(tp)} ${v0} = ${e0}->${VALUE_NAME};
                |${ind(co(v0),2)}
                |    ${e0} = ${e0}->nxt;
                |""".stripMargin

          s"""|{ //temp foreach
              |  ${tupType(ks.map(_._2), tp)}* ${e0} = ${mapName}.head;
              |  while(${e0}) {
              |${body}  
              |  }
              |}
              |""".stripMargin
        }
      }

    // "1L" is the neutral element for multiplication, and chaining is done with multiplication
    case Lift(n,e) =>
    // Mul(Lift(x,3),Mul(Lift(x,4),x)) ==> (x=3;x) == (x=4;x)
      if (ctx.contains(n)) cpsExpr(e, (v: String) => co(cmpFunc(TypeLong, OpEq, rn(n), v)), am)
      else e match {
        case Ref(n2) => ctx.add(n,(e.tp,rn(n2))); co("1L") // de-aliasing
        //This renaming is required. As an example:
        //
        //C[x] = Add(A[x,y], B[x,y])
        //D[x] = E[x]
        //
        // will fail without a renaming.
        case _ =>
          ctx.add(n,(e.tp,fresh("l")))
          cpsExpr(e,(v:String)=> typeToString(e.tp)+" "+rn(n)+" = "+v+";\n"+co("1L"),am)
      }
    // Mul(el,er)
    // ==
    //   Mul( (el,ctx0) -> (vl,ctx1) , (er,ctx1) -> (vr,ctx2) )
    //    ==>
    //   (v=vl*vr , ctx2)
    case Mul(el, er) => 
      def vx(vl: String, vr: String) = if (vl == "1L") vr else if (vr == "1L") vl else "(" + vl + " * " + vr + ")"
      cpsExpr(el, (vl: String) => {
        var ifcond = ""
        val body = cpsExpr(er, (vr: String) => {
          (extractBooleanExp(vl), extractBooleanExp(vr)) match {
              case (Some((cl,tl)), Some((cr,tr))) =>
                if (unionDepth == 0) { ifcond = cl;  co("(/*if */(" + cr + ") ? " + vx(vl,tr) + " : " + zeroOfType(ex.tp) + ")") }
                else co("(/*if */(" + cl + " && " + cr + ") ? " + vx(tl,tr) + " : " + zeroOfType(ex.tp) + ")")

              case (Some((cl,tl)), _) =>
                if (unionDepth == 0) { ifcond = cl; co(vx(tl,vr)) }
                else co("(/*if */(" + cl + ") ? " + vx(tl,vr) + " : " + zeroOfType(ex.tp) + ")")

              case (_, Some((cr,tr))) =>
                co("(/*if */(" + cr + ") ? " + vx(vl,tr) + " : " + zeroOfType(ex.tp) + ")")

              case _ => co(vx(vl,vr))
            }
          }, am)
          if (ifcond == "") body else "if (" + ifcond + ") {\n" + ind(body) + "\n}\n"
        }, am)

    // Add(el,er)
    // ==
    //   Add( (el,ctx0) -> (vl,ctx1) , (er,ctx0) -> (vr,ctx2) )
    //         <-------- L -------->    <-------- R -------->
    //    (add - if there's no free variable) ==>
    //   (v=vl+vr , ctx0)
    //    (union - if there are some free variables) ==>
    //   T = Map[....]
    //   foreach vl in L, T += vl
    //   foreach vr in R, T += vr
    //   foreach t in T, co(t) 

    case a @ Add(el,er) =>      
      val agg = a.schema._2.filter { case (n,t) => !ctx.contains(n) }
      val s =
        if (agg == Nil) {
          val cur = ctx.save
          unionDepth += 1
          cpsExpr(el, (vl: String) => {
            ctx.load(cur)
            cpsExpr(er, (vr: String) => {
              ctx.load(cur)
              unionDepth -= 1
              co(s"(${vl} + ${vr})")
            }, am)
          }, am)
        }
        else am match {
          case Some(t) if t.toSet.subsetOf(agg.toSet) =>
            val cur = ctx.save
            unionDepth += 1
            val s1 = cpsExpr(el, co, am)
            ctx.load(cur)
            val s2 = cpsExpr(er, co, am)
            ctx.load(cur)
            unionDepth -= 1
            (s1 + s2)
          case _ =>
            val (acc,k0,v0) = (fresh("_c"), fresh("k"), fresh("v"))
            val ks = agg.map(_._1)
            val ksTp = agg.map(_._2)
            val tmp = Some(agg)
            val cur = ctx.save
            unionDepth += 1
            val s1 = cpsExpr(el, (v: String) => ADD_TO_TEMP_MAP_FUNC(ksTp, a.tp, acc, ks,v), tmp)
            ctx.load(cur)
            val s2 = cpsExpr(er, (v: String) => ADD_TO_TEMP_MAP_FUNC(ksTp, a.tp, acc, ks,v), tmp)
            ctx.load(cur)
            unionDepth -= 1
            ( genVar(acc, a.tp, agg.map(_._2)) +
              s1 +
              s2 +
              cpsExpr(MapRef(acc, a.tp, agg, true), co) )
        }
      s

    case a @ AggSum(ks,e) =>
      val aks = ks.filter { case(n,t) => !ctx.contains(n) } // aggregation keys as (name,type)
      if (aks.size == 0) {
        val cur = ctx.save;
        val a0 = fresh("agg")
        genVar(a0, a.tp) +
        cpsExpr(e, (v: String) =>
          extractBooleanExp(v) match {
            case Some((c,t)) =>
              "(/*if */(" + c + ") ? " + a0 + " += " + t + " : " + zeroOfType(a.tp)+");\n"
            case _ =>
              a0 + " += " + v + ";\n"
          }) +
        {
          ctx.load(cur)
          co(a0)
        }
      } 
      else am match {
        case Some(t) if t.toSet.subsetOf(aks.toSet) => cpsExpr(e, co, am)
        case _ =>
          val a0 = fresh("agg")
          val tmp = Some(aks) // declare this as summing target
          val cur = ctx.save
          val s1 = genVar(a0, e.tp, aks.map(_._2)) + "\n" +
            cpsExpr(e, (v: String) => ADD_TO_TEMP_MAP_FUNC(aks.map(_._2), e.tp, a0, aks.map(_._1), v), tmp)
          ctx.load(cur)
          s1 + cpsExpr(MapRef(a0, e.tp, aks, true), co)
      }

    case Repartition(ks, e) => cpsExpr(e, (v: String) => co(v))
    case Gather(e) => cpsExpr(e, (v: String) => co(v))
  
    case _ => sys.error("Don't know how to generate " + ex)
  }

  override def genStmt(s: TriggerStmt):String = s match {
    case TriggerStmt(m,e,op,oi) =>
      val (fop, sop) = op match { 
        case OpAdd => (ADD_TO_MAP_FUNC(m.name), "+=") 
        case OpSet => (ADD_TO_MAP_FUNC(m.name), "=") 
      }
      val mapName = m.name
      val mapType = mapName + "_map"
      val mapEntry = mapName + "_entry"
      val sampleEnt = fresh("se")
      sampleEntDef += stringIf(m.keys.size > 0,  s"  ${mapEntry} ${sampleEnt};\n")
      val clear = op match { 
        case OpAdd => "" 
        case OpSet => stringIf(m.keys.size > 0, s"${m.name}.clear();\n");
      }
      val init = oi match {
        case Some(ie) =>
          ctx.load()
          cpsExpr(ie, (i: String) =>
            if (m.keys.size == 0) s"if (${m.name} == 0) ${m.name} = ${i};\n"
            else "if (" + FIND_IN_MAP_FUNC(m.name) + "(" +
              sampleEnt + ".modify(" + (m.keys map (x => rn(x._1))).mkString(",") + ")) == 0) " + 
              SET_IN_MAP_FUNC(m.name) + "(" + sampleEnt + ", " + i + ");\n"
          )
        case None => ""
      }
      ctx.load(); 
      
      clear + 
      init + 
      cpsExpr(e, (v: String) => 
        (if (m.keys.size == 0) {
          extractBooleanExp(v) match {
            case Some((c,t)) =>
              s"(/*if */(${c}) ? ${m.name} ${sop} ${t} : 0L);\n"
            case _ =>
              s"${m.name} ${sop} ${v};\n"
          }
        }
        else {
          val argList = (m.keys map (x => rn(x._1))).mkString(", ")
          extractBooleanExp(v) match {
            case Some((c,t)) =>            
              s"(/*if */(${c}) ? ${fop}(${sampleEnt}.modify(${argList}), ${t}) : (void)0);\n"
            case _ =>
              s"${fop}(${sampleEnt}.modify(${argList}), ${v});\n"
          }
        }), /*if (op==OpAdd)*/ Some(m.keys) /*else None*/)
  }

  override def genTrigger(t: Trigger, s0: System): String = {
    val fields = t.event match {
      case EventBatchUpdate(_) | EventInsert(_) | EventDelete(_) => t.event.schema.fields
      case EventReady => Nil
    }

    ctx = Ctx(fields.map(x => (x._1, (x._2, x._1))).toMap)
    val sTriggerBody = t.stmts.map(genStmt).mkString
    ctx = null

    t.event match {
      case EventBatchUpdate(s) =>
        if (EXPERIMENTAL_RUNTIME_LIBRARY) {
          s"""|void on_batch_update_${s.name}(const std::vector<${s.name}_entry>::iterator &begin, const std::vector<${s.name}_entry>::iterator &end) {
              |  tN += std::distance(begin, end);
              |${ind(sTriggerBody)}
              |}
              |""".stripMargin
        }
        else {
          val schema = t.event.schema
          s"""|void on_batch_update_${schema.name}(${delta(schema.name)}_map &${delta(schema.name)}) {
              |  tN += ${delta(schema.name)}.count() - 1; ++tN;
              |${ind(sTriggerBody)}
              |}
              |""".stripMargin
        }
      case EventInsert(s) =>
        val params = fields.map(f => s"const ${refTypeToString(f._2)} ${f._1}").mkString(", ")
        val schema = s0.sources.filter(_.schema.name == s.name).head.schema
        val args = schema.fields.map(f => s"e.${f._1}").mkString(", ")
        s"""|void on_insert_${s.name}(${params}) {
            |  ++tN;
            |${ind(sTriggerBody)}
            |}
            |""".stripMargin +
        stringIf(EXPERIMENTAL_RUNTIME_LIBRARY,
            s"""|void on_insert_${s.name}(${s.name}_entry &e) {
                |  on_insert_${s.name}(${args});
                |}
                |""".stripMargin)

      case EventDelete(s) =>
        val params = fields.map(a => s"const ${refTypeToString(a._2)} ${a._1}").mkString(", ")
        val schema = s0.sources.filter(_.schema.name == s.name).head.schema
        val args = schema.fields.map(f => s"e.${f._1}").mkString(", ")
        s"""|void on_delete_${s.name}(${params}) {
            |  ++tN;
            |${ind(sTriggerBody)}
            |}
            |""".stripMargin +
        stringIf(EXPERIMENTAL_RUNTIME_LIBRARY,
            s"""|void on_delete_${s.name}(${s.name}_entry &e) {
                |  on_delete_${s.name}(${args});
                |}
                |""".stripMargin)

      case EventReady =>
        s"""|void on_system_ready_event() {
            |${ind(sTriggerBody)}
            |}
            |""".stripMargin
    }
  }

  override def slice(m: String, i: List[Int]):Int = { // add slicing over particular index capability
    val s = sx.getOrElse(m, List[List[Int]]())
    val n = s.indexOf(i)
    if (n != -1) n else { sx.put(m,s ::: List(i)); s.size }
  }

  override def genMap(m: MapDef):String = 
    mapTypeToString(m) + " " + m.name + ";\n"
 
  override def genInitializationFor(map:String, keyNames:List[(String,Type)], keyNamesConcat: String) = 
    map + ".add(" + keyNamesConcat + ", 1L)"

  override def toMapFunction(q: Query) = q.name + ".toMap"
  override def clearOut = {}
  override def onEndStream = ""

  def genIntermediateDataStructureRefs(maps:List[MapDef], queries:List[Query]) = 
    maps.filter { m => queries.filter(_.name == m.name).size == 0}.map(genMap).mkString

  def genTempMapDefs = 
    if (EXPERIMENTAL_HASHMAP) {
      tmpMapDefs.map{ case (n, (ksTp, vsTp)) =>
        "MultiHashMap<" + tupType(ksTp, vsTp) + ", " + typeToString(vsTp) + ", PrimaryHashIndex<" + tupType(ksTp, vsTp) + "> > " + n + ";\n"
      }.mkString
    }
    else {
      tmpMapDefs.map{ case (n, (ksTp, vsTp)) =>
        "MultiHashMap<" + tupType(ksTp, vsTp) + ", " + typeToString(vsTp) + ", HashIndex<" + tupType(ksTp, vsTp) + ", " + typeToString(vsTp) + "> > " + n + ";\n"
      }.mkString
    }

  /**
    * By default, each user-provided (top-level) query is materialized as a single map.
    * If this flag is turned on, the compiler will materialize top-level queries as multiple maps (if it is more efficient to do so),
    * and only combine them on request. For more complex queries (in particular nested aggregate, and AVG aggregate queries),
    * this results in faster processing rates, and if fresh results are required less than once per update, a lower overall computational cost as well.
    * However, because the final evaluation of the top-level query is not performed until a result is requested, access latencies are higher.
    * This optimization is not activated by default at any optimization level.
    */
  def isExpressiveTLQSEnabled(queries:List[Query]) =
    queries.exists { query => query.expr match {
        case MapRef(n,_,_,_) => (n != query.name)
        case _ => true
      }
    }

  private def getInitializationForIntermediateValues(maps:List[MapDef],queries:List[Query]) = 
    maps.filter { m => (queries.filter(_.name == m.name).size == 0) && (m.keys.size == 0)}
        .map { m => ", " + m.name + "(" + zeroOfType(m.tp) + ")"}
        .mkString

  private def getInitializationForTempMaps = tmpMapDefs.map{ case (n, (ksTp, vsTp)) => ", "+n+"(16U)" }.mkString

  private def getInitializationForPublicValues(maps:List[MapDef],queries:List[Query]) = 
    maps.filter { m => (queries.filter(_.name == m.name).size != 0) && (m.keys.size == 0)}
        .map { m => ", " + m.name + "(" + zeroOfType(m.tp) + ")"}
        .mkString

  private def getInitializationForTLQ_T(maps:List[MapDef],queries:List[Query]) =
    if (usingPardis) "" else {
      getInitializationForPublicValues(maps,queries) +
      stringIf(isExpressiveTLQSEnabled(queries), getInitializationForIntermediateValues(maps,queries) + getInitializationForTempMaps)
    }

  private def getInitializationForDATA_T(maps:List[MapDef],queries:List[Query]) =
    if (usingPardis) "" else {
      stringIf(!isExpressiveTLQSEnabled(queries), getInitializationForIntermediateValues(maps,queries) + getInitializationForTempMaps)
    }

  override def apply(s0:System):String = {
    implicit val s0_ = s0

    def register_maps = s0.maps.map { m =>
        "pb.add_map<" + mapTypeToString(m) + ">( \"" + m.name + "\", " + m.name + " );\n"
      }.mkString

    def register_relations = s0.sources.map { s => 
        "pb.add_relation(\"" + s.schema.name + "\"" + (if (s.isStream) "" else ", true") + ");\n"
      }.mkString

    def register_table_triggers = s0.sources.filter(!_.isStream).map { s => 
        stringIf(
          s0.triggers.exists { _.event match {
              case EventBatchUpdate(_) => true
              case _ => false
          }}, "pb.add_trigger(\"" + s.schema.name + "\", batch_update, std::bind(&data_t::unwrap_batch_update_" + s.schema.name + ", this, std::placeholders::_1));\n" 
        ) + 
        "pb.add_trigger(\"" + s.schema.name + "\", insert_tuple, std::bind(&data_t::unwrap_insert_" + s.schema.name + ", this, std::placeholders::_1));\n"      
      }.mkString

    def register_stream_triggers = s0.triggers.filter(_.event != EventReady).map { _.event match {
        case EventBatchUpdate(Schema(n,_)) =>
          "pb.add_trigger(\"" + n + "\", batch_update, std::bind(&data_t::unwrap_batch_update_" + n + ", this, std::placeholders::_1));\n" +
          "pb.add_trigger(\"" + n + "\", insert_tuple, std::bind(&data_t::unwrap_insert_" + n + ", this, std::placeholders::_1));\n" +
          "pb.add_trigger(\"" + n + "\", delete_tuple, std::bind(&data_t::unwrap_delete_" + n + ", this, std::placeholders::_1));\n"
        case EventInsert(Schema(n,_)) => "pb.add_trigger(\"" + n + "\", insert_tuple, std::bind(&data_t::unwrap_insert_" + n + ", this, std::placeholders::_1));\n"
        case EventDelete(Schema(n,_)) => "pb.add_trigger(\"" + n + "\", delete_tuple, std::bind(&data_t::unwrap_delete_" + n + ", this, std::placeholders::_1));\n"
        case _ => ""
      }
    }.mkString

    def init_stats = ""

    def genTableTriggers = 
      if (EXPERIMENTAL_RUNTIME_LIBRARY) {
        s0.sources.filter(!_.isStream).map { s =>
          val name = s.schema.name
          val fields = s.schema.fields
          val params = s.schema.fields.map { case (fld,tp) => "const " + typeToString(tp) + " " + fld  }.mkString(", ")
          val args = s.schema.fields.map(_._1).mkString(", ")

          s"""|void on_insert_${name}(${params}) {
              |  ${name}_entry e(${args}, 1L);
              |  ${ADD_TO_MAP_FUNC(name)}(e, 1L);
              |}
              |""".stripMargin +
          s"""|void on_insert_${name}(${name}_entry &e) {
              |  e.${VALUE_NAME} = 1L;
              |  ${ADD_TO_MAP_FUNC(name)}(e, 1L);
              |}
              |""".stripMargin
        }.mkString
      }
      else {
        val entryParam = if(usingPardis && pardisExtendEntryParam) "false_type(), " else ""
        s0.sources.filter(!_.isStream).map { s =>
            val name = s.schema.name
            val fields = s.schema.fields
            
            "void on_insert_" + name + "(" + fields.map { 
                case (fld,tp) => "const " + typeToString(tp) + " " + fld 
              }.mkString(", ") + ") {\n"+
            "  " + name + "_entry e(" + entryParam + fields.map { case (fld,_) => fld }.mkString(", ") + ", 1L);\n" +
            (if (usingPardis) "  " + INSERT_TO_MAP_FUNC(name) + "(e);\n"
             else "  " + ADD_TO_MAP_FUNC(name) + "(e, 1L);\n") +
            "}\n\n" +
            generateUnwrapFunction(EventInsert(s.schema)) +
            stringIf(
              s0.triggers.exists { _.event match {
                  case EventBatchUpdate(_) => true
                  case _ => false
              }},
              "void unwrap_batch_update_" + name + "(const event_args_t& eaList) {\n"+
              "  size_t sz = eaList.size();\n"+
              "  for(size_t i=0; i < sz; i++){\n"+
              "    event_args_t* ea = reinterpret_cast<event_args_t*>(eaList[i].get());\n"+
              "    " + name + "_entry e(" + fields.zipWithIndex.map { case ((_, tp), i) => "*(reinterpret_cast<" + typeToString(tp) + "*>((*ea)[" + i + "].get())), "}.mkString + "1L);\n" +
              (if (usingPardis) "    " + INSERT_TO_MAP_FUNC(name) + "(e);\n"
               else "    " + ADD_TO_MAP_FUNC(name) + "(e, 1L);\n") +
              "  }\n" +
              "}\n\n"
            )
          }.mkString        
      }

    def genStreamTriggers = s0.triggers.map(t =>
        genTrigger(t, s0) + "\n" + 
        stringIf(t.event != EventReady, generateUnwrapFunction(t.event))
      ).mkString

    def genMapStructDef(m: MapDef) = {
      val mapName = m.name
      val mapType = mapName + "_map"
      val mapEntry = mapName + "_entry"
      val mapValueType = typeToString(m.tp)
      val fields = m.keys ++ List(VALUE_NAME -> m.tp)
      val fieldsWithIdx = fields.zipWithIndex
      val indices = sx.getOrElse(m.name, List[List[Int]]())
      //TODO XXX is it always required to create a unique index?
      val allIndices = ((0 until m.keys.size).toList -> true /*unique*/) :: indices.map(is => (is -> false /*non_unique*/))
      val multiKeyIndices = allIndices.filter{case (is,_) => is.size > 1}

      def genEntryStruct = {
        val sFields = fields.map { case (fld,tp) => typeToString(tp) + " " + fld + "; "}.mkString + mapEntry + "* nxt; " + mapEntry + "* prv;"
        val sConstructorParams = fieldsWithIdx.map { case ((_,tp), i) => "const " + refTypeToString(tp) + " c" + i }.mkString(", ")
        val sConstructorBody = fieldsWithIdx.map { case ((fld,_), i) => fld + " = c" + i + "; " }.mkString
        val sInitializers = fieldsWithIdx.map { case ((fld,tp),i) => fld + "(other." + fld + "), "}.mkString + "nxt(nullptr), prv(nullptr)"
        val sStringInit = m.keys.zipWithIndex.map { case ((fld,tp),i) =>
            tp match {
              case TypeLong | TypeDate => fld + " = std::stol(f[" + i + "]);"
              case TypeDouble => fld + " = std::stod(f[" + i + "]);"
              case TypeString => fld + " = f[" + i + "];"
              case _ => sys.error("Unsupported type in fromString")
            }
          }.mkString(" ") + s" ${VALUE_NAME} = v; nxt = nullptr; prv = nullptr;" 
        val sModifyFn = allIndices.map { case (is,unique) =>
          s"FORCE_INLINE ${mapEntry}& modify" + stringIf(!unique, getIndexId(mapName, is)) + 
          "(" + is.map { case i => "const " + refTypeToString(fields(i)._2) + " c" + i }.mkString(", ") + ") { " +
          is.map { case i => fields(i)._1 + " = c" + i + "; "}.mkString + " return *this; }"
        }.mkString("\n")
        val sSerialization = fields.map { case (fld,_) => 
            s"""|ar << ELEM_SEPARATOR;
                |DBT_SERIALIZATION_NVP(ar, ${fld});
                |""".stripMargin
          }.mkString
        val initFromString = stringIf(EXPERIMENTAL_RUNTIME_LIBRARY, 
          s"${mapEntry}(const std::vector<std::string>& f, const ${refTypeToString(m.tp)} v) { /* if (f.size() < ${m.keys.size}) return; */ ${sStringInit} }")

        s"""|struct ${mapEntry} {
            |  ${sFields}
            |  explicit ${mapEntry}() : nxt(nullptr), prv(nullptr) { }
            |  explicit ${mapEntry}(${sConstructorParams}) { ${sConstructorBody} }
            |  ${mapEntry}(const ${mapEntry}& other) : ${sInitializers} { }
            |  ${initFromString}
            |${ind(sModifyFn)}
            |  template<class Archive>
            |  void serialize(Archive& ar, const unsigned int version) const {
            |${ind(sSerialization, 2)}
            |  }
            |};
            |""".stripMargin
      }

      def genExtractorsAndHashers = allIndices.map{ case (is,unique) =>
        "struct " + mapType + "key" + getIndexId(mapName, is) + "_idxfn {\n"+
        "  FORCE_INLINE static size_t hash(const "+mapEntry+"& e) {\n"+
        "    size_t h = 0;\n"+
        is.take(EXPERIMENTAL_MAX_INDEX_VARS).map{ isIndex => "    hash_combine(h, e."+fields(isIndex)._1+");\n" }.mkString +
        "    return h;\n"+
        "  }\n"+
        "  FORCE_INLINE static bool equals(const "+mapEntry+"& x, const "+mapEntry+"& y) {\n"+
        "    return "+
        is.map{ isIndex =>
          val fld=fields(isIndex)._1
          cmpFunc(fields(isIndex)._2,OpEq,"x."+fld,"y."+fld,false)
        }.mkString(" && ") + ";\n" +
        "  }\n"+
        "};\n"
      }.mkString("\n")

      def genTypeDefs =
        if (EXPERIMENTAL_HASHMAP) {
          "typedef MultiHashMap<" + mapEntry + ", " + mapValueType + ", " +
          allIndices.map { case (is, unique) =>
            if (unique) "\n  PrimaryHashIndex<" + mapEntry + ", " + mapType + "key" + getIndexId(mapName, is) + "_idxfn>"
            else "\n  SecondaryHashIndex<" + mapEntry + ", " + mapType + "key" + getIndexId(mapName, is) + "_idxfn>"
          }.mkString(", ") +
          "\n> " + mapType + ";\n"
        }
        else {
          "typedef MultiHashMap<" + mapEntry + ", " + mapValueType + ", " +
          allIndices.map { case (is, unique) => "\n  HashIndex<" + mapEntry + ", " + mapValueType + ", " + mapType + "key" + getIndexId(mapName, is) + "_idxfn, " + unique + ">"}.mkString(",") +
          "\n> " + mapType + ";\n" +
          allIndices.map { case (is, unique) =>
            "typedef HashIndex<" + mapEntry + ", " + mapValueType + ", " + mapType + "key" + getIndexId(mapName, is) + "_idxfn, " + unique + "> HashIndex_" + mapType + "_" + getIndexId(mapName, is) + ";\n"
          }.mkString
        }

      s"""|${genEntryStruct}
          |${genExtractorsAndHashers}
          |${genTypeDefs}
          |""".stripMargin
    }

    def genTempTupleTypes = {
      if (EXPERIMENTAL_HASHMAP) {
        tempTupleTypes.map { case (name, (ksTp, vsTp)) =>
          val ksTpWithIdx = ksTp.zipWithIndex
          s"""|struct ${name} {
              |  ${ksTpWithIdx.map { case (k,i) => typeToString(k) + " _" + (i + 1) + "; "}.mkString} ${typeToString(vsTp)} ${VALUE_NAME}; ${name}* nxt; ${name}* prv;
              |  explicit ${name}() : nxt(nullptr), prv(nullptr) { }
              |  FORCE_INLINE ${name}& modify(${ksTpWithIdx.map { case (k,i) => "const " + refTypeToString(k) + " c" + i}.mkString(", ")}) { ${ksTpWithIdx.map { case (k,i) => "_" + (i + 1) + " = c" + i + "; "}.mkString} return *this; }
              |  static bool equals(const ${name} &x, const ${name} &y) {
              |    return (${ksTpWithIdx.map { case (v,i) => "(x._" + (i + 1) + "==y._" + (i + 1) + ")"}.mkString(" && ")});
              |  }
              |  static long hash(const ${name} &e) {
              |    size_t h = 0;
              |    ${ksTpWithIdx.take(EXPERIMENTAL_MAX_INDEX_VARS).map { case (v,i) => "    hash_combine(h, e._" + (i + 1) + ");" }.mkString("\n")}
              |    return h;
              |  }
              |};
              |""".stripMargin
        }
      }
      else {
        tempTupleTypes.map{case (name,(ksTp,vsTp)) =>
          val ksTpWithIdx = ksTp.zipWithIndex
          val valVarName = VALUE_NAME
          "struct " + name +" {\n"+
          "  "+ksTpWithIdx.map{case (k,i) => typeToString(k)+" _"+(i+1)+"; "}.mkString+(typeToString(vsTp)+" "+valVarName+"; "+name+"* nxt; "+name+"* prv;")+"\n"+
          "  explicit "+name+"() : nxt(nullptr), prv(nullptr) { "/*+ksTpWithIdx.map{case (k,i) => "_"+(i+1)+" = "+zeroOfType(k)+"; "}.mkString+(valVarName+" = "+zeroOfType(vsTp)+";")*/+"}\n"+
          "  explicit "+name+"("+ksTpWithIdx.map{case (k,i) => "const "+refTypeToString(k)+" c"+(i+1)+", "}.mkString+(typeToString(vsTp)+" c"+valVarName+"="+zeroOfType(vsTp))+") : nxt(nullptr), prv(nullptr) { "+ksTpWithIdx.map{case (_,i) => "_"+(i+1)+" = c"+(i+1)+"; "}.mkString+(valVarName+" = c"+valVarName+";")+"}\n"+
          "  int operator<(const "+name+" &rhs) const { \n"+ksTpWithIdx.map{case (v,i) => "    if(this->_"+(i+1)+"!=rhs._"+(i+1)+") return (this->_"+(i+1)+"<rhs._"+(i+1)+");\n"}.mkString+"    return 0;\n  }\n"+
          "  int operator==(const "+name+" &rhs) const { return ("+ksTpWithIdx.map{case (v,i) => "(this->_"+(i+1)+"==rhs._"+(i+1)+")"}.mkString(" && ")+"); }\n"+
          "  FORCE_INLINE "+name+"& modify("+ksTpWithIdx.map{case (k,i) => "const "+refTypeToString(k)+" c"+i+", "}.mkString+typeToString(vsTp)+" c"+valVarName+") { "+ksTpWithIdx.map{case (k,i) => "_"+(i+1)+" = c"+i+"; "}.mkString+valVarName+" = c"+valVarName+"; return *this; }\n"+
          "  static bool equals(const "+name+" &x, const "+name+" &y) { return ("+ksTpWithIdx.map{case (v,i) => "(x._"+(i+1)+"==y._"+(i+1)+")"}.mkString(" && ")+"); }\n"+
          "  static long hash(const "+name+" &e) {\n"+
          "    size_t h = 0;\n"+
          ksTpWithIdx.take(EXPERIMENTAL_MAX_INDEX_VARS).map{ case (v,i) => "    hash_combine(h, e._"+(i+1)+");\n" }.mkString +
          "    return h;\n"+
          "  }\n"+
          "};"
        }
      }
    }

    freshClear
    clearOut

    s0.maps.foreach { m =>
      mapDefsList += (m.name -> m)
    }
    s0.triggers.foreach(_.event match { //delta relations
      case EventBatchUpdate(s) =>
        val schema = s0.sources.filter(_.schema.name == s.name)(0).schema
        val deltaRel = delta(schema.name)
        mapDefsList += (deltaRel -> MapDef(deltaRel, TypeLong, schema.fields, null, LocalExp))
      case _ => //nothing to do
    })
    mapDefs = mapDefsList.toMap
    val (tsSC, msSC, tmpEntrySC) = genPardis(s0)

    deltaRelationNames = s0.triggers.flatMap(_.event match { //delta relations
      case EventBatchUpdate(s) =>
        val schema = s0.sources.filter(_.schema.name == s.name)(0).schema
        List(delta(schema.name))
      case _ => Nil
    }).toSet

    usedRelationDirectives = 
      s0.sources.map { s => 
        if (s.isStream) s"#define RELATION_${s.schema.name.toUpperCase}_DYNAMIC" 
        else s"#define RELATION_${s.schema.name.toUpperCase}_STATIC"
      }.mkString("\n")

    val ts =
      "/* Trigger functions for table relations */\n"+
      //table initializations (a.k.a ld0)
      genTableTriggers+
      "\n\n"+
      "/* Trigger functions for stream relations */\n"+
      //trigger implementations in the body (part 1)
      (if(tsSC != null) tsSC else genStreamTriggers)
    //serializer of the output query results
    val resAcc = helperResultAccessor(s0)

    //map definitions in the body (part 2)
    val sDataStructures = if(msSC != null) msSC else {
      // queries without a map (with -F EXPRESSIVE-TLQS)
      s0.queries.filter(q => (s0.maps.filter(_.name == q.name).size == 0) && (q.expr.ovars.size > 0))
                .map(q => genMapStructDef(MapDef(q.name, q.expr.tp, q.expr.ovars, q.expr, LocalExp))).mkString("\n") +
      // delta relations
      ( if (EXPERIMENTAL_RUNTIME_LIBRARY) {
          s0.triggers.map(_.event match {
            case EventBatchUpdate(s) =>
              val schema = s0.sources.filter(_.schema.name == s.name).head.schema
              genMapStructDef(MapDef(schema.name, TypeLong, schema.fields, null, LocalExp)) + 
              s"""|typedef ${schema.name}_entry ${delta(schema.name)}_entry;
                  |typedef ${schema.name}_map ${delta(schema.name)}_map;
                  |""".stripMargin
            case EventInsert(s) =>
              val schema = s0.sources.filter(_.schema.name == s.name).head.schema
              genMapStructDef(MapDef(schema.name, TypeLong, schema.fields, null, LocalExp))
            case _ => ""
          }).mkString
        }
        else {
          s0.triggers.map(_.event match {
            case EventBatchUpdate(s) =>
              val schema = s0.sources.filter(_.schema.name == s.name).head.schema
              genMapStructDef(MapDef(delta(schema.name), TypeLong, schema.fields, null, LocalExp))
            case _ => ""
          }).mkString 
        }
      ) +
      // maps
      s0.maps.filter(_.keys.size > 0).map(genMapStructDef).mkString + 
      // temp tuples
      genTempTupleTypes.mkString("\n")
    }

    "\n/* Definitions of auxiliary maps for storing materialized views. */\n"+
    sDataStructures +
    "\n\n"+
    resAcc+
    //start of the common part between all CPP code generators (part 1)
    "/* Type definition providing a way to incrementally maintain the results of the sql program */\n"+
    "struct data_t : tlq_t{\n"+
    "  data_t(): tlq_t()"+getInitializationForDATA_T(s0.maps,s0.queries)+" {\n"+
         ind(constsInit,2)+"\n"+
    (if(regexpCacheMap.isEmpty) "" else
    "    /* regex_t init */\n"+
    regexpCacheMap.map{case (regex,preg) =>
    "    if(regcomp(&"+preg+", \""+regex+"\", REG_EXTENDED | REG_NOSUB)){\n"+
    "      cerr << \"Error compiling regular expression: /"+regex+"/\" << endl;\n"+
    "      exit(-1);\n"+
    "    }\n"
    }.mkString)+
    "  }\n"+
    "\n"+
    (if(regexpCacheMap.isEmpty) "" else
      "  ~data_t() {\n" +
      regexpCacheMap.map { case (regex, preg) =>
        "    regfree(&" + preg + ");\n" 
      }.mkString + 
      "  }\n") + 
    (if (EXPERIMENTAL_RUNTIME_LIBRARY) "" else {
      "\n"+ 
      "  /* Registering relations and trigger functions */\n"+
      "  ProgramBase* program_base;\n"+
      "  void register_data(ProgramBase& pb) {\n"+
      "    program_base = &pb;\n"+
      //"  map<relation_id_t, std::shared_ptr<ProgramBase::relation_t> >::iterator r_it = pb.relations_by_id.find(12);\n"+
      "\n"+
           ind(register_maps,2)+
      "\n\n"+
           ind(register_relations,2)+
      "\n\n"+
           ind(register_table_triggers,2)+
      "\n\n"+
           ind(register_stream_triggers,2)+
      "\n\n"+
           ind(init_stats,2)+
      "\n\n"+
      "  }\n"
    }) +
    "\n"+
    ind(ts)+
    "\n\n"+
    //end of the common part between all CPP code generators (part 1)
    //start of constants
    (if(!isExpressiveTLQSEnabled(s0.queries)) {
    "private:\n"+
    "\n"+
    "  /* Sample entries for avoiding recreation of temporary objects */\n"+
      (if(tmpEntrySC != null) tmpEntrySC else sampleEntDef)+ "\n" +
    (if(regexpCacheMap.isEmpty) "" else
    "  /* regex_t temporary objects */\n"+
    regexpCacheMap.map{case (_,preg) => "  regex_t "+preg+";\n"}.mkString)+
    "\n"+
    "  /* Data structures used for storing materialized views */\n"+
       (if (usingPardis) ""
       else {
         ind(genIntermediateDataStructureRefs(mapDefsList.map(_._2).toList,s0.queries))+"\n"+
         ind(genTempMapDefs)+"\n"
       }) +
       ind(consts)+
    "\n\n"} else "")+
      //end of constants
      //start of the common part between all CPP code generators (part 2)
    "};\n"+
    "\n"+
    helper(s0)
    //end of the common part between all CPP code generators (part 2)
  }

  def generateUnwrapFunction(evt:EventTrigger)(implicit s0:System) = stringIf(!EXPERIMENTAL_RUNTIME_LIBRARY, {
    val (op,name,fields) = evt match {
      case EventBatchUpdate(Schema(n,cs)) => ("batch_update", n, cs)
      case EventInsert(Schema(n,cs)) => ("insert", n, cs)
      case EventDelete(Schema(n,cs)) => ("delete", n, cs)
      case _ => sys.error("Unsupported trigger event " + evt)
    }
    evt match {
      case b@EventBatchUpdate(_) =>
        var code =    "void unwrap_"+op+"_"+name+"(const event_args_t& eaList) {\n"
        code = code + "  size_t sz = eaList.size();\n"

        for (sources <- s0.sources.filter(_.isStream)) {
          val schema = sources.schema;
          val deltaRel = delta(schema.name)
          code = code + "    "+deltaRel+".clear();\n"
        }
        
        code = code +   "    for(size_t i=0; i < sz; i++){\n"
        code = code +   "      event_args_t* ea = reinterpret_cast<event_args_t*>(eaList[i].get());\n"
        code = code +   "      relation_id_t relation = *(reinterpret_cast<relation_id_t*>((*ea).back().get()));\n"
        
        for (sources <- s0.sources.filter(_.isStream)) {
          val schema = sources.schema;
          val deltaRel = delta(schema.name)
          val entryClass = deltaRel + "_entry"  
       
          code = code + "      if (relation == program_base->get_relation_id(\"" + schema.name + "\"" + ")) { \n"
          code = code + "        event_args_t* ea = reinterpret_cast<event_args_t*>(eaList[i].get());\n"
          code = code + "        "+entryClass+" e("+schema.fields.zipWithIndex.map{ case ((_,tp),i) => "*(reinterpret_cast<"+typeToString(tp)+"*>((*ea)["+i+"].get())), "}.mkString+"*(reinterpret_cast<"+typeToString(TypeLong)+"*>((*ea)["+schema.fields.size+"].get())));\n"
          code = code + "        "+deltaRel+".addOrDelOnZero(e, *(reinterpret_cast<"+typeToString(TypeLong)+"*>((*ea)["+schema.fields.size+"].get())));\n"
          code = code + "      }\n"
        }
        code = code +   "    }\n"
        for (sources <- s0.sources.filter(_.isStream)) {
          val schema = sources.schema;
          val deltaRel = delta(schema.name)
          code = code + "  on_"+op+"_"+schema.name+"("+deltaRel+");\n"
        }            
        code = code +   "}\n\n"

        val schema = s0.sources.filter(_.schema.name == name)(0).schema
        val deltaRel = delta(schema.name)
        val entryClass = deltaRel + "_entry"            
        code +
        (if(hasOnlyBatchProcessingForAdd(s0,b))
          "void unwrap_insert_"+name+"(const event_args_t& ea) {\n"+
          "  "+deltaRel+".clear();\n"+
          "  "+entryClass+" e("+schema.fields.zipWithIndex.map{ case ((_,tp),i) => "*(reinterpret_cast<"+typeToString(tp)+"*>(ea["+i+"].get())), "}.mkString+" 1L);\n"+
          (if (EXPERIMENTAL_HASHMAP) "  "+deltaRel+".insert(e);\n" else "  "+deltaRel+".insert_nocheck(e);\n") +
          "  on_batch_update_"+name+"("+deltaRel+");\n"+
          "}\n\n"
         else "") +
        (if(hasOnlyBatchProcessingForDel(s0,b))
          "void unwrap_delete_"+name+"(const event_args_t& ea) {\n"+
          "  "+deltaRel+".clear();\n"+
          "  "+entryClass+" e("+schema.fields.zipWithIndex.map{ case ((_,tp),i) => "*(reinterpret_cast<"+typeToString(tp)+"*>(ea["+i+"].get())), "}.mkString+"-1L);\n"+
          (if (EXPERIMENTAL_HASHMAP) "  "+deltaRel+".insert(e);\n" else "  "+deltaRel+".insert_nocheck(e);\n") +
          "  on_batch_update_"+name+"("+deltaRel+");\n"+
          "}\n\n"
         else "")
      case _ =>
        "void unwrap_"+op+"_"+name+"(const event_args_t& ea) {\n"+
        "  on_"+op+"_"+name+"("+fields.zipWithIndex.map{ case ((_,tp),i) => "*(reinterpret_cast<"+typeToString(tp)+"*>(ea["+i+"].get()))"}.mkString(", ")+");\n"+
        "}\n\n"
    }
  })


  private def helperResultAccessor(s0:System) = {
    def compile_serialization = s0.queries.map{q =>
      "ar << \"\\n\";\n"+
      "const " + queryRefTypeToString(q) + " _" + q.name + " = get_" + q.name + "();\n"+ 
      "dbtoaster::serialize_nvp_tabbed(ar, STRING(" + q.name+"), _" + q.name + ", \"\\t\");\n"
    }.mkString

    def compile_tlqs = s0.queries.map{ query =>
      "const " + queryRefTypeToString(query) + " get_" + query.name + "() const {\n"+
      (query.expr match {
        case MapRef(n,_,_,_) if (n == query.name) => "  return " + query.name + ";"
        case _ => 
          ctx = Ctx[(Type,String)]()
          ind(
            if (query.expr.ovars.length == 0) cpsExpr(query.expr, (v: String) => "return " + v + ";") + "\n"
            else {
              val ovars = query.expr.ovars
              val nk = ovars.map(_._1)
              val nkTp = ovars.map(_._2)
              val mapName = query.name
              val mapType = mapName+"_map"
              val mapEntry = mapName+"_entry"
              val sampleEnt=fresh("se")
              sampleEntDef+=(if(nk.size > 0) "  "+mapEntry+" "+sampleEnt+";\n" else "")
              // "val "+mapName+" = M3Map.make["+tk+","+query.tp.toScala+"]()\n"+
              mapName + ".clear();\n"+
              cpsExpr(query.expr, (v:String) =>ADD_TO_MAP_FUNC(query.name)+"("+sampleEnt+".modify("+(nk map rn).mkString(",")+"),"+v+");")+"\n"+
              "return " + mapName + ";"
            }
          )
      })+
      "\n}\n"
    }.mkString

    def compile_tlqs_decls = s0.queries.map { q =>
      queryTypeToString(q) + " " +q.name + ";\n"
    }.mkString

    def compile_expressive_tlqs = stringIf(isExpressiveTLQSEnabled(s0.queries), 
      "\n  /* Data structures used for storing materialized views */\n" +
      ind(genIntermediateDataStructureRefs(mapDefsList.map(_._2).toList, s0.queries)) + "\n" +
      ind(genTempMapDefs) + "\n" +
      ind(consts) + "\n"
    )

    s"""|/* Type definition providing a way to access the results of the sql program */
        |struct tlq_t {
        |  struct timeval t0, t; long tT, tN, tS;
        |  tlq_t(): tN(0), tS(0) ${getInitializationForTLQ_T(s0.maps,s0.queries)} { gettimeofday(&t0, NULL); }
        |
        |  /* Serialization Code */
        |  template<class Archive>
        |  void serialize(Archive& ar, const unsigned int version) const {
        |${ind(compile_serialization, 2)}
        |  }
        |
        |  /* Functions returning / computing the results of top level queries */
        |${ind(compile_tlqs)}
        |
        |protected:
        |  /* Data structures used for storing / computing top level queries */
        |${if(usingPardis) "" else ind(compile_tlqs_decls)}
        |${compile_expressive_tlqs}
        |};
        |
        |""".stripMargin
  }

  // Helper that contains the main and stream generator
  private def helper(s0: System) = stringIf(!EXPERIMENTAL_RUNTIME_LIBRARY, {
    val dataset = "standard"
    val programClass = stringIf(cls != "Program",
      s"""|class ${cls} : public Program
          |{
          |  public:
          |    ${cls}(int argc = 0, char* argv[] = 0) : Program(argc, argv) { }
          |};""".stripMargin
    )
    s"""|/* Type definition providing a way to execute the sql program */
        |class Program : public ProgramBase
        |{
        |  public:
        |    Program(int argc = 0, char* argv[] = 0) : ProgramBase(argc,argv) {
        |      data.register_data(*this);
        |${ind(streams(s0.sources), 3)}
        |    }
        |
        |    /* Imports data for static tables and performs view initialization based on it. */
        |    void init() {
        |        //P0_PLACE_HOLDER
        |        table_multiplexer.init_source(run_opts->batch_size, run_opts->parallel, true);
        |        stream_multiplexer.init_source(run_opts->batch_size, run_opts->parallel, false);
        |
        |        ${ifPrintTimingInfo}struct timeval ts0, ts1, ts2;
        |        ${ifPrintTimingInfo}gettimeofday(&ts0, NULL);
        |        process_tables();
        |        ${ifPrintTimingInfo}gettimeofday(&ts1, NULL);
        |        ${ifPrintTimingInfo}long int et1 = (ts1.tv_sec - ts0.tv_sec) * 1000L + (ts1.tv_usec - ts0.tv_usec) / 1000;
        |        ${ifPrintTimingInfo}std::cout << "Populating static tables time: " << et1 << " (ms)" << std::endl;
        |
        |        data.on_system_ready_event();
        |        ${ifPrintTimingInfo}gettimeofday(&ts2, NULL);
        |        ${ifPrintTimingInfo}long int et2 = (ts2.tv_sec - ts1.tv_sec) * 1000L + (ts2.tv_usec - ts1.tv_usec) / 1000;
        |        ${ifPrintTimingInfo}std::cout << "OnSystemReady time: " << et2 << " (ms)" << std::endl;
        |
        |        //P2_PLACE_HOLDER
        |        gettimeofday(&data.t0, NULL);
        |    }
        |
        |    /* Saves a snapshot of the data required to obtain the results of top level queries. */
        |    snapshot_t take_snapshot() {
        |        ${ifPrintTimingInfo}gettimeofday(&data.t, NULL);
        |        ${ifPrintTimingInfo}long int t = (data.t.tv_sec - data.t0.tv_sec) * 1000L + (data.t.tv_usec - data.t0.tv_usec) / 1000;
        |        ${ifPrintTimingInfo}std::cout << "Trigger running time: " << t << " (ms)" << std::endl;
        |
        |        tlq_t* d = new tlq_t((tlq_t&) data);
        |        ${ifReleaseMode}if (d->tS == 0) { ${tc("d->")} } printf(\"SAMPLE=${dataset},%ld,%ld,%ld\\n\", d->tT, d->tN, d->tS);
        |        //checkAll(); //print bucket statistics
        |        ${if (pardisProfilingOn) "" else "//"}ExecutionProfiler::printProfileToFile("profile${cls}.csv");
        |        return snapshot_t( d );
        |    }
        |
        |  protected:
        |    data_t data;
        |};
        |
        |${programClass}
        |""".stripMargin
  })

  private def tc(p:String="") = "gettimeofday(&("+p+"t),NULL); "+p+"tT=(("+p+"t).tv_sec-("+p+"t0).tv_sec)*1000000L+(("+p+"t).tv_usec-("+p+"t0).tv_usec);"

  private def genStreams(s:Source): String = {
    val sourceId = fresh("source");
    val sourceSplitVar = sourceId + "_fd"
    val adaptorVar = sourceId+"_adaptor"
    val paramsVar = adaptorVar+"_params"
    val sourceFileVar = sourceId+"_file"
    val in = s.in match { case SourceFile(path) => "std::shared_ptr<dbt_file_source> "+sourceFileVar+"(new dbt_file_source(\""+path+"\","+sourceSplitVar+","+adaptorVar+"));\n" }
    val split = "frame_descriptor "+sourceSplitVar+(s.split match { case SplitLine => "(\"\\n\")" case SplitSep(sep) => "(\""+sep+"\")" case SplitSize(bytes) => "("+bytes+")" case SplitPrefix(p) => "XXXXX("+p+")" })+";\n" //XXXX for SplitPrefix

    val schema_param = s.schema.fields.map{case (_,tp) => tp.toString}.mkString(",")
    val adaptor = s.adaptor.name match {
      case "ORDERBOOK" => {
        val bidsAndAsks = List("bids","asks")
        val orderBookTypesList = bidsAndAsks.filter(s.adaptor.options.contains)
        val orderBookType = orderBookTypesList.size match {
          case 1 => orderBookTypesList(0)
          case 2 => "both"
        }
        val a_opts = s.adaptor.options.filter{case (k,_) => !orderBookTypesList.contains(k)} ++ Map("schema" -> schema_param)
        val numParams = a_opts.size+1
        val a_def = "pair<string,string> "+paramsVar+"[] = { make_pair(\"book\",\""+orderBookType+"\"), "+a_opts.map{case (k,v) => "make_pair(\""+k+"\",\""+v+"\")"}.mkString(", ")+" };\n"+
          "std::shared_ptr<order_books::order_book_adaptor> "+adaptorVar+"(new order_books::order_book_adaptor("+bidsAndAsks.map{ x => {if(s.adaptor.options.contains(x)) "get_relation_id(\""+s.adaptor.options(x)+"\")" else "-1"}+","}.mkString+numParams+","+paramsVar+"));\n"

        a_def
      }
      case "CSV" => {
        val a_opts = s.adaptor.options ++ Map("schema" -> schema_param)
        val numParams = a_opts.size
        val a_def = "pair<string,string> "+paramsVar+"[] = { "+a_opts.map{case (k,v) => "make_pair(\""+k+"\",\""+v+"\")"}.mkString(", ")+" };\n"+
          "std::shared_ptr<csv_adaptor> "+adaptorVar+"(new csv_adaptor(get_relation_id(\""+s.schema.name+"\"),"+numParams+","+paramsVar+"));\n"

        a_def
      }
    }

    adaptor+split+in+
    "add_source("+sourceFileVar+(if(s.isStream) "" else ", true")+");\n"
  }

  override def streams(sources:List[Source]) = {
    def fixOrderbook(ss:List[Source]):List[Source] = { // one source generates BOTH asks and bids events
      val (os,xs) = ss.partition{_.adaptor.name=="ORDERBOOK"}
      val ob = new java.util.HashMap[(Boolean,SourceIn),(Schema,Split,Map[String,String], LocalityType)]()
      os.foreach { case Source(s,sc,in,sp,ad,loc) =>
        val (k,v) = ((s,in),(ad.options-"book") + ((ad.options.getOrElse("book","bids"),sc.name)))
        val p=ob.get(k); if (p==null) ob.put(k,(sc,sp,v,loc)) else ob.put(k,(sc,sp,p._3++v,loc))
      }
      scala.collection.JavaConversions.mapAsScalaMap(ob).toList.map { case ((s,in),(sc,sp,opts,loc)) => Source(s,sc,in,sp,Adaptor("ORDERBOOK",opts),loc) } ::: xs
    }
    val src = fixOrderbook(sources)
    val ss="\n/* Specifying data sources */\n\n"+src.filter{!_.isStream}.map(genStreams).mkString("\n")+"\n"+src.filter{_.isStream}.map(genStreams).mkString("\n")
    ss
  }

  override def additionalImports: String =
    stringIf(usingPardis, "#define SC_GENERATED 1\n") +
    stringIf(!EXPERIMENTAL_HASHMAP, "#define USE_OLD_MAP\n") +
    stringIf(EXPERIMENTAL_RUNTIME_LIBRARY,
      s"""|#include <sys/time.h>
          |#include <vector>
          |#include "macro.hpp"
          |#include "types.hpp"
          |#include "functions.hpp"
          |#include "hash.hpp"
          |#include "mmap.hpp"
          |#include "serialization.hpp"
          |""".stripMargin,
      s"""|#include "program_base.hpp"
          |#include "hpds/KDouble.hpp"
          |#include "hash.hpp"
          |#include "mmap/mmap.hpp"
          |#include "hpds/pstring.hpp"
          |#include "hpds/pstringops.hpp"
          |""".stripMargin) +    
    stringIf(pardisProfilingOn, "#define EXEC_PROFILE 1\n") +
    "#include \"ExecutionProfiler.h\"\n"

  override def pkgWrapper(pkg: String, body: String) = 
    s"""|${additionalImports}
        |${stringIf(EXPERIMENTAL_RUNTIME_LIBRARY, usedRelationDirectives)}
        |
        |namespace dbtoaster {
        |${ind(body)}
        |}
        """.stripMargin    
}