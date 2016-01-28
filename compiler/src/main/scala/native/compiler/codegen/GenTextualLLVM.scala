package native
package compiler
package codegen

import scala.collection.mutable
import native.nir._
import native.util.unsupported
import native.util.{sh, Show}
import native.util.Show.{Sequence => s, Indent => i, Unindent => ui, Repeat => r, Newline => nl}
import native.compiler.analysis.CFG
import native.nir.Shows.brace

object GenTextualLLVM extends GenShow {
  implicit val showDefns: Show[Seq[Defn]] = Show { defns =>
    r(defns, sep = nl(""))
  }

  implicit val showDefn: Show[Defn] = Show {
    case Defn.Var(attrs, name, _, rhs) =>
      sh"@$name = global $rhs"
    case Defn.Declare(attrs, name, Type.Function(argtys, retty)) =>
      sh"declare $retty @$name(${r(argtys, sep = ", ")})"
    case Defn.Define(attrs, name, Type.Function(_, retty), blocks) =>
      showDefine(attrs, retty, name, blocks)
    case Defn.Struct(attrs, name, tys) =>
      sh"%$name = type {${r(tys, sep = ", ")}}"
    case defn =>
      unsupported(defn)
  }

  def showDefine(attrs: Seq[Attr], retty: Type, name: Global, blocks: Seq[Block]) = {
    val body = brace(i(showBlocks(blocks)))
    val params = sh"(${r(blocks.head.params, sep = ", ")})"
    sh"define $retty @$name$params $body"
  }

  def showBlocks(blocks: Seq[Block]) = {
    val cfg = CFG(blocks)
    val visited = mutable.Set.empty[CFG.Node]
    val worklist = mutable.Stack.empty[CFG.Node]
    val result = mutable.UnrolledBuffer.empty[Show.Result]
    val entry = cfg(blocks.head.name)

    worklist.push(entry)
    while (worklist.nonEmpty) {
      val node = worklist.pop()
      if (!visited.contains(node)){
        visited += node
        node.succ.foreach(e => worklist.push(e.to))
        result += showBlock(node.block, node.pred, isEntry = node eq entry)
      }
    }

    r(result)
  }

  def showBlock(block: Block, pred: Seq[CFG.Edge], isEntry: Boolean): Show.Result = {
    val instructions = r(block.instrs, sep = nl(""))

    if (isEntry)
      instructions
    else {
      val label = ui(sh"${block.name}:")
      val phis = r(block.params.zipWithIndex.map {
        case (Param(n, ty), i) =>
          val branches = pred.map { e =>
            sh"[${e.values(i)}, ${e.from.block.name}]"
          }
          sh"phi $ty ${r(branches, sep = ", ")}"
      }.map(i(_)))
      sh"$label$phis${nl("")}$instructions"
    }
  }

  implicit val showType: Show[Type] = Show {
    case Type.Void                => "void"
    case Type.Bool                => "i1"
    case Type.I8                  => "i8"
    case Type.I16                 => "i16"
    case Type.I32                 => "i32"
    case Type.I64                 => "i64"
    case Type.F32                 => "f32"
    case Type.F64                 => "f64"
    case Type.Array(ty, n)        => sh"[$n x $ty]"
    case Type.Ptr(ty)             => sh"${ty}*"
    case Type.Function(args, ret) => sh"$ret (${r(args, sep = ", ")})"
    case Type.Struct(name)        => sh"%$name"
    case ty                       => unsupported(ty)
  }

  def justVal(v: Val): Show.Result = v match {
    case Val.True          => "true"
    case Val.False         => "false"
    case Val.Zero(ty)      => "zeroinitializer"
    case Val.I8(v)         => v.toString
    case Val.I16(v)        => v.toString
    case Val.I32(v)        => v.toString
    case Val.I64(v)        => v.toString
    case Val.Struct(n, vs) => sh"{ ${r(vs, sep = ", ")} }"
    case Val.Array(_, vs)  => sh"[ ${r(vs, sep = ", ")} ]"
    case Val.Chars(v)      => s("c\"", v, "\"")
    case Val.Local(n, ty)  => sh"%$n"
    case Val.Global(n, ty) => sh"@$n"
  }

  implicit val showVal: Show[Val] = Show { v =>
    val justv = justVal(v)
    val ty = v.ty
    sh"$ty $justv"
  }

  implicit val showGlobal: Show[Global] = Show {
    case Global.Atom(id)              => id
    case Global.Nested(owner, member) => sh"${owner}__$member"
    case Global.Tagged(owner, tag)    => sh"${owner}.$tag"
  }

  implicit val showLocal: Show[Local] = Show {
    case Local(scope, id) => sh"$scope.$id"
  }

  implicit val showInstr: Show[Instr] = Show {
    case Instr(None,     attrs, op) => showOp(attrs, op)
    case Instr(Some(id), attrs, op) => sh"%$id = ${showOp(attrs, op)}"
  }

  def showOp(attrs: Seq[Attr], op: Op): Show.Result = op match {
    case Op.Unreachable =>
      "unreachable"
    case Op.Ret(Val.None) =>
      sh"ret void"
    case Op.Ret(value) =>
      sh"ret $value"
    case Op.Jump(next) =>
      "todo: jump"
    case Op.If(cond, thenp, elsep) =>
      sh"br $cond, $thenp, $elsep"
    case Op.Switch(scrut, default, cases)  =>
      "todo: switch"
    case Op.Invoke(ty, f, args, succ, fail) =>
      "todo: invoke"

    case Op.Call(ty, f, args) =>
      sh"call $ty ${justVal(f)}(${r(args, sep = ", ")})"
    case Op.Load(ty, ptr) =>
      sh"load $ty, $ptr"
    case Op.Store(ty, ptr, value) =>
      sh"store $value, $ptr"
    case Op.Elem(_, ptr, indexes) =>
      val Type.Ptr(ty) = ptr.ty
      sh"getelementptr $ty, $ptr, ${r(indexes, sep = ", ")}"
    case Op.Extract(ty, aggr, index) =>
      "todo: extract"
    case Op.Insert(ty, aggr, value, index) =>
      "todo: insert"
    case Op.Alloca(ty) =>
      sh"alloca[$ty]"
    case Op.Bin(name, ty, l, r) =>
      "todo: bin"
    case Op.Comp(name, ty, l, r) =>
      assert(attrs.isEmpty, "TODO: unsigned")
      val cmp = ty match {
        case Type.F(_) =>
          name match {
            case Comp.Eq  => "fcmp oeq"
            case Comp.Neq => "fcmp one"
            case Comp.Lt  => "fcmp olt"
            case Comp.Lte => "fcmp ole"
            case Comp.Gt  => "fcmp ogt"
            case Comp.Gte => "fcmp oge"
          }
        case _ =>
          name match {
            case Comp.Eq  => "icmp eq"
            case Comp.Neq => "icmp ne"
            case Comp.Lt  => "icmp slt"
            case Comp.Lte => "icmp sle"
            case Comp.Gt  => "icmp sgt"
            case Comp.Gte => "icmp sge"
          }
      }
      sh"$cmp $l, ${justVal(r)}"
    case Op.Conv(name, ty, v) =>
      "todo: conv"
    case op =>
      sh"unsupported: ${op.toString}"
  }

  implicit val showNext: Show[Next] = Show {
    case Next(n, _) =>
      sh"label %$n"
  }

  implicit def showCase: Show[Case] = ???

  implicit def showParam: Show[Param] = Show {
    case Param(n, ty) =>
      sh"$ty %$n"
  }
}