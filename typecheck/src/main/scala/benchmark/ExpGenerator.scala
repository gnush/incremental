package benchmark

import incremental.Exp._
import incremental.pcf.TFun
import incremental.{ExpKind, Type}

/**
 * Created by seba on 05/11/14.
 */
object ExpGenerator {
  trait LeaveMaker {
    def reset()
    def next(): Exp
  }
  def constantLeaveMaker(c: => Exp): LeaveMaker = new LeaveMaker {
    override def next(): Exp = c
    override def reset(): Unit = {}
  }
  def stateLeaveMaker[T](stateInit: T, stateUpdate: T => T, treeMaker: T => Exp): LeaveMaker = {
    object maker extends LeaveMaker {
      var state: T = stateInit
      override def next(): Exp = {
        val t = treeMaker(state)
        state = stateUpdate(state)
        t
      }
      override def reset(): Unit = state = stateInit
    }
    maker
  }

  def makeBinTree(height: Int, kind: ExpKind, leaveMaker: LeaveMaker, sharing: Boolean = false): Exp = {
    val leaveCount = Math.pow(2, height-1).toInt
    val ts = Array.ofDim[Exp](leaveCount)
    leaveMaker.reset()

    for (i <- 0 until leaveCount)
      ts(i) = leaveMaker.next()

    for (h <- height to 1 by -1)
      for (i <- 0 until Math.pow(2, h-1).toInt-1 by 2) {
        val l = ts(i)
        val r = ts(i+1)
        if (sharing && l == r)
          ts(i/2) = kind(l, l)
        else
          ts(i/2) = kind(l, r)
      }
    ts(0)
  }

  def usedVars(h: Int) = for (j <- (1 to Math.pow(2, h-1).toInt).toSeq) yield Symbol(s"x$j")

  def makeFunType(height: Int, returnType: Type, argMaker: () => Type): Type = {
    val length = Math.pow(2,height-1).toInt
    var argTypes = Seq[Type]()
    for (i <- 1 to length)
      argTypes = argMaker() +: argTypes
    var t = returnType
    for (i <- 1 to length) {
      t = TFun(argTypes.head, t)
      argTypes = argTypes.tail
    }
    t
  }
}