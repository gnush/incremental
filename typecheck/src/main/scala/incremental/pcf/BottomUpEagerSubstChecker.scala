package incremental.pcf

import akka.actor.{Props, Inbox, ActorSystem}
import scala.concurrent.{ExecutionContext, Await, Promise, Future}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import incremental.ConstraintOps._
import incremental.Node.Node
import incremental.Type.Companion._
import incremental.Node._
import incremental._
import incremental.concurrent._

/**
 * Created by seba on 13/11/14.
 */
class BottomUpEagerSubstChecker extends TypeChecker[Type] {

  val constraint = new ConstraintOps
  import constraint._

  var preparationTime = 0.0
  var typecheckTime = 0.0
  def constraintCount = constraint.constraintCount
  def mergeReqsTime = constraint.mergeReqsTime
  def constraintSolveTime = constraint.constraintSolveTime
  def mergeSolutionTime = constraint.mergeSolutionTime

  type Reqs = Map[Symbol, Type]

  type Result = (Type, Reqs, Solution)

  def typecheck(e: Node): Either[Type, TError] = {
    val root = e.withType[Result]

//    val (uninitialized, ptime) = Util.timed {root.uninitialized}
//    preparationTime += ptime

    val (res, ctime) = Util.timed {
      root.visitUninitialized {e =>
        e.typ = typecheckStep(e)
        true
      }

      val (t_, reqs, sol_) = root.typ
      val sol = sol_.tryFinalize
      val t = t_.subst(sol.substitution)
      
      if (!reqs.isEmpty)
        Right(s"Unresolved context requirements $reqs, type $t, unres ${sol.unsolved}")
      else if (!sol.isSolved)
        Right(s"Unresolved constraints ${sol.unsolved}, type $t")
      else
        Left(t)
    }
    typecheckTime += ctime
    res
  }

  def typecheckStep(e: Node_[Result]): Result = e.kind match {
    case Num => (TNum, Map(), emptySol)
    case op if op == Add || op == Mul =>
      val (t1, reqs1, sol1) = e.kids(0).typ
      val (t2, reqs2, sol2) = e.kids(1).typ

      val lcons = EqConstraint(TNum, t1)
      val rcons = EqConstraint(TNum, t2)

      val (mcons, mreqs) = mergeReqMaps(reqs1, reqs2)

      val sol = solve(mcons, solve(lcons) ++++ solve(rcons))
      (TNum, mreqs.mapValues(_.subst(sol.substitution)), sol1 +++ sol2 <++ sol)
    case Var =>
      val x = e.lits(0).asInstanceOf[Symbol]
      val X = freshUVar()
      (X, Map(x -> X), emptySol)
    case App =>
      val (t1, reqs1, sol1) = e.kids(0).typ
      val (t2, reqs2, sol2) = e.kids(1).typ

      val X = freshUVar()
      val fcons = EqConstraint(TFun(t2, X), t1)
      val (mcons, mreqs) = mergeReqMaps(reqs1, reqs2)

      val sol = solve(fcons +: mcons)
      (X.subst(sol.substitution), mreqs.mapValues(_.subst(sol.substitution)), sol1 +++ sol2 <++ sol)
    case Abs if (e.lits(0).isInstanceOf[Symbol]) =>
      val x = e.lits(0).asInstanceOf[Symbol]
      val (t, reqs, subsol) = e.kids(0).typ

      reqs.get(x) match {
        case None =>
          val X = if (e.lits.size == 2) e.lits(1).asInstanceOf[Type] else freshUVar()
          (TFun(X, t), reqs, subsol)
        case Some(treq) =>
          val otherReqs = reqs - x
          if (e.lits.size == 2) {
            val sol = solve(EqConstraint(e.lits(1).asInstanceOf[Type], treq))
            (TFun(treq, t).subst(sol.substitution), otherReqs.mapValues(_.subst(sol.substitution)), subsol <++ sol)
          }
          else
            (TFun(treq, t), otherReqs, subsol)
      }
    case Abs if (e.lits(0).isInstanceOf[Seq[_]]) =>
      val xs = e.lits(0).asInstanceOf[Seq[Symbol]]
      val (t, reqs, subsol) = e.kids(0).typ

      val Xs = xs map (_ => freshUVar())

      var restReqs = reqs
      var tfun = t
      for (i <- xs.size-1 to 0 by -1) {
        val x = xs(i)
        restReqs.get(x) match {
          case None =>
            val X = freshUVar()
            tfun = TFun(X, tfun)
          case Some(treq) =>
            restReqs = restReqs - x
            tfun = TFun(treq, tfun)
        }
      }

      (tfun, restReqs, subsol)
    case If0 =>
      val (t1, reqs1, sol1) = e.kids(0).typ
      val (t2, reqs2, sol2) = e.kids(1).typ
      val (t3, reqs3, sol3) = e.kids(2).typ

      val (mcons12, mreqs12) = mergeReqMaps(reqs1, reqs2)
      val (mcons23, mreqs123) = mergeReqMaps(mreqs12, reqs3)

      val cond = EqConstraint(TNum, t1)
      val body = EqConstraint(t2, t3)

      val sol = solve(cond +: body +: (mcons12 ++ mcons23))

      (t2.subst(sol.substitution), mreqs123.mapValues(_.subst(sol.substitution)), sol1 +++ sol2 +++ sol3 <++ sol)

    case Fix =>
      val (t, reqs, subsol) = e.kids(0).typ
      val X = freshUVar()
      val fixCons = EqConstraint(t, TFun(X, X))
      val sol = solve(fixCons)
      (X.subst(sol.substitution), reqs.mapValues(_.subst(sol.substitution)), subsol <++ sol)
  }
}

class BottomUpEagerSubstCheckerConcurrent(implicit system: ActorSystem)  extends BottomUpEagerSubstChecker {
  override def typecheck(e: Node): Either[Type, TError] = {
    val root = e.withType[Result]
    val inbox = Inbox.create(system)
    val trigger = system.actorOf(Props[Trigger])
    val actor = system.actorOf(Props(new RootNodeActor[Result](inbox.getRef(), root, 0, {e =>
      e.typ = typecheckStep(e)
      true
    }, trigger)))

    val (res, ctime) = Util.timed {
      trigger ! Start
      val Done(0, res) = inbox.receive(1 minute)
      val (t_, reqs, sol_) = res.asInstanceOf[Result]
      val sol = sol_.tryFinalize
      val t = t_.subst(sol.substitution)

      if (!reqs.isEmpty)
        Right(s"Unresolved context requirements $reqs, type $t, unres ${sol.unsolved}")
      else if (!sol.isSolved)
        Right(s"Unresolved constraints ${sol.unsolved}, type $t")
      else
        Left(t)
    }
    typecheckTime += ctime
    res
  }
}

object BottomUpEagerSubstConcurrentCheckerFactory extends TypeCheckerFactory[Type] {
  def makeChecker = new BottomUpEagerSubstCheckerConcurrent()(ActorSystem("PCFBottomUpEagerSubstCheckerConcurrent")) //TODO not sure where to put actor system
}

class FuturisticBottomUpEagerSubstChecker extends BottomUpEagerSubstChecker {
  val clusterHeight = 3
  def bottomUpFuture(e: Node): (Future[Any], Promise[Unit]) = {
    val trigger: Promise[Unit] = Promise()
    val fut = trigger.future
    def recurse(e: Node): (Future[Any], Int) = {
      if (e.height <= clusterHeight) {
        val f = fut map { _ =>
          e.visitUninitialized { e =>
            val ee = e.withType[Result]
            ee.typ = typecheckStep(ee)
            true
          }
        }
        (f, 1)
      }
      else {
        val (fs, hops) = (e.kids.seq.map { k => recurse(k) }).unzip
        val max = hops.foldLeft(0) { case (i,j) => i.max(j) }
        val join = Future.sequence(fs)

        val future =
          if ((max % clusterHeight) == 0)
            join.map { _ =>
              e.visitUninitialized { e =>
                val ee = e.withType[Result]
                ee.typ = typecheckStep(ee)
                true
              }
            }
          else
            join

        (future, max + 1)
      }
    }

    val (res, hops) = recurse(e)

    if ((e.height % clusterHeight) != 0) {
      val res2 = res map { _ =>
        e.visitUninitialized { e =>
          val ee = e.withType[Result]
          ee.typ = typecheckStep(ee)
          true
        }
      }
      (res2, trigger)
    }
    else (res, trigger)
  }

  override def typecheck(e: Node): Either[Type, TError] = {
    val root = e.withType[Result]
    val (fut, trigger) = bottomUpFuture(e)

    val (res, ctime) = Util.timed {
      trigger success ()
      Await.result(fut, 1 minute)
      val (t_, reqs, sol_) = root.typ
      val sol = sol_.tryFinalize
      val t = t_.subst(sol.substitution)

      if (!reqs.isEmpty)
        Right(s"Unresolved context requirements $reqs, type $t, unres ${sol.unsolved}")
      else if (!sol.isSolved)
        Right(s"Unresolved constraints ${sol.unsolved}, type $t")
      else
        Left(t)
    }
    typecheckTime += ctime
    res
  }
}

object FuturisticBottomUpEagerSubstCheckerFactory extends TypeCheckerFactory[Type] {
  def makeChecker = new FuturisticBottomUpEagerSubstChecker
}

object BottomUpEagerSubstCheckerFactory extends TypeCheckerFactory[Type] {
  def makeChecker = new BottomUpEagerSubstChecker
}