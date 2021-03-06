package constraints.subtype.impl

import constraints.{Statistics, CVar, subtype}
import constraints.subtype._
import constraints.subtype.CSubst.CSubst
import incremental.Util

import scala.collection.generic.CanBuildFrom

object SolveContinuously extends ConstraintSystemFactory[SolveContinuouslyCS] {
  def freshConstraintSystem = new SolveContinuouslyCS(Map(), defaultBounds, Seq())
  def solved(s: CSubst) = new SolveContinuouslyCS(s, defaultBounds, Seq())
  def notyet(c: Constraint) = freshConstraintSystem addNewConstraint (c)
  def never(c: Constraint) = new SolveContinuouslyCS(Map(), defaultBounds, Seq(c))
}

case class SolveContinuouslyCS(substitution: CSubst, bounds: Map[CVar[Type], (LBound, UBound)], never: Seq[Constraint]) extends ConstraintSystem[SolveContinuouslyCS] {
  //invariant: substitution maps to ground types
  //invariant: there is at most one ground type in each bound, each key does not occur in its bounds, keys of solution and bounds are distinct

  def state = SolveContinuously.state.value
  
  def notyet = {
    var cons = Seq[Constraint]()
    for ((x, (l, u)) <- bounds) {
      val join = subtype.Join(UVar(x), l.nonground ++ l.ground.toSet)
      val meet = subtype.Meet(UVar(x), u.nonground ++ u.ground.toSet)
      cons = cons :+ join :+ meet
    }
    cons
  }

  def never(c: Constraint) = SolveContinuouslyCS(substitution, bounds, never :+ c)

  def mergeSubsystem(that: SolveContinuouslyCS) = {
    val msubst = substitution ++ that.substitution
    var mbounds = bounds
    var mnever = never ++ that.never

    for((tv, (l1, u1)) <- that.bounds) {
      val (l2, u2) = mbounds(tv)
      val (newL, errorl) = l2 merge l1
      val (newU, erroru) = u2 merge u1
      if(errorl.nonEmpty)
        mnever = mnever :+ subtype.Join(UVar(tv), errorl)
      if(erroru.nonEmpty)
        mnever = mnever :+ subtype.Meet(UVar(tv), erroru)
      val merged = (newL, newU)
      mbounds = mbounds + (tv -> merged)
    }

    SolveContinuouslyCS(msubst, mbounds, mnever)
  }

  def addNewConstraint(c: Constraint) = {
    state += Statistics.constraintCount -> 1
    Util.timed(state -> Statistics.constraintSolveTime) {
      c.solve(this).trySolve
    }
  }

  def addNewConstraints(cons: Iterable[Constraint]) = {
    state += Statistics.constraintCount  -> cons.size
    Util.timed(state -> Statistics.constraintSolveTime) {
      cons.foldLeft(this)((cs, c) => c.solve(cs)).trySolve
    }
  }

  def tryFinalize =
    Util.timed(state -> Statistics.finalizeTime) {
      //set upper bounds of negative vars to Top if still undetermined and solve
      val finalbounds = bounds.map {
        case (tv, (lower, upper)) if gen.isNegative(tv) && !upper.isGround =>
          val (newUpper, _) = upper.add(subtype.Top)
          (tv, (lower, newUpper))
        case x => x
      }

      SolveContinuouslyCS(substitution, finalbounds, never).saturateSolution
    }


  private def substitutedBounds(s: CSubst) = {
    var newnever = Seq[Constraint]()
    val newbounds: Map[CVar[Type], (LBound,UBound)] = for ((tv, (lb, ub)) <- bounds) yield {
      val (newLb, errorl) = lb.subst(s)
      val (newUb, erroru) = ub.subst(s)
      if(errorl.nonEmpty)
        newnever  = newnever  :+ subtype.Join(UVar(tv).subst(s), errorl)
      if(erroru.nonEmpty)
        newnever  = newnever  :+ subtype.Meet(UVar(tv).subst(s), erroru)
      (tv -> (newLb, newUb))
    }
    (newbounds, newnever)
  }

  private def withSubstitutedBounds = {
    val (newbounds, newnever) = substitutedBounds(substitution)
    SolveContinuouslyCS(substitution, newbounds, never ++ newnever)
  }

  def trySolve = saturateSolution

  private def saturateSolution = {
    var current = this.withSubstitutedBounds
    var sol = solveOnce
    while (sol.nonEmpty) {
      val subst = substitution ++ sol
      val (newbounds, newnever) = current.substitutedBounds(sol)

      var temp = SolveContinuouslyCS(subst, SolveContinuously.defaultBounds, Seq())

      for ((tv, (lb, ub)) <- newbounds) {
        val t = subst.hgetOrElse(tv, UVar(tv))
        for(tpe <- lb.ground.toSet ++ lb.nonground)
          temp = tpe.subtype(t, temp)
        for(tpe <- ub.ground.toSet ++ ub.nonground)
          temp = t.subtype(tpe, temp)
      }

      current = SolveContinuouslyCS(temp.substitution, temp.bounds, current.never ++ newnever ++ temp.never)
      sol = current.solveOnce
    }
    current
  }

  private def solveOnce: CSubst = {
    var sol = CSubst.empty
    for ((tv, (lower, upper)) <- bounds) {
      if (gen.isBipolar(tv)) {
        if (lower.isGround && upper.isGround)
          sol += tv -> lower.ground.get
      }
      else if (gen.isProperPositive(tv)) {
        if (lower.isGround)
          sol += tv -> lower.ground.get
      }
      else if (gen.isProperNegative(tv)) {
        if(upper.isGround)
          sol += tv -> upper.ground.get
      }
    }
    sol
  }

  def addLowerBound(v: CVar[Type], t: Type) = {
    val (lower, upper) = bounds(v)
    val (newLower, error) = lower.add(t)
    val changed = if (newLower.isGround) newLower.ground.get else t

    val newnever =
      if (error.isEmpty)
        never
      else
        never :+ subtype.Join(UVar(v), error)
    val newbounds = bounds + (v -> (newLower, upper))
    val cs = SolveContinuouslyCS(substitution, newbounds, newnever)

    subtype.Meet(changed, upper.nonground ++ upper.ground.toSet).solve(cs)
  }

  def addUpperBound(v: CVar[Type], t: Type) = {
    val (lower, upper) = bounds(v)
    val (newUpper, error) = upper.add(t)
    val changed = if (newUpper.isGround) newUpper.ground.get else t

    val newnever =
      if (error.isEmpty)
        never
      else
        never :+ subtype.Meet(UVar(v), error)
    val newbounds = bounds + (v -> (lower, newUpper))
    val cs = SolveContinuouslyCS(substitution, newbounds, newnever)

    subtype.Join(changed, lower.nonground ++ lower.ground.toSet).solve(cs)
  }


  def applyPartialSolution[CT <: constraints.CTerm[Gen, Constraint, CT]](t: CT) = t

  def applyPartialSolutionIt[U, C <: Iterable[U], CT <: constraints.CTerm[Gen, Constraint, CT]]
    (it: C, f: U=>CT)
    (implicit bf: CanBuildFrom[Iterable[U], (U, CT), C])
  = it

  def propagate = this

}