package ddbt

import java.io._
object Compiler {
  import ddbt.frontend._
  import ddbt.codegen._

  def test(m3:String) {
    val ir=M3Parser.parse(m3)
    val ir2=TypeCheck.fixTypes(ir)
    println(m3)
    println("--------------------------------------")
    println(ScalaGen.genSystem(ir2))
  }

  def main(args: Array[String]) {

    //test(toast("resources/queries/finance/simple.sql"));
    //test(toast("resources/queries/finance/axfinder.sql"));
    
    // TPC-H 13,15,18
    //test(toast("resources/queries/tpch/query13.sql"));
	//(0 until 10).foreach{x=>println}
	//test(toast("resources/queries/tpch/query15.sql"));
	test(toast("resources/queries/tpch/query15.sql"));
    //test(toast("resources/queries/simple/r_agtbexists.sql"));
    //test(scala.io.Source.fromFile("resources/docs/chrissedtrades.m3").mkString);

/*
1. frontend
	- create temp directory (OK)
	- generate the m3 file (OK)
	- parse the m3 (OK)
	- parse the SQL (INCOMPLETE)
2. type the AST (?)
	- make sure we have enough information to output scala
	==> obtain an AST
3. optimize the AST
	- high level optimization like replacing List
4. prepare for distributed system
	- add node conditionals in the code
	- replace some read operations by send/receive (depending node)
5. generate code with LMS
6. Distribute over nodes
7. Evaluate query
*/
  }

  // UTILITIES -----------------------------------------------------------------
  def toast(path:String) = {
    val os=System.getProperty("os.name").toLowerCase
    val fam = List("win","lin","mac").filter{os.contains(_)}.head
    exec(Array("resources/dbtoaster_"+fam,"-l","M3","-F","K3-NO-OPTIMIZE",path))._1
  }
  
  // execute arbitrary command, return (out,err)
  def exec(cmd:String):(String,String) = exec(cmd.split(" "))
  def exec(cmd:Array[String]):(String,String) = {
    val p = Runtime.getRuntime.exec(cmd /*,null,new File(outPath)*/)
    def gobble(in:InputStream) = new Runnable {
      var out = new StringBuilder
      var thr = new Thread(this); thr.start
      override def toString = { thr.join; out.toString.trim }
      override def run {
        val r = new BufferedReader(new InputStreamReader(in))
        var l = r.readLine; while(l != null) { out.append(l+"\n"); l = r.readLine }; r.close
      }
    }
    val out=gobble(p.getInputStream); val err=gobble(p.getErrorStream); p.waitFor
    val o=out.toString; val e=err.toString
    if (!e.equals("")) println("\nExec: "+cmd+"\n- Out: "+o+"\n- Err: "+e+"\n")
    (o,e)
  }

  /*
  def makeTemp(path:String) {
    def del(f:File) {
      if (f.isDirectory()) f.listFiles().foreach{c=>del(c)}
      if (!f.delete()) sys.error("Failed to delete file: " + f)
    }
    val tmp=new File(path);
    if (tmp.exists) del(tmp)
    if (!tmp.mkdirs()) sys.error("Dir creation failed")
  }
  */
}
