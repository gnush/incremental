package incremental.pcf

import constraints.equality.{EqConstraint, Type, UVar}
import constraints.equality.Type.Companion._
import constraints.equality.ConstraintSystemFactory._

/**
 * Created by seba on 13/11/14.
 */
//trait CheckResult {
//  (Type, Map[Symbol, Type], Unsolvable)
//}

case object TNum extends Type {
  def freeTVars = Set()
  def occurs(x: Symbol) = false
  def normalize = this
  def subst(s: TSubst) = this
  def unify(other: Type, s: TSubst) = other match {
    case TNum => emptySolution
    case UVar(x) => other.unify(this, s)
    case _ => never(EqConstraint(this, other))
  }
}

case class TFun(t1: Type, t2: Type) extends Type {
  def freeTVars = t1.freeTVars ++ t2.freeTVars
  def occurs(x: Symbol) = t1.occurs(x) || t2.occurs(x)
  def normalize = TFun(t1.normalize, t2.normalize)
  def subst(s: TSubst) = {
    var args = List(t1.subst(s))
    var res = t2
    while (res.isInstanceOf[TFun]) {
      val resfun = res.asInstanceOf[TFun]
      args = resfun.t1.subst(s) :: args
      res = resfun.t2
    }
    res = res.subst(s)
    for (a <- args)
      res = TFun(a, res)
    res
  }
  def unify(other: Type, s: TSubst) = other match {
    case TFun(t1_, t2_) =>
      val cs1 = t1.unify(t1_, s)
      val cs2 = t2.unify(t2_, cs1.solution._1 ++ s)
      cs1 ++++ cs2
    case UVar(x) => other.unify(this, s)
    case _ => never(EqConstraint(this, other))
  }
  override def toString= s"($t1 --> $t2)"
}
