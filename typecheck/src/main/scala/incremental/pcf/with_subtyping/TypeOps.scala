package incremental.pcf.with_subtyping

import incremental.Type
import incremental.pcf.{TFun, TNum, TVar}

import scala.language.implicitConversions

/**
 * Created by oliver on 20.11.14.
 */
object TypeOps {
  implicit def toTypeOp(tpe: Type): TypeOps = new TypeOps(tpe)
  class TypeOps(val tpe: Type) extends AnyVal {
    def ||(that: Type): Option[Type] = (tpe, that) match {
      case (Top, _) | (_, Top) => Some(Top)
      case (TNum, TNum) => Some(TNum)
      case (s1 -->: t1, s2 -->: t2) if (s1 && s2).isDefined && (t1 || t2).isDefined => Some((s1 && s2).get -->: (t1 || t2).get)
      case _ => Some(Top)
    }

    def &&(that: Type): Option[Type] = (tpe, that) match {
      case (Top, t) => Some(t)
      case (t, Top) => Some(t)
      case (TNum, TNum) => Some(TNum)
      case (s1 -->: t1, s2 -->: t2) if (s1 || s2).isDefined && (t1 && t2).isDefined => Some((s1 || s2).get -->: (t1 && t2).get)
      case _ => None
    }

    def <(that: Type): Boolean = (tpe, that) match {
      case (_, Top) => true
      case (TNum, TNum) => true
      case (s1 -->: t1, s2 -->: t2) => s2 < s1 && t1 < t2
    }

    def -->:(that: Type): Type = TFun(that, tpe)
  }

  object -->: {
    def unapply(t: Type): Option[(Type, Type)] = t match {
      case TFun(t1, t2) => Some((t1,t2))
      case _ => None
    }
  }
}
